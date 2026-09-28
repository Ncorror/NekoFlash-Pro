package io.github.ncorror.nekoflash.protocol.adb

/**
 * Сжатый мета-блок Brotli: заголовок (типы блоков, контекстные карты,
 * префиксные коды) и команды — `ProcessCommandsInternal` из `libbrotli`.
 */
internal class BrotliMetaBlock(
    private val bits: BrotliBits,
    private val history: BrotliHistory,
    private val ring: BrotliDistanceRing,
    windowBits: Int,
) {
    private val maxBackward = (1 shl windowBits) - WINDOW_GAP
    private val types = IntArray(KINDS) { 1 }
    private val left = IntArray(KINDS) { BLOCK_CAP }
    private val typeRing = intArrayOf(1, 0, 1, 0, 1, 0)
    private val typeCodes = arrayOfNulls<BrotliPrefixCode>(KINDS)
    private val lengthCodes = arrayOfNulls<BrotliPrefixCode>(KINDS)
    private var contextModes = IntArray(0)
    private var literalMap = IntArray(0)
    private var distanceMap = IntArray(0)
    private var literalTrees = emptyArray<BrotliPrefixCode>()
    private var commandTrees = emptyArray<BrotliPrefixCode>()
    private var distanceTrees = emptyArray<BrotliPrefixCode>()
    private var postfix = 0
    private var distanceOffset = IntArray(0)
    private var distanceExtra = IntArray(0)

    /** Разбирает мета-блок длины [size]; возвращает остаток, отрицательный — перебор. */
    fun decode(size: Int): Int {
        header()
        var remaining = size
        while (true) {
            if (left[COMMAND] == 0) switchBlock(COMMAND)
            left[COMMAND]--
            val code = commandTrees[typeRing[COMMAND * 2 + 1]].read(bits)
            val cell = code ushr CELL_SHIFT
            val insertCode = BrotliTables.CELL_INSERT[cell] + ((code ushr LOW_SHIFT) and LOW_MASK)
            val copyCode = BrotliTables.CELL_COPY[cell] + (code and LOW_MASK)
            val insert = BrotliTables.INSERT_BASE[insertCode] + bits.read(BrotliTables.INSERT_EXTRA[insertCode])
            val copy = BrotliTables.COPY_BASE[copyCode] + bits.read(BrotliTables.COPY_EXTRA[copyCode])
            remaining -= insert
            repeat(insert) { literal() }
            if (remaining <= 0) return remaining
            remaining -= reference(code < IMPLICIT_DISTANCE_CODES, copy)
            if (remaining <= 0) return remaining
        }
    }

    private fun header() {
        for (kind in 0 until KINDS) {
            types[kind] = varLength() + 1
            if (types[kind] >= 2) {
                typeCodes[kind] = BrotliPrefixCode.read(bits, types[kind] + 2)
                lengthCodes[kind] = BrotliPrefixCode.read(bits, BrotliTables.BLOCK_LENGTH_BASE.size)
                left[kind] = blockLength(kind)
            }
        }
        val parameters = bits.read(DISTANCE_PARAMETER_BITS)
        postfix = parameters and POSTFIX_MASK
        val direct = (parameters ushr 2) shl postfix
        contextModes = IntArray(types[LITERAL]) { bits.read(2) }
        val (literals, literalTreeCount) = contextMap(types[LITERAL] shl LITERAL_CONTEXT_BITS)
        val (distances, distanceTreeCount) = contextMap(types[DISTANCE] shl DISTANCE_CONTEXT_BITS)
        literalMap = literals
        distanceMap = distances
        literalTrees = Array(literalTreeCount) { BrotliPrefixCode.read(bits, LITERALS) }
        commandTrees = Array(types[COMMAND]) { BrotliPrefixCode.read(bits, COMMANDS) }
        val alphabet = SHORT_CODES + direct + (MAX_DISTANCE_BITS shl (postfix + 1))
        distanceTrees = Array(distanceTreeCount) { BrotliPrefixCode.read(bits, alphabet) }
        distanceTable(direct, alphabet)
    }

    /** `CalculateDistanceLut`. */
    private fun distanceTable(direct: Int, alphabet: Int) {
        distanceOffset = IntArray(alphabet)
        distanceExtra = IntArray(alphabet)
        var i = SHORT_CODES
        for (j in 0 until direct) distanceOffset[i++] = j + 1
        var extra = 1
        var half = 0
        while (i < alphabet) {
            val base = direct + ((((2 + half) shl extra) - FOUR) shl postfix) + 1
            for (j in 0 until (1 shl postfix)) {
                distanceExtra[i] = extra
                distanceOffset[i] = base + j
                i++
            }
            extra += half
            half = half xor 1
        }
    }

    /** `DecodeVarLenUint8`: 0..255. */
    private fun varLength(): Int {
        if (bits.read(1) == 0) return 0
        val width = bits.read(VAR_LENGTH_WIDTH)
        return if (width == 0) 1 else (1 shl width) + bits.read(width)
    }

    /** `DecodeContextMap`: карта и число деревьев. */
    private fun contextMap(size: Int): Pair<IntArray, Int> {
        val trees = varLength() + 1
        val map = IntArray(size)
        if (trees <= 1) return map to trees
        val runPrefix = if (bits.read(1) == 1) bits.read(RUN_PREFIX_WIDTH) + 1 else 0
        val code = BrotliPrefixCode.read(bits, trees + runPrefix)
        var i = 0
        while (i < size) {
            val symbol = code.read(bits)
            when {
                symbol == 0 -> map[i++] = 0
                symbol > runPrefix -> map[i++] = symbol - runPrefix
                else -> {
                    val zeros = (1 shl symbol) + bits.read(symbol)
                    corruptIf(i + zeros > size) { "Brotli context map repeat" }
                    i += zeros
                }
            }
        }
        if (bits.read(1) == 1) inverseMoveToFront(map)
        return map to trees
    }

    private fun inverseMoveToFront(map: IntArray) {
        val list = IntArray(MTF_SIZE) { it }
        for (i in map.indices) {
            val index = map[i]
            val value = list[index]
            map[i] = value
            list.copyInto(list, 1, 0, index)
            list[0] = value
        }
    }

    private fun blockLength(kind: Int): Int {
        val code = requireNotNull(lengthCodes[kind]).read(bits)
        return BrotliTables.BLOCK_LENGTH_BASE[code] + bits.read(BrotliTables.BLOCK_LENGTH_EXTRA[code])
    }

    /** `DecodeBlockTypeAndLength`: при одном типе ничего не читается. */
    private fun switchBlock(kind: Int) {
        if (types[kind] <= 1) return
        val symbol = requireNotNull(typeCodes[kind]).read(bits)
        left[kind] = blockLength(kind)
        var type = when (symbol) {
            0 -> typeRing[kind * 2]
            1 -> typeRing[kind * 2 + 1] + 1
            else -> symbol - 2
        }
        if (type >= types[kind]) type -= types[kind]
        typeRing[kind * 2] = typeRing[kind * 2 + 1]
        typeRing[kind * 2 + 1] = type
    }

    private fun literal() {
        if (left[LITERAL] == 0) switchBlock(LITERAL)
        left[LITERAL]--
        val type = typeRing[LITERAL * 2 + 1]
        val lookup = contextModes[type] * CONTEXT_TABLE
        val context = BrotliTables.CONTEXT_LOOKUP[lookup + history.back(1)] or
            BrotliTables.CONTEXT_LOOKUP[lookup + CONTEXT_SECOND + history.back(2)]
        history.put(literalTrees[literalMap[(type shl LITERAL_CONTEXT_BITS) + context]].read(bits))
    }

    /** Ссылка назад или слово словаря; возвращает число выведенных байт. */
    private fun reference(implicit: Boolean, copy: Int): Int {
        var compensation = 0
        val distance: Int
        if (implicit) {
            ring.index--
            distance = ring.values[ring.index and RING_MASK]
            compensation = 1
        } else {
            if (left[DISTANCE] == 0) switchBlock(DISTANCE)
            left[DISTANCE]--
            val context = if (copy > FOUR) DISTANCE_CONTEXTS - 1 else copy - 2
            val tree = distanceMap[(typeRing[DISTANCE * 2 + 1] shl DISTANCE_CONTEXT_BITS) + context]
            val code = distanceTrees[tree].read(bits)
            distance = when {
                code == 0 -> {
                    compensation = 1
                    ring.values[(ring.index - 1) and RING_MASK].also { ring.index-- }
                }
                code < SHORT_CODES -> shortDistance(code)
                else -> distanceOffset[code] + (bits.read(distanceExtra[code]) shl postfix)
            }
        }
        val maxDistance = minOf(history.total, maxBackward.toLong()).toInt()
        if (distance <= maxDistance) {
            ring.values[ring.index and RING_MASK] = distance
            ring.index++
            history.copy(distance, copy)
            return copy
        }
        return dictionary(distance - maxDistance - 1, copy, distance, compensation)
    }

    /** `TakeDistanceFromRingBuffer` для кодов 1..15. */
    private fun shortDistance(code: Int): Int {
        if (code <= SHORT_RING_CODES) return ring.values[(ring.index + SHORT_RING_CODES - code) and RING_MASK]
        val base = if (code < SECOND_LAST_CODES) code - FOUR else code - SECOND_LAST_CODES
        val back = if (code < SECOND_LAST_CODES) SHORT_RING_CODES else 2
        val delta = ((SHORT_DELTAS ushr (FOUR * base)) and LOW_NIBBLE) - SHORT_RING_CODES
        val value = ring.values[(ring.index + back) and RING_MASK] + delta
        return if (value <= 0) Int.MAX_VALUE else value
    }

    private fun dictionary(address: Int, copy: Int, distance: Int, compensation: Int): Int {
        corruptIf(distance > MAX_ALLOWED_DISTANCE) { "Brotli distance" }
        corruptIf(copy !in BrotliDictionary.MIN_WORD..BrotliDictionary.MAX_WORD) { "Brotli dictionary word length" }
        val shift = BrotliDictionary.SIZE_BITS[copy]
        val word = address and ((1 shl shift) - 1)
        val transform = address ushr shift
        ring.index += compensation
        corruptIf(transform >= BrotliTables.TRANSFORM_COUNT) { "Brotli transform" }
        val bytes = BrotliDictionary.word(copy, word, transform)
        corruptIf(bytes.isEmpty() && distance <= EMPTY_WORD_LIMIT) { "Brotli empty word" }
        history.append(bytes, 0, bytes.size)
        return bytes.size
    }

    private companion object {
        const val KINDS = 3
        const val LITERAL = 0
        const val COMMAND = 1
        const val DISTANCE = 2
        const val BLOCK_CAP = 1 shl 24
        const val WINDOW_GAP = 16
        const val CELL_SHIFT = 6
        const val LOW_SHIFT = 3
        const val LOW_MASK = 7
        const val IMPLICIT_DISTANCE_CODES = 128
        const val DISTANCE_PARAMETER_BITS = 6
        const val POSTFIX_MASK = 3
        const val LITERAL_CONTEXT_BITS = 6
        const val DISTANCE_CONTEXT_BITS = 2
        const val DISTANCE_CONTEXTS = 4
        const val LITERALS = 256
        const val COMMANDS = 704
        const val SHORT_CODES = 16
        const val MAX_DISTANCE_BITS = 24
        const val FOUR = 4
        const val VAR_LENGTH_WIDTH = 3
        const val RUN_PREFIX_WIDTH = 4
        const val MTF_SIZE = 256
        const val CONTEXT_TABLE = 512
        const val CONTEXT_SECOND = 256
        const val RING_MASK = 3
        const val SHORT_RING_CODES = 3
        const val SECOND_LAST_CODES = 10
        const val SHORT_DELTAS = 0x605142
        const val LOW_NIBBLE = 0xF
        const val MAX_ALLOWED_DISTANCE = 0x7FFFFFFC
        const val EMPTY_WORD_LIMIT = 120
    }
}
