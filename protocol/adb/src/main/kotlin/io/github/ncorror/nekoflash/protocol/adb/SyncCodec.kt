package io.github.ncorror.nekoflash.protocol.adb

/**
 * Сжатие `sync` второй версии — флаги `kSyncFlagBrotli`, `kSyncFlagLZ4`,
 * `kSyncFlagZstd` (`file_sync_protocol.h`) и кодеки `compression_utils.h`
 * AOSP `packages/modules/adb`.
 */
public enum class SyncCompression(public val flag: Int, public val feature: String) {
    BROTLI(1, "sendrecv_v2_brotli"),
    LZ4(2, "sendrecv_v2_lz4"),
    ZSTD(4, "sendrecv_v2_zstd"),
    ;

    /** Новый декодер потока, который пришлёт устройство. */
    public fun decoder(): SyncDecoder = when (this) {
        LZ4 -> Lz4FrameDecoder()
        ZSTD -> ZstdDecoder()
        BROTLI -> BrotliDecoder()
    }

    /** Новый кодировщик потока, который уйдёт устройству. */
    public fun encoder(): SyncEncoder = when (this) {
        LZ4 -> Lz4FrameEncoder()
        ZSTD -> ZstdEncoder()
        BROTLI -> BrotliEncoder()
    }

    public companion object {
        /**
         * `ResolveCompressionType` для `any`: zstd, затем lz4, затем brotli —
         * первый, который устройство объявило; `null` — без сжатия.
         */
        public fun any(features: Set<String>): SyncCompression? =
            listOf(ZSTD, LZ4, BROTLI).firstOrNull { it.feature in features }

        /**
         * `ResolveCompressionType` для имени из `-z`: `any` — по [any], `none` —
         * без сжатия, остальное — ровно названный кодек, объявило его
         * устройство или нет: отказ тогда даёт само устройство.
         */
        public fun named(name: String, features: Set<String>): SyncCompression? = when (name) {
            ANY -> any(features)
            NONE -> null
            else -> valueOf(name.uppercase())
        }

        public const val ANY: String = "any"
        public const val NONE: String = "none"
    }
}

/** Сжатый поток испорчен или кончился не там: `decompress failed` у настольного клиента. */
public class SyncCodecException(message: String) : RuntimeException(message)

/**
 * Декодер одного сжатого файла. Данные приходят кусками `DATA`; распакованное
 * отдаётся в `output` по мере готовности. Поток — ровно один кадр: данные
 * после его конца и `DONE` до конца — ошибка, как у `LZ4Decoder` и
 * `ZstdDecoder` AOSP.
 */
public interface SyncDecoder {
    /** Очередной кусок `DATA`. */
    public fun feed(chunk: ByteArray, output: (ByteArray) -> Unit)

    /** Пришёл `DONE`: поток обязан быть закончен; хвост вывода — в `output`. */
    public fun end(output: (ByteArray) -> Unit)
}

/** Кодировщик одного файла: сжатое отдаётся в `output` кусками любого размера. */
public interface SyncEncoder {
    public fun feed(data: ByteArray, length: Int, output: (ByteArray) -> Unit)

    /** Конец файла: хвост потока. */
    public fun end(output: (ByteArray) -> Unit)
}

/** Поток нарушает формат, если [broken]: `decompress failed` с причиной [what]. */
internal inline fun corruptIf(broken: Boolean, what: () -> String) {
    if (broken) throw SyncCodecException(what())
}

/** Четыре байта little-endian — магия и суммы кадров. */
internal fun intLe(value: Int): ByteArray =
    byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())

/**
 * Накопитель входа: куски `DATA` складываются, а разбор снимает с начала,
 * когда целая единица (заголовок, блок) уже пришла.
 */
internal class InputBuffer {
    private var data = ByteArray(INITIAL)
    private var start = 0
    private var end = 0

    val available: Int get() = end - start

    fun append(chunk: ByteArray) {
        if (end + chunk.size > data.size) {
            val live = available
            val grown = if (live + chunk.size > data.size) ByteArray(maxOf(data.size * 2, live + chunk.size)) else data
            data.copyInto(grown, 0, start, end)
            data = grown
            start = 0
            end = live
        }
        chunk.copyInto(data, end)
        end += chunk.size
    }

    fun peek(offset: Int): Int = data[start + offset].toInt() and BYTE

    fun intLe(offset: Int): Int =
        peek(offset) or (peek(offset + 1) shl 8) or (peek(offset + 2) shl 16) or (peek(offset + 3) shl 24)

    fun take(count: Int): ByteArray = data.copyOfRange(start, start + count).also { start += count }

    fun skip(count: Int) {
        start += count
    }

    private companion object {
        const val INITIAL = 1 shl 16
        const val BYTE = 0xFF
    }
}

/** Вывод кусками не больше [limit]: так `DATA` и запись в файл не растут без меры. */
internal class OutputChunks(private val limit: Int, private val output: (ByteArray) -> Unit) {
    private var buffer = ByteArray(limit)
    private var used = 0

    fun write(source: ByteArray, offset: Int, length: Int) {
        var at = offset
        var left = length
        while (left > 0) {
            val take = minOf(left, limit - used)
            source.copyInto(buffer, used, at, at + take)
            used += take
            at += take
            left -= take
            if (used == limit) flush()
        }
    }

    fun flush() {
        if (used > 0) output(buffer.copyOf(used))
        used = 0
    }
}
