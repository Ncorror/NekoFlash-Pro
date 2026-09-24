package com.ncorror.nekoflash.pro.adb

import com.ncorror.nekoflash.pro.transport.AdbByteTransport
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
        val banner = "host::".toByteArray(Charsets.US_ASCII)
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(CNXN).putInt(VERSION).putInt(MAX_DATA)
            .putInt(banner.size).putInt(banner.sumOf { it.toInt() and 0xff })
            .putInt(CNXN.inv()).array()
        session.write(header)
        session.write(banner)

        val incoming = session.readExact(HEADER_SIZE)
        if (incoming.size != HEADER_SIZE) throw AdbProtocolException("Short ADB header")
        val fields = ByteBuffer.wrap(incoming).order(ByteOrder.LITTLE_ENDIAN)
        val command = fields.int
        val arg0 = fields.int
        val arg1 = fields.int
        val length = fields.int
        val checksum = fields.int
        val magic = fields.int
        if (magic != command.inv()) throw AdbProtocolException("Invalid ADB header magic")
        if (length !in 0..MAX_DATA) throw AdbProtocolException("ADB reply exceeds 4096 bytes")
        val payload = session.readExact(length)
        if (payload.size != length) throw AdbProtocolException("Short ADB payload")
        // ADB 1.1 may omit the checksum. When provided, check it before classifying the reply.
        if (checksum != 0 && checksum != payload.sumOf { it.toInt() and 0xff }) {
            throw AdbProtocolException("Invalid ADB payload checksum")
        }
        when (command) {
            CNXN -> {
                if (arg0 < VERSION_MIN || arg1 < MAX_DATA) {
                    throw AdbProtocolException("Unsupported ADB version or maxData")
                }
                // The CNXN payload can contain a serial number; intentionally discard it.
                AdbHandshakeResult.Connected(arg0.toLong() and UINT_MASK, arg1.toLong() and UINT_MASK)
            }
            AUTH -> {
                if (arg0 != AUTH_TOKEN || length != TOKEN_SIZE) {
                    throw AdbProtocolException("Unexpected ADB AUTH reply")
                }
                // Never log or retain the challenge token.
                AdbHandshakeResult.AuthorizationRequired
            }
            STLS -> {
                if (length != 0) throw AdbProtocolException("Unexpected ADB STLS payload")
                AdbHandshakeResult.TlsRequired
            }
            else -> throw AdbProtocolException("Unexpected ADB reply before CNXN")
        }
    }

    private companion object {
        const val HEADER_SIZE = 24
        const val MAX_DATA = 4096
        const val TOKEN_SIZE = 20
        const val AUTH_TOKEN = 1
        const val VERSION_MIN = 0x01000000
        const val VERSION = 0x01000001
        const val UINT_MASK = 0xffffffffL
        const val CNXN = 0x4e584e43
        const val AUTH = 0x48545541
        const val STLS = 0x534c5453
    }
}
