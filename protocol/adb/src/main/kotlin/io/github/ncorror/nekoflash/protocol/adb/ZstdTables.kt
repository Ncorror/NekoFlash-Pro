package io.github.ncorror.nekoflash.protocol.adb

/** Таблицы и примитивы zstd из RFC 8878: базы кодов, предопределённые распределения, FSE, биты. */
internal object ZstdTables {
    val LL_BASE = intArrayOf(
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 18, 20, 22, 24, 28, 32, 40, 48, 64, 128, 256, 512,
        1024, 2048, 4096, 8192, 16384, 32768, 65536,
    )
    val LL_BITS = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        1, 1, 1, 1, 2, 2, 3, 3, 4, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
    )
    val ML_BASE = IntArray(53) { code ->
        if (code < 32) code + 3 else intArrayOf(
            35, 37, 39, 41, 43, 47, 51, 59, 67, 83, 99, 131, 259, 515, 1027, 2051, 4099, 8195, 16387, 32771, 65539,
        )[code - 32]
    }
    private val ML_EXTRA_BITS = intArrayOf(1, 1, 1, 1, 2, 2, 3, 3, 4, 4, 5, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
    val ML_BITS = IntArray(53) { code ->
        if (code < 32) 0 else ML_EXTRA_BITS[code - 32]
    }
    val LL_DEFAULT = shortArrayOf(
        4, 3, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 2, 1, 1, 1, 1, 1, -1, -1, -1, -1,
    )
    val ML_DEFAULT = shortArrayOf(
        1, 4, 3, 2, 2, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, -1, -1, -1, -1, -1, -1, -1,
    )
    val OF_DEFAULT = shortArrayOf(
        1, 1, 1, 1, 1, 1, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, -1, -1, -1, -1, -1,
    )
    const val LL_DEFAULT_LOG = 6
    const val ML_DEFAULT_LOG = 6
    const val OF_DEFAULT_LOG = 5
    const val LL_MAX_LOG = 9
    const val ML_MAX_LOG = 9
    const val OF_MAX_LOG = 8
    const val LL_MAX_SYMBOL = 35
    const val ML_MAX_SYMBOL = 52
    const val OF_MAX_SYMBOL = 31

    /** Предопределённое распределение и пределы для одного вида кодов последовательности. */
    class CodeSpec(val defaults: ShortArray, val defaultLog: Int, val maxSymbol: Int, val maxLog: Int)

    val LL = CodeSpec(LL_DEFAULT, LL_DEFAULT_LOG, LL_MAX_SYMBOL, LL_MAX_LOG)
    val OF = CodeSpec(OF_DEFAULT, OF_DEFAULT_LOG, OF_MAX_SYMBOL, OF_MAX_LOG)
    val ML = CodeSpec(ML_DEFAULT, ML_DEFAULT_LOG, ML_MAX_SYMBOL, ML_MAX_LOG)

    fun highBit(value: Int): Int = 31 - Integer.numberOfLeadingZeros(value)

    /** Разброс символов по состояниям — общий для декодера и кодировщика (`FSE_buildDTable`). */
    fun spread(counts: ShortArray, log: Int): IntArray {
        val size = 1 shl log
        val symbols = IntArray(size)
        var high = size - 1
        for (s in counts.indices) if (counts[s].toInt() == -1) symbols[high--] = s
        val step = (size ushr 1) + (size ushr 3) + 3
        var position = 0
        for (s in counts.indices) {
            repeat(maxOf(counts[s].toInt(), 0)) {
                symbols[position] = s
                do position = (position + step) and (size - 1) while (position > high)
            }
        }
        corruptIf(position != 0) { "zstd FSE table spread" }
        return symbols
    }
}

/** Таблица декодирования FSE: символ, число бит и база следующего состояния. */
internal class FseTable(val log: Int, val symbol: IntArray, val bits: IntArray, val base: IntArray) {
    companion object {
        fun build(counts: ShortArray, log: Int): FseTable {
            val size = 1 shl log
            val symbols = ZstdTables.spread(counts, log)
            val next = IntArray(counts.size) { s -> if (counts[s].toInt() == -1) 1 else maxOf(counts[s].toInt(), 0) }
            val bits = IntArray(size)
            val base = IntArray(size)
            for (u in 0 until size) {
                val s = symbols[u]
                val state = next[s]++
                bits[u] = log - ZstdTables.highBit(state)
                base[u] = (state shl bits[u]) - size
            }
            return FseTable(log, symbols, bits, base)
        }

        /** Таблица из одного символа (режим RLE). */
        fun rle(symbol: Int): FseTable = FseTable(0, intArrayOf(symbol), intArrayOf(0), intArrayOf(0))

        /**
         * Описание распределения в прямом потоке (`FSE_readNCount`): возвращает
         * таблицу и сколько байт занято.
         */
        fun read(source: ByteArray, from: Int, end: Int, maxSymbol: Int, maxLog: Int): Pair<FseTable, Int> {
            val bits = ForwardBits(source, from, end)
            val log = bits.read(4) + 5
            corruptIf(log > maxLog) { "zstd FSE accuracy $log" }
            val counts = ShortArray(maxSymbol + 1)
            var remaining = (1 shl log) + 1
            var threshold = 1 shl log
            var width = log + 1
            var symbol = 0
            var previousZero = false
            while (remaining > 1 && symbol <= maxSymbol) {
                if (previousZero) {
                    symbol = zeroRun(bits, symbol)
                    corruptIf(symbol > maxSymbol + 1) { "zstd FSE zero run" }
                    previousZero = false
                    if (symbol > maxSymbol) break
                }
                val max = (2 * threshold - 1) - remaining
                val low = bits.peek(width - 1)
                val count: Int
                if (low < max) {
                    count = low
                    bits.skip(width - 1)
                } else {
                    var value = bits.peek(width)
                    if (value >= threshold) value -= max
                    count = value
                    bits.skip(width)
                }
                val probability = count - 1
                remaining -= if (probability < 0) -probability else probability
                counts[symbol++] = probability.toShort()
                previousZero = probability == 0
                while (remaining < threshold) {
                    width--
                    threshold = threshold ushr 1
                }
            }
            corruptIf(remaining != 1) { "zstd FSE distribution" }
            return build(counts.copyOf(symbol), log) to bits.bytesUsed()
        }

        /** Серия нулевых вероятностей: пары бит, `3` — продолжение. */
        private fun zeroRun(bits: ForwardBits, from: Int): Int {
            var symbol = from
            do {
                val flag = bits.read(2)
                symbol += flag
            } while (flag == 3)
            return symbol
        }
    }
}

/** Прямой поток бит, младший бит первым. */
internal class ForwardBits(private val source: ByteArray, private val from: Int, private val end: Int) {
    private var position = 0L

    fun peek(count: Int): Int {
        var value = 0L
        for (i in 0 until count) {
            val bit = position + i
            val index = from + (bit ushr 3).toInt()
            val b = if (index < end) (source[index].toInt() ushr (bit and 7).toInt()) and 1 else 0
            value = value or (b.toLong() shl i)
        }
        return value.toInt()
    }

    fun skip(count: Int) {
        position += count
        corruptIf(from + ((position + 7) ushr 3) > end) { "zstd FSE description truncated" }
    }

    fun read(count: Int): Int = peek(count).also { skip(count) }

    fun bytesUsed(): Int = ((position + 7) ushr 3).toInt()
}

/**
 * Обратный поток бит zstd: читается с конца, в последнем байте старший
 * единичный бит — метка начала. Чтение за начало даёт нули и отмечается.
 */
internal class BackwardBits(private val source: ByteArray, private val from: Int, end: Int) {
    private var position: Long

    init {
        corruptIf(end <= from) { "zstd empty bitstream" }
        val last = source[end - 1].toInt() and BYTE
        corruptIf(last == 0) { "zstd bitstream without end mark" }
        position = (end - from - 1) * 8L + ZstdTables.highBit(last)
    }

    val overflowed: Boolean get() = position < 0
    val finished: Boolean get() = position == 0L

    /** Прочитано всё до метки, если последние [lookahead] бит были только подсмотрены. */
    fun consumedExactly(lookahead: Int): Boolean = position + lookahead == 0L

    fun read(count: Int): Int {
        if (count == 0) return 0
        position -= count
        var value = 0L
        for (i in 0 until count) {
            val bit = position + i
            val b = if (bit < 0) 0 else (source[from + (bit ushr 3).toInt()].toInt() ushr (bit and 7).toInt()) and 1
            value = value or (b.toLong() shl i)
        }
        return value.toInt()
    }

    private companion object {
        const val BYTE = 0xFF
    }
}
