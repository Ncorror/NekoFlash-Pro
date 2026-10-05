package io.github.ncorror.nekoflash.protocol.fastboot

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `super_empty.img` — как его пишет и читает `liblp`. */
class FastbootSuperMetadataTest {
    @Test
    fun largeImageTailIsNotReadAndShortReadsWork() {
        val image = superEmpty()
        var at = 0
        val stream = object : InputStream() {
            override fun read(): Int = error("unexpected read of the image tail")
            override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
                check(at < image.size) { "metadata parser read the multi-gigabyte image tail" }
                check(count <= 16 * 1024)
                val take = minOf(count, 7, image.size - at)
                image.copyInto(bytes, offset, at, at + take)
                at += take
                return take
            }
        }

        assertEquals(FastbootSuperMetadata.fromImageBlob(image), FastbootSuperMetadata.fromImageStream(stream))
        assertEquals(image.size, at)
    }

    @Test
    fun zeroProgressAndTruncatedTablesAreHandled() {
        val image = superEmpty()
        var at = 0
        val stream = object : InputStream() {
            override fun read(): Int = if (at < image.size) image[at++].toInt() and 0xFF else -1
            override fun read(bytes: ByteArray, offset: Int, count: Int): Int = 0
        }
        assertEquals(FastbootSuperMetadata.fromImageBlob(image), FastbootSuperMetadata.fromImageStream(stream))
        val truncated = image.copyOf(image.size - 1)
        assertEquals(
            LpMetadataRead.Invalid("read ${image.size - 4096 - 128} bytes failed"),
            FastbootSuperMetadata.fromImageStream(truncated.inputStream()),
        )
    }

    @Test
    fun tablesCanBeReordered() {
        val original = superEmpty()
        val tables = original.copyOfRange(4224, original.size)
        val reordered = tables.copyOfRange(252, 316) + tables.copyOfRange(156, 252) + tables.copyOfRange(0, 156)
        val header = original.copyOfRange(4096, 4224)
        val out = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(80, 160)
        out.putInt(92, 316)
        out.putInt(104, 64)
        out.putInt(116, 0)
        sha256(reordered).copyInto(header, 48)
        header.fill(0, 12, 44)
        sha256(header).copyInto(header, 12)
        val image = original.copyOf(4096) + header + reordered

        assertEquals(FastbootSuperMetadata.fromImageBlob(original), FastbootSuperMetadata.fromImageBlob(image))
    }

    @Test
    fun partitionsGroupsAndTheSuperDeviceAreRead() {
        val parsed = FastbootSuperMetadata.fromImageBlob(superEmpty()) as LpMetadataRead.Parsed
        val metadata = parsed.metadata

        assertEquals(listOf("system", "vendor", "product"), metadata.partitions.map { it.name })
        assertEquals(listOf("default", "main"), metadata.groups.map { it.name })
        assertEquals("super", metadata.blockDevices.single().partitionName)
        assertEquals(LpGeometry(65536, 2, 4096), metadata.geometry)
        assertEquals(10, metadata.majorVersion)
    }

    @Test
    fun userspacePartitionsFollowShouldFlashInUserspace() {
        val metadata = (FastbootSuperMetadata.fromImageBlob(superEmpty()) as LpMetadataRead.Parsed).metadata

        assertTrue(metadata.flashesInUserspace("system"))
        assertTrue(metadata.flashesInUserspace("vendor_a"))
        assertTrue(metadata.flashesInUserspace("vendor_b"))
        assertFalse(metadata.flashesInUserspace("vendor"))
        assertFalse(metadata.flashesInUserspace("boot"))
        assertFalse(metadata.flashesInUserspace("system_a"))
    }

    @Test
    fun damagedBytesAreNamedInLiblpWords() {
        val geometry = superEmpty().also { it[CHECKSUMMED_GEOMETRY_BYTE] = 1 }
        assertEquals(
            LpMetadataRead.Invalid("Logical partition metadata has invalid geometry checksum."),
            FastbootSuperMetadata.fromImageBlob(geometry),
        )
        val tables = superEmpty().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertEquals(
            LpMetadataRead.Invalid("Logical partition metadata has invalid table checksum."),
            FastbootSuperMetadata.fromImageBlob(tables),
        )
        assertEquals(
            LpMetadataRead.Invalid("100 is smaller than geometry header"),
            FastbootSuperMetadata.fromImageBlob(ByteArray(100)),
        )
    }

    private companion object {
        const val CHECKSUMMED_GEOMETRY_BYTE = 44

        /** Как `SerializeGeometry` + `SerializeMetadata`: геометрия, заголовок 1.0 и таблицы. */
        fun superEmpty(): ByteArray {
            val partitions = listOf(
                partition("system", attributes = 1, group = 1),
                partition("vendor", attributes = 1 or 2, group = 1),
                partition("product", attributes = 0, group = 1),
            )
            val groups = listOf(group("default"), group("main"))
            val device = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN).apply {
                putLong(2048)
                putInt(1048576)
                putInt(0)
                putLong(4L shl 30)
                put(name("super"))
                putInt(0)
            }.array()
            val tables = partitions.reduce(ByteArray::plus) + groups.reduce(ByteArray::plus) + device
            val header = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(0x414C5030)
                putShort(10)
                putShort(0)
                putInt(128)
                put(ByteArray(32))
                putInt(tables.size)
                put(sha256(tables))
                table(0, partitions.size, 52)
                table(partitions.size * 52, 0, 24)
                table(partitions.size * 52, groups.size, 48)
                table(partitions.size * 52 + groups.size * 48, 1, 64)
            }.array()
            sha256(header).copyInto(header, 12)
            val geometry = ByteBuffer.allocate(52).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(0x616c4467)
                putInt(52)
                put(ByteArray(32))
                putInt(65536)
                putInt(2)
                putInt(4096)
            }.array()
            sha256(geometry).copyInto(geometry, 8)
            return geometry.copyOf(4096) + header + tables
        }

        fun ByteBuffer.table(offset: Int, count: Int, size: Int) {
            putInt(offset)
            putInt(count)
            putInt(size)
        }

        fun partition(name: String, attributes: Int, group: Int): ByteArray =
            ByteBuffer.allocate(52).order(ByteOrder.LITTLE_ENDIAN).apply {
                put(name(name))
                putInt(attributes)
                putInt(0)
                putInt(0)
                putInt(group)
            }.array()

        fun group(name: String): ByteArray = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(name(name))
            putInt(0)
            putLong(0)
        }.array()

        fun name(value: String): ByteArray = value.toByteArray(Charsets.US_ASCII).copyOf(36)

        fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}
