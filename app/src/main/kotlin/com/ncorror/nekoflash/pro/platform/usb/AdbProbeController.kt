package com.ncorror.nekoflash.pro.platform.usb

import android.app.Activity
import android.content.Context
import android.hardware.usb.UsbManager
import com.ncorror.nekoflash.pro.adb.AdbHandshakeProbe
import com.ncorror.nekoflash.pro.adb.AdbHandshakeResult
import com.ncorror.nekoflash.pro.adb.AdbAuthorizationSession
import com.ncorror.nekoflash.pro.adb.AdbAuthenticationPath
import com.ncorror.nekoflash.pro.adb.AdbProductProbe
import com.ncorror.nekoflash.pro.adb.AdbProductResult
import com.ncorror.nekoflash.pro.model.AdbProbeKind
import com.ncorror.nekoflash.pro.model.AdbProbePhase
import com.ncorror.nekoflash.pro.model.AdbProbeState
import com.ncorror.nekoflash.pro.model.UsbDeviceSummary
import com.ncorror.nekoflash.pro.transport.UsbWireProtocol
import java.io.IOException

/** Owns the exclusive ADB USB session for connection and explicit read-only checks. */
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
    private enum class Mode { FIRST_REPLY, CONNECTION, PRODUCT }
    private data class Queued(val summary: UsbDeviceSummary, val mode: Mode, val generation: Long)
    private var queued: Queued? = null

    fun probe(summary: UsbDeviceSummary) = start(summary, Mode.FIRST_REPLY)

    fun authorize(summary: UsbDeviceSummary) = start(summary, Mode.CONNECTION)

    fun readProduct(summary: UsbDeviceSummary) = start(summary, Mode.PRODUCT)

    private fun start(summary: UsbDeviceSummary, mode: Mode) {
        val currentGeneration = synchronized(lock) {
            if (active) {
                if (cancelled) {
                    queued = Queued(summary, mode, generation)
                    record("ADB request queued until prior USB session closes")
                } else {
                    record("ADB probe already running or closing")
                }
                return
            }
            active = true
            cancelled = false
            ++generation
        }
        val event = when (mode) {
            Mode.FIRST_REPLY -> "ADB CNXN"
            Mode.CONNECTION -> "ADB authorization"
            Mode.PRODUCT -> "ADB product property"
        }
        record("$event requested for VID=${summary.vendorId.toString(16)} " +
            "PID=${summary.productId.toString(16)} id=${summary.deviceId}")
        val kind = when (mode) {
            Mode.FIRST_REPLY -> AdbProbeKind.FIRST_REPLY
            Mode.CONNECTION -> AdbProbeKind.CONNECTION
            Mode.PRODUCT -> AdbProbeKind.PRODUCT
        }
        publish(summary.state(if (mode == Mode.FIRST_REPLY) AdbProbePhase.RUNNING else AdbProbePhase.AUTHORIZING,
            kind = kind))
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
                val key = if (mode == Mode.FIRST_REPLY) null else AdbKeystoreHostKey.getOrCreate()
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
                val awaitingApproval = {
                    record("ADB signature not accepted; public key offered; awaiting device confirmation")
                    activity.runOnUiThread {
                        if (synchronized(lock) { !cancelled && generation == currentGeneration }) {
                            publish(summary.state(AdbProbePhase.WAITING_CONFIRMATION, kind = kind))
                        }
                    }
                }
                if (mode == Mode.PRODUCT) {
                    when (val answer = AdbProductProbe(session, checkNotNull(key)).read(awaitingApproval)) {
                        is AdbProductResult.Value -> summary.state(AdbProbePhase.VALUE,
                            kind = kind, authentication = answer.connection.authentication,
                            productCode = answer.productCode)
                        AdbProductResult.TlsRequired -> summary.state(AdbProbePhase.TLS_REQUIRED, kind = kind)
                    }
                } else {
                    val answer = if (key == null) AdbHandshakeProbe(session).read()
                    else AdbAuthorizationSession(session, key).connect(awaitingApproval)
                    when (answer) {
                        is AdbHandshakeResult.Connected -> summary.state(
                            AdbProbePhase.CONNECTED, answer.protocolVersion, answer.maxData,
                            kind, answer.authentication,
                        )
                        AdbHandshakeResult.AuthorizationRequired -> summary.state(AdbProbePhase.AUTH_REQUIRED,
                            kind = kind)
                        AdbHandshakeResult.TlsRequired -> summary.state(AdbProbePhase.TLS_REQUIRED,
                            kind = kind)
                    }
                }
            } catch (error: Exception) {
                record("$event failed: ${error.javaClass.simpleName}: ${safeText(error.message.orEmpty())}")
                summary.state(AdbProbePhase.FAILED, kind = kind)
            }
            val (shouldPublish, next) = synchronized(lock) {
                transport = null
                active = false
                val pending = queued
                queued = null
                (!cancelled && generation == currentGeneration) to pending
            }
            if (shouldPublish) {
                record("$event result=${result.phase}" +
                    (if (result.phase == AdbProbePhase.VALUE)
                        " product=${result.productCode} auth=${result.authentication?.name}" else if (result.phase == AdbProbePhase.CONNECTED)
                        " version=${result.protocolVersion?.toString(16)} maxData=${result.maxData}" +
                            (result.authentication?.let { " auth=${it.name}" } ?: "") else ""))
                activity.runOnUiThread {
                    if (synchronized(lock) { !cancelled && generation == currentGeneration }) {
                        publish(result)
                    }
                }
            }
            if (next != null) {
                activity.runOnUiThread {
                    if (synchronized(lock) { !active && generation == next.generation }) {
                        start(next.summary, next.mode)
                    }
                }
            }
        }, "adb-handshake-probe").start()
    }

    fun cancel() {
        val previous = synchronized(lock) {
            cancelled = true
            ++generation
            queued = null
            transport
        }
        try {
            previous?.close()
        } catch (error: RuntimeException) {
            record("ADB USB cleanup failed: ${error.javaClass.simpleName}")
        }
    }

    private fun UsbDeviceSummary.state(
        phase: AdbProbePhase,
        version: Long? = null,
        maxData: Long? = null,
        kind: AdbProbeKind = AdbProbeKind.CONNECTION,
        authentication: AdbAuthenticationPath? = null,
        productCode: String? = null,
    ) = AdbProbeState(key, deviceId, vendorId, productId, phase, version, maxData, kind, authentication, productCode)

    private fun safeText(value: String): String = value
        .replace(Regex("[\\p{Cntrl}]+"), " ")
        .take(96)
}
