package io.github.ncorror.nekoflash.protocol.adb

import java.io.ByteArrayOutputStream
import java.util.PriorityQueue

/**
 * Кодировщик Brotli (RFC 7932) для `push -z brotli`: окно 2^18, мета-блоки
 * по 128 КиБ с одним типом блоков каждого вида и одним деревом. Совпадения
 * ищутся жадно по хешу четырёх байт, расстояния пишутся явными кодами.
 * Мета-блок, который так не уменьшился, уходит несжатым.
 */
internal class BrotliEncoder : SyncEncoder {
    private val buffer = ByteArray(WINDOW + BLOCK)
    private var start = 0
    private var used = 0
    private var started = false
    private val table = IntArray(1 shl HASH_BITS) { -1 }
    private val bits = BrotliBitWriter()

    override fun feed(data: ByteArray, length: Int, output: (ByteArray) -> Unit) {
        begin()
        var at = 0
        while (at < length) {
            val take = minOf(length - at, BLOCK - used)
            data.copyInto(buffer, start + used, at, at + take)
            used += take
            at += take
            if (used == BLOCK) block(output)
        }
    }

    override fun end(output: (ByteArray) -> Unit) {
        begin()
        if (used > 0) block(output)
        bits.add(LAST_EMPTY, 2)
        bits.alignToByte()
        output(bits.drain())
    }

    /** `WBITS` = 18: единица и три бита `18 - 17`. */
    private fun begin() {
        if (started) return
        started = true
        bits.add(WINDOW_HEADER, WINDOW_HEADER_BITS)
    }

    private fun block(output: (ByteArray) -> Unit) {
        val compressed = BrotliBitWriter()
        metaBlockHeader(compressed, used, uncompressed = false)
        commands(compressed)
        if (compressed.bitLength < (used + UNCOMPRESSED_OVERHEAD) * Byte.SIZE_BITS.toLong()) {
            bits.append(compressed)
        } else {
            metaBlockHeader(bits, used, uncompressed = true)
            bits.alignToByte()
            bits.write(buffer, start, used)
        }
        val bytes = bits.drain()
        if (bytes.isNotEmpty()) output(bytes)
        slide()
    }

    private fun metaBlockHeader(out: BrotliBitWriter, size: Int, uncompressed: Boolean) {
        val nibbles = maxOf(MIN_NIBBLES, (Int.SIZE_BITS - Integer.numberOfLeadingZeros(size - 1) + NIBBLE - 1) / NIBBLE)
        out.add(0, 1)
        out.add(nibbles - MIN_NIBBLES, 2)
        out.add(size - 1, nibbles * NIBBLE)
        out.add(if (uncompressed) 1 else 0, 1)
    }

    /** Команды мета-блока: заголовок с одним деревом каждого вида, коды и тело. */
    private fun commands(out: BrotliBitWriter) {
        val found = matches()
        val literals = IntArray(LITERALS)
        val commands = IntArray(COMMANDS)
        val distances = IntArray(DISTANCES)
        for (command in found) {
            for (i in command.from until command.from + command.insert) literals[buffer[i].toInt() and BYTE]++
            commands[command.code]++
            if (command.distance > 0) distances[command.distanceCode]++
        }
        out.add(0, BLOCK_TYPES_AND_DISTANCE_HEADER)
        val literalCode = BrotliHuffman.write(out, literals)
        val commandCode = BrotliHuffman.write(out, commands)
        val distanceCode = BrotliHuffman.write(out, distances)
        for (command in found) {
            commandCode.put(out, command.code)
            val insertCode = command.insertCode
            val copyCode = command.copyCode
            out.add(command.insert - BrotliTables.INSERT_BASE[insertCode], BrotliTables.INSERT_EXTRA[insertCode])
            out.add(command.copy - BrotliTables.COPY_BASE[copyCode], BrotliTables.COPY_EXTRA[copyCode])
            for (i in command.from until command.from + command.insert) literalCode.put(out, buffer[i].toInt() and BYTE)
            if (command.distance > 0) {
                distanceCode.put(out, command.distanceCode)
                out.add(command.distanceExtra, command.distanceBits)
            }
        }
    }

    /** Жадный поиск совпадений; хвост без совпадения — команда только со вставкой. */
    private fun matches(): List<BrotliCommand> {
        val end = start + used
        val found = ArrayList<BrotliCommand>()
        var anchor = start
        var at = start
        while (at + MIN_MATCH <= end) {
            val word = XxHash32.intLe(buffer, at)
            val slot = (word * HASH_MULTIPLIER) ushr (Int.SIZE_BITS - HASH_BITS)
            val candidate = table[slot]
            table[slot] = at
            if (candidate < 0 || at - candidate > MAX_DISTANCE || XxHash32.intLe(buffer, candidate) != word) {
                at += 1 + ((at - anchor) ushr SKIP_SHIFT)
                continue
            }
            var length = MIN_MATCH
            while (at + length < end && buffer[candidate + length] == buffer[at + length]) length++
            found += BrotliCommand(anchor, at - anchor, length, at - candidate)
            for (inside in at + 1 until minOf(at + length, end - MIN_MATCH + 1)) {
                table[(XxHash32.intLe(buffer, inside) * HASH_MULTIPLIER) ushr (Int.SIZE_BITS - HASH_BITS)] = inside
            }
            at += length
            anchor = at
        }
        if (anchor < end) found += BrotliCommand(anchor, end - anchor, BrotliTables.COPY_BASE[0], 0)
        return found
    }

    private fun slide() {
        val end = start + used
        val keep = minOf(end, WINDOW)
        val shift = end - keep
        if (shift > 0) {
            buffer.copyInto(buffer, 0, shift, end)
            for (i in table.indices) table[i] = if (table[i] >= shift) table[i] - shift else -1
        }
        start = keep
        used = 0
    }

    private companion object {
        const val WINDOW = 1 shl 18
        const val MAX_DISTANCE = WINDOW - 16
        const val BLOCK = 1 shl 17
        const val HASH_BITS = 16
        const val HASH_MULTIPLIER = -0x61c8864f
        const val MIN_MATCH = 4
        const val SKIP_SHIFT = 6
        const val WINDOW_HEADER = 0b0011
        const val WINDOW_HEADER_BITS = 4
        const val LAST_EMPTY = 0b11
        const val MIN_NIBBLES = 4
        const val NIBBLE = 4
        const val UNCOMPRESSED_OVERHEAD = 4

        /** Три `NBLTYPES` = 1, `NPOSTFIX` = 0, `NDIRECT` = 0, режим LSB6, `NTREESL` = `NTREESD` = 1. */
        const val BLOCK_TYPES_AND_DISTANCE_HEADER = 3 + 6 + 2 + 2
        const val LITERALS = 256
        const val COMMANDS = 704
        const val DISTANCES = 16 + (24 shl 1)
        const val BYTE = 0xFF
    }
}

/**
 * Команда: [insert] литералов с [from], затем [copy] байт на [distance]
 * назад (0 — команда только со вставкой, последняя в мета-блоке).
 */
internal class BrotliCommand(val from: Int, val insert: Int, val copy: Int, val distance: Int) {
    val insertCode = code(BrotliTables.INSERT_BASE, insert)
    val copyCode = code(BrotliTables.COPY_BASE, copy)

    /** Клетка с явным расстоянием: 2..10 по старшим частям кодов вставки и копирования. */
    val code: Int = run {
        val cell = EXPLICIT_CELLS[(insertCode ushr LOW_BITS) * CELL_ROW + (copyCode ushr LOW_BITS)]
        (cell shl CELL_SHIFT) or ((insertCode and LOW_MASK) shl LOW_BITS) or (copyCode and LOW_MASK)
    }

    /** Код расстояния при `NPOSTFIX` = `NDIRECT` = 0: 16 + 2 (бит − 1) + половина. */
    private val shifted = distance + DISTANCE_BIAS
    val distanceBits = if (distance > 0) ZstdTables.highBit(shifted) - 1 else 0
    private val half = if (distance > 0) (shifted ushr distanceBits) and 1 else 0
    val distanceCode = SHORT_CODES + 2 * (distanceBits - 1) + half
    val distanceExtra = shifted - ((2 + half) shl distanceBits)

    private companion object {
        val EXPLICIT_CELLS = intArrayOf(2, 3, 6, 4, 5, 8, 7, 9, 10)
        const val CELL_ROW = 3
        const val CELL_SHIFT = 6
        const val LOW_BITS = 3
        const val LOW_MASK = 7
        const val SHORT_CODES = 16
        const val DISTANCE_BIAS = 3

        fun code(bases: IntArray, value: Int): Int {
            var code = bases.size - 1
            while (bases[code] > value) code--
            return code
        }
    }
}

/** Префиксный код для записи: длины и перевёрнутые канонические коды. */
internal class BrotliWriteCode(private val lengths: IntArray, private val codes: IntArray) {
    fun put(out: BrotliBitWriter, symbol: Int) = out.add(codes[symbol], lengths[symbol])
}

/** Построение и запись префиксных кодов: простых (1..4 символа) и сложных. */
internal object BrotliHuffman {
    fun write(out: BrotliBitWriter, counts: IntArray): BrotliWriteCode {
        val used = counts.indices.filter { counts[it] > 0 }.sortedByDescending { counts[it] }
        val width = Int.SIZE_BITS - Integer.numberOfLeadingZeros(counts.size - 1)
        if (used.size <= SIMPLE_MAX) return simple(out, counts, used.ifEmpty { listOf(0) }, width)
        val lengths = lengths(counts, MAX_LENGTH)
        complex(out, lengths)
        return BrotliWriteCode(lengths, canonical(lengths))
    }

    private fun simple(out: BrotliBitWriter, counts: IntArray, used: List<Int>, width: Int): BrotliWriteCode {
        val lengths = IntArray(counts.size)
        val shape = when (used.size) {
            1 -> intArrayOf(0)
            2 -> intArrayOf(1, 1)
            3 -> intArrayOf(1, 2, 2)
            else -> {
                val f = used.map { counts[it].toLong() }
                val skewed = f[0] + 2 * f[1] + 3 * (f[2] + f[3]) < 2 * f.sum()
                if (skewed) intArrayOf(1, 2, 3, 3) else intArrayOf(2, 2, 2, 2)
            }
        }
        out.add(1, 2)
        out.add(used.size - 1, 2)
        for (i in used.indices) {
            out.add(used[i], width)
            lengths[used[i]] = shape[i]
        }
        if (used.size == SIMPLE_MAX) out.add(if (shape[0] == 1) 1 else 0, 1)
        return BrotliWriteCode(lengths, canonical(lengths))
    }

    /** Сложный код: длины кодов длин статическим кодом, затем длины символов с повторами 16 и 17. */
    private fun complex(out: BrotliBitWriter, lengths: IntArray) {
        val symbols = ArrayList<Int>()
        val extras = ArrayList<Int>()
        runs(lengths) { symbol, extra ->
            symbols += symbol
            extras += extra
        }
        val counts = IntArray(CODE_LENGTH_SYMBOLS)
        for (symbol in symbols) counts[symbol]++
        val used = counts.count { it > 0 }
        val codeLengths = if (used == 1) {
            IntArray(CODE_LENGTH_SYMBOLS) { if (counts[it] > 0) 1 else 0 }
        } else {
            lengths(counts, CODE_LENGTH_MAX)
        }
        out.add(0, 2)
        val order = BrotliTables.CODE_LENGTH_ORDER
        val last = if (used == 1) order.size - 1 else order.indexOfLast { codeLengths[it] != 0 }
        for (i in 0..last) {
            val length = codeLengths[order[i]]
            out.add(STATIC_CODE[length], STATIC_LENGTH[length])
        }
        val codes = canonical(codeLengths)
        for (i in symbols.indices) {
            val symbol = symbols[i]
            if (used > 1) out.add(codes[symbol], codeLengths[symbol])
            when (symbol) {
                REPEAT_PREVIOUS -> out.add(extras[i], 2)
                REPEAT_ZERO -> out.add(extras[i], REPEAT_ZERO_BITS)
            }
        }
    }

    /** Длины до последней ненулевой: нули длиннее двух — кодом 17, повторы длиннее двух — кодом 16. */
    private fun runs(lengths: IntArray, emit: (Int, Int) -> Unit) {
        val end = lengths.indexOfLast { it != 0 } + 1
        var previous = INITIAL_PREVIOUS
        var i = 0
        while (i < end) {
            val value = lengths[i]
            var run = 1
            while (i + run < end && lengths[i + run] == value) run++
            i += run
            if (value == 0) {
                if (run < REPEAT_MIN) {
                    repeat(run) { emit(0, 0) }
                } else {
                    repeatRun(run, REPEAT_ZERO, REPEAT_ZERO_BITS, emit)
                }
                continue
            }
            if (value != previous) {
                emit(value, 0)
                previous = value
                run--
            }
            if (run < REPEAT_MIN) repeat(run) { emit(value, 0) } else repeatRun(run, REPEAT_PREVIOUS, 2, emit)
        }
    }

    /** Повтор [count] раз цепочкой кодов: `repeat = ((repeat - 2) << bits) + extra + 3`. */
    private fun repeatRun(count: Int, symbol: Int, bits: Int, emit: (Int, Int) -> Unit) {
        val single = (1 shl bits) + 2
        if (count <= single) {
            emit(symbol, count - REPEAT_MIN)
            return
        }
        repeatRun(((count - REPEAT_MIN) ushr bits) + 2, symbol, bits, emit)
        emit(symbol, (count - REPEAT_MIN) and ((1 shl bits) - 1))
    }

    /** Длины Хаффмана не длиннее [limit]: частоты сглаживаются, пока дерево не уложится. */
    fun lengths(counts: IntArray, limit: Int): IntArray {
        val weights = counts.copyOf()
        while (true) {
            val lengths = huffman(weights)
            if (lengths.max() <= limit) return lengths
            for (i in weights.indices) if (weights[i] > 0) weights[i] = (weights[i] + 1) / 2
        }
    }

    private fun huffman(weights: IntArray): IntArray {
        val parent = IntArray(weights.size * 2) { -1 }
        val queue = PriorityQueue<LongArray>(compareBy<LongArray> { it[0] }.thenBy { it[1] })
        for (i in weights.indices) if (weights[i] > 0) queue += longArrayOf(weights[i].toLong(), i.toLong())
        var next = weights.size
        while (queue.size > 1) {
            val a = queue.poll()
            val b = queue.poll()
            parent[a[1].toInt()] = next
            parent[b[1].toInt()] = next
            queue += longArrayOf(a[0] + b[0], next.toLong())
            next++
        }
        return IntArray(weights.size) { symbol ->
            var depth = 0
            var node = symbol
            if (weights[symbol] > 0) {
                while (parent[node] >= 0) {
                    node = parent[node]
                    depth++
                }
            }
            depth
        }
    }

    /** Канонические коды по (длина, символ), перевёрнутые для записи младшим битом вперёд. */
    fun canonical(lengths: IntArray): IntArray {
        val count = IntArray(BrotliPrefixCode.MAX_LENGTH + 1)
        for (length in lengths) if (length > 0) count[length]++
        val next = IntArray(BrotliPrefixCode.MAX_LENGTH + 2)
        var code = 0
        for (length in 1..BrotliPrefixCode.MAX_LENGTH) {
            code = (code + count[length - 1]) shl 1
            next[length] = code
        }
        return IntArray(lengths.size) { symbol ->
            val length = lengths[symbol]
            if (length == 0) 0 else Integer.reverse(next[length]++) ushr (Int.SIZE_BITS - length)
        }
    }

    private const val SIMPLE_MAX = 4
    private const val MAX_LENGTH = 15
    private const val CODE_LENGTH_MAX = 5
    private const val CODE_LENGTH_SYMBOLS = 18
    private const val REPEAT_PREVIOUS = 16
    private const val REPEAT_ZERO = 17
    private const val REPEAT_ZERO_BITS = 3
    private const val REPEAT_MIN = 3
    private const val INITIAL_PREVIOUS = 8

    /** Статический код длин кода длин (обратный `kCodeLengthPrefixValue`). */
    private val STATIC_CODE = intArrayOf(0, 7, 3, 2, 1, 15)
    private val STATIC_LENGTH = intArrayOf(2, 4, 3, 2, 2, 4)
}

/** Поток бит Brotli для записи, младший бит первым. */
internal class BrotliBitWriter {
    private val out = ByteArrayOutputStream()
    private var acc = 0L
    private var count = 0

    val bitLength: Long get() = out.size() * Byte.SIZE_BITS.toLong() + count

    fun add(value: Int, bits: Int) {
        if (bits == 0) return
        acc = acc or ((value.toLong() and ((1L shl bits) - 1)) shl count)
        count += bits
        while (count >= Byte.SIZE_BITS) {
            out.write(acc.toInt())
            acc = acc ushr Byte.SIZE_BITS
            count -= Byte.SIZE_BITS
        }
    }

    /** Нули до границы байта. */
    fun alignToByte() {
        if (count > 0) add(0, Byte.SIZE_BITS - count)
    }

    fun write(source: ByteArray, from: Int, length: Int) {
        check(count == 0) { "unaligned write" }
        out.write(source, from, length)
    }

    fun append(other: BrotliBitWriter) {
        for (b in other.out.toByteArray()) add(b.toInt(), Byte.SIZE_BITS)
        add(other.acc.toInt(), other.count)
    }

    /** Готовые целые байты; неполный байт остаётся. */
    fun drain(): ByteArray = out.toByteArray().also { out.reset() }
}
