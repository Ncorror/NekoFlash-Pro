package com.ncorror.nekoflash.pro.adb

import com.ncorror.nekoflash.pro.transport.AdbByteTransport
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A bounded ADB packet codec. Payloads never leave the calling session for diagnostics. */
internal class AdbWire(
    private val transport: AdbByteTransport,
    private val maxIncomingPayload: Int = MAX_DATA,
) {
    init { require(maxIncomingPayload in MAX_DATA..MAX_NEGOTIATED_DATA) }
    data class Packet(val command: Int, val arg0: Int, val arg1: Int, val payload: ByteArray)

    fun sendHello() = send(CNXN, VERSION, MAX_DATA, "host::".toByteArray(Charsets.US_ASCII))

    fun send(command: Int, arg0: Int, arg1: Int, payload: ByteArray) {
        require(payload.size <= MAX_DATA)
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command).putInt(arg0).putInt(arg1)
            .putInt(payload.size).putInt(payload.sumOf { it.toInt() and 0xff })
            .putInt(command.inv()).array()
        transport.write(header)
        if (payload.isNotEmpty()) transport.write(payload)
    }

    fun receive(awaitApproval: Boolean = false): Packet {
        val incoming = if (awaitApproval) transport.readExactAwaitingApproval(HEADER_SIZE)
            else transport.readExact(HEADER_SIZE)
        if (incoming.size != HEADER_SIZE) throw AdbProtocolException("Short ADB header")
        val fields = ByteBuffer.wrap(incoming).order(ByteOrder.LITTLE_ENDIAN)
        val command = fields.int
        val arg0 = fields.int
        val arg1 = fields.int
        val length = fields.int
        val checksum = fields.int
        val magic = fields.int
        if (magic != command.inv()) throw AdbProtocolException("Invalid ADB header magic")
        if (length !in 0..maxIncomingPayload) throw AdbProtocolException("ADB reply exceeds negotiated limit")
        val payload = transport.readExact(length)
        if (payload.size != length) throw AdbProtocolException("Short ADB payload")
        // ADB 1.1 may omit the checksum; validate it whenever it is present.
        if (checksum != 0 && checksum != payload.sumOf { it.toInt() and 0xff }) {
            throw AdbProtocolException("Invalid ADB payload checksum")
        }
        return Packet(command, arg0, arg1, payload)
    }

    fun connected(packet: Packet): AdbHandshakeResult.Connected {
        if (packet.arg0 < VERSION_MIN || packet.arg1 < MAX_DATA) {
            throw AdbProtocolException("Unsupported ADB version or maxData")
        }
        // The CNXN payload can contain a serial number; deliberately discard it.
        return AdbHandshakeResult.Connected(packet.arg0.toLong() and UINT_MASK,
            packet.arg1.toLong() and UINT_MASK)
    }

    fun token(packet: Packet): ByteArray {
        if (packet.arg0 != AUTH_TOKEN || packet.arg1 != 0 || packet.payload.size != TOKEN_SIZE) {
            throw AdbProtocolException("Unexpected ADB AUTH reply")
        }
        return packet.payload
    }

    fun tls(packet: Packet): AdbHandshakeResult.TlsRequired {
        if (packet.payload.isNotEmpty()) throw AdbProtocolException("Unexpected ADB STLS payload")
        return AdbHandshakeResult.TlsRequired
    }

    companion object {
        const val HEADER_SIZE = 24
        const val MAX_DATA = 4096
        const val MAX_NEGOTIATED_DATA = 256 * 1024
        const val TOKEN_SIZE = 20
        const val AUTH_TOKEN = 1
        const val AUTH_SIGNATURE = 2
        const val AUTH_PUBLIC_KEY = 3
        const val VERSION_MIN = 0x01000000
        const val VERSION = 0x01000001
        const val UINT_MASK = 0xffffffffL
        const val CNXN = 0x4e584e43
        const val AUTH = 0x48545541
        const val STLS = 0x534c5453
        const val OPEN = 0x4e45504f
        const val OKAY = 0x59414b4f
        const val WRTE = 0x45545257
        const val CLSE = 0x45534c43
    }
}
