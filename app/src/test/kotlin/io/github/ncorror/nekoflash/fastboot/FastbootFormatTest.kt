package io.github.ncorror.nekoflash.fastboot

import com.ncorror.nekoflash.pro.engine.CURRENT_SLOT
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootExt4
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootLane
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FastbootFormatTest {
    private val options = FormatOptions(random = { size -> ByteArray(size) { 7 } }, now = { NOW })

    /** Тот же образ, что соберёт генератор для этого раздела. */
    private fun expected(bytes: Long, erase: Long = 0, logical: Long = 0) = FastbootExt4.image(
        FastbootExt4.Request(bytes, erase, logical, false, false, ByteArray(16) { 7 }, ByteArray(16) { 7 }, NOW),
    ).getOrThrow()

    @Test
    fun formatAsksTheDeviceBuildsExt4AndFlashesIt() {
        val device = ScriptedBootloader(
            mapOf(
                "partition-type:userdata" to "ext4",
                // `fb_fix_numeric_var`: без `0x` значение всё равно шестнадцатеричное.
                "partition-size:userdata" to "1000000",
                "erase-block-size" to "0x80000",
                "logical-block-size" to "0x1000",
            ),
        )

        val planned = formatPartition(FastbootLane(device), "userdata", null, null, CURRENT_SLOT, options)
            as FastbootConsoleState.Planned

        assertNull(planned.detail, planned.stoppedAt)
        assertTrue(device.sent.last() == "flash:userdata")
        assertArrayEquals(expected(0x1000000, erase = 0x80000, logical = 0x1000), device.downloads.single())
    }

    @Test
    fun typeAndSizeFromTheCommandReplaceWhatTheDeviceSaysWithAWarning() {
        val device = ScriptedBootloader(mapOf("partition-type:cache" to "raw", "partition-size:cache" to "0x4000000"))

        val planned = formatPartition(FastbootLane(device), "cache", "ext4", "0x1000000", CURRENT_SLOT, options)
            as FastbootConsoleState.Planned

        assertNull(planned.stoppedAt)
        assertTrue(planned.detail.contains("Warning: cache type is raw, but ext4 was requested for formatting."))
        assertTrue(planned.detail.contains("Warning: cache size is 0x4000000, but 0x1000000 was requested for formatting."))
        assertArrayEquals(expected(0x1000000), device.downloads.single())
    }

    @Test
    fun formatStopsBeforeSendingWhenTheFileSystemCannotBeBuiltHere() {
        val f2fs = ScriptedBootloader(mapOf("partition-type:userdata" to "f2fs", "partition-size:userdata" to "0x1000000"))
        val planned = formatPartition(FastbootLane(f2fs), "userdata", null, null, CURRENT_SLOT, options)
            as FastbootConsoleState.Planned
        assertEquals("Creating f2fs on the host is not implemented here yet.", planned.detail)
        assertTrue(f2fs.downloads.isEmpty())

        val unknown = ScriptedBootloader(emptyMap())
        val failed = formatPartition(FastbootLane(unknown), "userdata", null, null, CURRENT_SLOT, options)
            as FastbootConsoleState.Planned
        assertEquals("FAILED (unknown)\nCommand failed", failed.detail)
    }

    @Test
    fun wipeErasesAndFormatsWhatTheDeviceKnowsThenReboots() {
        val device = ScriptedBootloader(
            mapOf(
                "partition-type:userdata" to "ext4",
                "partition-size:userdata" to "0x1000000",
                "partition-type:metadata" to "",
            ),
        )

        val planned = FastbootFormat(FastbootLane(device), options).wipe(WIPE_PARTITIONS, then = listOf("reboot"))

        assertNull(planned.detail, planned.stoppedAt)
        assertEquals(listOf("erase:userdata", "flash:userdata", "reboot"), device.sent.filter { !it.startsWith("getvar:") && !it.startsWith("download:") })
        assertTrue(planned.detail.contains("wipe task partition not found: cache"))
        assertArrayEquals(expected(0x1000000), device.downloads.single())
    }

    @Test
    fun wipeGoesOnPastAFileSystemItCannotBuild() {
        val device = ScriptedBootloader(
            mapOf(
                "partition-type:userdata" to "f2fs",
                "partition-size:userdata" to "0x1000000",
                "partition-type:cache" to "ext4",
                "partition-size:cache" to "0x1000000",
                "partition-type:metadata" to "ext4",
                "snapshot-update-status" to "snapshotted",
            ),
            refused = setOf("erase:metadata"),
        )

        val planned = FastbootFormat(FastbootLane(device), options).wipe(WIPE_PARTITIONS, force = true)

        assertNull(planned.detail, planned.stoppedAt)
        assertEquals(
            listOf("snapshot-update:cancel", "erase:userdata", "erase:cache", "flash:cache", "erase:metadata"),
            device.sent.filter { !it.startsWith("getvar:") && !it.startsWith("download:") },
        )
        assertTrue(planned.detail.contains("File system type f2fs not supported."))
    }

    private companion object {
        const val NOW = 1700000000L
    }
}
