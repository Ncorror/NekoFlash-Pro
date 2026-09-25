package com.ncorror.nekoflash.pro.platform.usb

import android.app.Activity
import android.content.Context
import android.hardware.usb.UsbManager
import com.ncorror.nekoflash.pro.adb.AdbHandshakeProbe
import com.ncorror.nekoflash.pro.adb.AdbHandshakeResult
import com.ncorror.nekoflash.pro.adb.AdbAuthorizationSession
import com.ncorror.nekoflash.pro.model.AdbProbePhase
import com.ncorror.nekoflash.pro.model.AdbProbeState
import com.ncorror.nekoflash.pro.model.UsbDeviceSummary
import com.ncorror.nekoflash.pro.transport.UsbWireProtocol
import java.io.IOException

/** Read-only first-reply check and a separate, user-initiated ADB authorization. */
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

    fun probe(summary: UsbDeviceSummary) = start(summary, authorize = false)

    fun authorize(summary: UsbDeviceSummary) = start(summary, authorize = true)

    private fun start(summary: UsbDeviceSummary, authorize: Boolean) {
        val currentGeneration = synchronized(lock) {
            if (active) {
                record("ADB probe already running or closing")
                return
            }
            active = true
            cancelled = false
            ++generation
        }
        val event = if (authorize) "ADB authorization" else "ADB CNXN"
        record("$event requested for VID=${summary.vendorId.toString(16)} " +
            "PID=${summary.productId.toString(16)} id=${summary.deviceId}")
        publish(summary.state(if (authorize) AdbProbePhase.AUTHORIZING else AdbProbePhase.RUNNING))
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
                val key = if (authorize) AdbKeystoreHostKey.getOrCreate() else null
                if (synchronized(lock) { cancelled || generation != currentGeneration }) {
                    throw IOException("ADB request cancelled")
                }
                val session = AdbUsbByteTransport.open(manager, device, matches.single())
                val stopRequested = synchronized(lock) {
                    if (!cancelled) transport = session
                    cancelled
                }
                if (stopRequested) {
                    session.close()
                    throw IOException("ADB probe cancelled")
                }
                val answer = if (key == null) AdbHandshakeProbe(session).read()
                else AdbAuthorizationSession(session, key).connect {
                    record("ADB authorization awaiting device confirmation")
                    activity.runOnUiThread {
                        if (synchronized(lock) { !cancelled && generation == currentGeneration }) {
                            publish(summary.state(AdbProbePhase.WAITING_CONFIRMATION))
                        }
                    }
                }
                when (answer) {
                    is AdbHandshakeResult.Connected -> summary.state(
                        AdbProbePhase.CONNECTED, answer.protocolVersion, answer.maxData,
                    )
                    AdbHandshakeResult.AuthorizationRequired -> summary.state(AdbProbePhase.AUTH_REQUIRED)
                    AdbHandshakeResult.TlsRequired -> summary.state(AdbProbePhase.TLS_REQUIRED)
                }
            } catch (error: Exception) {
                record("$event failed: ${error.javaClass.simpleName}: ${safeText(error.message.orEmpty())}")
                summary.state(AdbProbePhase.FAILED)
            }
            val shouldPublish = synchronized(lock) {
                transport = null
                active = false
                !cancelled && generation == currentGeneration
            }
            if (shouldPublish) {
                record("$event result=${result.phase}" +
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
