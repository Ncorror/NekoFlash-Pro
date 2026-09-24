package com.ncorror.nekoflash.pro.platform.usb

import android.app.Activity
import android.content.Context
import android.hardware.usb.UsbManager
import com.ncorror.nekoflash.pro.adb.AdbHandshakeProbe
import com.ncorror.nekoflash.pro.adb.AdbHandshakeResult
import com.ncorror.nekoflash.pro.model.AdbProbePhase
import com.ncorror.nekoflash.pro.model.AdbProbeState
import com.ncorror.nekoflash.pro.model.UsbDeviceSummary
import com.ncorror.nekoflash.pro.transport.UsbWireProtocol
import java.io.IOException

/** Explicit one-reply diagnostic. It does not generate keys or authorize ADB. */
class AdbProbeController(
    private val activity: Activity,
    private val record: (String) -> Unit,
    private val publish: (AdbProbeState) -> Unit,
) {
    private val manager = activity.getSystemService(Context.USB_SERVICE) as UsbManager
    private val lock = Any()
    private var active = false
    private var cancelled = false
    private var generation = 0L
    private var transport: AdbUsbByteTransport? = null

    fun probe(summary: UsbDeviceSummary) {
        val currentGeneration = synchronized(lock) {
            if (active) {
                record("ADB probe already running or closing")
                return
            }
            active = true
            cancelled = false
            ++generation
        }
        record("ADB CNXN requested for VID=${summary.vendorId.toString(16)} " +
            "PID=${summary.productId.toString(16)} id=${summary.deviceId}")
        publish(summary.state(AdbProbePhase.RUNNING))
        Thread({
            val result = try {
                val device = manager.deviceList[summary.key]?.takeIf {
                    it.deviceId == summary.deviceId && it.vendorId == summary.vendorId &&
                        it.productId == summary.productId
                } ?: throw IOException("Selected USB device disconnected")
                if (!manager.hasPermission(device)) throw SecurityException("USB access not granted")
                val matches = UsbInterfaceScanner.scan(device).filter {
                    it.protocol == UsbWireProtocol.ADB && it.bulkPairAvailable && it.configurationIndex == 0
                }
                if (matches.size != 1) throw IOException("Expected exactly one usable ADB interface")
                val session = AdbUsbByteTransport.open(manager, device, matches.single())
                val stopRequested = synchronized(lock) {
                    if (!cancelled) transport = session
                    cancelled
                }
                if (stopRequested) {
                    session.close()
                    throw IOException("ADB probe cancelled")
                }
                when (val answer = AdbHandshakeProbe(session).read()) {
                    is AdbHandshakeResult.Connected -> summary.state(
                        AdbProbePhase.CONNECTED, answer.protocolVersion, answer.maxData,
                    )
                    AdbHandshakeResult.AuthorizationRequired -> summary.state(AdbProbePhase.AUTH_REQUIRED)
                    AdbHandshakeResult.TlsRequired -> summary.state(AdbProbePhase.TLS_REQUIRED)
                }
            } catch (error: Exception) {
                record("ADB CNXN failed: ${error.javaClass.simpleName}: ${safeText(error.message.orEmpty())}")
                summary.state(AdbProbePhase.FAILED)
            }
            val shouldPublish = synchronized(lock) {
                transport = null
                active = false
                !cancelled && generation == currentGeneration
            }
            if (shouldPublish) {
                record("ADB CNXN result=${result.phase}" +
                    (if (result.phase == AdbProbePhase.CONNECTED)
                        " version=${result.protocolVersion?.toString(16)} maxData=${result.maxData}" else ""))
                activity.runOnUiThread {
                    if (synchronized(lock) { !cancelled && generation == currentGeneration }) {
                        publish(result)
                    }
                }
            }
        }, "adb-handshake-probe").start()
    }

    fun cancel() {
        val previous = synchronized(lock) {
            cancelled = true
            ++generation
            transport
        }
        try {
            previous?.close()
        } catch (error: RuntimeException) {
            record("ADB USB cleanup failed: ${error.javaClass.simpleName}")
        }
    }

    private fun UsbDeviceSummary.state(phase: AdbProbePhase, version: Long? = null, maxData: Long? = null) =
        AdbProbeState(key, deviceId, vendorId, productId, phase, version, maxData)

    private fun safeText(value: String): String = value
        .replace(Regex("[\\p{Cntrl}]+"), " ")
        .take(96)
}
