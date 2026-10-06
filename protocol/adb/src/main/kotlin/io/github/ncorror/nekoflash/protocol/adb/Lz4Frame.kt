package io.github.ncorror.nekoflash.protocol.adb

/**
 * Кадр LZ4 (формат `lz4_Frame_format.md` 1.6.x) — то, что `LZ4F_decompress`
 * принимает у `adbd`: независимые и связанные блоки, суммы блоков, заголовка
 * и содержимого, размер содержимого. Словарь (`DictID`) — ошибка: `adbd` его
 * не шлёт, а общего словаря у клиента нет.
 */
internal class Lz4FrameDecoder : SyncDecoder {
    private val input = InputBuffer()
    private var state = State.MAGIC
    private var independent = true
    private var blockChecksum = false
    private var contentChecksum = false
    private var contentSize = -1L
    private var blockMax = 0
    private var produced = 0L
    private var skip = 0L
    private val content = XxHash32()

    /** Последние до 64 КиБ вывода — окно для связанных блоков. */
    private var history = ByteArray(0)

    private enum class State { MAGIC, SKIP_SIZE, SKIP, DESCRIPTOR, BLOCK, CHECKSUM, DONE }

    override fun feed(chunk: ByteArray, output: (ByteArray) -> Unit) {
        corruptIf(state == State.DONE && chunk.isNotEmpty()) { "LZ4 stream hit end before reading all data" }
        input.append(chunk)
        while (step(output)) {
            // Keep consuming complete input already buffered.
        }
        corruptIf(state == State.DONE && input.available > 0) { "LZ4 stream hit end before reading all data" }
    }

    override fun end(output: (ByteArray) -> Unit) {
        corruptIf(state != State.DONE) { "LZ4 stream ended in the middle of a frame" }
    }

    /** Один шаг разбора; `false` — нужно больше входа или кадр кончился. */
    private fun step(output: (ByteArray) -> Unit): Boolean = when (state) {
        State.MAGIC -> magic()
        State.SKIP_SIZE -> need(Int.SIZE_BYTES) {
            skip = input.intLe(0).toLong() and UINT
            input.skip(Int.SIZE_BYTES)
            state = State.SKIP
        }
        State.SKIP -> {
            val take = minOf(skip, input.available.toLong()).toInt()
            input.skip(take)
            skip -= take
            if (skip == 0L) state = State.DONE
            take > 0 && state != State.DONE
        }
        State.DESCRIPTOR -> descriptor()
        State.BLOCK -> block(output)
        State.CHECKSUM -> need(Int.SIZE_BYTES) {
            corruptIf(contentChecksum && input.intLe(0) != content.digest()) { "LZ4 content checksum" }
            input.skip(Int.SIZE_BYTES)
            state = State.DONE
        }
        State.DONE -> false
    }

    private inline fun need(bytes: Int, body: () -> Unit): Boolean {
        if (input.available < bytes) return false
        body()
        return true
    }

    private fun magic(): Boolean = need(Int.SIZE_BYTES) {
        val magic = input.intLe(0)
        input.skip(Int.SIZE_BYTES)
        state = when {
            magic == MAGIC -> State.DESCRIPTOR
            magic and SKIPPABLE_MASK == SKIPPABLE -> State.SKIP_SIZE
            else -> throw SyncCodecException("LZ4 frame magic 0x${Integer.toHexString(magic)}")
        }
    }

    private fun descriptor(): Boolean {
        if (input.available < 2) return false
        val flg = input.peek(0)
        val bd = input.peek(1)
        val length = 2 + (if (flg and FLG_CONTENT_SIZE != 0) Long.SIZE_BYTES else 0) +
            (if (flg and FLG_DICT_ID != 0) Int.SIZE_BYTES else 0)
        if (input.available < length + 1) return false
        corruptIf(flg ushr VERSION_SHIFT != VERSION) { "LZ4 frame version" }
        corruptIf(flg and FLG_RESERVED != 0 || bd and BD_RESERVED != 0) { "LZ4 reserved bits" }
        corruptIf(flg and FLG_DICT_ID != 0) { "LZ4 dictionary is not supported" }
        val sizeId = (bd ushr BLOCK_SHIFT) and BLOCK_MASK
        corruptIf(sizeId < MIN_BLOCK_ID) { "LZ4 block size id $sizeId" }
        val bytes = input.take(length)
        corruptIf(((XxHash32.of(bytes) ushr Byte.SIZE_BITS) and BYTE) != input.peek(0)) { "LZ4 header checksum" }
        input.skip(1)
        independent = flg and FLG_INDEPENDENT != 0
        blockChecksum = flg and FLG_BLOCK_CHECKSUM != 0
        contentChecksum = flg and FLG_CONTENT_CHECKSUM != 0
        contentSize = if (flg and FLG_CONTENT_SIZE != 0) {
            (XxHash32.intLe(bytes, 2).toLong() and UINT) or (XxHash32.intLe(bytes, 2 + Int.SIZE_BYTES).toLong() shl 32)
        } else {
            -1
        }
        blockMax = 1 shl (BLOCK_BASE_LOG + 2 * sizeId)
        state = State.BLOCK
        return true
    }

    private fun block(output: (ByteArray) -> Unit): Boolean {
        if (input.available < Int.SIZE_BYTES) return false
        val header = input.intLe(0)
        return if (header == 0) endMark() else dataBlock(header, output)
    }

    /** `EndMark`: блоков больше нет, дальше — сумма содержимого, если объявлена. */
    private fun endMark(): Boolean {
        input.skip(Int.SIZE_BYTES)
        corruptIf(contentSize >= 0 && contentSize != produced) { "LZ4 content size" }
        state = if (contentChecksum) State.CHECKSUM else State.DONE
        return state != State.DONE
    }

    private fun dataBlock(header: Int, output: (ByteArray) -> Unit): Boolean {
        val stored = header and UNCOMPRESSED_BIT != 0
        val size = header and UNCOMPRESSED_BIT.inv()
        corruptIf(size > blockMax) { "LZ4 block of $size bytes" }
        val total = Int.SIZE_BYTES + size + if (blockChecksum) Int.SIZE_BYTES else 0
        if (input.available < total) return false
        input.skip(Int.SIZE_BYTES)
        val data = input.take(size)
        if (blockChecksum) {
            corruptIf(input.intLe(0) != XxHash32.of(data)) { "LZ4 block checksum" }
            input.skip(Int.SIZE_BYTES)
        }
        val decoded = if (stored) data else Lz4Block.decompress(data, if (independent) EMPTY else history, blockMax)
        produced += decoded.size
        if (contentChecksum) content.update(decoded)
        if (!independent) history = window(history, decoded)
        if (decoded.isNotEmpty()) output(decoded)
        return true
    }

    private fun window(previous: ByteArray, next: ByteArray): ByteArray {
        if (next.size >= WINDOW) return next.copyOfRange(next.size - WINDOW, next.size)
        val keep = minOf(previous.size, WINDOW - next.size)
        return previous.copyOfRange(previous.size - keep, previous.size) + next
    }

    private companion object {
        const val MAGIC = 0x184D2204
        const val SKIPPABLE = 0x184D2A50
        const val SKIPPABLE_MASK = -0x10
        const val VERSION_SHIFT = 6
        const val VERSION = 1
        const val FLG_INDEPENDENT = 0x20
        const val FLG_BLOCK_CHECKSUM = 0x10
        const val FLG_CONTENT_SIZE = 0x08
        const val FLG_CONTENT_CHECKSUM = 0x04
        const val FLG_RESERVED = 0x02
        const val FLG_DICT_ID = 0x01
        const val BD_RESERVED = 0x8F
        const val BLOCK_SHIFT = 4
        const val BLOCK_MASK = 7
        const val MIN_BLOCK_ID = 4
        const val BLOCK_BASE_LOG = 8
        const val UNCOMPRESSED_BIT = Int.MIN_VALUE
        const val WINDOW = 1 shl 16
        const val UINT = 0xFFFF_FFFFL
        const val BYTE = 0xFF
        val EMPTY = ByteArray(0)
    }
}

/**
 * Кадр LZ4 от клиента: блоки по 64 КиБ, независимые, без сумм — `LZ4F`
 * с настройками по умолчанию, кроме связи блоков (`adbd` читает любые).
 */
internal class Lz4FrameEncoder : SyncEncoder {
    private val pending = ByteArray(BLOCK)
    private var used = 0
    private var started = false

    override fun feed(data: ByteArray, length: Int, output: (ByteArray) -> Unit) {
        start(output)
        var at = 0
        while (at < length) {
            val take = minOf(length - at, BLOCK - used)
            data.copyInto(pending, used, at, at + take)
            used += take
            at += take
            if (used == BLOCK) flush(output)
        }
    }

    override fun end(output: (ByteArray) -> Unit) {
        start(output)
        flush(output)
        output(ByteArray(Int.SIZE_BYTES))
    }

    private fun start(output: (ByteArray) -> Unit) {
        if (started) return
        started = true
        val descriptor = byteArrayOf(FLG.toByte(), BD.toByte())
        val checksum = ((XxHash32.of(descriptor) ushr Byte.SIZE_BITS) and BYTE).toByte()
        output(intLe(MAGIC) + descriptor + checksum)
    }

    private fun flush(output: (ByteArray) -> Unit) {
        if (used == 0) return
        val compressed = Lz4Block.compress(pending, used)
        output(
            if (compressed.size < used) {
                intLe(compressed.size) + compressed
            } else {
                intLe(used or Int.MIN_VALUE) + pending.copyOf(used)
            },
        )
        used = 0
    }

    private companion object {
        const val MAGIC = 0x184D2204
        const val FLG = 0x60
        const val BD = 0x40
        const val BLOCK = 1 shl 16
        const val BYTE = 0xFF
    }
}

/** Блок LZ4 (`lz4_Block_format.md`): распаковка с окном и жадное сжатие по хешу. */
internal object Lz4Block {
    private const val MIN_MATCH = 4
    private const val LAST_LITERALS = 5
    private const val MF_LIMIT = 12
    private const val RUN_MASK = 15
    private const val MAX_OFFSET = 65535
    private const val HASH_LOG = 16
    private const val HASH_MUL = -0x61c8864f
    private const val BYTE = 0xFF

    /** Распаковка в буфер с окном [prefix] перед ним; вывод — только новое. */
    fun decompress(source: ByteArray, prefix: ByteArray, maxOutput: Int): ByteArray {
        val out = ByteArray(prefix.size + maxOutput)
        prefix.copyInto(out)
        var op = prefix.size
        var ip = 0
        while (true) {
            corruptIf(ip >= source.size) { "LZ4 block truncated" }
            val token = source[ip++].toInt() and BYTE
            var literals = token ushr 4
            if (literals == RUN_MASK) {
                do {
                    corruptIf(ip >= source.size) { "LZ4 block truncated" }
                    val more = source[ip++].toInt() and BYTE
                    literals += more
                } while (more == BYTE)
            }
            corruptIf(ip + literals > source.size || op + literals > out.size) { "LZ4 literals" }
            source.copyInto(out, op, ip, ip + literals)
            ip += literals
            op += literals
            if (ip == source.size) break
            corruptIf(ip + 2 > source.size) { "LZ4 block truncated" }
            val offset = (source[ip].toInt() and BYTE) or ((source[ip + 1].toInt() and BYTE) shl 8)
            ip += 2
            corruptIf(offset == 0 || offset > op) { "LZ4 offset $offset" }
            var length = token and RUN_MASK
            if (length == RUN_MASK) {
                do {
                    corruptIf(ip >= source.size) { "LZ4 block truncated" }
                    val more = source[ip++].toInt() and BYTE
                    length += more
                } while (more == BYTE)
            }
            length += MIN_MATCH
            corruptIf(op + length > out.size) { "LZ4 match past block end" }
            var from = op - offset
            repeat(length) { out[op++] = out[from++] }
        }
        return out.copyOfRange(prefix.size, op)
    }

    /** Жадное сжатие блока: совпадение от 4 байт по хеш-таблице, хвост — литералами. */
    fun compress(source: ByteArray, length: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(length + length / BYTE + 16)
        val table = IntArray(1 shl HASH_LOG) { -1 }
        var anchor = 0
        var ip = 0
        val matchLimit = length - LAST_LITERALS
        while (ip < length - MF_LIMIT) {
            val sequence = int(source, ip)
            val hash = (sequence * HASH_MUL) ushr (Int.SIZE_BITS - HASH_LOG)
            val candidate = table[hash]
            table[hash] = ip
            if (candidate < 0 || ip - candidate > MAX_OFFSET || int(source, candidate) != sequence) {
                ip++
                continue
            }
            var matched = MIN_MATCH
            while (ip + matched < matchLimit && source[candidate + matched] == source[ip + matched]) matched++
            sequence(out, source, anchor, ip - anchor, ip - candidate, matched)
            ip += matched
            anchor = ip
        }
        sequence(out, source, anchor, length - anchor, 0, 0)
        return out.toByteArray()
    }

    private fun sequence(
        out: java.io.ByteArrayOutputStream,
        source: ByteArray,
        from: Int,
        literals: Int,
        offset: Int,
        match: Int,
    ) {
        val matchCode = if (match == 0) 0 else match - MIN_MATCH
        out.write((minOf(literals, RUN_MASK) shl 4) or minOf(matchCode, RUN_MASK))
        if (literals >= RUN_MASK) extend(out, literals - RUN_MASK)
        out.write(source, from, literals)
        if (match == 0) return
        out.write(offset and BYTE)
        out.write(offset ushr Byte.SIZE_BITS)
        if (matchCode >= RUN_MASK) extend(out, matchCode - RUN_MASK)
    }

    private fun extend(out: java.io.ByteArrayOutputStream, value: Int) {
        var left = value
        while (left >= BYTE) {
            out.write(BYTE)
            left -= BYTE
        }
        out.write(left)
    }

    private fun int(source: ByteArray, at: Int): Int = XxHash32.intLe(source, at)
}
