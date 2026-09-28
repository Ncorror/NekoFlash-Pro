package io.github.ncorror.nekoflash.protocol.adb

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Кодеки `sync` второй версии против эталонных программ.
 *
 * `sync-codec/input.bin` (28 940 байт: код и документы, случайные байты,
 * русский текст, повторы) сжат настоящими `lz4` 1.9.4, `zstd` 1.5.5 и
 * `brotli` 1.1.0 с разными ключами; `large` — шесть копий `input.bin` и
 * 100 000 нулей, чтобы потоки шли через несколько блоков и мета-блоков.
 * Вывод кодировщиков приложения проверен теми же программами (`-d`) при
 * записи этих сумм; здесь сумма ловит любое изменение кодировщика.
 */
class SyncCodecTest {
    private val input = resource("input.bin")
    private val large = ByteArray(input.size * LARGE_COPIES + LARGE_ZEROS).also { bytes ->
        repeat(LARGE_COPIES) { input.copyInto(bytes, it * input.size) }
    }

    @Test
    fun decodesWhatTheReferenceToolsWrote() {
        val random = Random(1)
        for (name in REFERENCE) {
            val expected = if (name.startsWith("large")) large else input
            assertArrayEquals(name, expected, decode(codecOf(name), resource(name), random))
        }
    }

    @Test
    fun decodesOneByteAtATime() {
        for (name in listOf("input.q11.br", "input.w10.br", "input.l19.zst", "input.linked.lz4")) {
            val decoder = codecOf(name).decoder()
            val out = ByteArrayOutputStream()
            for (b in resource(name)) decoder.feed(byteArrayOf(b)) { out.write(it) }
            decoder.end { out.write(it) }
            assertArrayEquals(name, input, out.toByteArray())
        }
    }

    @Test
    fun encodersWriteWhatTheReferenceToolsRead() {
        val random = Random(2)
        for ((key, sha256) in ENCODED) {
            val (name, codec) = key
            val data = if (name == "large") large else input
            val encoded = encode(codec, data, random)
            assertEquals("$name $codec", sha256, sha256(encoded))
            assertArrayEquals("$name $codec", data, decode(codec, encoded, random))
        }
    }

    @Test
    fun emptyFileRoundTrips() {
        for (codec in SyncCompression.entries) {
            val encoded = encode(codec, ByteArray(0), Random(3))
            assertArrayEquals(codec.name, ByteArray(0), decode(codec, encoded, Random(3)))
        }
    }

    @Test
    fun streamCutShortFailsAtDone() {
        for (name in listOf("input.lz4", "input.l1.zst", "input.q5.br")) {
            val data = resource(name)
            val decoder = codecOf(name).decoder()
            decoder.feed(data.copyOf(data.size - 1)) { }
            assertThrows(name, SyncCodecException::class.java) { decoder.end { } }
        }
    }

    @Test
    fun bytesAfterTheStreamFail() {
        for (name in listOf("input.lz4", "input.l1.zst", "input.q5.br")) {
            val decoder = codecOf(name).decoder()
            assertThrows(name, SyncCodecException::class.java) {
                decoder.feed(resource(name) + byteArrayOf(0)) { }
                decoder.end { }
            }
        }
    }

    @Test
    fun damagedContentChecksumFails() {
        for (name in listOf("input.lz4", "input.l1.zst")) {
            val data = resource(name)
            data[data.size - 1] = (data[data.size - 1].toInt() xor 1).toByte()
            val decoder = codecOf(name).decoder()
            assertThrows(name, SyncCodecException::class.java) {
                decoder.feed(data) { }
                decoder.end { }
            }
        }
    }

    @Test
    fun anyPrefersZstdThenLz4ThenBrotli() {
        val all = SyncCompression.entries.map { it.feature }.toSet()
        assertEquals(SyncCompression.ZSTD, SyncCompression.any(all))
        assertEquals(SyncCompression.LZ4, SyncCompression.any(all - "sendrecv_v2_zstd"))
        assertEquals(SyncCompression.BROTLI, SyncCompression.any(setOf("sendrecv_v2_brotli")))
        assertNull(SyncCompression.any(setOf("sendrecv_v2")))
        assertNull(SyncCompression.named("none", all))
        assertEquals(SyncCompression.LZ4, SyncCompression.named("lz4", emptySet()))
        assertEquals(SyncCompression.BROTLI, SyncCompression.named("any", setOf("sendrecv_v2_brotli")))
    }

    /** Таблица контекстов литералов — та же, что `_kBrotliContextLookupTable` (CRC-32 её 2048 байт). */
    @Test
    fun brotliContextTableMatchesTheReference() {
        val crc = CRC32().apply { update(ByteArray(2048) { BrotliTables.CONTEXT_LOOKUP[it].toByte() }) }
        assertEquals(1_813_288_888L, crc.value)
        assertEquals(122_784, BrotliDictionary.data.size)
    }

    private fun decode(codec: SyncCompression, data: ByteArray, random: Random): ByteArray {
        val decoder = codec.decoder()
        val out = ByteArrayOutputStream()
        var at = 0
        while (at < data.size) {
            val take = minOf(data.size - at, 1 + random.nextInt(CHUNK))
            decoder.feed(data.copyOfRange(at, at + take)) { out.write(it) }
            at += take
        }
        decoder.end { out.write(it) }
        return out.toByteArray()
    }

    private fun encode(codec: SyncCompression, data: ByteArray, random: Random): ByteArray {
        val encoder = codec.encoder()
        val out = ByteArrayOutputStream()
        var at = 0
        while (at < data.size) {
            val take = minOf(data.size - at, 1 + random.nextInt(CHUNK))
            encoder.feed(data.copyOfRange(at, at + take), take) { out.write(it) }
            at += take
        }
        encoder.end { out.write(it) }
        return out.toByteArray()
    }

    private fun codecOf(name: String): SyncCompression = when {
        name.endsWith(".lz4") -> SyncCompression.LZ4
        name.endsWith(".zst") -> SyncCompression.ZSTD
        else -> SyncCompression.BROTLI
    }

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("sync-codec/$name")) { name }.use { it.readBytes() }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val LARGE_COPIES = 6
        const val LARGE_ZEROS = 100_000
        const val CHUNK = 20_000

        val REFERENCE = listOf(
            "input.lz4", "input.linked.lz4", "input.b4x.lz4", "large.lz4",
            "input.l1.zst", "input.l19.zst", "input.nocheck.zst", "input.fast.zst", "large.zst",
            "input.q0.br", "input.q1.br", "input.q2.br", "input.q5.br", "input.q9.br", "input.q11.br",
            "input.w10.br", "input.w24.br", "large.br",
        )

        val ENCODED = mapOf(
            ("input" to SyncCompression.LZ4) to "e5d3f9e02f37b79fef0ad8ac667385cb3b4151c5be367dd83790f8948be24647",
            ("input" to SyncCompression.ZSTD) to "20e50d4b320287034504f6359ffd9ccd94bb1056fc976f43d5f12492346ddf59",
            ("input" to SyncCompression.BROTLI) to "9ed6698de6713ff09a41678b5a2564e0b50afdd0a0149dee6e1946ad1e0e9466",
            ("large" to SyncCompression.LZ4) to "5eccea4f9aa2ab29ff33428f65b680903f080d2d9082dea582701e4d2ebcb0e6",
            ("large" to SyncCompression.ZSTD) to "93d35e14c216ba2caea6b721a280015f05389fa9c7d2f9dccccb60cd0eb25cbf",
            ("large" to SyncCompression.BROTLI) to "3952ea6e5ddca21bff5dfc10247eedd6933a8f9798e60746878704e0560058cf",
        )
    }
}
