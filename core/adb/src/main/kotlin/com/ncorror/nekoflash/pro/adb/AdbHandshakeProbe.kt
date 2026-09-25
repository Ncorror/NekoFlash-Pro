package com.ncorror.nekoflash.pro.adb

import com.ncorror.nekoflash.pro.transport.AdbByteTransport

sealed interface AdbHandshakeResult {
    /** ADB answered CNXN; this diagnostic session is closed immediately afterwards. */
    data class Connected(val protocolVersion: Long, val maxData: Long) : AdbHandshakeResult
    /** ADB answered AUTH/TOKEN; no token, signature or key is saved or sent. */
    data object AuthorizationRequired : AdbHandshakeResult
    /** A peer requested TLS; negotiation is outside this diagnostic probe. */
    data object TlsRequired : AdbHandshakeResult
}

class AdbProtocolException(message: String) : Exception(message)

/** Sends one fixed CNXN and classifies the first reply. No service, auth or TLS follows. */
class AdbHandshakeProbe(private val transport: AdbByteTransport) {
    fun read(): AdbHandshakeResult = transport.use { session ->
        val wire = AdbWire(session)
        wire.sendHello()
        val reply = wire.receive()
        when (reply.command) {
            AdbWire.CNXN -> wire.connected(reply)
            AdbWire.AUTH -> {
                wire.token(reply)
                AdbHandshakeResult.AuthorizationRequired
            }
            AdbWire.STLS -> wire.tls(reply)
            else -> throw AdbProtocolException("Unexpected ADB reply before CNXN")
        }
    }
}
