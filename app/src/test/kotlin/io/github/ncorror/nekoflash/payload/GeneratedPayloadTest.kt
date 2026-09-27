package io.github.ncorror.nekoflash.payload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Содержимое, которое приложение записывает на устройство.
 *
 * Оно существует ради аппаратного гейта, поэтому от него требуется ровно две
 * вещи: точный размер и воспроизводимость. Обе проверяются здесь.
 */
class GeneratedPayloadTest {
    @Test
    fun producesExactlyTheRequestedNumberOfBytes() {
        val payload = GeneratedPayload(10_000L)
        val buffer = ByteArray(1024)

        var total = 0L
        while (true) {
            val count = payload.fill(buffer)
            if (count <= 0) break
            total += count
        }

        assertEquals(10_000L, total)
    }

    /** Последний кусок короче буфера, и обрезается он по остатку, а не по буферу. */
    @Test
    fun lastChunkIsTruncatedToTheRemainder() {
        val payload = GeneratedPayload(1_500L)
        val buffer = ByteArray(1024)

        assertEquals(1024, payload.fill(buffer))
        assertEquals(476, payload.fill(buffer))
        assertEquals(0, payload.fill(buffer))
    }

    @Test
    fun emptyPayloadProducesNothingAtOnce() {
        assertEquals(0, GeneratedPayload(0L).fill(ByteArray(64)))
    }

    /** Дважды порождённое одно и то же — иначе прогон нечем повторить. */
    @Test
    fun contentIsReproducible() {
        val first = ByteArray(300)
        val second = ByteArray(300)

        GeneratedPayload(300L).fill(first)
        GeneratedPayload(300L).fill(second)

        assertEquals(first.toList(), second.toList())
    }

    /**
     * Содержимое не зависит от того, какими порциями его брали.
     *
     * Иначе отпечаток менялся бы от размера буфера, и сверка с устройством
     * потеряла бы смысл.
     */
    @Test
    fun contentDoesNotDependOnChunkSize() {
        val whole = ByteArray(500).also { GeneratedPayload(500L).fill(it) }

        val pieced = ByteArray(500)
        val payload = GeneratedPayload(500L)
        val small = ByteArray(64)
        var offset = 0
        while (true) {
            val count = payload.fill(small)
            if (count <= 0) break
            small.copyInto(pieced, offset, 0, count)
            offset += count
        }

        assertEquals(whole.toList(), pieced.toList())
    }

    /**
     * Период узора не совпадает с границей блока протокола.
     *
     * Узор с периодом 256 повторялся бы ровно на каждой границе блока в 64 КиБ,
     * и переставленные местами блоки дали бы тот же отпечаток. Проверяется на
     * самой границе: байт в её начале и байт блоком дальше не совпадают.
     */
    @Test
    fun patternDoesNotRepeatOnTheProtocolChunkBoundary() {
        val chunk = 64 * 1024
        val content = ByteArray(chunk * 2)
        val payload = GeneratedPayload(content.size.toLong())
        val buffer = ByteArray(chunk)

        payload.fill(buffer)
        buffer.copyInto(content, 0)
        payload.fill(buffer)
        buffer.copyInto(content, chunk)

        assertNotEquals(content[0], content[chunk])
        assertTrue(content.take(chunk) != content.drop(chunk))
    }
}
