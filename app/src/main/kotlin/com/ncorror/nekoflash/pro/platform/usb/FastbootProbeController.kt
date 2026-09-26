package com.ncorror.nekoflash.pro.platform.usb

import android.app.Activity
import android.content.Context
import android.hardware.usb.UsbManager
import com.ncorror.nekoflash.pro.fastboot.FastbootVariable
import com.ncorror.nekoflash.pro.fastboot.FastbootWireMessage
import com.ncorror.nekoflash.pro.fastboot.FastbootWireResult
import com.ncorror.nekoflash.pro.fastboot.FastbootPlanExecutor
import com.ncorror.nekoflash.pro.fastboot.FastbootPlanExecution
import com.ncorror.nekoflash.pro.engine.FastbootCommand
import com.ncorror.nekoflash.pro.engine.FastbootOptions
import com.ncorror.nekoflash.pro.engine.PlannedFastbootVariable
import com.ncorror.nekoflash.pro.engine.TerminalInvocation
import com.ncorror.nekoflash.pro.engine.TerminalPlan
import com.ncorror.nekoflash.pro.engine.TerminalPlanCompiler
import com.ncorror.nekoflash.pro.engine.TerminalPlanResult
import com.ncorror.nekoflash.pro.engine.TerminalPlanStep
import com.ncorror.nekoflash.pro.model.FastbootProbePhase
import com.ncorror.nekoflash.pro.model.FastbootProbeState
import com.ncorror.nekoflash.pro.model.UsbDeviceSummary
import com.ncorror.nekoflash.pro.transport.UsbWireProtocol
import com.ncorror.nekoflash.pro.transport.UsbSessionGate
import java.io.IOException

/** A single, short-lived, user-triggered USB probe. All UI callbacks run on the main thread. */
class FastbootProbeController(
    private val activity: Activity,
    private val gate: UsbSessionGate,
    private val record: (String) -> Unit,
    private val publish: (FastbootProbeState) -> Unit,
) {
    private val manager = activity.getSystemService(Context.USB_SERVICE) as UsbManager
    private val lock = Any()
    private var active = false
    private var cancelled = false
    private var generation = 0L
    private var transport: FastbootUsbPacketTransport? = null

    fun probe(summary: UsbDeviceSummary, variable: FastbootVariable) {
        val compiled = TerminalPlanCompiler().compile(
            TerminalInvocation.Fastboot(FastbootOptions(), FastbootCommand.Getvar(variable.wireName))
        )
        val plan = (compiled as? TerminalPlanResult.Ready)?.plan ?: return
        probePlan(summary, plan)
    }

    fun probePlan(summary: UsbDeviceSummary, plan: TerminalPlan) {
        val step = plan.steps.singleOrNull() as? TerminalPlanStep.FastbootGetvar ?: return
        val variable = when (step.variable) {
            PlannedFastbootVariable.VERSION -> FastbootVariable.VERSION
            PlannedFastbootVariable.PRODUCT -> FastbootVariable.PRODUCT
            PlannedFastbootVariable.DOWNLOAD_SIZE -> FastbootVariable.DOWNLOAD_SIZE
        }
        val currentGeneration = synchronized(lock) {
            if (active) {
                record("Fastboot probe already running or closing")
                return
            }
            active = true
            cancelled = false
            ++generation
        }
        record("Fastboot getvar:${variable.wireName} requested for VID=${summary.vendorId.toString(16)} " +
            "PID=${summary.productId.toString(16)} id=${summary.deviceId}")
        publish(summary.state(variable, FastbootProbePhase.RUNNING))
        Thread({
            var lease: UsbSessionGate.Lease? = null
            val result = try {
                val device = manager.deviceList[summary.key]?.takeIf {
                    it.deviceId == summary.deviceId && it.vendorId == summary.vendorId &&
                        it.productId == summary.productId
                } ?: throw IOException("Selected USB device disconnected")
                if (!manager.hasPermission(device)) throw SecurityException("USB access not granted")
                val matches = UsbInterfaceScanner.scan(device).filter {
                    it.protocol == UsbWireProtocol.FASTBOOT && it.bulkPairAvailable &&
                        it.configurationIndex == 0
                }
                if (matches.size != 1) throw IOException("Expected exactly one usable Fastboot interface")
                lease = gate.tryAcquire() ?: throw IOException("USB session is already in use")
                val session = FastbootUsbPacketTransport.open(manager, device, matches.single())
                val stopRequested = synchronized(lock) {
                    if (!cancelled) transport = session
                    cancelled
                }
                if (stopRequested) {
                    session.close()
                    throw IOException("Fastboot probe cancelled")
                }
                val execution = FastbootPlanExecutor { session }.execute(TerminalPlanResult.Ready(plan)) { message ->
                    val content = when (message) {
                        is FastbootWireMessage.Info -> message.text
                        is FastbootWireMessage.Text -> message.text
                    }
                    record("Fastboot getvar:${variable.wireName} response ${message.javaClass.simpleName}: ${safeText(content)}")
                }
                val answer = when (execution) {
                    is FastbootPlanExecution.Reply -> execution.result
                    is FastbootPlanExecution.Refused -> {
                        session.close()
                        throw IOException("Fastboot plan refused: ${execution.reason}")
                    }
                }
                when (answer) {
                    is FastbootWireResult.Okay -> summary.state(variable, FastbootProbePhase.VALUE, safeText(answer.message))
                    is FastbootWireResult.Fail -> summary.state(variable, FastbootProbePhase.REJECTED, safeText(answer.reason))
                    is FastbootWireResult.DataPhaseRequired ->
                        throw IOException("Unexpected Fastboot DATA response to getvar:${variable.wireName}")
                }
            } catch (error: Exception) {
                record("Fastboot getvar:${variable.wireName} failed: ${error.javaClass.simpleName}: ${safeText(error.message.orEmpty())}")
                summary.state(variable, FastbootProbePhase.FAILED)
            } finally {
                try {
                    synchronized(lock) { transport }?.close()
                } catch (error: RuntimeException) {
                    record("Fastboot USB cleanup failed: ${error.javaClass.simpleName}")
                }
                lease?.close()
            }
            val shouldPublish = synchronized(lock) {
                transport = null
                active = false
                !cancelled && generation == currentGeneration
            }
            if (shouldPublish) {
                record("Fastboot getvar:${variable.wireName} result=${result.phase} ${result.detail.orEmpty()}")
                activity.runOnUiThread {
                    if (synchronized(lock) { !cancelled && generation == currentGeneration }) {
                        publish(result)
                    }
                }
            }
        }, "fastboot-getvar-probe").start()
    }

    /** Closing the connection interrupts the bounded bulk read; stale replies are discarded. */
    fun cancel() {
        val previous = synchronized(lock) {
            cancelled = true
            ++generation
            transport
        }
        try {
            previous?.close()
        } catch (error: RuntimeException) {
            record("Fastboot USB cleanup failed: ${error.javaClass.simpleName}")
        }
    }

    private fun UsbDeviceSummary.state(variable: FastbootVariable, phase: FastbootProbePhase, detail: String? = null) =
        FastbootProbeState(key, deviceId, vendorId, productId, variable, phase, detail)

    private fun safeText(value: String): String = value
        .replace(Regex("[\\p{Cntrl}]+"), " ")
        .take(96)
}
