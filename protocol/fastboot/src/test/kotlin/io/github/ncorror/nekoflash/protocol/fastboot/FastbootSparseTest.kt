package io.github.ncorror.nekoflash.protocol.fastboot

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sparse — как у AOSP `libsparse` и `fastboot`.
 *
 * Главная проверка — наложение: куски, записанные устройством поверх любого
 * содержимого, дают ровно исходный файл. Остальные закрепляют правила
 * `libsparse`, от которых зависит число и размер кусков.
 */
class FastbootSparseTest {
    private val block = FastbootSparse.RAW_BLOCK_SIZE

    @Test
    fun aRawImageBecomesDataAndFillExtentsMergedLikeLibsparse() {
        val image = rawImage(
            data(1), data(2), fill(0), fill(0), fill(0x11223344), data(3),
            tail = Random(9).nextBytes(100),
        )

        val read = FastbootSparse.read({ ByteArrayInputStream(image) }, image.size.toLong())

        assertEquals(
            listOf(
                SparseExtent.Data(0, 2L * block, 0),
                SparseExtent.Fill(2, 2L * block, 0),
                SparseExtent.Fill(4, 1L * block, 0x11223344),
                // Последний неполный блок — данные, а не заполнение.
                SparseExtent.Data(5, block + 100L, 5L * block),
            ),
            read.extents,
        )
        assertEquals(image.size.toLong(), read.length)
        assertEquals(7L, read.totalBlocks)
    }

    @Test
    fun piecesLaidOverAnyContentGiveTheOriginalFile() {
        val image = rawImage(
            *Array(12) { data(it) }, fill(0), fill(0), fill(7), *Array(9) { data(100 + it) }, fill(0),
            tail = ByteArray(0),
        )
        val read = FastbootSparse.read({ ByteArrayInputStream(image) }, image.size.toLong())

        for (max in listOf(20_000L, 33_333L, 70_000L, 1_000_000L)) {
            val pieces = FastbootSparse.resparse(read, max)
            val device = Random(max).nextBytes(image.size)
            val reader = SparseDataReader(ByteArrayInputStream(image))
            for (piece in pieces) {
                val bytes = piece.stream(reader).readBytes()
                assertEquals("declared length, max=$max", piece.sparseLength, bytes.size.toLong())
                assertTrue("piece within $max: ${bytes.size}", bytes.size <= max)
                applySparse(bytes, device)
            }
            assertArrayEquals("max=$max", image, device)
        }
    }

    @Test
    fun resparseSplitsAnExtentThatDoesNotFitAnEmptyPiece() {
        val image = SparseImage(block, 10L * block, listOf(SparseExtent.Data(0, 10L * block, 0)))

        val pieces = FastbootSparse.resparse(image, 20_000)

        assertEquals(
            listOf(
                listOf(SparseExtent.Data(0, 4L * block, 0)),
                listOf(SparseExtent.Data(4, 4L * block, 4L * block)),
                listOf(SparseExtent.Data(8, 2L * block, 8L * block)),
            ),
            pieces.map { it.extents },
        )
    }

    @Test
    fun aPieceCloseToFullIsClosedRatherThanSplitTheNextExtent() {
        val first = SparseExtent.Fill(0, block.toLong(), 7)
        val second = SparseExtent.Data(1, 4L * block, 0)
        val third = SparseExtent.Data(5, 4L * block, 100_000)
        val image = SparseImage(block, 9L * block, listOf(first, second, third))

        // Места остаётся меньше восьмой части: кусок закрывается.
        assertEquals(
            listOf(listOf(first, second), listOf(third)),
            FastbootSparse.resparse(image, 18_056).map { it.extents },
        )
        // Больше восьмой части: следующий участок делится.
        assertEquals(
            listOf(
                listOf(first, second, SparseExtent.Data(5, 3L * block, 100_000)),
                listOf(SparseExtent.Data(8, block.toLong(), 100_000L + 3 * block)),
            ),
            FastbootSparse.resparse(image, 30_056).map { it.extents },
        )
    }

    @Test
    fun aSparseFileIsReadByItsChunksAndReWrittenTheSame() {
        val original = rawImage(data(1), fill(0), fill(0), data(2), fill(5), tail = ByteArray(0))
        val asRaw = FastbootSparse.read({ ByteArrayInputStream(original) }, original.size.toLong())
        val sparse = asRaw.stream(SparseDataReader(ByteArrayInputStream(original))).readBytes()

        val read = FastbootSparse.read({ ByteArrayInputStream(sparse) }, sparse.size.toLong())

        assertEquals(asRaw.extents.map { it::class to it.block to it.length }, read.extents.map { it::class to it.block to it.length })
        assertEquals(original.size.toLong(), read.length)
        val again = read.stream(SparseDataReader(ByteArrayInputStream(sparse))).readBytes()
        assertArrayEquals(sparse, again)
    }

    @Test
    fun crcAndDontCareChunksAreAcceptedWithoutCheckingTheCrc() {
        val bytes = sparseFile(
            blocks = 3,
            chunks = listOf(
                chunk(FastbootSparse.CHUNK_DONT_CARE, 1, ByteArray(0)),
                chunk(FastbootSparse.CHUNK_RAW, 1, ByteArray(block) { 9 }),
                chunk(FastbootSparse.CHUNK_FILL, 1, le32(0x0A0B0C0D)),
                chunk(FastbootSparse.CHUNK_CRC32, 0, le32(12345)),
            ),
        )

        val read = FastbootSparse.read({ ByteArrayInputStream(bytes) }, bytes.size.toLong())

        assertEquals(
            listOf(
                SparseExtent.Data(1, block.toLong(), (28 + 12 + 12).toLong()),
                SparseExtent.Fill(2, block.toLong(), 0x0A0B0C0D),
            ),
            read.extents,
        )
    }

    @Test
    fun aBrokenSparseFileIsReadAsAnOrdinaryImageLikeAosp() {
        // Заголовок sparse, а записей меньше, чем обещано: разбор срывается.
        val bytes = sparseFile(blocks = 2, chunks = listOf(chunk(FastbootSparse.CHUNK_FILL, 1, le32(1))))

        val read = FastbootSparse.read({ ByteArrayInputStream(bytes) }, bytes.size.toLong())

        assertEquals(listOf(SparseExtent.Data(0, bytes.size.toLong(), 0)), read.extents)
    }

    @Test
    fun theLimitFollowsGetSparseLimit() {
        val mib = 1L shl 20
        assertEquals(0L, FastbootSparse.limit(100 * mib, requested = null, maxDownloadSize = 768 * mib))
        assertEquals(768 * mib, FastbootSparse.limit(900 * mib, requested = null, maxDownloadSize = 768 * mib))
        assertEquals(256 * mib, FastbootSparse.limit(300 * mib, requested = 256 * mib, maxDownloadSize = 768 * mib))
        // `-S 0` — как без `-S`; устройство предела не назвало — не режем.
        assertEquals(768 * mib, FastbootSparse.limit(900 * mib, requested = 0, maxDownloadSize = 768 * mib))
        assertEquals(0L, FastbootSparse.limit(900 * mib, requested = null, maxDownloadSize = null))
        // Куски не крупнее 1 ГиБ, даже если устройство примет больше.
        assertEquals(1L shl 30, FastbootSparse.limit(5L shl 30, requested = null, maxDownloadSize = 4L shl 30))
    }

    private fun data(seed: Int): ByteArray = Random(seed).nextBytes(block)

    private fun fill(value: Int): ByteArray = ByteArray(block).also { bytes ->
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().apply { while (hasRemaining()) put(value) }
    }

    private fun rawImage(vararg blocks: ByteArray, tail: ByteArray): ByteArray =
        blocks.fold(ByteArray(0)) { all, next -> all + next } + tail

    private fun le32(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun chunk(type: Int, blocks: Int, data: ByteArray): ByteArray =
        ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(type.toShort()).putShort(0).putInt(blocks).putInt(12 + data.size).array() + data

    private fun sparseFile(blocks: Int, chunks: List<ByteArray>): ByteArray =
        ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(FastbootSparse.MAGIC).putShort(1).putShort(0).putShort(28).putShort(12)
            .putInt(block).putInt(blocks).putInt(chunks.size).putInt(0).array() +
            chunks.fold(ByteArray(0)) { all, next -> all + next }

    /** Что сделало бы устройство: записи sparse поверх содержимого раздела. */
    private fun applySparse(sparse: ByteArray, device: ByteArray) {
        val buffer = ByteBuffer.wrap(sparse).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(FastbootSparse.MAGIC, buffer.getInt())
        buffer.position(12)
        val blockSize = buffer.getInt()
        val totalBlocks = buffer.getInt()
        val chunks = buffer.getInt()
        buffer.position(28)
        var at = 0
        repeat(chunks) {
            val type = buffer.getShort().toInt() and 0xFFFF
            buffer.getShort()
            val count = buffer.getInt()
            val total = buffer.getInt()
            val bytes = count * blockSize
            when (type) {
                FastbootSparse.CHUNK_RAW -> {
                    val data = ByteArray(total - 12).also { buffer.get(it) }
                    data.copyInto(device, at * blockSize, 0, minOf(bytes, device.size - at * blockSize))
                }
                FastbootSparse.CHUNK_FILL -> {
                    val word = le32(buffer.getInt())
                    for (i in 0 until bytes) if (at * blockSize + i < device.size) device[at * blockSize + i] = word[i % 4]
                }
                FastbootSparse.CHUNK_DONT_CARE -> Unit
                else -> error("unexpected chunk $type")
            }
            at += count
        }
        assertEquals("chunks cover the whole image", totalBlocks, at)
        assertEquals("no bytes after the last chunk", sparse.size, buffer.position())
    }
}
