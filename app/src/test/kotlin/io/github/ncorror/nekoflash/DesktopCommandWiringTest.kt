package io.github.ncorror.nekoflash

import io.github.ncorror.nekoflash.adb.AdbInstallState
import io.github.ncorror.nekoflash.adb.AdbSideloadState
import io.github.ncorror.nekoflash.adb.AdbTransferReport
import io.github.ncorror.nekoflash.adb.AdbTransferState
import io.github.ncorror.nekoflash.command.DesktopCommandStart
import io.github.ncorror.nekoflash.protocol.adb.AdbSideloadOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopCommandWiringTest {
    @Test
    fun filePickerFailuresAreVisibleInHostTerminal() {
        assertEquals(
            "error: required file or folder was not selected",
            desktopStartTerminalText(DesktopCommandStart.FileMissing),
        )
        assertEquals(
            "error: command expects 3 files, selected 2",
            desktopStartTerminalText(DesktopCommandStart.FileCount(expected = 3, picked = 2)),
        )
    }

    @Test
    fun installReturnsThePackageManagerAnswer() {
        assertEquals(
            "Success",
            adbInstallTerminalText(
                AdbInstallState.Finished(success = true, answer = "Success", files = 1, bytes = 1234),
            ),
        )
    }

    @Test
    fun transferKeepsWarningsAndSummary() {
        val text = adbTransferTerminalText(
            AdbTransferState.Finished(
                what = "push → /sdcard/Download",
                report = AdbTransferReport(
                    files = 2,
                    bytes = 4096,
                    skipped = 1,
                    messages = listOf("warning: unchanged file skipped"),
                    success = true,
                ),
            ),
        ).orEmpty()

        assertTrue(text.contains("warning: unchanged file skipped"))
        assertTrue(text.contains("push → /sdcard/Download: complete"))
        assertTrue(text.contains("files=2 bytes=4096 skipped=1"))
    }

    @Test
    fun sideloadTransferNeverClaimsInstallationSuccess() {
        assertEquals(
            "sideload: transfer complete; Recovery verification required",
            adbSideloadTerminalText(
                AdbSideloadState.Finished(
                    outcome = AdbSideloadOutcome.TransferComplete,
                    verificationPending = true,
                ),
            ),
        )
    }

    @Test
    fun sideloadInterruptionReportsUniqueCoverage() {
        val text = adbSideloadTerminalText(
            AdbSideloadState.Finished(
                outcome = AdbSideloadOutcome.InterruptedAfterPayload(
                    servedBytes = 75,
                    uniqueBytes = 50,
                    totalBytes = 100,
                    detail = "transport closed",
                ),
                verificationPending = true,
            ),
        ).orEmpty()

        assertTrue(text.contains("50% unique coverage"))
        assertTrue(text.contains("transport closed"))
        assertTrue(text.contains("Recovery verification required"))
    }
}
