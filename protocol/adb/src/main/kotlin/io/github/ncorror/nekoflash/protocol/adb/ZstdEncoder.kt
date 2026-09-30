package io.github.ncorror.nekoflash.protocol.adb

import java.io.ByteArrayOutputStream

/**
 * Кодировщик zstd (RFC 8878) для `push -z zstd`: кадр с окном 128 КиБ и
 * суммой содержимого, блоки по 128 КиБ. Совпадения ищутся жадно по хешу
 * четырёх байт, литералы идут без сжатия, последовательности — по
 * предопределённым распределениям FSE. Блок, который так не уменьшился,
 * уходит как есть (raw).
 */
internal class ZstdEncoder : SyncEncoder {
    private val buffer = ByteArray(WINDOW + BLOCK)
    private var start = 0
    private var used = 0
    private var started = false
    private val table = IntArray(1 shl HASH_BITS) { -1 }
    private val hash = XxHash64()

    override fun feed(data: ByteArray, length: Int, output: (ByteArray) -> Unit) {
        begin(output)
        hash.update(data, 0, length)
        var at = 0
        while (at < length) {
            val take = minOf(length - at, BLOCK - used)
            data.copyInto(buffer, start + used, at, at + take)
            used += take
            at += take
            if (used == BLOCK) block(last = false, output)
        }
    }

    override fun end(output: (ByteArray) -> Unit) {
        begin(output)
        block(last = true, output)
        output(intLe(hash.digest().toInt()))
    }

    private fun begin(output: (ByteArray) -> Unit) {
        if (started) return
        started = true
        output(intLe(MAGIC) + byteArrayOf(DESCRIPTOR.toByte(), WINDOW_DESCRIPTOR.toByte()))
    }

    private fun block(last: Boolean, output: (ByteArray) -> Unit) {
        val compressed = compress()
        val flag = if (last) 1 else 0
        output(
            if (compressed != null && compressed.size < used) {
                header(flag or (TYPE_COMPRESSED shl 1) or (compressed.size shl SIZE_SHIFT)) + compressed
            } else {
                header(flag or (used shl SIZE_SHIFT)) + buffer.copyOfRange(start, start + used)
            },
        )
        slide()
    }

    /** Сдвиг окна: остаются последние [WINDOW] байт, хеш-таблица сдвигается вместе с ними. */
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

    /** Сжатое тело блока или `null`, если последовательностей нет. */
    private fun compress(): ByteArray? {
        val end = start + used
        val sequences = ArrayList<IntArray>()
        val literals = ByteArrayOutputStream(used)
        var anchor = start
        var at = start
        while (at + MIN_MATCH <= end) {
            val word = int(at)
            val slot = (word * HASH_MULTIPLIER) ushr (Int.SIZE_BITS - HASH_BITS)
            val candidate = table[slot]
            table[slot] = at
            if (candidate < 0 || at - candidate >= WINDOW || int(candidate) != word) {
                at += 1 + ((at - anchor) ushr SKIP_SHIFT)
                continue
            }
            var length = MIN_MATCH
            while (at + length < end && buffer[candidate + length] == buffer[at + length]) length++
            literals.write(buffer, anchor, at - anchor)
            sequences += intArrayOf(at - anchor, at - candidate, length)
            for (inside in at + 1 until minOf(at + length, end - MIN_MATCH + 1)) {
                table[(int(inside) * HASH_MULTIPLIER) ushr (Int.SIZE_BITS - HASH_BITS)] = inside
            }
            at += length
            anchor = at
        }
        if (sequences.isEmpty()) return null
        literals.write(buffer, anchor, end - anchor)
        val out = ByteArrayOutputStream(used)
        literalsHeader(out, literals.size())
        literals.writeTo(out)
        sequencesHeader(out, sequences.size)
        out.write(0)
        out.write(ZstdSequenceWriter.encode(sequences))
        return out.toByteArray()
    }

    private fun int(at: Int): Int = XxHash32.intLe(buffer, at)

    private fun literalsHeader(out: ByteArrayOutputStream, size: Int) {
        when {
            size < SHORT_LITERALS -> out.write(size shl LITERALS_SHORT_SHIFT)
            size < MEDIUM_LITERALS -> {
                val value = MEDIUM_FORMAT or (size shl LITERALS_SIZE_SHIFT)
                out.write(value)
                out.write(value ushr Byte.SIZE_BITS)
            }
            else -> {
                val value = LONG_FORMAT or (size shl LITERALS_SIZE_SHIFT)
                out.write(value)
                out.write(value ushr Byte.SIZE_BITS)
                out.write(value ushr (2 * Byte.SIZE_BITS))
            }
        }
    }

    private fun sequencesHeader(out: ByteArrayOutputStream, count: Int) {
        when {
            count < SHORT_SEQUENCES -> out.write(count)
            count < LONG_SEQUENCES -> {
                out.write((count ushr Byte.SIZE_BITS) + SHORT_SEQUENCES)
                out.write(count)
            }
            else -> {
                out.write(LONG_SEQUENCES_MARK)
                out.write(count - LONG_SEQUENCES)
                out.write((count - LONG_SEQUENCES) ushr Byte.SIZE_BITS)
            }
        }
    }

    private fun header(value: Int): ByteArray =
        byteArrayOf(value.toByte(), (value ushr Byte.SIZE_BITS).toByte(), (value ushr (2 * Byte.SIZE_BITS)).toByte())

    private companion object {
        const val MAGIC = -0x2d04ad8
        const val DESCRIPTOR = 0x04
        const val WINDOW_DESCRIPTOR = 0x38
        const val WINDOW = 1 shl 17
        const val BLOCK = 1 shl 17
        const val HASH_BITS = 16
        const val HASH_MULTIPLIER = -0x61c8864f
        const val MIN_MATCH = 4
        const val SKIP_SHIFT = 6
        const val TYPE_COMPRESSED = 2
        const val SIZE_SHIFT = 3
        const val SHORT_LITERALS = 32
        const val MEDIUM_LITERALS = 4096
        const val LITERALS_SHORT_SHIFT = 3
        const val LITERALS_SIZE_SHIFT = 4
        const val MEDIUM_FORMAT = 0x4
        const val LONG_FORMAT = 0xC
        const val SHORT_SEQUENCES = 128
        const val LONG_SEQUENCES = 0x7F00
        const val LONG_SEQUENCES_MARK = 0xFF
    }
}

/**
 * Поток последовательностей по предопределённым таблицам FSE —
 * `ZSTD_encodeSequences` в обратном порядке с тремя состояниями.
 */
internal object ZstdSequenceWriter {
    private val literalLengths = FseEncodeTable(ZstdTables.LL_DEFAULT, ZstdTables.LL_DEFAULT_LOG)
    private val matchLengths = FseEncodeTable(ZstdTables.ML_DEFAULT, ZstdTables.ML_DEFAULT_LOG)
    private val offsets = FseEncodeTable(ZstdTables.OF_DEFAULT, ZstdTables.OF_DEFAULT_LOG)

    /** Каждая последовательность — литералы, смещение, длина совпадения. */
    fun encode(sequences: List<IntArray>): ByteArray {
        val count = sequences.size
        val ll = IntArray(count) { code(ZstdTables.LL_BASE, sequences[it][0]) }
        val ml = IntArray(count) { code(ZstdTables.ML_BASE, sequences[it][2]) }
        val of = IntArray(count) { ZstdTables.highBit(sequences[it][1] + REPEAT_CODES) }
        val bits = ForwardBitWriter()
        val last = count - 1
        var mlState = matchLengths.init(ml[last])
        var ofState = offsets.init(of[last])
        var llState = literalLengths.init(ll[last])
        extras(bits, sequences[last], ll[last], ml[last], of[last])
        for (n in last - 1 downTo 0) {
            ofState = offsets.encode(bits, ofState, of[n])
            mlState = matchLengths.encode(bits, mlState, ml[n])
            llState = literalLengths.encode(bits, llState, ll[n])
            extras(bits, sequences[n], ll[n], ml[n], of[n])
        }
        matchLengths.flush(bits, mlState)
        offsets.flush(bits, ofState)
        literalLengths.flush(bits, llState)
        return bits.close()
    }

    private fun extras(bits: ForwardBitWriter, sequence: IntArray, ll: Int, ml: Int, of: Int) {
        bits.add(sequence[0] - ZstdTables.LL_BASE[ll], ZstdTables.LL_BITS[ll])
        bits.add(sequence[2] - ZstdTables.ML_BASE[ml], ZstdTables.ML_BITS[ml])
        bits.add(sequence[1] + REPEAT_CODES - (1 shl of), of)
    }

    private fun code(bases: IntArray, value: Int): Int {
        var code = bases.size - 1
        while (bases[code] > value) code--
        return code
    }

    private const val REPEAT_CODES = 3
}

/** Таблица кодирования FSE (`FSE_buildCTable`) из нормированного распределения. */
internal class FseEncodeTable(counts: ShortArray, private val log: Int) {
    private val states = IntArray(1 shl log)
    private val deltaBits = IntArray(counts.size)
    private val deltaState = IntArray(counts.size)

    init {
        val size = 1 shl log
        val symbols = ZstdTables.spread(counts, log)
        val cumulative = IntArray(counts.size + 1)
        for (s in counts.indices) {
            cumulative[s + 1] = cumulative[s] + if (counts[s].toInt() == -1) 1 else counts[s].toInt()
        }
        val next = cumulative.copyOf()
        for (u in 0 until size) states[next[symbols[u]]++] = size + u
        var total = 0
        for (s in counts.indices) {
            when (val count = counts[s].toInt()) {
                0 -> deltaBits[s] = ((log + 1) shl STATE_SHIFT) - size
                -1, 1 -> {
                    deltaBits[s] = (log shl STATE_SHIFT) - size
                    deltaState[s] = total - 1
                    total++
                }
                else -> {
                    val out = log - ZstdTables.highBit(count - 1)
                    deltaBits[s] = (out shl STATE_SHIFT) - (count shl out)
                    deltaState[s] = total - count
                    total += count
                }
            }
        }
    }

    /** `FSE_initCState2`. */
    fun init(symbol: Int): Int {
        val out = (deltaBits[symbol] + (1 shl (STATE_SHIFT - 1))) ushr STATE_SHIFT
        val value = (out shl STATE_SHIFT) - deltaBits[symbol]
        return states[(value ushr out) + deltaState[symbol]]
    }

    /** `FSE_encodeSymbol`. */
    fun encode(bits: ForwardBitWriter, state: Int, symbol: Int): Int {
        val out = (state + deltaBits[symbol]) ushr STATE_SHIFT
        bits.add(state and ((1 shl out) - 1), out)
        return states[(state ushr out) + deltaState[symbol]]
    }

    fun flush(bits: ForwardBitWriter, state: Int) = bits.add(state and ((1 shl log) - 1), log)

    private companion object {
        const val STATE_SHIFT = 16
    }
}

/** Прямой поток бит, младший первым; закрывается единичной меткой конца. */
internal class ForwardBitWriter {
    private val out = ByteArrayOutputStream()
    private var acc = 0L
    private var count = 0

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

    fun close(): ByteArray {
        add(1, 1)
        if (count > 0) out.write(acc.toInt())
        return out.toByteArray()
    }
}
