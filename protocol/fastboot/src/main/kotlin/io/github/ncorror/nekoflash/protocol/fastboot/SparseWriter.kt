package io.github.ncorror.nekoflash.protocol.fastboot

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Участок образа, который собирается на хосте: с блока [block]. */
internal sealed interface SparseChunk {
    val block: Long

    /** [blocks] блоков, заполненных 32-битным словом [value]. */
    data class Fill(override val block: Long, val blocks: Long, val value: Int = 0) : SparseChunk

    /** Данные, дополненные нулями до целого блока. */
    class Raw(override val block: Long, val bytes: ByteArray) : SparseChunk
}

/**
 * `sparse_file_write` из AOSP `libsparse` без сжатия и суммы: участки по
 * возрастанию блока, промежутки между ними и хвост до [totalBlocks] —
 * `DONT_CARE`. Так пишут образы `mke2fs -E android_sparse` и `ImageBuilder`.
 */
internal object SparseWriter {
    fun write(blockSize: Int, totalBlocks: Long, chunks: List<SparseChunk>): ByteArray {
        require(totalBlocks in 0..UINT) { "Block device is too large to encode with libsparse." }
        val body = ByteArrayOutputStream()
        var count = 0
        var at = 0L
        for (chunk in chunks) {
            require(chunk.block >= at) { "sparse chunks overlap at block ${chunk.block}" }
            if (chunk.block > at) {
                body.write(header(FastbootSparse.CHUNK_DONT_CARE, chunk.block - at, 0))
                count++
            }
            at = chunk.block + when (chunk) {
                is SparseChunk.Fill -> {
                    body.write(header(FastbootSparse.CHUNK_FILL, chunk.blocks, FILL_BYTES))
                    body.write(ByteBuffer.allocate(FILL_BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(chunk.value).array())
                    chunk.blocks
                }
                is SparseChunk.Raw -> {
                    val blocks = (chunk.bytes.size + blockSize - 1) / blockSize
                    body.write(header(FastbootSparse.CHUNK_RAW, blocks.toLong(), blocks * blockSize))
                    body.write(chunk.bytes)
                    body.write(ByteArray(blocks * blockSize - chunk.bytes.size))
                    blocks.toLong()
                }
            }
            count++
        }
        require(at <= totalBlocks) { "sparse chunks run past block $totalBlocks" }
        if (at < totalBlocks) {
            body.write(header(FastbootSparse.CHUNK_DONT_CARE, totalBlocks - at, 0))
            count++
        }
        val file = ByteBuffer.allocate(FastbootSparse.HEADER_LEN).order(ByteOrder.LITTLE_ENDIAN).apply {
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
        return file + body.toByteArray()
    }

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
