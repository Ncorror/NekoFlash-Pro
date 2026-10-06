package io.github.ncorror.nekoflash.command

import com.ncorror.nekoflash.pro.engine.LocalReportKind
import com.ncorror.nekoflash.pro.engine.ShellMode
import com.ncorror.nekoflash.pro.engine.TerminalParser
import com.ncorror.nekoflash.pro.engine.TerminalPlan
import com.ncorror.nekoflash.pro.engine.TerminalPlanCompiler
import com.ncorror.nekoflash.pro.engine.TerminalPlanResult
import com.ncorror.nekoflash.pro.engine.TerminalPlanStep
import io.github.ncorror.nekoflash.artifact.LocalFolder
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
    private val folder = PickedFile.Folder { error("not listed in this test") }

    @Test
    fun flashIsOneDownloadWithTheCommandsAfterIt() {
        val start = executor.execute(plan("fastboot --set-active=b flash boot boot.img"), picked)

        assertEquals(DesktopCommandStart.Started(DesktopCommandArea.FASTBOOT), start)
        // Без `--slot` слот уточнит устройство: пустой — «текущий».
        assertEquals(listOf("downloadThen flash:boot,set_active:b slot="), calls)
    }

    @Test
    fun unavailableTransportAnswersBeforeStartingOrRequestingAFile() {
        val blockedCalls = mutableListOf<String>()
        val blocked = DesktopCommandExecutor(
            RecordingTargets(blockedCalls),
            unavailable = { "error: no active transport" },
        )

        assertEquals(
            DesktopCommandStart.Answered("error: no active transport"),
            blocked.execute(plan("adb shell id"), null),
        )
        assertEquals(
            DesktopCommandStart.Answered("error: no active transport"),
            blocked.execute(plan("adb pull /sdcard/a"), null),
        )
        assertEquals(emptyList<String>(), blockedCalls)
    }

    @Test
    fun localReportsBypassTransportAdmission() {
        val blocked = DesktopCommandExecutor(
            RecordingTargets(mutableListOf()),
            unavailable = { "error: no active transport" },
        )

        assertEquals(
            DesktopCommandStart.Answered("ADB DEVICES"),
            blocked.execute(plan("adb devices"), null),
        )
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
        executor.execute(plan("fastboot --slot a format userdata"), null)
        executor.execute(plan("fastboot -w reboot"), null)
        executor.execute(plan("fastboot stage payload.bin"), picked)
        executor.execute(plan("fastboot get_staged out.bin"), saved)

        assertEquals(
            listOf(
                "getvar product",
                "getvar all",
                "command oem unlock",
                "downloadThen boot",
                "fetch vendor_boot slot=",
                "format userdata slot=a",
                "wipe then=reboot",
                "downloadThen ",
                "get_staged",
            ),
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
            executor.execute(plan("adb pull /sdcard/a.txt"), folder),
            executor.execute(plan("adb push a.txt /sdcard/"), picked),
            executor.execute(plan("adb sideload ota.zip"), picked),
            executor.execute(plan("adb logcat -s Tag"), null),
        ).map { (it as DesktopCommandStart.Started).area }

        assertEquals(
            listOf(
                "shell id", "interactive", "service root:", "reboot bootloader",
                "pull /sdcard/a.txt into=. z=none", "push a.txt -> /sdcard/ z=any", "sideload",
                "interactive logcat -s Tag",
            ),
            calls,
        )
        assertEquals(
            listOf(
                DesktopCommandArea.ADB_SHELL, DesktopCommandArea.ADB_TERMINAL, DesktopCommandArea.ADB_SERVICE,
                DesktopCommandArea.ADB_REBOOT, DesktopCommandArea.ADB_TRANSFER, DesktopCommandArea.ADB_TRANSFER,
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
    fun severalSourcesBindToOnePickOfTheSameCount() {
        val two = PickedFile.OpenMany(listOf({ error("x") }, { error("y") }))
        assertEquals(DesktopCommandStart.FileCount(3, 2), executor.execute(plan("adb push a b c /sdcard/"), two))
        executor.execute(plan("adb push --sync a b /sdcard/"), two)
        executor.execute(plan("adb push photos /sdcard/"), folder)
        executor.execute(plan("adb pull /sdcard/DCIM photos"), folder)
        executor.execute(plan("adb pull -a /sdcard/a.txt"), folder)
        executor.execute(plan("adb sync -l system"), folder)
        executor.execute(plan("adb push -z lz4 photos /sdcard/"), folder)
        executor.execute(plan("adb pull -z any /sdcard/DCIM photos"), folder)
        executor.execute(plan("adb sync -Z system"), folder)

        assertEquals(
            listOf(
                "push a,b -> /sdcard/ sync z=any",
                "push photos -> /sdcard/ z=any",
                "pull /sdcard/DCIM into=photos z=none",
                "pull /sdcard/a.txt into=. -a z=none",
                "sync system -l z=any",
                "push photos -> /sdcard/ z=lz4",
                "pull /sdcard/DCIM into=photos z=any",
                "sync system z=none",
            ),
            calls,
        )
    }

    @Test
    fun sparseLimitTravelsWithTheFlash() {
        executor.execute(plan("fastboot -S 256M flash system system.img"), picked)

        assertEquals(listOf("downloadThen flash:system slot= S=268435456"), calls)
    }

    @Test
    fun shellModesReachTheTerminal() {
        executor.execute(plan("adb shell -T"), null)
        executor.execute(plan("adb shell -x"), null)

        assertEquals(listOf("interactive RAW", "interactive LEGACY"), calls)
    }

    @Test
    fun localReportsAreAnsweredWithoutTouchingTheEngines() {
        assertEquals(
            DesktopCommandStart.Answered("ADB DEVICES_LONG"),
            executor.execute(plan("adb devices -l"), null),
        )
        assertEquals(DesktopCommandStart.Answered("FASTBOOT STATUS"), executor.execute(plan("fastboot status"), null))
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun slotTravelsWithTheCommandsItNames() {
        executor.execute(plan("fastboot --slot other flash boot boot.img"), picked)
        executor.execute(plan("fastboot --slot all erase cache"), null)
        executor.execute(plan("fastboot erase cache"), null)
        executor.execute(plan("fastboot raw erase:cache"), null)

        assertEquals(
            listOf(
                "downloadThen flash:boot slot=other",
                "plan erase:cache slot=all",
                "plan erase:cache slot=",
                "command erase:cache",
            ),
            calls,
        )
    }

    private class RecordingTargets(private val calls: MutableList<String>) : DesktopCommandTargets {
        override fun fastbootCommand(command: String) { calls += "command $command" }
        override fun fastbootPlan(commands: List<String>, slot: String?) {
            calls += "plan ${commands.joinToString(",")}" + (slot?.let { " slot=$it" } ?: "")
        }
        override fun fastbootVariable(name: String?) { calls += "getvar ${name ?: "all"}" }
        override fun fastbootDownloadThen(
            then: List<String>,
            slot: String?,
            sparseLimit: Long?,
            origin: () -> ArtifactSource,
        ) {
            calls += "downloadThen ${then.joinToString(",")}" + (slot?.let { " slot=$it" } ?: "") +
                (sparseLimit?.let { " S=$it" } ?: "")
        }
        override fun fastbootReceive(step: TerminalPlanStep, destination: () -> ArtifactSink) {
            calls += when (step) {
                is TerminalPlanStep.FastbootFetch -> "fetch ${step.partition}" + (step.slot?.let { " slot=$it" } ?: "")
                else -> "get_staged"
            }
        }
        override fun fastbootVendorRamdisk(ramdisk: String, slot: String, sparseLimit: Long?, origin: () -> ArtifactSource) {
            calls += "vendor_boot:$ramdisk slot=$slot"
        }
        override fun fastbootWipeSuper(slot: String, sparseLimit: Long?, origin: () -> ArtifactSource) {
            calls += "wipe-super slot=$slot"
        }
        override fun fastbootFlashAll(step: TerminalPlanStep.FastbootFlashAll, picked: PickedFile) {
            calls += (step.archiveName?.let { "update $it" } ?: "flashall") + " ${picked.javaClass.simpleName}"
        }
        override fun fastbootFormat(step: TerminalPlanStep) {
            calls += when (step) {
                is TerminalPlanStep.FastbootFormat -> "format ${step.partition} slot=${step.slot}"
                is TerminalPlanStep.FastbootWipe -> "wipe then=${step.then.joinToString(",")}"
                else -> "format $step"
            }
        }
        override fun adbShell(command: String) { calls += "shell $command" }
        override fun adbInteractiveShell(firstLine: String?, mode: ShellMode) {
            calls += "interactive" + (firstLine?.let { " $it" } ?: "") + (if (mode == ShellMode.PTY) "" else " $mode")
        }
        override fun adbService(service: String) { calls += "service $service" }
        override fun adbReboot(target: String) { calls += "reboot $target" }
        override fun adbPull(
            remotePaths: List<String>,
            localName: String?,
            keepAttributes: Boolean,
            compression: String,
            folder: () -> LocalFolder,
        ) {
            calls += "pull ${remotePaths.joinToString(" ")} into=${localName ?: "."}" + (if (keepAttributes) " -a" else "") +
                " z=$compression"
        }
        override fun adbSync(
            partition: String,
            listOnly: Boolean,
            dryRun: Boolean,
            compression: String,
            productOut: () -> LocalFolder,
        ) {
            calls += "sync $partition" + (if (listOnly) " -l" else "") + (if (dryRun) " -n" else "") + " z=$compression"
        }
        override fun adbPush(
            localNames: List<String>,
            picked: PickedFile,
            remotePath: String,
            sync: Boolean,
            dryRun: Boolean,
            compression: String,
        ) {
            calls += "push ${localNames.joinToString(",")} -> $remotePath" + (if (sync) " sync" else "") +
                (if (dryRun) " -n" else "") + " z=$compression"
        }
        override fun adbSideload(origin: () -> ArtifactSource) { calls += "sideload" }
        override fun adbInstall(origins: List<() -> ArtifactSource>, options: List<String>, several: Boolean) {
            calls += "install ${origins.size} ${options.joinToString(",")} $several"
        }
        override fun localReport(tool: TerminalParser.Tool, kind: LocalReportKind): String = "$tool $kind"
    }
}
