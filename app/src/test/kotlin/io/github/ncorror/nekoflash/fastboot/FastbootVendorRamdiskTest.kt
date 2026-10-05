package io.github.ncorror.nekoflash.fastboot

import com.ncorror.nekoflash.pro.engine.CURRENT_SLOT
import io.github.ncorror.nekoflash.core.artifact.ArtifactAccess
import io.github.ncorror.nekoflash.core.artifact.ArtifactIdentity
import io.github.ncorror.nekoflash.core.artifact.ArtifactRandomAccess
import io.github.ncorror.nekoflash.core.artifact.ArtifactSource
import io.github.ncorror.nekoflash.core.artifact.ArtifactStamp
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootLane
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `flash vendor_boot:ИМЯ` — `repack_ramdisk`: прочитать раздел, заменить рамдиск, прошить обратно. */
class FastbootVendorRamdiskTest {
    @Test
    fun unknownLengthRamdiskStreamsThroughZeroProgressAndIsCleanedUp() {
        val device = ScriptedBootloader(
            variables = mapOf(
                "max-fetch-size" to "0x1000", "partition-size:vendor_boot" to "0x6000",
                "max-download-size" to "0x100000",
            ),
            partitions = mapOf("vendor_boot" to vendorBoot()),
        )
        val bytes = ByteArray(20_000) { 7 }
        val source = StreamingRamdisk(bytes)

        val planned = flashVendorRamdisk(FastbootLane(device), source, "default", CURRENT_SLOT, null, staging)
            as FastbootConsoleState.Planned

        assertEquals(null, planned.stoppedAt)
        assertEquals(1, source.opens)
        assertTrue(source.closed)
        assertArrayEquals(bytes, device.downloads.single().copyOfRange(page, page + bytes.size))
        assertTrue(staging.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun failedCopyNeverReachesUsbAndRemovesThePartialFile() {
        val device = ScriptedBootloader(emptyMap())
        val source = StreamingRamdisk(ByteArray(20_000), fails = true)

        val planned = flashVendorRamdisk(FastbootLane(device), source, "default", CURRENT_SLOT, null, staging)
            as FastbootConsoleState.Planned

        assertTrue(planned.detail.contains("ramdisk read failed"))
        assertTrue(device.sent.isEmpty())
        assertTrue(source.closed)
        assertTrue(staging.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun sparseAndEmptyRamdisksStopBeforeUsbAndLeaveNoFiles() {
        for (bytes in listOf(ByteArray(0), byteArrayOf(0x3A, 0xFF.toByte(), 0x26, 0xED.toByte()))) {
            val device = ScriptedBootloader(emptyMap())
            val planned = flashVendorRamdisk(FastbootLane(device), source(bytes), "default", CURRENT_SLOT, null, staging)
                as FastbootConsoleState.Planned
            assertEquals(0, planned.stoppedAt)
            assertTrue(device.sent.isEmpty())
            assertTrue(staging.listFiles().orEmpty().isEmpty())
        }
    }

    private class StreamingRamdisk(private val bytes: ByteArray, private val fails: Boolean = false) : ArtifactSource {
        var opens = 0
        var closed = false
        override val identity = ArtifactIdentity("ramdisk.img", null, ArtifactAccess.NON_SEEKABLE)
        override val openedStamp = ArtifactStamp(null)
        override fun stamp() = openedStamp
        override fun randomAccess(): ArtifactRandomAccess? = null
        override fun open(): InputStream {
            opens++
            return object : InputStream() {
                private var at = 0
                private var stalled = false
                override fun read(): Int = if (at < bytes.size) bytes[at++].toInt() and 0xFF else -1
                override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
                    check(count <= 16 * 1024)
                    if (fails && at > 0) throw IOException("ramdisk read failed")
                    if (at >= bytes.size) return -1
                    stalled = !stalled
                    if (stalled) return 0
                    val take = minOf(count, bytes.size - at)
                    bytes.copyInto(buffer, offset, at, at + take)
                    at += take
                    return take
                }
                override fun close() { closed = true }
            }
        }
    }

    private val page = 4096
    private val staging = Files.createTempDirectory("staging").toFile()

    private fun vendorBoot(): ByteArray {
        val bytes = ByteArray(6 * page)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("VNDRBOOT".toByteArray())
            putInt(8, 3)
            putInt(12, page)
            putInt(24, 100)
            putInt(2100, 0)
        }
        ByteArray(100) { 1 }.copyInto(bytes, page)
        return bytes
    }

    private fun source(bytes: ByteArray) = object : ArtifactSource {
        override val identity = ArtifactIdentity("ramdisk.img", bytes.size.toLong(), ArtifactAccess.SEEKABLE)
        override val openedStamp = ArtifactStamp(bytes.size.toLong())
        override fun stamp() = openedStamp
        override fun open() = bytes.inputStream()
        override fun randomAccess(): ArtifactRandomAccess? = null
    }

    @Test
    fun thePartitionIsFetchedRepackedAndFlashedBack() {
        val device = ScriptedBootloader(
            variables = mapOf(
                "max-fetch-size" to "0x1000",
                "partition-size:vendor_boot" to "0x6000",
                "max-download-size" to "0x100000",
            ),
            partitions = mapOf("vendor_boot" to vendorBoot()),
        )
        val ramdisk = ByteArray(200) { 7 }

        val planned = flashVendorRamdisk(FastbootLane(device), source(ramdisk), "default", CURRENT_SLOT, null, staging)
            as FastbootConsoleState.Planned

        assertEquals(null, planned.stoppedAt)
        assertEquals("flash:vendor_boot", device.sent.last())
        val flashed = device.downloads.single()
        assertEquals(6 * page, flashed.size)
        assertEquals(200, ByteBuffer.wrap(flashed).order(ByteOrder.LITTLE_ENDIAN).getInt(24))
        assertArrayEquals(ramdisk, flashed.copyOfRange(page, page + 200))
        assertTrue(staging.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun withoutFetchTheDesktopClientsWordsStopIt() {
        val device = ScriptedBootloader(emptyMap())

        val planned = flashVendorRamdisk(FastbootLane(device), source(ByteArray(5)), "default", CURRENT_SLOT, null, staging)
            as FastbootConsoleState.Planned

        assertEquals("Unable to get max-fetch-size. Device does not support fetch command.", planned.detail)
        assertTrue(device.sent.none { it.startsWith("fetch:") || it.startsWith("flash:") })
    }
}
