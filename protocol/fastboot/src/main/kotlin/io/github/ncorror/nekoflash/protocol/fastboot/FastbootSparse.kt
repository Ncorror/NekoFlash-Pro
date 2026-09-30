package io.github.ncorror.nekoflash.protocol.fastboot

import java.io.EOFException
import java.io.InputStream

/**
 * Участок образа: [length] байт начиная с блока [block].
 *
 * Между участками — «не важно» (`DONT_CARE`): эти блоки устройство не трогает.
 */
public sealed interface SparseExtent {
    public val block: Long
    public val length: Long

    /** Байты источника с [offset]; хвост до границы блока дополняется нулями. */
    public data class Data(override val block: Long, override val length: Long, val offset: Long) : SparseExtent

    /** Блоки, заполненные одним 32-битным словом [value] (порядок байт — little-endian). */
    public data class Fill(override val block: Long, override val length: Long, val value: Int) : SparseExtent
}

/**
 * Образ в представлении `libsparse`: размер целого образа и участки с данными.
 *
 * Кусок, полученный [FastbootSparse.resparse], — такой же образ той же длины:
 * всё, что не вошло в кусок, в нём значится как `DONT_CARE`. Поэтому каждый
 * кусок прошивается отдельной парой `download` + `flash:РАЗДЕЛ` в один и тот же
 * раздел, и устройство пишет только свои блоки.
 */
public class SparseImage(
    public val blockSize: Int,
    public val length: Long,
    public val extents: List<SparseExtent>,
) {
    /** Блоков в целом образе: `total_blks` заголовка. */
    public val totalBlocks: Long get() = divRoundUp(length, blockSize.toLong())

    /** Число записей в файле sparse: `sparse_count_chunks`. */
    public val chunkCount: Int
        get() {
            var lastBlock = 0L
            var chunks = 0
            for (extent in extents) {
                if (extent.block > lastBlock) chunks++
                chunks++
                lastBlock = extent.block + divRoundUp(extent.length, blockSize.toLong())
            }
            if (lastBlock < totalBlocks) chunks++
            return chunks
        }

    /** Размер в формате sparse без CRC: `sparse_file_len(s, true, false)`. */
    public val sparseLength: Long
        get() {
            var total = FastbootSparse.HEADER_LEN.toLong()
            var lastBlock = 0L
            for (extent in extents) {
                if (extent.block > lastBlock) total += FastbootSparse.CHUNK_HEADER_LEN
                total += chunkBytes(extent)
                lastBlock = extent.block + divRoundUp(extent.length, blockSize.toLong())
            }
            if (lastBlock < totalBlocks) total += FastbootSparse.CHUNK_HEADER_LEN
            return total
        }

    /**
     * Байты этого образа в формате sparse, по порядку.
     *
     * Данные берутся из [source] — потока того же файла, из которого образ
     * прочитан. Участки идут по возрастанию смещения, поэтому поток читается
     * только вперёд, и один поток обслуживает все куски подряд.
     */
    public fun stream(source: SparseDataReader): InputStream = SparseImageStream(this, source)

    internal fun chunkBytes(extent: SparseExtent): Long = FastbootSparse.CHUNK_HEADER_LEN + when (extent) {
        is SparseExtent.Data -> align(extent.length, blockSize.toLong())
        is SparseExtent.Fill -> Int.SIZE_BYTES.toLong()
    }
}

/** Чтение файла образа только вперёд: смещения запросов не убывают. */
public class SparseDataReader(private val input: InputStream) {
    private var position = 0L

    /** Кладёт в [target] [length] байт файла с [offset]. */
    public fun read(offset: Long, target: ByteArray, length: Int) {
        require(offset >= position) { "sparse data must be read forward: $offset < $position" }
        skipFully(input, offset - position)
        readFully(input, target, length)
        position = offset + length
    }
}

/**
 * Sparse-образы Android — как их готовит настольный `fastboot`.
 *
 * Это новая работа, а не перенос: в архивах слова `sparse` нет. Источник
 * поведения — AOSP `libsparse` (`sparse_format.h`, `sparse_read.cpp`,
 * `sparse.cpp`, `output_file.cpp`) и `fastboot.cpp` (`get_sparse_limit`,
 * `load_buf_fd`); функции ниже названы по их функциям.
 */
public object FastbootSparse {
    public const val MAGIC: Int = 0xED26FF3A.toInt()
    public const val HEADER_LEN: Int = 28
    public const val CHUNK_HEADER_LEN: Int = 12
    public const val CHUNK_RAW: Int = 0xCAC1
    public const val CHUNK_FILL: Int = 0xCAC2
    public const val CHUNK_DONT_CARE: Int = 0xCAC3
    public const val CHUNK_CRC32: Int = 0xCAC4

    /** Блок, которым режется обычный образ: `sparse_file_import_auto`. */
    public const val RAW_BLOCK_SIZE: Int = 4096

    /** `RESPARSE_LIMIT` настольного клиента: больше куски не бывают, чтобы не раздувать память. */
    public const val RESPARSE_LIMIT: Long = 1L shl 30

    /**
     * Размер куска или `0`, если резать не нужно: `get_sparse_limit`.
     *
     * [requested] — `-S`; `0` и его отсутствие значат «как скажет устройство»,
     * тогда предел — `max-download-size`. Режется только файл больше предела,
     * и не крупнее [RESPARSE_LIMIT]. Предел не превращается в запрет: файл,
     * которому резать нечего, уходит целиком, и отказ по объёму — ответ
     * устройства (`03` §2).
     */
    public fun limit(fileSize: Long, requested: Long?, maxDownloadSize: Long?): Long {
        val limit = requested?.takeIf { it != 0L } ?: maxDownloadSize?.takeIf { it > 0 } ?: return 0
        return if (fileSize > limit) minOf(limit, RESPARSE_LIMIT) else 0
    }

    /**
     * Читает образ: `sparse_file_import_auto`.
     *
     * Файл, который разбирается как sparse, берётся по своим записям. Любой
     * другой — и sparse, разбор которого сорвался, — читается как обычный:
     * блоками по 4 КиБ, где блок из одного повторённого слова становится
     * заполнением, а прочий — данными. Так делает и AOSP.
     */
    public fun read(open: () -> InputStream, size: Long): SparseImage =
        open().buffered(READ_BUFFER).use { input -> readSparse(input) }
            ?: open().buffered(READ_BUFFER).use { input -> readNormal(input, size) }

    /**
     * Режет образ на куски не больше [maxLength] байт в формате sparse:
     * `sparse_file_resparse` и `move_chunks_up_to_len`.
     *
     * Кусок набирается участками по порядку. Участок, который не влезает,
     * делится, если в куске остаётся больше восьмой части места, — иначе
     * кусок закрывается и участок целиком уходит в следующий.
     */
    public fun resparse(image: SparseImage, maxLength: Long): List<SparseImage> {
        require(maxLength > 0 && maxLength <= UINT_MAX) { "invalid max size $maxLength" }
        val pending = ArrayDeque(image.extents)
        val pieces = mutableListOf<SparseImage>()
        do {
            val piece = takePiece(image, pending, maxLength)
            pieces += SparseImage(image.blockSize, image.length, piece)
        } while (pending.isNotEmpty())
        return pieces
    }

    private fun takePiece(image: SparseImage, pending: ArrayDeque<SparseExtent>, maxLength: Long): List<SparseExtent> {
        // Заголовок файла, возможный хвостовой DONT_CARE и CRC — как в AOSP,
        // где `len` беззнаковый: слишком малый предел переворачивается.
        val len = (maxLength - OVERHEAD) and UINT_MAX
        val taken = mutableListOf<SparseExtent>()
        var fileLength = 0L
        var lastBlock = 0L
        while (pending.isNotEmpty()) {
            val extent = pending.first()
            var count = if (extent.block > lastBlock) CHUNK_HEADER_LEN.toLong() else 0L
            lastBlock = extent.block + divRoundUp(extent.length, image.blockSize.toLong())
            count += image.chunkBytes(extent)
            if (fileLength + count > len) {
                fileLength += CHUNK_HEADER_LEN
                if (taken.isEmpty() || len - fileLength > len / SPLIT_SHARE) {
                    val room = ((len - fileLength) and UINT_MAX) / image.blockSize * image.blockSize
                    pending.removeFirst()
                    val (head, tail) = split(extent, room, image.blockSize)
                    taken += head
                    tail?.let(pending::addFirst)
                }
                return taken
            }
            fileLength += count
            taken += pending.removeFirst()
        }
        return taken
    }

    /** `backed_block_split`: первые [room] байт — в этот кусок, остальное — в следующий. */
    private fun split(extent: SparseExtent, room: Long, blockSize: Int): Pair<SparseExtent, SparseExtent?> {
        if (extent.length <= room) return extent to null
        val blocks = room / blockSize
        return when (extent) {
            is SparseExtent.Data -> extent.copy(length = room) to
                SparseExtent.Data(extent.block + blocks, extent.length - room, extent.offset + room)
            is SparseExtent.Fill -> extent.copy(length = room) to
                SparseExtent.Fill(extent.block + blocks, extent.length - room, extent.value)
        }
    }

    /**
     * Разбор файла sparse без проверки CRC, как у `fastboot`: `sparse_file_import`.
     * `null` — файл не sparse или разобран не был.
     */
    private fun readSparse(input: InputStream): SparseImage? {
        val bytes = ByteArray(HEADER_LEN)
        val header = if (readAvailable(input, bytes)) SparseHeader(bytes).takeIf { it.valid } else null
        return header?.let {
            runCatching {
                skipFully(input, (it.fileHeaderSize - HEADER_LEN).toLong())
                SparseImage(it.blockSize.toInt(), it.totalBlocks * it.blockSize, readChunks(input, it))
            }.getOrNull()
        }
    }

    private fun readChunks(input: InputStream, header: SparseHeader): List<SparseExtent> {
        val extents = ExtentList(header.blockSize)
        val chunk = ByteArray(CHUNK_HEADER_LEN)
        var offset = header.fileHeaderSize.toLong()
        var block = 0L
        var remaining = header.totalChunks
        while (remaining-- > 0) {
            readFully(input, chunk, CHUNK_HEADER_LEN)
            skipFully(input, (header.chunkHeaderSize - CHUNK_HEADER_LEN).toLong())
            offset += header.chunkHeaderSize
            val type = u16(chunk, 0)
            val blocks = u32(chunk, CHUNK_BLOCKS_AT)
            val dataSize = u32(chunk, CHUNK_TOTAL_AT) - header.chunkHeaderSize
            readChunk(input, type, dataSize)?.let { value ->
                val length = blocks * header.blockSize
                extents.add(
                    when (value) {
                        is ChunkData.Raw -> {
                            check(dataSize == length) { "raw chunk of $dataSize bytes for $blocks blocks" }
                            skipFully(input, length)
                            SparseExtent.Data(block, length, offset)
                        }
                        is ChunkData.Fill -> SparseExtent.Fill(block, length, value.value)
                    },
                )
            }
            offset += dataSize
            if (type != CHUNK_CRC32) block += blocks
        }
        check(block == header.totalBlocks) { "sparse image covers $block of ${header.totalBlocks} blocks" }
        return extents.toList()
    }

    /**
     * Данные одной записи: сырые байты читает вызывающий, заполнение — здесь;
     * `DONT_CARE` и CRC данных для образа не несут. CRC не сверяется: так
     * читает `fastboot`.
     */
    private fun readChunk(input: InputStream, type: Int, dataSize: Long): ChunkData? = when (type) {
        CHUNK_RAW -> ChunkData.Raw
        CHUNK_FILL -> {
            check(dataSize == Int.SIZE_BYTES.toLong()) { "fill chunk of $dataSize bytes" }
            val value = ByteArray(Int.SIZE_BYTES).also { readFully(input, it, it.size) }
            ChunkData.Fill(i32(value, 0))
        }
        CHUNK_DONT_CARE -> null.also { check(dataSize == 0L) { "don't care chunk of $dataSize bytes" } }
        CHUNK_CRC32 -> null.also {
            check(dataSize == Int.SIZE_BYTES.toLong()) { "crc chunk of $dataSize bytes" }
            skipFully(input, dataSize)
        }
        // AOSP пропускает незнакомую запись, не сдвигаясь по файлу, и дальше
        // читает её данные как заголовки; здесь это отказ разбора.
        else -> error("unknown chunk type $type")
    }

    /** `sparse_file_read_normal`: блоки по 4 КиБ, повтор одного слова — заполнение. */
    private fun readNormal(input: InputStream, size: Long): SparseImage {
        val extents = ExtentList(RAW_BLOCK_SIZE.toLong())
        val buffer = ByteArray(RAW_BLOCK_SIZE)
        var offset = 0L
        var block = 0L
        while (offset < size) {
            val length = minOf(size - offset, RAW_BLOCK_SIZE.toLong()).toInt()
            readFully(input, buffer, length)
            val fill = if (length == RAW_BLOCK_SIZE) uniformWord(buffer) else null
            extents.add(
                if (fill != null) {
                    SparseExtent.Fill(block, length.toLong(), fill)
                } else {
                    SparseExtent.Data(block, length.toLong(), offset)
                },
            )
            offset += length
            block++
        }
        return SparseImage(RAW_BLOCK_SIZE, size, extents.toList())
    }

    private fun uniformWord(block: ByteArray): Int? {
        val first = i32(block, 0)
        for (index in Int.SIZE_BYTES until block.size step Int.SIZE_BYTES) {
            if (i32(block, index) != first) return null
        }
        return first
    }

    private const val READ_BUFFER = 1 shl 20
    private const val CHUNK_BLOCKS_AT = 4
    private const val CHUNK_TOTAL_AT = 8
    private const val SPLIT_SHARE = 8
    private const val UINT_MAX = 0xFFFF_FFFFL
    private const val OVERHEAD = (HEADER_LEN + 2 * CHUNK_HEADER_LEN + Int.SIZE_BYTES).toLong()
}

/** Заголовок файла sparse: поля `sparse_header_t` и проверки `sparse_file_import`. */
private class SparseHeader(bytes: ByteArray) {
    val fileHeaderSize = u16(bytes, 8)
    val chunkHeaderSize = u16(bytes, 10)
    val blockSize = u32(bytes, 12)
    val totalBlocks = u32(bytes, 16)
    val totalChunks = u32(bytes, 20)
    val valid = i32(bytes, 0) == FastbootSparse.MAGIC && u16(bytes, 4) == MAJOR_VERSION &&
        fileHeaderSize >= FastbootSparse.HEADER_LEN && chunkHeaderSize >= FastbootSparse.CHUNK_HEADER_LEN &&
        blockSize != 0L && blockSize % Int.SIZE_BYTES == 0L && totalBlocks != 0L

    private companion object {
        const val MAJOR_VERSION = 1
    }
}

private sealed interface ChunkData {
    data object Raw : ChunkData
    class Fill(val value: Int) : ChunkData
}

/**
 * Список участков со слиянием соседних, как `queue_bb`/`merge_bb`: заполнения
 * одним словом и данные, идущие подряд в файле, становятся одним участком.
 */
private class ExtentList(private val blockSize: Long) {
    private val extents = mutableListOf<SparseExtent>()

    fun add(extent: SparseExtent) {
        if (extent.length == 0L) return
        val last = extents.lastOrNull()
        val adjacent = last != null && last.block + last.length / blockSize == extent.block
        val merged = when {
            !adjacent -> null
            last is SparseExtent.Fill && extent is SparseExtent.Fill && last.value == extent.value ->
                last.copy(length = last.length + extent.length)
            last is SparseExtent.Data && extent is SparseExtent.Data && last.offset + last.length == extent.offset ->
                last.copy(length = last.length + extent.length)
            else -> null
        }
        if (merged != null) extents[extents.lastIndex] = merged else extents += extent
    }

    fun toList(): List<SparseExtent> = extents.toList()
}

/** Байты образа sparse: заголовок, записи, данные из файла и нули до границы блока. */
private class SparseImageStream(private val image: SparseImage, private val source: SparseDataReader) : InputStream() {
    private val parts = ArrayDeque<Part>()
    private var current: ByteArray = ByteArray(0)
    private var index = 0

    init {
        parts += Part.Bytes(header())
        var lastBlock = 0L
        for (extent in image.extents) {
            if (extent.block > lastBlock) parts += Part.Bytes(dontCare(extent.block - lastBlock))
            val blocks = divRoundUp(extent.length, image.blockSize.toLong())
            when (extent) {
                is SparseExtent.Data -> {
                    val padded = blocks * image.blockSize
                    parts += Part.Bytes(chunk(FastbootSparse.CHUNK_RAW, blocks, padded))
                    parts += Part.Source(extent.offset, extent.length)
                    if (padded > extent.length) parts += Part.Zeros(padded - extent.length)
                }
                is SparseExtent.Fill -> parts += Part.Bytes(
                    chunk(FastbootSparse.CHUNK_FILL, blocks, Int.SIZE_BYTES.toLong()) + le32(extent.value),
                )
            }
            lastBlock = extent.block + blocks
        }
        // Хвост до конца образа — тоже DONT_CARE. У образа, длина которого не
        // кратна блоку, AOSP считает эту запись, но не пишет её: здесь она
        // пишется целым числом блоков, как и значится в заголовке.
        if (lastBlock < image.totalBlocks) parts += Part.Bytes(dontCare(image.totalBlocks - lastBlock))
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and BYTE_MASK
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (index == current.size && !advance()) return -1
        val count = minOf(length, current.size - index)
        current.copyInto(target, offset, index, index + count)
        index += count
        return count
    }

    /** Следующая порция байт; `false` — образ кончился. */
    private fun advance(): Boolean {
        while (parts.isNotEmpty()) {
            val next = when (val part = parts.removeFirst()) {
                is Part.Bytes -> part.bytes
                is Part.Zeros -> ByteArray(minOf(part.count, PORTION.toLong()).toInt()).also {
                    if (part.count > it.size) parts.addFirst(Part.Zeros(part.count - it.size))
                }
                is Part.Source -> {
                    val size = minOf(part.length, PORTION.toLong()).toInt()
                    ByteArray(size).also {
                        source.read(part.offset, it, size)
                        if (part.length > size) parts.addFirst(Part.Source(part.offset + size, part.length - size))
                    }
                }
            }
            if (next.isNotEmpty()) {
                current = next
                index = 0
                return true
            }
        }
        return false
    }

    private fun header(): ByteArray = le16(MAGIC_LOW) + le16(MAGIC_HIGH) + le16(1) + le16(0) +
        le16(FastbootSparse.HEADER_LEN) + le16(FastbootSparse.CHUNK_HEADER_LEN) +
        le32(image.blockSize) + le32(image.totalBlocks.toInt()) + le32(image.chunkCount) + le32(0)

    private fun dontCare(blocks: Long): ByteArray = chunk(FastbootSparse.CHUNK_DONT_CARE, blocks, 0)

    private fun chunk(type: Int, blocks: Long, dataBytes: Long): ByteArray =
        le16(type) + le16(0) + le32(blocks.toInt()) + le32((FastbootSparse.CHUNK_HEADER_LEN + dataBytes).toInt())

    private sealed interface Part {
        class Bytes(val bytes: ByteArray) : Part
        class Zeros(val count: Long) : Part
        class Source(val offset: Long, val length: Long) : Part
    }

    private companion object {
        const val PORTION = 64 * 1024
        const val BYTE_MASK = 0xFF
        const val MAGIC_LOW = 0xFF3A
        const val MAGIC_HIGH = 0xED26
    }
}

private fun divRoundUp(value: Long, divisor: Long): Long = (value + divisor - 1) / divisor

private fun align(value: Long, divisor: Long): Long = divRoundUp(value, divisor) * divisor

private fun u16(bytes: ByteArray, at: Int): Int =
    (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

private fun i32(bytes: ByteArray, at: Int): Int = u16(bytes, at) or (u16(bytes, at + 2) shl 16)

private fun u32(bytes: ByteArray, at: Int): Long = i32(bytes, at).toLong() and 0xFFFF_FFFFL

private fun le16(value: Int): ByteArray = byteArrayOf(value.toByte(), (value shr 8).toByte())

private fun le32(value: Int): ByteArray = le16(value and 0xFFFF) + le16(value ushr 16)

/** Читает ровно [length] байт; короче — конец файла раньше объявленного размера. */
private fun readFully(input: InputStream, target: ByteArray, length: Int) {
    var done = 0
    while (done < length) {
        val read = input.read(target, done, length - done)
        if (read < 0) throw EOFException("file ended after $done of $length bytes")
        done += read
    }
}

/** Как [readFully], но конец файла — ответ `false`, а не ошибка: для заголовка. */
private fun readAvailable(input: InputStream, target: ByteArray): Boolean =
    runCatching { readFully(input, target, target.size) }.isSuccess

private fun skipFully(input: InputStream, count: Long) {
    var left = count
    while (left > 0) {
        val skipped = input.skip(left)
        if (skipped > 0) {
            left -= skipped
        } else if (input.read() < 0) {
            throw EOFException("file ended with $left bytes left to skip")
        } else {
            left--
        }
    }
}
