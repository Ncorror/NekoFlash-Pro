package com.ncorror.nekoflash.pro.platform.usb

import android.app.Activity
import android.content.Context
import android.hardware.usb.UsbManager
import com.ncorror.nekoflash.pro.fastboot.FastbootVersionProbe
import com.ncorror.nekoflash.pro.fastboot.FastbootWireMessage
import com.ncorror.nekoflash.pro.fastboot.FastbootWireResult
import com.ncorror.nekoflash.pro.model.FastbootProbePhase
import com.ncorror.nekoflash.pro.model.FastbootProbeState
import com.ncorror.nekoflash.pro.model.UsbDeviceSummary
import com.ncorror.nekoflash.pro.transport.UsbWireProtocol
import java.io.IOException

/** A single, short-lived, user-triggered USB probe. All UI callbacks run on the main thread. */
class FastbootProbeController(
    private val activity: Activity,
    private val record: (String) -> Unit,
    private val publish: (FastbootProbeState) -> Unit,
) {
    private val manager = activity.getSystemService(Context.USB_SERVICE) as UsbManager
    private val lock = Any()
    private var active = false
    private var cancelled = false
    private var generation = 0L
    private var transport: FastbootUsbPacketTransport? = null

    fun probe(summary: UsbDeviceSummary) {
        val currentGeneration = synchronized(lock) {
            if (active) {
                record("Fastboot probe already running or closing")
                return
            }
            active = true
            cancelled = false
            ++generation
        }
        record("Fastboot getvar:version requested for VID=${summary.vendorId.toString(16)} " +
            "PID=${summary.productId.toString(16)} id=${summary.deviceId}")
        publish(summary.state(FastbootProbePhase.RUNNING))
        Thread({
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
                val session = FastbootUsbPacketTransport.open(manager, device, matches.single())
                val stopRequested = synchronized(lock) {
                    if (!cancelled) transport = session
                    cancelled
                }
                if (stopRequested) {
                    session.close()
                    throw IOException("Fastboot probe cancelled")
                }
                when (val answer = FastbootVersionProbe(session).read { message ->
                    val content = when (message) {
                        is FastbootWireMessage.Info -> message.text
                        is FastbootWireMessage.Text -> message.text
                    }
                    record("Fastboot version response ${message.javaClass.simpleName}: ${safeText(content)}")
                }) {
                    is FastbootWireResult.Okay -> summary.state(FastbootProbePhase.VERSION, safeText(answer.message))
                    is FastbootWireResult.Fail -> summary.state(FastbootProbePhase.REJECTED, safeText(answer.reason))
                    is FastbootWireResult.DataPhaseRequired ->
                        throw IOException("Unexpected Fastboot DATA response to getvar:version")
                }
            } catch (error: Exception) {
                record("Fastboot getvar:version failed: ${error.javaClass.simpleName}: ${safeText(error.message.orEmpty())}")
                summary.state(FastbootProbePhase.FAILED)
            }
            val shouldPublish = synchronized(lock) {
                transport = null
                active = false
                !cancelled && generation == currentGeneration
            }
            if (shouldPublish) {
                record("Fastboot getvar:version result=${result.phase} ${result.detail.orEmpty()}")
                activity.runOnUiThread {
                    if (synchronized(lock) { !cancelled && generation == currentGeneration }) {
                        publish(result)
                    }
                }
            }
        }, "fastboot-version-probe").start()
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

    private fun UsbDeviceSummary.state(phase: FastbootProbePhase, detail: String? = null) =
        FastbootProbeState(key, deviceId, vendorId, productId, phase, detail)

    private fun safeText(value: String): String = value
        .replace(Regex("[\\p{Cntrl}]+"), " ")
        .take(96)
}
