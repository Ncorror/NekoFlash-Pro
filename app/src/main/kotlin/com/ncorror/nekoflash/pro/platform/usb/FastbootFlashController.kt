package com.ncorror.nekoflash.pro.platform.usb

import android.app.Activity
import android.content.Context
import android.hardware.usb.UsbManager
import android.net.Uri
import com.ncorror.nekoflash.pro.engine.TerminalPlanStep
import com.ncorror.nekoflash.pro.fastboot.FastbootFlashExecutor
import com.ncorror.nekoflash.pro.fastboot.FastbootFlashResult
import com.ncorror.nekoflash.pro.fastboot.FastbootFlashStage
import com.ncorror.nekoflash.pro.fastboot.FastbootProtocolException
import com.ncorror.nekoflash.pro.model.FastbootFlashPhase
import com.ncorror.nekoflash.pro.model.FastbootFlashState
import com.ncorror.nekoflash.pro.model.UsbDeviceSummary
import com.ncorror.nekoflash.pro.transport.UsbSessionGate
import com.ncorror.nekoflash.pro.transport.UsbWireProtocol
import java.io.IOException
import java.io.InputStream

/** One confirmed raw-image operation; file Uri and image bytes never enter diagnostics. */
class FastbootFlashController(
    private val activity: Activity,
    private val gate: UsbSessionGate,
    private val record: (String) -> Unit,
    private val publish: (FastbootFlashState) -> Unit,
) {
    private val manager = activity.getSystemService(Context.USB_SERVICE) as UsbManager
    private val lock = Any()
    private var active = false
    private var cancelled = false
    private var generation = 0L
    private var transport: FastbootUsbPacketTransport? = null
    private var imageStream: InputStream? = null

    fun flash(summary: UsbDeviceSummary, step: TerminalPlanStep.FastbootFlashRaw, uri: Uri) {
        val currentGeneration = synchronized(lock) {
            if (active) return
            active = true
            cancelled = false
            ++generation
        }
        val running = summary.state(step, FastbootFlashPhase.RUNNING)
        publish(running)
        record("Fastboot raw flash requested partition=${step.partition} bytes=${step.imageSize}")
        Thread({
            var lease: UsbSessionGate.Lease? = null
            var bytesSent = 0L
            var lastUpdateMs = 0L
            var safeDetail = ""
            var stage = FastbootFlashStage.READ_LIMIT
            val result = try {
                lease = gate.tryAcquire() ?: throw IOException("USB session is already in use")
                if (synchronized(lock) { cancelled }) throw IOException("Flash cancelled")
                val device = manager.deviceList[summary.key]?.takeIf {
                    it.deviceId == summary.deviceId && it.vendorId == summary.vendorId &&
                        it.productId == summary.productId
                } ?: throw IOException("Selected USB device disconnected")
                if (!manager.hasPermission(device)) throw SecurityException("USB access not granted")
                val matches = UsbInterfaceScanner.scan(device).filter {
                    it.protocol == UsbWireProtocol.FASTBOOT && it.bulkPairAvailable &&
                        it.configurationIndex == 0
                }
                if (matches.size != 1) throw IOException("Expected exactly one Fastboot bulk interface")
                val session = FastbootUsbPacketTransport.open(manager, device, matches.single())
                synchronized(lock) {
                    if (cancelled) {
                        session.close()
                        throw IOException("Flash cancelled")
                    }
                    transport = session
                }
                val answer = FastbootFlashExecutor { session }.flashRaw(
                    step.partition, step.imageSize,
                    openImage = {
                        val source = activity.contentResolver.openInputStream(uri)
                            ?: throw IOException("Cannot open selected image")
                        synchronized(lock) {
                            if (cancelled) {
                                source.close()
                                throw IOException("Flash cancelled")
                            }
                            imageStream = source
                        }
                        source
                    },
                    onProgress = { count ->
                        bytesSent = count
                        val now = System.currentTimeMillis()
                        if (count == step.imageSize || now - lastUpdateMs >= 250) {
                            lastUpdateMs = now
                            activity.runOnUiThread {
                                if (synchronized(lock) { !cancelled && generation == currentGeneration }) {
                                    publish(running.copy(sentBytes = count))
                                }
                            }
                        }
                    },
                    onStage = { stage = it },
                )
                safeDetail = when (answer) {
                    is FastbootFlashResult.DeviceRejected -> " stage=${answer.stage}"
                    is FastbootFlashResult.TooLarge -> " limit=${answer.limit}"
                    else -> ""
                }
                when (answer) {
                    is FastbootFlashResult.Finished -> running.copy(
                        phase = FastbootFlashPhase.FINISHED, sentBytes = bytesSent)
                    is FastbootFlashResult.DeviceRejected -> running.copy(
                        phase = FastbootFlashPhase.REJECTED, sentBytes = bytesSent)
                    is FastbootFlashResult.TooLarge -> running.copy(phase = FastbootFlashPhase.TOO_LARGE)
                    FastbootFlashResult.MissingDownloadLimit -> running.copy(phase = FastbootFlashPhase.LIMIT_UNKNOWN)
                    FastbootFlashResult.SparseImageUnsupported -> running.copy(
                        phase = FastbootFlashPhase.SPARSE_UNSUPPORTED)
                }
            } catch (failure: Exception) {
                // Provider messages can contain the Uri. Core protocol errors contain only status metadata.
                val protocolDetail = if (failure is FastbootProtocolException) {
                    " detail=" + failure.message.orEmpty().replace(Regex("[^A-Za-z0-9 _:-]"), "?").take(96)
                } else ""
                record("Fastboot raw flash failed stage=$stage: ${failure.javaClass.simpleName}$protocolDetail")
                val invalidData = failure is FastbootProtocolException && bytesSent == 0L &&
                    (failure.message.orEmpty().startsWith("Fastboot DATA size mismatch") ||
                        failure.message.orEmpty().startsWith("Malformed Fastboot DATA reply"))
                running.copy(phase = if (invalidData) FastbootFlashPhase.INVALID_DATA_REPLY
                    else FastbootFlashPhase.FAILED, sentBytes = bytesSent)
            } finally {
                val opened = synchronized(lock) {
                    val previous = transport
                    transport = null
                    imageStream = null
                    previous
                }
                try {
                    opened?.close()
                } catch (failure: RuntimeException) {
                    record("Fastboot USB cleanup failed: ${failure.javaClass.simpleName}")
                }
                lease?.close()
            }
            val shouldPublish = synchronized(lock) {
                active = false
                !cancelled && generation == currentGeneration
            }
            if (shouldPublish) {
                record("Fastboot raw flash result=${result.phase}$safeDetail bytes=$bytesSent/${step.imageSize}")
                activity.runOnUiThread {
                    if (synchronized(lock) { !cancelled && generation == currentGeneration }) {
                        publish(result)
                    }
                }
            }
        }, "fastboot-raw-flash").start()
    }

    fun cancel() {
        val handles = synchronized(lock) {
            cancelled = true
            ++generation
            val previous = transport to imageStream
            transport = null
            imageStream = null
            previous
        }
        try { handles.second?.close() } catch (_: Exception) { /* USB is also closed below. */ }
        try { handles.first?.close() } catch (_: Exception) { /* Worker reports completion. */ }
        record("Fastboot raw flash cancelled")
    }

    private fun UsbDeviceSummary.state(step: TerminalPlanStep.FastbootFlashRaw,
                                       phase: FastbootFlashPhase) = FastbootFlashState(
        key, deviceId, vendorId, productId, phase, step.partition, step.imageSize)
}
