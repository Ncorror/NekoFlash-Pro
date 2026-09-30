package io.github.ncorror.nekoflash.protocol.adb

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Потоковый UTF-8 decoder для вывода shell.
 *
 * Граница ADB-пакета или `shell,v2` frame не является границей символа. Если
 * декодировать каждый кусок отдельно, многобайтовый символ, разрезанный между
 * двумя кусками, превращается в replacement character. Здесь незавершённый
 * хвост сохраняется до следующего вызова.
 */
internal class IncrementalUtf8Decoder {
    private val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)

    private var pending = ByteArray(0)

    /** Декодирует очередной кусок, не завершая поток. */
    fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val combined = if (pending.isEmpty()) bytes else pending + bytes
        val input = ByteBuffer.wrap(combined)
        val output = CharBuffer.allocate(maxOf(MIN_CHAR_BUFFER, combined.size + CHAR_BUFFER_SLACK))
        val result = decoder.decode(input, output, false)
        check(!result.isError) { "UTF-8 decoder failed despite replacement policy: $result" }
        pending = input.remainingBytes()
        return output.asText()
    }

    /**
     * Завершает поток и отдаёт незавершённый хвост как replacement character.
     * После этого decoder можно использовать для нового потока.
     */
    fun finish(): String {
        val input = ByteBuffer.wrap(pending)
        val output = CharBuffer.allocate(maxOf(MIN_CHAR_BUFFER, pending.size + CHAR_BUFFER_SLACK))
        val decoded = decoder.decode(input, output, true)
        check(!decoded.isError && !decoded.isOverflow) { "UTF-8 final decode failed: $decoded" }
        val flushed = decoder.flush(output)
        check(!flushed.isError && !flushed.isOverflow) { "UTF-8 decoder flush failed: $flushed" }
        pending = ByteArray(0)
        decoder.reset()
        return output.asText()
    }

    private fun ByteBuffer.remainingBytes(): ByteArray =
        ByteArray(remaining()).also { bytes -> get(bytes) }

    private fun CharBuffer.asText(): String {
        flip()
        return toString()
    }

    private companion object {
        const val MIN_CHAR_BUFFER = 8
        const val CHAR_BUFFER_SLACK = 2
    }
}
