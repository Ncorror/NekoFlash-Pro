package io.github.ncorror.nekoflash.protocol.adb

/**
 * Декодер zstd по RFC 8878 — то, что `ZSTD_decompressStream` принимает у
 * `adbd`: один кадр (или пропускаемый кадр), блоки raw, RLE и сжатые,
 * литералы Хаффмана в одном или четырёх потоках, последовательности FSE во
 * всех режимах, сумма содержимого. Словарь — ошибка.
 */
internal class ZstdDecoder : SyncDecoder {
    private val input = InputBuffer()
    private var state = State.MAGIC
    private var checksum = false
    private var windowSize = 0L
    private var contentSize = -1L
    private var produced = 0L
    private var skip = 0L
    private val hash = XxHash64()
    private val window = ZstdWindow()
    private val block = ZstdBlock(window)

    private enum class State { MAGIC, SKIP_SIZE, SKIP, HEADER, BLOCK, CHECKSUM, DONE }

    override fun feed(chunk: ByteArray, output: (ByteArray) -> Unit) {
        corruptIf(state == State.DONE && chunk.isNotEmpty()) { "Zstd stream hit end before reading all data" }
        input.append(chunk)
        while (step(output)) Unit
        corruptIf(state == State.DONE && input.available > 0) { "Zstd stream hit end before reading all data" }
    }

    override fun end(output: (ByteArray) -> Unit) {
        corruptIf(state != State.DONE) { "Zstd stream ended in the middle of a frame" }
    }

    private fun step(output: (ByteArray) -> Unit): Boolean = when (state) {
        State.MAGIC -> magic()
        State.SKIP_SIZE -> input.available >= Int.SIZE_BYTES && run {
            skip = input.intLe(0).toLong() and UINT
            input.skip(Int.SIZE_BYTES)
            state = if (skip == 0L) State.DONE else State.SKIP
            true
        }
        State.SKIP -> {
            val take = minOf(skip, input.available.toLong()).toInt()
            input.skip(take)
            skip -= take
            if (skip == 0L) state = State.DONE
            take > 0 && state != State.DONE
        }
        State.HEADER -> header()
        State.BLOCK -> block(output)
        State.CHECKSUM -> input.available >= Int.SIZE_BYTES && run {
            corruptIf(input.intLe(0) != hash.digest().toInt()) { "Zstd content checksum" }
            input.skip(Int.SIZE_BYTES)
            state = State.DONE
            false
        }
        State.DONE -> false
    }

    private fun magic(): Boolean {
        if (input.available < Int.SIZE_BYTES) return false
        val magic = input.intLe(0)
        input.skip(Int.SIZE_BYTES)
        state = when {
            magic == MAGIC -> State.HEADER
            magic and SKIPPABLE_MASK == SKIPPABLE -> State.SKIP_SIZE
            else -> throw SyncCodecException("Zstd frame magic 0x${Integer.toHexString(magic)}")
        }
        return true
    }

    private fun header(): Boolean {
        if (input.available < 1) return false
        val descriptor = input.peek(0)
        val fcsFlag = descriptor ushr FCS_SHIFT
        val single = descriptor and SINGLE_SEGMENT != 0
        val dictionaryBytes = intArrayOf(0, 1, 2, 4)[descriptor and DICT_MASK]
        val fcsBytes = when (fcsFlag) {
            0 -> if (single) 1 else 0
            1 -> 2
            2 -> 4
            else -> 8
        }
        val length = 1 + (if (single) 0 else 1) + dictionaryBytes + fcsBytes
        if (input.available < length) return false
        corruptIf(descriptor and RESERVED != 0) { "Zstd reserved bit" }
        val bytes = input.take(length)
        var at = 1
        if (!single) {
            val exponent = (bytes[at].toInt() and BYTE) ushr MANTISSA_BITS
            val mantissa = bytes[at].toInt() and MANTISSA_MASK
            val base = 1L shl (WINDOW_LOG_MIN + exponent)
            windowSize = base + (base / 8) * mantissa
            at++
        }
        var dictionary = 0L
        for (i in 0 until dictionaryBytes) dictionary = dictionary or ((bytes[at + i].toLong() and BYTE_L) shl (8 * i))
        at += dictionaryBytes
        corruptIf(dictionary != 0L) { "Zstd dictionary is not supported" }
        if (fcsBytes > 0) {
            var size = 0L
            for (i in 0 until fcsBytes) size = size or ((bytes[at + i].toLong() and BYTE_L) shl (8 * i))
            contentSize = if (fcsBytes == 2) size + FCS_2_OFFSET else size
        }
        if (single) windowSize = contentSize
        corruptIf(windowSize > WINDOW_MAX) { "Zstd window of $windowSize bytes" }
        checksum = descriptor and CHECKSUM_FLAG != 0
        window.limit = windowSize
        state = State.BLOCK
        return true
    }

    private fun block(output: (ByteArray) -> Unit): Boolean {
        if (input.available < BLOCK_HEADER) return false
        val header = input.peek(0) or (input.peek(1) shl 8) or (input.peek(2) shl 16)
        val last = header and 1 != 0
        val type = (header ushr 1) and 3
        val size = header ushr 3
        val blockMax = minOf(windowSize, BLOCK_MAX).toInt()
        val stored = when (type) {
            TYPE_RAW, TYPE_COMPRESSED -> size
            TYPE_RLE -> 1
            else -> throw SyncCodecException("Zstd reserved block type")
        }
        if (input.available < BLOCK_HEADER + stored) return false
        input.skip(BLOCK_HEADER)
        val data = input.take(stored)
        val decoded = when (type) {
            TYPE_RAW -> data
            TYPE_RLE -> ByteArray(size) { data[0] }
            else -> {
                corruptIf(size > blockMax) { "Zstd block of $size bytes" }
                block.decode(data)
            }
        }
        corruptIf(decoded.size > blockMax) { "Zstd block regenerated ${decoded.size} bytes" }
        if (type != TYPE_COMPRESSED) window.append(decoded)
        produced += decoded.size
        if (checksum) hash.update(decoded)
        if (decoded.isNotEmpty()) output(decoded)
        if (last) {
            corruptIf(contentSize >= 0 && produced != contentSize) { "Zstd content size" }
            state = if (checksum) State.CHECKSUM else State.DONE
        }
        return state != State.DONE
    }

    private companion object {
        const val MAGIC = -0x2d04ad8
        const val SKIPPABLE = 0x184D2A50
        const val SKIPPABLE_MASK = -0x10
        const val FCS_SHIFT = 6
        const val SINGLE_SEGMENT = 0x20
        const val RESERVED = 0x08
        const val CHECKSUM_FLAG = 0x04
        const val DICT_MASK = 3
        const val MANTISSA_BITS = 3
        const val MANTISSA_MASK = 7
        const val WINDOW_LOG_MIN = 10
        const val FCS_2_OFFSET = 256L
        const val WINDOW_MAX = 1L shl 31
        const val BLOCK_MAX = 128L * 1024
        const val BLOCK_HEADER = 3
        const val TYPE_RAW = 0
        const val TYPE_RLE = 1
        const val TYPE_COMPRESSED = 2
        const val UINT = 0xFFFF_FFFFL
        const val BYTE = 0xFF
        const val BYTE_L = 0xFFL
    }
}

/** Окно вывода: последние [limit] байт, к которым тянутся совпадения. */
internal class ZstdWindow {
    var limit = 0L
    private var data = ByteArray(1 shl 16)
    var size = 0
        private set

    /** Всего байт, прошедших через окно, — сдвиг буфера его не меняет. */
    var total = 0L
        private set

    fun append(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        ensure(length)
        bytes.copyInto(data, size, offset, offset + length)
        size += length
        total += length
    }

    fun byteAt(back: Int): Byte = data[size - back]

    fun put(value: Byte) {
        ensure(1)
        data[size++] = value
        total++
    }

    /** Последние [count] байт: блок никогда не длиннее окна, поэтому они ещё в буфере. */
    fun last(count: Int): ByteArray = data.copyOfRange(size - count, size)

    private fun ensure(extra: Int) {
        if (size + extra <= data.size) return
        val keep = minOf(size.toLong(), limit).toInt()
        if (keep + extra <= data.size) {
            data.copyInto(data, 0, size - keep, size)
        } else {
            val grown = ByteArray(maxOf(data.size * 2, keep + extra))
            data.copyInto(grown, 0, size - keep, size)
            data = grown
        }
        size = keep
    }
}

/** Сжатый блок: литералы и последовательности, состояние таблиц — на весь кадр. */
internal class ZstdBlock(private val window: ZstdWindow) {
    private var huffman: HuffmanTable? = null
    private var llTable: FseTable? = null
    private var ofTable: FseTable? = null
    private var mlTable: FseTable? = null
    private val repeat = intArrayOf(1, 4, 8)

    fun decode(block: ByteArray): ByteArray {
        val (literals, used) = literals(block)
        val start = window.total
        sequences(block, used, literals)
        return window.last((window.total - start).toInt())
    }

    private fun literals(block: ByteArray): Pair<ByteArray, Int> {
        corruptIf(block.isEmpty()) { "Zstd empty block" }
        val b0 = block[0].toInt() and BYTE
        val type = b0 and 3
        val format = (b0 ushr 2) and 3
        return when (type) {
            RAW, RLE -> {
                val (size, header) = when (format) {
                    0, 2 -> (b0 ushr 3) to 1
                    1 -> ((b0 ushr 4) or (u8(block, 1) shl 4)) to 2
                    else -> ((b0 ushr 4) or (u8(block, 1) shl 4) or (u8(block, 2) shl 12)) to 3
                }
                if (type == RAW) {
                    corruptIf(header + size > block.size) { "Zstd raw literals truncated" }
                    block.copyOfRange(header, header + size) to header + size
                } else {
                    corruptIf(header >= block.size) { "Zstd RLE literals truncated" }
                    ByteArray(size) { block[header] } to header + 1
                }
            }
            else -> compressedLiterals(block, type, format, b0)
        }
    }

    private fun compressedLiterals(block: ByteArray, type: Int, format: Int, b0: Int): Pair<ByteArray, Int> {
        val streams = if (format == 0) 1 else 4
        val header: Int
        val regenerated: Int
        val compressed: Int
        when (format) {
            0, 1 -> {
                header = 3
                val v = (b0 ushr 4) or (u8(block, 1) shl 4) or (u8(block, 2) shl 12)
                regenerated = v and 0x3FF
                compressed = v ushr 10
            }
            2 -> {
                header = 4
                val v = (b0 ushr 4) or (u8(block, 1) shl 4) or (u8(block, 2) shl 12) or (u8(block, 3) shl 20)
                regenerated = v and 0x3FFF
                compressed = v ushr 14
            }
            else -> {
                header = 5
                val v = ((b0 ushr 4).toLong() or (u8(block, 1).toLong() shl 4) or (u8(block, 2).toLong() shl 12) or
                    (u8(block, 3).toLong() shl 20) or (u8(block, 4).toLong() shl 28))
                regenerated = (v and 0x3FFFF).toInt()
                compressed = (v ushr 18).toInt()
            }
        }
        corruptIf(header + compressed > block.size) { "Zstd literals truncated" }
        var at = header
        if (type == COMPRESSED) {
            val (table, used) = HuffmanTable.read(block, at, header + compressed)
            huffman = table
            at += used
        }
        val table = huffman ?: throw SyncCodecException("Zstd treeless literals without a table")
        val end = header + compressed
        val out = ByteArray(regenerated)
        if (streams == 1) {
            table.decode(block, at, end, out, 0, regenerated)
        } else {
            corruptIf(at + 6 > end) { "Zstd jump table" }
            val s1 = u16(block, at)
            val s2 = u16(block, at + 2)
            val s3 = u16(block, at + 4)
            at += 6
            val each = (regenerated + 3) / 4
            val starts = intArrayOf(at, at + s1, at + s1 + s2, at + s1 + s2 + s3)
            val ends = intArrayOf(starts[1], starts[2], starts[3], end)
            corruptIf(starts[3] > end) { "Zstd jump table" }
            for (i in 0 until 4) {
                val from = i * each
                val count = if (i == 3) regenerated - 3 * each else each
                table.decode(block, starts[i], ends[i], out, from, count)
            }
        }
        return out to end
    }

    private fun sequences(block: ByteArray, from: Int, literals: ByteArray) {
        var at = from
        corruptIf(at >= block.size) { "Zstd sequences header" }
        val b0 = u8(block, at)
        val count = when {
            b0 == 0 -> 0
            b0 < 128 -> b0.also { at += 0 }
            b0 < 255 -> (((b0 - 128) shl 8) + u8(block, at + 1)).also { at += 1 }
            else -> (u8(block, at + 1) + (u8(block, at + 2) shl 8) + 0x7F00).also { at += 2 }
        }
        at++
        if (count == 0) {
            corruptIf(at != block.size) { "Zstd trailing bytes after sequences" }
            window.append(literals)
            return
        }
        val modes = u8(block, at++)
        corruptIf(modes and 3 != 0) { "Zstd reserved sequence mode bits" }
        val ll = table(block, at, modes ushr 6, llTable, ZstdTables.LL)
            .also { llTable = it.first; at += it.second }.first
        val of = table(block, at, (modes ushr 4) and 3, ofTable, ZstdTables.OF)
            .also { ofTable = it.first; at += it.second }.first
        val ml = table(block, at, (modes ushr 2) and 3, mlTable, ZstdTables.ML)
            .also { mlTable = it.first; at += it.second }.first
        execute(BackwardBits(block, at, block.size), count, ll, of, ml, literals)
    }

    private fun table(
        block: ByteArray,
        at: Int,
        mode: Int,
        previous: FseTable?,
        spec: ZstdTables.CodeSpec,
    ): Pair<FseTable, Int> = when (mode) {
        PREDEFINED -> FseTable.build(spec.defaults, spec.defaultLog) to 0
        RLE_MODE -> {
            corruptIf(at >= block.size) { "Zstd RLE table" }
            val symbol = u8(block, at)
            corruptIf(symbol > spec.maxSymbol) { "Zstd RLE symbol $symbol" }
            FseTable.rle(symbol) to 1
        }
        FSE_MODE -> FseTable.read(block, at, block.size, spec.maxSymbol, spec.maxLog)
        else -> (previous ?: throw SyncCodecException("Zstd repeat table without a previous one")) to 0
    }

    @Suppress("LongMethod")
    private fun execute(bits: BackwardBits, count: Int, ll: FseTable, of: FseTable, ml: FseTable, literals: ByteArray) {
        var llState = bits.read(ll.log)
        var ofState = bits.read(of.log)
        var mlState = bits.read(ml.log)
        var literal = 0
        for (n in 0 until count) {
            val ofCode = of.symbol[ofState]
            val llCode = ll.symbol[llState]
            val mlCode = ml.symbol[mlState]
            val invalid = ofCode > OF_CODE_MAX || llCode > ZstdTables.LL_MAX_SYMBOL || mlCode > ZstdTables.ML_MAX_SYMBOL
            corruptIf(invalid) { "Zstd sequence code" }
            val offsetValue = (1 shl ofCode) + bits.read(ofCode)
            val matchLength = ZstdTables.ML_BASE[mlCode] + bits.read(ZstdTables.ML_BITS[mlCode])
            val literalLength = ZstdTables.LL_BASE[llCode] + bits.read(ZstdTables.LL_BITS[llCode])
            val offset = offset(offsetValue, literalLength)
            if (n != count - 1) {
                llState = ll.base[llState] + bits.read(ll.bits[llState])
                mlState = ml.base[mlState] + bits.read(ml.bits[mlState])
                ofState = of.base[ofState] + bits.read(of.bits[ofState])
            }
            corruptIf(literal + literalLength > literals.size) { "Zstd literals overrun" }
            window.append(literals, literal, literalLength)
            literal += literalLength
            corruptIf(offset <= 0 || offset > window.size || offset > window.limit) { "Zstd offset $offset" }
            repeat(matchLength) { window.put(window.byteAt(offset)) }
        }
        corruptIf(bits.overflowed || !bits.finished) { "Zstd sequence bitstream" }
        window.append(literals, literal, literals.size - literal)
    }

    /** Смещение и повторные смещения (RFC 8878, 3.1.1.5). */
    private fun offset(value: Int, literalLength: Int): Int {
        if (value > 3) {
            val offset = value - 3
            repeat[2] = repeat[1]
            repeat[1] = repeat[0]
            repeat[0] = offset
            return offset
        }
        var index = value - 1
        if (literalLength == 0) index++
        if (index == 0) return repeat[0]
        val offset = if (index == 3) repeat[0] - 1 else repeat[index]
        if (index > 1) repeat[2] = repeat[1]
        repeat[1] = repeat[0]
        repeat[0] = offset
        return offset
    }

    private companion object {
        const val RAW = 0
        const val RLE = 1
        const val COMPRESSED = 2
        const val PREDEFINED = 0
        const val RLE_MODE = 1
        const val FSE_MODE = 2
        const val OF_CODE_MAX = 31
        const val BYTE = 0xFF

        fun u8(source: ByteArray, at: Int): Int {
            corruptIf(at >= source.size) { "Zstd block truncated" }
            return source[at].toInt() and BYTE
        }

        fun u16(source: ByteArray, at: Int): Int = u8(source, at) or (u8(source, at + 1) shl 8)
    }
}

/** Таблица Хаффмана литералов zstd (RFC 8878, 4.2.1). */
internal class HuffmanTable(private val maxBits: Int, private val symbol: ByteArray, private val bits: ByteArray) {
    /** Один поток Хаффмана, читаемый с конца, — [count] символов в [out] с [at]. */
    fun decode(source: ByteArray, from: Int, end: Int, out: ByteArray, at: Int, count: Int) {
        val stream = BackwardBits(source, from, end)
        var state = 0
        var have = 0
        for (i in 0 until count) {
            state = ((state shl (maxBits - have)) or stream.read(maxBits - have)) and ((1 shl maxBits) - 1)
            out[at + i] = symbol[state]
            have = maxBits - bits[state]
        }
        corruptIf(!stream.consumedExactly(have)) { "Zstd Huffman stream" }
    }

    companion object {
        private const val MAX_BITS = 11
        private const val MAX_SYMBOLS = 256

        /** Описание дерева: веса прямо (по 4 бита) или сжатые FSE. */
        fun read(source: ByteArray, at: Int, end: Int): Pair<HuffmanTable, Int> {
            corruptIf(at >= end) { "Zstd Huffman header" }
            val header = source[at].toInt() and 0xFF
            val weights: IntArray
            val used: Int
            if (header >= 128) {
                val count = header - 127
                used = 1 + (count + 1) / 2
                corruptIf(at + used > end) { "Zstd Huffman weights" }
                weights = IntArray(count) { i ->
                    val b = source[at + 1 + i / 2].toInt() and 0xFF
                    if (i % 2 == 0) b ushr 4 else b and 0xF
                }
            } else {
                used = 1 + header
                corruptIf(at + used > end) { "Zstd Huffman weights" }
                weights = fseWeights(source, at + 1, at + 1 + header)
            }
            return build(weights) to used
        }

        private fun fseWeights(source: ByteArray, from: Int, end: Int): IntArray {
            val (table, used) = FseTable.read(source, from, end, MAX_WEIGHT_SYMBOL, MAX_WEIGHT_LOG)
            val bits = BackwardBits(source, from + used, end)
            val states = intArrayOf(bits.read(table.log), bits.read(table.log))
            val out = ArrayList<Int>()
            var turn = 0
            var done = false
            // Два состояния по очереди; когда поток кончился, последним идёт символ другого.
            while (!done) {
                val state = states[turn]
                out += table.symbol[state]
                states[turn] = table.base[state] + bits.read(table.bits[state])
                if (bits.overflowed) {
                    out += table.symbol[states[1 - turn]]
                    done = true
                }
                turn = 1 - turn
                corruptIf(out.size > MAX_SYMBOLS) { "Zstd Huffman weight count" }
            }
            return out.toIntArray()
        }

        private fun build(given: IntArray): HuffmanTable {
            var total = 0
            for (w in given) {
                corruptIf(w > MAX_BITS) { "Zstd Huffman weight $w" }
                if (w > 0) total += 1 shl (w - 1)
            }
            corruptIf(total == 0) { "Zstd Huffman weights sum" }
            val maxBits = ZstdTables.highBit(total) + 1
            val rest = (1 shl maxBits) - total
            corruptIf(rest and (rest - 1) != 0) { "Zstd Huffman last weight" }
            val weights = given + (ZstdTables.highBit(rest) + 1)
            corruptIf(weights.size > MAX_SYMBOLS || maxBits > MAX_BITS) { "Zstd Huffman table" }
            val size = 1 shl maxBits
            val symbol = ByteArray(size)
            val bits = ByteArray(size)
            var position = 0
            for (w in 1..maxBits) {
                for (s in weights.indices) {
                    if (weights[s] != w) continue
                    val length = 1 shl (w - 1)
                    for (i in position until position + length) {
                        symbol[i] = s.toByte()
                        bits[i] = (maxBits + 1 - w).toByte()
                    }
                    position += length
                }
            }
            return HuffmanTable(maxBits, symbol, bits)
        }

        private const val MAX_WEIGHT_SYMBOL = 11
        private const val MAX_WEIGHT_LOG = 6
    }
}
