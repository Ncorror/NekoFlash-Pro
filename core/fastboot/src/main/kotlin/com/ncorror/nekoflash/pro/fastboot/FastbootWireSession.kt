package com.ncorror.nekoflash.pro.fastboot

import com.ncorror.nekoflash.pro.transport.FastbootPacketTransport

sealed interface FastbootWireResult {
    data class Okay(val message: String) : FastbootWireResult
    data class Fail(val reason: String) : FastbootWireResult
    /** The connection has been closed; a later data-capable executor must handle this phase. */
    data class DataPhaseRequired(val byteCount: Long) : FastbootWireResult
}

sealed interface FastbootWireMessage {
    data class Info(val text: String) : FastbootWireMessage
    data class Text(val text: String) : FastbootWireMessage
}

class FastbootProtocolException(message: String) : Exception(message)

/** Executes one ordinary wire request and streams diagnostics without storing an unbounded log.
 * Fastboot DATA is deliberately not acknowledged without a data-phase implementation. */
class FastbootWireSession(private val transport: FastbootPacketTransport) : AutoCloseable {
    private var closed = false

    fun execute(request: String, onMessage: (FastbootWireMessage) -> Unit = {}): FastbootWireResult {
        check(!closed) { "Fastboot session is closed" }
        val command = request.toByteArray(Charsets.UTF_8)
        require(command.isNotEmpty() && command.size <= MAX_COMMAND_SIZE && 0.toByte() !in command) {
            "Fastboot command must fit in one protocol packet without a NUL byte"
        }

        try {
            transport.writePacket(command)
            while (true) {
                val response = transport.readPacket(MAX_RESPONSE_SIZE)
                if (response.size !in 4..MAX_RESPONSE_SIZE) {
                    throw FastbootProtocolException("Malformed Fastboot response size: ${response.size}")
                }
                val status = response.copyOfRange(0, 4).toString(Charsets.US_ASCII)
                val payload = response.copyOfRange(4, response.size).toString(Charsets.UTF_8)
                when (status) {
                    "INFO" -> onMessage(FastbootWireMessage.Info(payload))
                    "TEXT" -> onMessage(FastbootWireMessage.Text(payload.substringBefore('\u0000')))
                    "OKAY" -> return FastbootWireResult.Okay(payload)
                    "FAIL" -> return FastbootWireResult.Fail(payload)
                    "DATA" -> {
                        if (response.size != 12 || !payload.matches(Regex("[0-9a-fA-F]{8}"))) {
                            throw FastbootProtocolException("Malformed Fastboot DATA response")
                        }
                        close() // Until a data-phase runner exists, this transport cannot be reused.
                        return FastbootWireResult.DataPhaseRequired(payload.toLong(16))
                    }
                    else -> throw FastbootProtocolException("Unknown Fastboot status: $status")
                }
            }
        } catch (failure: Exception) {
            try {
                close()
            } catch (closeFailure: Exception) {
                failure.addSuppressed(closeFailure)
            }
            throw failure
        }
    }

    override fun close() {
        if (!closed) {
            closed = true
            transport.close()
        }
    }

    private companion object {
        // AOSP fastboot protocol: command <= 4096 bytes; response <= 256 bytes.
        const val MAX_COMMAND_SIZE = 4096
        const val MAX_RESPONSE_SIZE = 256
    }
}
