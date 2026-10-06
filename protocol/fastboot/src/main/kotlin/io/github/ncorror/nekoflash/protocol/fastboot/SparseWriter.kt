package io.github.ncorror.nekoflash.protocol.fastboot

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Участок образа, который собирается на хосте: с блока [block]. */
internal sealed interface SparseChunk {
    val block: Long

    /** [blocks] блоков, заполненных 32-битным словом [value]. */
    data class Fill(override val block: Long, val blocks: Long, val value: Int = 0) : SparseChunk

    /**
     * Данные, дополненные нулями до целого блока.
     *
     * [parts] держит ссылки на уже собранные блоки, а не склеивает их во второй
     * гигантский массив. Вторичный конструктор сохраняет прежний удобный вход.
     */
    class Raw(override val block: Long, val parts: List<ByteArray>) : SparseChunk {
        constructor(block: Long, bytes: ByteArray) : this(block, listOf(bytes))
    }
}

/**
 * `sparse_file_write` из AOSP `libsparse` без сжатия и суммы: участки по
 * возрастанию блока, промежутки между ними и хвост до [totalBlocks] —
 * `DONT_CARE`. Так пишут образы `mke2fs -E android_sparse` и `ImageBuilder`.
 */
internal object SparseWriter {
    fun write(blockSize: Int, totalBlocks: Long, chunks: List<SparseChunk>): ByteArray =
        ByteArrayOutputStream().also { output ->
            writeTo(output, blockSize, totalBlocks, chunks)
        }.toByteArray()

    /**
     * Пишет sparse-файл прямо в [output] и возвращает его фактическую длину.
     *
     * Заголовок требует число chunks заранее, поэтому список обходится дважды,
     * но сами RAW-данные ни разу не копируются в промежуточный общий массив.
     */
    fun writeTo(
        output: OutputStream,
        blockSize: Int,
        totalBlocks: Long,
        chunks: List<SparseChunk>,
    ): Long {
        require(totalBlocks in 0..UINT) { "Block device is too large to encode with libsparse." }
        val count = encodedChunkCount(blockSize, totalBlocks, chunks)
        val file = fileHeader(blockSize, totalBlocks, count)
        output.write(file)
        var written = file.size.toLong()
        var at = 0L
        for (chunk in chunks) {
            if (chunk.block > at) {
                val bytes = header(FastbootSparse.CHUNK_DONT_CARE, chunk.block - at, 0)
                output.write(bytes)
                written += bytes.size
            }
            at = chunk.block + when (chunk) {
                is SparseChunk.Fill -> {
                    val bytes = header(FastbootSparse.CHUNK_FILL, chunk.blocks, FILL_BYTES)
                    output.write(bytes)
                    output.write(fillWord(chunk.value))
                    written += bytes.size + FILL_BYTES
                    chunk.blocks
                }
                is SparseChunk.Raw -> {
                    val rawBytes = chunk.parts.sumOf { it.size.toLong() }
                    val blocks = (rawBytes + blockSize - 1) / blockSize
                    val payload = Math.multiplyExact(blocks, blockSize.toLong())
                    require(payload <= Int.MAX_VALUE - FastbootSparse.CHUNK_HEADER_LEN) { "Sparse RAW chunk is too large." }
                    val bytes = header(FastbootSparse.CHUNK_RAW, blocks, payload.toInt())
                    output.write(bytes)
                    chunk.parts.forEach { part -> output.write(part) }
                    val padding = (payload - rawBytes).toInt()
                    if (padding > 0) output.write(ByteArray(padding))
                    written += bytes.size + payload
                    blocks
                }
            }
        }
        if (at < totalBlocks) {
            val bytes = header(FastbootSparse.CHUNK_DONT_CARE, totalBlocks - at, 0)
            output.write(bytes)
            written += bytes.size
        }
        return written
    }

    private fun encodedChunkCount(blockSize: Int, totalBlocks: Long, chunks: List<SparseChunk>): Int {
        var at = 0L
        var count = 0
        for (chunk in chunks) {
            require(chunk.block >= at) { "sparse chunks overlap at block ${chunk.block}" }
            if (chunk.block > at) count++
            at = chunk.block + blocksOf(chunk, blockSize)
            count++
        }
        require(at <= totalBlocks) { "sparse chunks run past block $totalBlocks" }
        if (at < totalBlocks) count++
        return count
    }

    private fun blocksOf(chunk: SparseChunk, blockSize: Int): Long = when (chunk) {
        is SparseChunk.Fill -> chunk.blocks
        is SparseChunk.Raw -> {
            val rawBytes = chunk.parts.sumOf { it.size.toLong() }
            (rawBytes + blockSize - 1) / blockSize
        }
    }

    private fun fileHeader(blockSize: Int, totalBlocks: Long, count: Int): ByteArray =
        ByteBuffer.allocate(FastbootSparse.HEADER_LEN).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(FastbootSparse.MAGIC)
            putShort(MAJOR.toShort())
            putShort(0)
            putShort(FastbootSparse.HEADER_LEN.toShort())
            putShort(FastbootSparse.CHUNK_HEADER_LEN.toShort())
            putInt(blockSize)
            putInt(totalBlocks.toInt())
            putInt(count)
            putInt(0)
        }.array()

    private fun fillWord(value: Int): ByteArray =
        ByteBuffer.allocate(FILL_BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun header(type: Int, blocks: Long, payload: Int): ByteArray =
        ByteBuffer.allocate(FastbootSparse.CHUNK_HEADER_LEN).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(type.toShort())
            putShort(0)
            putInt(blocks.toInt())
            putInt(FastbootSparse.CHUNK_HEADER_LEN + payload)
        }.array()

    private const val FILL_BYTES = 4
    private const val MAJOR = 1
    private const val UINT = 0xFFFF_FFFFL
}
