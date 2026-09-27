package io.github.ncorror.nekoflash.command

import com.ncorror.nekoflash.pro.engine.TerminalPlan
import com.ncorror.nekoflash.pro.engine.TerminalPlanCompiler
import com.ncorror.nekoflash.pro.engine.TerminalPlanResult
import io.github.ncorror.nekoflash.core.artifact.ArtifactSink
import io.github.ncorror.nekoflash.core.artifact.ArtifactSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * План доходит до тех же входов движков, что и кнопки.
 *
 * Проверяется сопоставление, а не протокол: протокол закрыт тестами своих
 * модулей. Здесь важно, что `fastboot flash` — одна операция загрузки с
 * `flash:` следом, а неизвестная строка не становится другой командой.
 */
class DesktopCommandExecutorTest {
    private val calls = mutableListOf<String>()
    private val executor = DesktopCommandExecutor(RecordingTargets(calls))
    private val compiler = TerminalPlanCompiler()

    private fun plan(line: String): TerminalPlan = (compiler.compile(line) as TerminalPlanResult.Ready).plan

    private val picked = PickedFile.Open { error("not opened in this test") }
    private val saved = PickedFile.Create { error("not created in this test") }

    @Test
    fun flashIsOneDownloadWithTheCommandsAfterIt() {
        val start = executor.execute(plan("fastboot --set-active=b flash boot boot.img"), picked)

        assertEquals(DesktopCommandStart.Started(DesktopCommandArea.FASTBOOT), start)
        assertEquals(listOf("downloadThen flash:boot,set_active:b"), calls)
    }

    @Test
    fun aPlanThatNeedsAFileSendsNothingWithoutIt() {
        assertEquals(DesktopCommandStart.FileMissing, executor.execute(plan("fastboot flash boot boot.img"), null))
        assertEquals(DesktopCommandStart.FileMissing, executor.execute(plan("adb pull /sdcard/a"), picked))
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun fastbootStepsGoToTheirEntries() {
        executor.execute(plan("fastboot getvar product"), null)
        executor.execute(plan("fastboot getvar all"), null)
        executor.execute(plan("fastboot oem unlock"), null)
        executor.execute(plan("fastboot boot boot.img"), picked)
        executor.execute(plan("fastboot fetch vendor_boot out.img"), saved)

        assertEquals(
            listOf("getvar product", "getvar all", "command oem unlock", "downloadThen boot", "fetch vendor_boot"),
            calls,
        )
    }

    @Test
    fun adbStepsGoToTheirEntries() {
        val areas = listOf(
            executor.execute(plan("adb shell id"), null),
            executor.execute(plan("adb shell"), null),
            executor.execute(plan("adb root"), null),
            executor.execute(plan("adb reboot bootloader"), null),
            executor.execute(plan("adb pull /sdcard/a.txt"), saved),
            executor.execute(plan("adb push a.txt /sdcard/"), picked),
            executor.execute(plan("adb sideload ota.zip"), picked),
            executor.execute(plan("adb logcat -s Tag"), null),
        ).map { (it as DesktopCommandStart.Started).area }

        assertEquals(
            listOf(
                "shell id", "interactive", "service root:", "reboot bootloader",
                "pull /sdcard/a.txt", "push /sdcard/a.txt", "sideload", "interactive logcat -s Tag",
            ),
            calls,
        )
        assertEquals(
            listOf(
                DesktopCommandArea.ADB_SHELL, DesktopCommandArea.ADB_TERMINAL, DesktopCommandArea.ADB_SERVICE,
                DesktopCommandArea.ADB_REBOOT, DesktopCommandArea.ADB_FILES, DesktopCommandArea.ADB_FILES,
                DesktopCommandArea.ADB_SIDELOAD, DesktopCommandArea.ADB_TERMINAL,
            ),
            areas,
        )
    }

    @Test
    fun installBindsEveryNamedFileToOnePick() {
        val two = PickedFile.OpenMany(listOf({ error("x") }, { error("y") }))
        assertEquals(
            DesktopCommandStart.FileCount(expected = 3, picked = 2),
            executor.execute(plan("adb install-multiple a.apk b.apk c.apk"), two),
        )
        executor.execute(plan("adb install-multiple -rg base.apk split.apk"), two)
        executor.execute(plan("adb install -r app.apk"), picked)

        assertEquals(listOf("install 2 -r,-g true", "install 1 -r false"), calls)
    }

    @Test
    fun slotTravelsWithTheCommandsItNames() {
        executor.execute(plan("fastboot --slot other flash boot boot.img"), picked)
        executor.execute(plan("fastboot --slot all erase cache"), null)

        assertEquals(listOf("downloadThen flash:boot slot=other", "plan erase:cache slot=all"), calls)
    }

    private class RecordingTargets(private val calls: MutableList<String>) : DesktopCommandTargets {
        override fun fastbootCommand(command: String) { calls += "command $command" }
        override fun fastbootPlan(commands: List<String>, slot: String?) {
            calls += "plan ${commands.joinToString(",")}" + (slot?.let { " slot=$it" } ?: "")
        }
        override fun fastbootVariable(name: String) { calls += "getvar $name" }
        override fun fastbootAllVariables() { calls += "getvar all" }
        override fun fastbootDownloadThen(then: List<String>, slot: String?, origin: () -> ArtifactSource) {
            calls += "downloadThen ${then.joinToString(",")}" + (slot?.let { " slot=$it" } ?: "")
        }
        override fun fastbootFetch(partition: String, destination: () -> ArtifactSink) { calls += "fetch $partition" }
        override fun adbShell(command: String) { calls += "shell $command" }
        override fun adbInteractiveShell(firstLine: String?) { calls += "interactive" + (firstLine?.let { " $it" } ?: "") }
        override fun adbService(service: String) { calls += "service $service" }
        override fun adbReboot(target: String) { calls += "reboot $target" }
        override fun adbPull(remotePath: String, destination: () -> ArtifactSink) { calls += "pull $remotePath" }
        override fun adbPush(remotePath: String, origin: () -> ArtifactSource) { calls += "push $remotePath" }
        override fun adbSideload(origin: () -> ArtifactSource) { calls += "sideload" }
        override fun adbInstall(origins: List<() -> ArtifactSource>, options: List<String>, several: Boolean) {
            calls += "install ${origins.size} ${options.joinToString(",")} $several"
        }
    }
}
