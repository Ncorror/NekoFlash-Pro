package io.github.ncorror.nekoflash.artifact

import io.github.ncorror.nekoflash.core.artifact.ArtifactAccess
import io.github.ncorror.nekoflash.core.artifact.ArtifactIdentity
import io.github.ncorror.nekoflash.core.artifact.ArtifactRandomAccess
import io.github.ncorror.nekoflash.core.artifact.ArtifactSource
import io.github.ncorror.nekoflash.core.artifact.ArtifactStamp
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Архив `update` читается по центральному каталогу, данные — с места. */
class ZipArchiveTest {
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

        assertArrayEquals(info, zip.bytes("android-info.txt"))
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
