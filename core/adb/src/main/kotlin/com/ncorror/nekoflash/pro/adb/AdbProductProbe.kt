package com.ncorror.nekoflash.pro.adb

import com.ncorror.nekoflash.pro.transport.AdbByteTransport
import java.io.ByteArrayOutputStream

sealed interface AdbProductResult {
    data class Value(val productCode: String, val connection: AdbHandshakeResult.Connected) : AdbProductResult
    data object TlsRequired : AdbProductResult
}

/** Runs exactly one fixed read-only ADB shell request after authorization. */
class AdbProductProbe(private val transport: AdbByteTransport, private val key: AdbHostKey) {
    fun read(onAwaitingApproval: () -> Unit): AdbProductResult = transport.use { session ->
        when (val connection = AdbAuthorizationSession(session, key).handshake(onAwaitingApproval)) {
            is AdbHandshakeResult.Connected -> {
                val wire = AdbWire(session, connection.maxData.coerceAtMost(
                    AdbWire.MAX_NEGOTIATED_DATA.toLong()).toInt())
                wire.send(AdbWire.OPEN, LOCAL_ID, 0, SERVICE)
                val output = ByteArrayOutputStream()
                var remoteId = 0
                var packets = 0
                while (true) {
                    if (++packets > MAX_PACKETS) throw AdbProtocolException("ADB property reply took too many packets")
                    val packet = wire.receive()
                    if (packet.arg1 != LOCAL_ID || packet.arg0 < 0 ||
                        (packet.arg0 == 0 && packet.command != AdbWire.CLSE) ||
                        packet.payload.size > MAX_OUTPUT) {
                        throw AdbProtocolException(
                            "Invalid ADB property stream packet: ${packet.headerSummary()}")
                    }
                    when (packet.command) {
                        AdbWire.OKAY -> {
                            if (remoteId != 0 || packet.payload.isNotEmpty()) {
                                throw AdbProtocolException("Unexpected ADB stream acknowledgement")
                            }
                            remoteId = packet.arg0
                        }
                        AdbWire.WRTE -> {
                            if (remoteId == 0 || packet.arg0 != remoteId ||
                                output.size() + packet.payload.size > MAX_OUTPUT) {
                                throw AdbProtocolException("Invalid ADB property output")
                            }
                            output.write(packet.payload)
                            wire.send(AdbWire.OKAY, LOCAL_ID, remoteId, byteArrayOf())
                        }
                        AdbWire.CLSE -> {
                            if (packet.payload.isNotEmpty() || packet.arg0 != remoteId) {
                                throw AdbProtocolException("Invalid ADB property close")
                            }
                            if (remoteId == 0) throw AdbProtocolException("ADB property service rejected")
                            return@use AdbProductResult.Value(parseProduct(output.toByteArray()), connection)
                        }
                        else -> throw AdbProtocolException("Unexpected ADB property stream reply")
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                throw AssertionError("ADB property loop ended")
            }
            AdbHandshakeResult.TlsRequired -> AdbProductResult.TlsRequired
            AdbHandshakeResult.AuthorizationRequired -> throw AdbProtocolException("ADB authorization required")
        }
    }

    private fun parseProduct(bytes: ByteArray): String {
        val raw = bytes.toString(Charsets.US_ASCII)
        val value = raw.removeSuffix("\n").removeSuffix("\r")
        if (value.isEmpty() || value.length > 64 || !value.all {
                it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._-"
            } || !bytes.contentEquals(raw.toByteArray(Charsets.US_ASCII))) {
            throw AdbProtocolException("Invalid ADB product code")
        }
        return value
    }

    private companion object {
        const val LOCAL_ID = 1
        const val MAX_OUTPUT = 128
        const val MAX_PACKETS = 32
        val SERVICE = "shell:getprop ro.product.device\u0000".toByteArray(Charsets.US_ASCII)
    }
}
