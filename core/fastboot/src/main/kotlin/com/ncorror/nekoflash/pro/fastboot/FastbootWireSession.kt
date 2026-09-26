package com.ncorror.nekoflash.pro.fastboot

import com.ncorror.nekoflash.pro.transport.FastbootPacketTransport
import java.io.EOFException
import java.io.InputStream

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

/** Executes bounded wire requests. The read-only path closes on unexpected DATA;
 * the explicit download path handles DATA only after its size is verified. */
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
                if (response.isEmpty()) continue // Ignore a USB zero-length packet before the reply.
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

    /** Transfers one bounded raw image into device RAM. This does not send flash:PARTITION.
     * The caller owns [source] and must not issue a write command without a separate plan. */
    fun download(
        source: InputStream,
        byteCount: Long,
        onProgress: (Long) -> Unit = {},
        onMessage: (FastbootWireMessage) -> Unit = {},
    ): FastbootWireResult {
        check(!closed) { "Fastboot session is closed" }
        require(byteCount in 1..0xffff_ffffL) { "Fastboot download size exceeds the 8-digit protocol field" }
        try {
            transport.expectLongRunningOperation()
            transport.writePacket("download:${byteCount.toString(16).padStart(8, '0')}".toByteArray(Charsets.US_ASCII))
            var dataStarted = false
            while (true) {
                val response = transport.readPacket(MAX_RESPONSE_SIZE)
                if (response.isEmpty()) continue
                if (response.size !in 4..MAX_RESPONSE_SIZE) {
                    throw FastbootProtocolException("Malformed Fastboot response size: ${response.size}")
                }
                val status = response.copyOfRange(0, 4).toString(Charsets.US_ASCII)
                val payload = response.copyOfRange(4, response.size).toString(Charsets.UTF_8)
                when (status) {
                    "INFO" -> onMessage(FastbootWireMessage.Info(payload))
                    "TEXT" -> onMessage(FastbootWireMessage.Text(payload.substringBefore('\u0000')))
                    "FAIL" -> return FastbootWireResult.Fail(payload)
                    "OKAY" -> {
                        if (!dataStarted) throw FastbootProtocolException("Fastboot download ended before DATA")
                        return FastbootWireResult.Okay(payload)
                    }
                    "DATA" -> {
                        // Report only frame length and validated numeric metadata. Never log
                        // arbitrary device bytes, image contents, or file identifiers.
                        val offer = if (response.size >= 12) {
                            response.copyOfRange(4, 12).toString(Charsets.US_ASCII)
                                .takeIf { it.matches(Regex("[0-9a-fA-F]{8}")) }?.toLong(16)
                        } else null
                        if (response.size != 12 || offer == null) {
                            val trailingZeros = response.size > 12 &&
                                response.copyOfRange(12, response.size).all { it == 0.toByte() }
                            throw FastbootProtocolException(
                                "Malformed Fastboot DATA reply bytes:${response.size} " +
                                    "advertised:${offer ?: "unknown"} trailingZeros:$trailingZeros")
                        }
                        if (dataStarted) throw FastbootProtocolException("Duplicate Fastboot DATA reply")
                        if (offer != byteCount) throw FastbootProtocolException(
                            "Fastboot DATA size mismatch requested:$byteCount advertised:$offer")
                        dataStarted = true
                        val buffer = ByteArray(MAX_DATA_CHUNK_SIZE)
                        var sent = 0L
                        while (sent < byteCount) {
                            val amount = minOf(buffer.size.toLong(), byteCount - sent).toInt()
                            val count = source.read(buffer, 0, amount)
                            if (count < 0) throw EOFException("Fastboot image ended before declared size")
                            if (count == 0) throw FastbootProtocolException("Fastboot image source made no progress")
                            transport.writeData(buffer.copyOf(count))
                            sent += count
                            onProgress(sent)
                        }
                        if (source.read() != -1) {
                            throw FastbootProtocolException("Fastboot image exceeds declared size")
                        }
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
        @Suppress("UNREACHABLE_CODE")
        throw AssertionError("Fastboot download loop ended")
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
        // DATA is a byte stream. Keep file reads bounded while reducing USB host calls.
        const val MAX_DATA_CHUNK_SIZE = 64 * 1024
    }
}
