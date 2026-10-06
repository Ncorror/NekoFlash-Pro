package io.github.ncorror.nekoflash.fastboot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `fastboot-info.txt` — как `ParseFastbootInfo`. */
class FastbootInfoFileTest {
    @Test
    fun aMalformedStreamStopsWithoutReadingTheRemainingFile() {
        val lines = sequence {
            yield("version 1")
            yield("flash a b c")
            error("parser continued past an invalid command")
        }

        assertTrue(FastbootInfoFile.parse(lines, "", "") is FastbootInfoRead.Invalid)
    }

    @Test
    fun manyCommentLinesDoNotBecomeTasks() {
        val lines = sequence {
            repeat(100_000) { yield("# image bundle comment") }
            yield("flash boot")
        }
        val parsed = FastbootInfoFile.parse(lines, "a", "b") as FastbootInfoRead.Tasks

        assertEquals(listOf(FlashAllTask.Flash("boot", "boot.img", "a", false)), parsed.tasks)
    }

    @Test
    fun linesBecomeTasks() {
        val read = FastbootInfoFile.parse(
            "version 1\nflash --slot-other system system_other.img\nflash --apply-vbmeta vbmeta\nupdate-super\nreboot bootloader",
            slot = "",
            secondary = "b",
        ) as FastbootInfoRead.Tasks

        assertEquals(
            listOf(
                FlashAllTask.Flash("system", "system_other.img", "b", false),
                FlashAllTask.Flash("vbmeta", "vbmeta.img", "", true),
                FlashAllTask.UpdateSuper,
                FlashAllTask.Reboot("bootloader"),
            ),
            read.tasks,
        )
    }

    @Test
    fun unknownLinesRejectTheFile() {
        val read = FastbootInfoFile.parse("flash a b c", "", "") as FastbootInfoRead.Invalid
        assertTrue(read.reason.contains("unknown command parsing fastboot-info.txt line: flash a b c"))
        assertTrue(FastbootInfoFile.parse("update-super extra", "", "") is FastbootInfoRead.Invalid)
    }
}
