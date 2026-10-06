package io.github.ncorror.nekoflash.artifact

import io.github.ncorror.nekoflash.core.artifact.ArtifactAccess
import io.github.ncorror.nekoflash.core.artifact.ArtifactIdentity
import io.github.ncorror.nekoflash.core.artifact.ArtifactRandomAccess
import io.github.ncorror.nekoflash.core.artifact.ArtifactSource
import io.github.ncorror.nekoflash.core.artifact.ArtifactStamp
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Архив `update` читается по центральному каталогу, данные — с места. */
class ZipArchiveTest {
    @Test
    fun centralDirectoryIsReadOneEntryAtATime() {
        val bytes = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                repeat(2500) { zip.putNextEntry(ZipEntry("image-$it.img")); zip.closeEntry() }
            }
        }.toByteArray()
        var largestRead = 0
        val source = object : ArtifactSource by Seekable(bytes) {
            override fun randomAccess(): ArtifactRandomAccess = ArtifactRandomAccess { at, count ->
                largestRead = maxOf(largestRead, count)
                check(count <= 65557) { "whole central directory was read" }
                bytes.copyOfRange(at.toInt(), at.toInt() + count)
            }
        }

        assertEquals(2500, ZipArchive(source).entries.size)
        assertTrue(largestRead <= 65557)
    }

    @Test
    fun zip64StoredImageLargerThanFourGiBStreamsWithLongOffsets() {
        val name = "huge.img".toByteArray()
        val imageSize = 5L shl 30
        val local = ByteBuffer.allocate(30 + name.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0, 0x04034b50)
            putShort(26, name.size.toShort())
            position(30)
            put(name)
        }.array()
        val at = local.size + imageSize
        val central = ByteBuffer.allocate(46 + name.size + 28).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0, 0x02014b50)
            putInt(20, -1)
            putInt(24, -1)
            putShort(28, name.size.toShort())
            putShort(30, 28)
            putInt(42, -1)
            position(46)
            put(name)
            putShort(1)
            putShort(24)
            putLong(imageSize)
            putLong(imageSize)
            putLong(0)
        }.array()
        val end = ByteBuffer.allocate(98).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0, 0x06064b50)
            putLong(4, 44)
            putLong(32, 1)
            putLong(40, central.size.toLong())
            putLong(48, at)
            putInt(56, 0x07064b50)
            putLong(64, at + central.size)
            putInt(76, 0x06054b50)
            putShort(86, -1)
            putInt(88, -1)
            putInt(92, -1)
        }.array()
        val size = at + central.size + end.size
        val regions = listOf(0L to local, at to central, at + central.size to end)
        val source = object : ArtifactSource {
            override val identity = ArtifactIdentity("large.zip", size, ArtifactAccess.SEEKABLE)
            override val openedStamp = ArtifactStamp(size)
            override fun stamp() = openedStamp
            override fun open(): InputStream = error("archive must not be read sequentially")
            override fun randomAccess(): ArtifactRandomAccess = ArtifactRandomAccess { offset, count ->
                check(count <= 65557)
                ByteArray(count) { 7 }.also { bytes ->
                    for ((start, data) in regions) {
                        val first = maxOf(offset, start)
                        val last = minOf(offset + count, start + data.size)
                        if (first < last) data.copyInto(bytes, (first - offset).toInt(),
                            (first - start).toInt(), (last - start).toInt())
                    }
                }
            }
        }

        val image = ZipArchive(source).source("huge.img")!!
        assertEquals(imageSize, image.identity.sizeBytes)
        image.open().use { input ->
            val bytes = ByteArray(70000)
            var read = 0
            while (read < bytes.size) read += input.read(bytes, read, bytes.size - read)
            assertTrue(bytes.all { it == 7.toByte() })
        }
    }

    private val system = Random(7).nextBytes(300_000)
    private val info = "require board=vayu\n".toByteArray()

    private fun archive(comment: String = ""): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            zip.setComment(comment)
            zip.putNextEntry(ZipEntry("android-info.txt"))
            zip.write(info)
            val stored = ZipEntry("system.img").apply {
                method = ZipEntry.STORED
                size = system.size.toLong()
                crc = CRC32().apply { update(system) }.value
            }
            zip.putNextEntry(stored)
            zip.write(system)
            zip.putNextEntry(ZipEntry("boot.img"))
            zip.write(system, 0, 70_000)
        }
    }.toByteArray()

    @Test
    fun storedAndDeflatedEntriesReadBack() {
        val zip = ZipArchive(Seekable(archive(comment = "a comment moves the end record")))

        assertArrayEquals(info, zip.source("android-info.txt")!!.open().use { it.readBytes() })
        assertArrayEquals(system, zip.source("system.img")!!.open().readBytes())
        assertEquals(70_000L, zip.source("boot.img")!!.identity.sizeBytes)
        assertArrayEquals(system.copyOf(70_000), zip.source("boot.img")!!.open().readBytes())
        assertNull(zip.source("vendor.img"))
    }

    @Test
    fun notAZipIsNamed() {
        assertThrows(IOException::class.java) { ZipArchive(Seekable(ByteArray(100))).entries }
    }

    private class Seekable(private val bytes: ByteArray) : ArtifactSource {
        override val identity = ArtifactIdentity("update.zip", bytes.size.toLong(), ArtifactAccess.SEEKABLE)
        override val openedStamp = ArtifactStamp(bytes.size.toLong(), null, null)
        override fun stamp(): ArtifactStamp = openedStamp
        override fun open(): InputStream = bytes.inputStream()
        override fun randomAccess(): ArtifactRandomAccess =
            ArtifactRandomAccess { offset, length -> bytes.copyOfRange(offset.toInt(), offset.toInt() + length) }
    }
}
