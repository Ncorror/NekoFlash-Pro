package io.github.ncorror.nekoflash.protocol.adb

/**
 * Декодер Brotli (RFC 7932) по `BrotliDecoderDecompressStream` из
 * `libbrotli` 1.1.0: окно из заголовка потока, мета-блоки сжатые, несжатые
 * и метаданные, встроенный словарь с преобразованиями.
 *
 * Мета-блок разбирается целиком: если вход кончился посреди него, разбор
 * откатывается к его началу и повторяется, когда входа станет вдвое больше
 * (или пришёл `DONE`). Так работа остаётся линейной, а память — в пределах
 * окна и одного сжатого мета-блока.
 */
internal class BrotliDecoder : SyncDecoder {
    private var input = ByteArray(INITIAL)
    private var length = 0
    private var bitOffset = 0
    private var attempted = 0
    private var windowBits = 0
    private var finished = false
    private val history = BrotliHistory()
    private val ring = BrotliDistanceRing()

    override fun feed(chunk: ByteArray, output: (ByteArray) -> Unit) {
        if (finished) {
            corruptIf(chunk.isNotEmpty()) { "Brotli stream hit end before reading all data" }
            return
        }
        if (length + chunk.size > input.size) input = input.copyOf(maxOf(input.size * 2, length + chunk.size))
        chunk.copyInto(input, length)
        length += chunk.size
        if (length >= attempted * 2) decode(output, last = false)
    }

    override fun end(output: (ByteArray) -> Unit) {
        if (!finished) decode(output, last = true)
    }

    private fun decode(output: (ByteArray) -> Unit, last: Boolean) {
        while (!finished) {
            val bits = BrotliBits(input, length, 0)
            val size = history.size
            val total = history.total
            val saved = ring.save()
            try {
                bits.read(bitOffset)
                if (windowBits == 0) windowBits = windowBits(bits) else metaBlock(bits)
            } catch (_: BrotliNeedMore) {
                history.rollback(size, total)
                ring.restore(saved)
                attempted = length
                corruptIf(last) { "Brotli stream ended in the middle of a meta-block" }
                return
            }
            commit(bits.position)
            history.emit(size, output)
            history.trim(1 shl windowBits)
        }
        corruptIf(length > 0) { "Brotli stream hit end before reading all data" }
    }

    private fun commit(position: Long) {
        val bytes = (position / Byte.SIZE_BITS).toInt()
        input.copyInto(input, 0, bytes, length)
        length -= bytes
        bitOffset = (position % Byte.SIZE_BITS).toInt()
        attempted = 0
    }

    /** `DecodeWindowBits` без большого окна: оно не входит в RFC 7932. */
    private fun windowBits(bits: BrotliBits): Int {
        if (bits.read(1) == 0) return MIN_WINDOW
        val large = bits.read(WINDOW_FIELD)
        if (large != 0) return BASE_WINDOW + large
        val small = bits.read(WINDOW_FIELD)
        corruptIf(small == 1) { "Brotli window bits" }
        return if (small != 0) SMALL_WINDOW + small else BASE_WINDOW
    }

    /** `DecodeMetaBlockLength` и тело мета-блока. */
    private fun metaBlock(bits: BrotliBits) {
        val last = bits.read(1) == 1
        if (last && bits.read(1) == 1) return finish(bits)
        val nibbles = bits.read(2) + MIN_NIBBLES
        if (nibbles == METADATA_NIBBLES) {
            metadata(bits)
        } else {
            var size = 0
            for (i in 0 until nibbles) {
                val nibble = bits.read(NIBBLE)
                corruptIf(i + 1 == nibbles && nibbles > MIN_NIBBLES && nibble == 0) { "Brotli exuberant nibble" }
                size = size or (nibble shl (NIBBLE * i))
            }
            size++
            if (!last && bits.read(1) == 1) {
                corruptIf(!bits.alignToByte()) { "Brotli padding" }
                bits.bytes(size, history::append)
            } else {
                val left = BrotliMetaBlock(bits, history, ring, windowBits).decode(size)
                corruptIf(left < 0) { "Brotli meta-block overrun" }
            }
        }
        if (last) finish(bits)
    }

    private fun metadata(bits: BrotliBits) {
        corruptIf(bits.read(1) != 0) { "Brotli reserved bit" }
        val bytes = bits.read(2)
        var size = 0
        for (i in 0 until bytes) {
            val value = bits.read(Byte.SIZE_BITS)
            corruptIf(i + 1 == bytes && bytes > 1 && value == 0) { "Brotli exuberant metadata size" }
            size = size or (value shl (Byte.SIZE_BITS * i))
        }
        if (bytes > 0) size++
        corruptIf(!bits.alignToByte()) { "Brotli padding" }
        bits.bytes(size) { _, _, _ -> }
    }

    private fun finish(bits: BrotliBits) {
        corruptIf(!bits.alignToByte()) { "Brotli final padding" }
        finished = true
    }

    private companion object {
        const val INITIAL = 1 shl 16
        const val MIN_WINDOW = 16
        const val BASE_WINDOW = 17
        const val SMALL_WINDOW = 8
        const val WINDOW_FIELD = 3
        const val MIN_NIBBLES = 4
        const val METADATA_NIBBLES = 7
        const val NIBBLE = 4
    }
}

/** Последние четыре расстояния (`dist_rb`, `dist_rb_idx`). */
internal class BrotliDistanceRing {
    val values = intArrayOf(16, 15, 11, 4)
    var index = 0

    fun save(): IntArray = values + index

    fun restore(saved: IntArray) {
        saved.copyInto(values, 0, 0, values.size)
        index = saved[values.size]
    }
}

/** Выход потока: окно для ссылок назад и байты текущего мета-блока. */
internal class BrotliHistory {
    private var data = ByteArray(INITIAL)
    var size = 0
        private set
    var total = 0L
        private set

    /** Байт [distance] назад; до начала потока — ноль, как в кольце `libbrotli`. */
    fun back(distance: Int): Int = if (distance > size) 0 else data[size - distance].toInt() and BYTE

    fun put(value: Int) {
        ensure(1)
        data[size++] = value.toByte()
        total++
    }

    fun append(source: ByteArray, from: Int, length: Int) {
        ensure(length)
        source.copyInto(data, size, from, from + length)
        size += length
        total += length
    }

    fun copy(distance: Int, length: Int) {
        ensure(length)
        val from = size - distance
        if (distance >= length) {
            data.copyInto(data, size, from, from + length)
        } else {
            for (i in 0 until length) data[size + i] = data[from + i]
        }
        size += length
        total += length
    }

    fun rollback(size: Int, total: Long) {
        this.size = size
        this.total = total
    }

    fun emit(from: Int, output: (ByteArray) -> Unit) {
        var at = from
        while (at < size) {
            val take = minOf(CHUNK, size - at)
            output(data.copyOfRange(at, at + take))
            at += take
        }
    }

    /** Держит последние [window] байт, сдвигая буфер, когда он вырос вдвое. */
    fun trim(window: Int) {
        if (size <= window * 2) return
        data.copyInto(data, 0, size - window, size)
        size = window
    }

    private fun ensure(extra: Int) {
        if (size + extra > data.size) data = data.copyOf(maxOf(data.size * 2, size + extra))
    }

    private companion object {
        const val INITIAL = 1 shl 16
        const val CHUNK = 1 shl 16
        const val BYTE = 0xFF
    }
}
