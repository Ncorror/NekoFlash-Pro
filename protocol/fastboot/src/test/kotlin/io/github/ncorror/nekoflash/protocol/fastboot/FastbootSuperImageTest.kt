package io.github.ncorror.nekoflash.protocol.fastboot

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Пустой `super` — как `ImageBuilder` при `wipe-super`. */
class FastbootSuperImageTest {
    private val metadata = LpMetadata(
        geometry = LpGeometry(metadataMaxSize = 65536, metadataSlotCount = 2, logicalBlockSize = 4096),
        majorVersion = 10,
        minorVersion = 2,
        headerFlags = 1,
        partitions = listOf(LpPartition("system", 2, 0, 1, 1)),
        extents = listOf(LpExtent(sectors = 2048, targetType = 0, targetData = 2048, targetSource = 0)),
        groups = listOf(LpGroup("default", 0, 0), LpGroup("main", 1, 1L shl 30)),
        blockDevices = listOf(LpBlockDevice(2048, 1048576, 0, 64L shl 20, "super", 0)),
    )

    @Test
    fun serializedMetadataReadsBackUnchanged() {
        val blob = FastbootSuperImage.geometry(metadata.geometry) + FastbootSuperImage.metadata(metadata)

        assertEquals(LpMetadataRead.Parsed(metadata), FastbootSuperMetadata.fromImageBlob(blob))
    }

    @Test
    fun theEmptySuperHoldsZeroesGeometryTwiceAndEveryMetadataCopy() {
        val image = FastbootSuperImage.empty(metadata).getOrThrow().single()
        val expanded = expand(image.sparse)

        assertEquals("super", image.partition)
        assertEquals(64L shl 20, expanded.size.toLong())
        assertTrue(expanded.copyOfRange(0, 4096).all { it == 0.toByte() })
        val geometry = expanded.copyOfRange(4096, 8192)
        assertArrayEquals(geometry, expanded.copyOfRange(8192, 12288))
        val serialized = FastbootSuperImage.metadata(metadata)
        for (copy in 0 until 4) {
            val at = 12288 + copy * 65536
            assertArrayEquals(serialized, expanded.copyOfRange(at, at + serialized.size))
        }
        // На устройстве за геометрией — её копия, метаданные начинаются через 8192 байта.
        val primary = geometry + expanded.copyOfRange(12288, 12288 + 65536)
        assertEquals(LpMetadataRead.Parsed(metadata), FastbootSuperMetadata.fromImageBlob(primary))
    }

    /** Разворачивает sparse: RAW, FILL нулём и пропуск. */
    private fun expand(sparse: ByteArray): ByteArray {
        val buffer = ByteBuffer.wrap(sparse).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(FastbootSparse.MAGIC, buffer.getInt(0))
        val blockSize = buffer.getInt(12)
        val out = ByteArray(buffer.getInt(16) * blockSize)
        buffer.position(28)
        var block = 0
        repeat(buffer.getInt(20)) {
            val type = buffer.getShort().toInt() and 0xFFFF
            buffer.getShort()
            val count = buffer.getInt()
            val total = buffer.getInt()
            if (type == FastbootSparse.CHUNK_RAW) buffer.get(out, block * blockSize, total - 12)
            if (type == FastbootSparse.CHUNK_FILL) buffer.getInt()
            block += count
        }
        return out
    }
}
