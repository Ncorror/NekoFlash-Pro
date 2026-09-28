package io.github.ncorror.nekoflash.protocol.adb

/** Вход кончился посреди мета-блока: разбор повторится с его начала, когда данных станет больше. */
internal class BrotliNeedMore : RuntimeException(null, null, false, false)

/**
 * Поток бит Brotli, младший бит первым. Подглядывание за конец данных даёт
 * нули, а потребление за конец — [BrotliNeedMore].
 */
internal class BrotliBits(private val source: ByteArray, private val limit: Int, start: Int) {
    private var acc = 0L
    private var count = 0
    private var next = start

    /** Позиция в битах от начала [source]. */
    val position: Long get() = next * Byte.SIZE_BITS.toLong() - count

    fun peek(bits: Int): Int {
        if (count < bits) fill()
        return (acc and ((1L shl bits) - 1)).toInt()
    }

    fun drop(bits: Int) {
        if (count < bits) {
            fill()
            if (count < bits) throw BrotliNeedMore()
        }
        acc = acc ushr bits
        count -= bits
    }

    fun read(bits: Int): Int {
        if (bits == 0) return 0
        val value = peek(bits)
        drop(bits)
        return value
    }

    /** До границы байта; `false` — в заполнителе не нули (`BrotliJumpToByteBoundary`). */
    fun alignToByte(): Boolean {
        if (count < Byte.SIZE_BITS) fill()
        return read(count and (Byte.SIZE_BITS - 1)) == 0
    }

    /** [length] байт с границы байта — несжатый мета-блок. */
    fun bytes(length: Int, sink: (ByteArray, Int, Int) -> Unit) {
        val at = (position / Byte.SIZE_BITS).toInt()
        if (limit - at < length) throw BrotliNeedMore()
        sink(source, at, length)
        next = at + length
        acc = 0
        count = 0
    }

    private fun fill() {
        while (count <= FILL_LIMIT && next < limit) {
            acc = acc or ((source[next++].toLong() and BYTE) shl count)
            count += Byte.SIZE_BITS
        }
    }

    private companion object {
        const val FILL_LIMIT = 56
        const val BYTE = 0xFFL
    }
}

/**
 * Канонический префиксный код Brotli. Коды до [ROOT] бит декодируются
 * таблицей, длиннее — побитно по первым кодам каждой длины.
 */
internal class BrotliPrefixCode private constructor(
    private val root: IntArray,
    private val first: IntArray,
    private val count: IntArray,
    private val offset: IntArray,
    private val sorted: IntArray,
    private val single: Int,
) {
    fun read(bits: BrotliBits): Int {
        if (single >= 0) return single
        val entry = root[bits.peek(ROOT)]
        if (entry >= 0) {
            bits.drop(entry ushr ENTRY_SHIFT)
            return entry and ENTRY_MASK
        }
        var code = Integer.reverse(bits.peek(ROOT)) ushr (Int.SIZE_BITS - ROOT)
        bits.drop(ROOT)
        for (length in ROOT + 1..MAX_LENGTH) {
            code = (code shl 1) or bits.read(1)
            if (code - first[length] < count[length]) return sorted[offset[length] + code - first[length]]
        }
        throw SyncCodecException("Brotli prefix code")
    }

    companion object {
        const val ROOT = 8
        const val MAX_LENGTH = 15
        private const val ENTRY_SHIFT = 16
        private const val ENTRY_MASK = 0xFFFF

        /** Код из одного символа: читается без бит. */
        fun single(symbol: Int): BrotliPrefixCode =
            BrotliPrefixCode(IntArray(0), IntArray(0), IntArray(0), IntArray(0), IntArray(0), symbol)

        /** Код из длин: символы одной длины идут по возрастанию, как в `BrotliBuildHuffmanTable`. */
        fun of(lengths: IntArray): BrotliPrefixCode {
            val count = IntArray(MAX_LENGTH + 1)
            for (length in lengths) if (length > 0) count[length]++
            val offset = IntArray(MAX_LENGTH + 2)
            for (length in 1..MAX_LENGTH) offset[length + 1] = offset[length] + count[length]
            val sorted = IntArray(offset[MAX_LENGTH + 1])
            val fill = offset.copyOf()
            for (symbol in lengths.indices) if (lengths[symbol] > 0) sorted[fill[lengths[symbol]]++] = symbol
            val first = IntArray(MAX_LENGTH + 1)
            var code = 0
            for (length in 1..MAX_LENGTH) {
                code = (code + count[length - 1]) shl 1
                first[length] = code
            }
            val root = IntArray(1 shl ROOT) { -1 }
            for (length in 1..ROOT) {
                for (i in 0 until count[length]) {
                    val reversed = Integer.reverse(first[length] + i) ushr (Int.SIZE_BITS - length)
                    val entry = (length shl ENTRY_SHIFT) or sorted[offset[length] + i]
                    for (high in 0 until (1 shl (ROOT - length))) root[reversed or (high shl length)] = entry
                }
            }
            return BrotliPrefixCode(root, first, count, offset, sorted, -1)
        }

        /** `ReadHuffmanCode`: простой (1..4 символа) или сложный код. */
        fun read(bits: BrotliBits, alphabet: Int): BrotliPrefixCode {
            val skip = bits.read(2)
            return if (skip == 1) simple(bits, alphabet) else complex(bits, alphabet, skip)
        }

        private fun simple(bits: BrotliBits, alphabet: Int): BrotliPrefixCode {
            val symbols = IntArray(bits.read(2) + 1)
            val width = Int.SIZE_BITS - Integer.numberOfLeadingZeros(alphabet - 1)
            for (i in symbols.indices) {
                symbols[i] = bits.read(width)
                corruptIf(symbols[i] >= alphabet) { "Brotli simple code symbol" }
            }
            corruptIf(symbols.toSet().size != symbols.size) { "Brotli simple code repeats" }
            if (symbols.size == 1) return single(symbols[0])
            val shape = when (symbols.size) {
                2 -> intArrayOf(1, 1)
                3 -> intArrayOf(1, 2, 2)
                else -> if (bits.read(1) == 0) intArrayOf(2, 2, 2, 2) else intArrayOf(1, 2, 3, 3)
            }
            val lengths = IntArray(alphabet)
            for (i in symbols.indices) lengths[symbols[i]] = shape[i]
            return of(lengths)
        }

        private fun complex(bits: BrotliBits, alphabet: Int, skip: Int): BrotliPrefixCode {
            val lengthCode = lengthCode(bits, skip)
            val lengths = IntArray(alphabet)
            var symbol = 0
            var space = SPACE
            var previous = INITIAL_REPEATED_LENGTH
            var repeat = 0
            var repeatLength = 0
            while (symbol < alphabet && space > 0) {
                val length = lengthCode.read(bits)
                if (length < REPEAT_PREVIOUS) {
                    repeat = 0
                    if (length != 0) {
                        lengths[symbol] = length
                        previous = length
                        space -= SPACE ushr length
                    }
                    symbol++
                    continue
                }
                val extra = if (length == REPEAT_PREVIOUS) 2 else 3
                val newLength = if (length == REPEAT_PREVIOUS) previous else 0
                var delta = bits.read(extra)
                if (repeatLength != newLength) {
                    repeat = 0
                    repeatLength = newLength
                }
                val old = repeat
                if (repeat > 0) repeat = (repeat - 2) shl extra
                repeat += delta + REPEAT_MIN
                delta = repeat - old
                corruptIf(symbol + delta > alphabet) { "Brotli code length repeat" }
                if (repeatLength != 0) {
                    lengths.fill(repeatLength, symbol, symbol + delta)
                    space -= delta shl (MAX_LENGTH - repeatLength)
                }
                symbol += delta
            }
            corruptIf(space != 0) { "Brotli prefix code space" }
            return of(lengths)
        }

        /** Код длин кодов — 18 длин по статическому коду переменной длины. */
        private fun lengthCode(bits: BrotliBits, skip: Int): BrotliPrefixCode {
            val lengths = IntArray(BrotliTables.CODE_LENGTH_ORDER.size)
            var space = LENGTH_CODE_SPACE
            var codes = 0
            for (i in skip until lengths.size) {
                val peek = bits.peek(LENGTH_CODE_PEEK)
                bits.drop(BrotliTables.CODE_LENGTH_PREFIX_LENGTH[peek])
                val value = BrotliTables.CODE_LENGTH_PREFIX_VALUE[peek]
                lengths[BrotliTables.CODE_LENGTH_ORDER[i]] = value
                if (value != 0) {
                    space -= LENGTH_CODE_SPACE ushr value
                    codes++
                    if (space <= 0) break
                }
            }
            corruptIf(codes != 1 && space != 0) { "Brotli code length code space" }
            if (codes == 1) return single(lengths.indexOfFirst { it != 0 })
            return of(lengths)
        }

        private const val SPACE = 32768
        private const val LENGTH_CODE_SPACE = 32
        private const val LENGTH_CODE_PEEK = 4
        private const val INITIAL_REPEATED_LENGTH = 8
        private const val REPEAT_PREVIOUS = 16
        private const val REPEAT_MIN = 3
    }
}
