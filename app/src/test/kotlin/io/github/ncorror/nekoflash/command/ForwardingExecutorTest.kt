package io.github.ncorror.nekoflash.command

import com.ncorror.nekoflash.pro.engine.LocalReportKind
import com.ncorror.nekoflash.pro.engine.ShellMode
import com.ncorror.nekoflash.pro.engine.TerminalParser
import com.ncorror.nekoflash.pro.engine.TerminalPlanCompiler
import com.ncorror.nekoflash.pro.engine.TerminalPlanResult
import com.ncorror.nekoflash.pro.engine.TerminalPlanStep
import io.github.ncorror.nekoflash.artifact.LocalFolder
import io.github.ncorror.nekoflash.core.artifact.ArtifactSink
import io.github.ncorror.nekoflash.core.artifact.ArtifactSource
import org.junit.Assert.assertEquals
import org.junit.Test

class ForwardingExecutorTest {
    private val calls = mutableListOf<String>()
    private val targets = Targets(calls)
    private val executor = DesktopCommandExecutor(targets)
    private val compiler = TerminalPlanCompiler()

    private fun run(line: String): DesktopCommandStart {
        val plan = (compiler.compile(line) as TerminalPlanResult.Ready).plan
        return executor.execute(plan, null) { area -> calls += "start:$area" }
    }

    @Test
    fun forwardingUsesTheFormerWorkspaceControllers() {
        assertEquals(DesktopCommandStart.Started(DesktopCommandArea.ADB_FORWARD), run("adb forward tcp:1234 tcp:5555"))
        assertEquals(DesktopCommandStart.Started(DesktopCommandArea.ADB_FORWARD), run("adb forward --remove tcp:1234"))
        assertEquals(DesktopCommandStart.Started(DesktopCommandArea.ADB_REVERSE), run("adb reverse tcp:7777 tcp:8888"))
        assertEquals(DesktopCommandStart.Started(DesktopCommandArea.ADB_REVERSE_LIST), run("adb reverse --list"))
        assertEquals(DesktopCommandStart.Started(DesktopCommandArea.ADB_REVERSE), run("adb reverse --remove-all"))

        assertEquals(
            listOf(
                "start:ADB_FORWARD", "forward+ 1234 tcp:5555",
                "start:ADB_FORWARD", "forward- 1234",
                "start:ADB_REVERSE", "reverse+ tcp:7777 tcp:8888",
                "start:ADB_REVERSE_LIST", "reverse-list",
                "start:ADB_REVERSE", "reverse-clear",
            ),
            calls,
        )
    }

    @Test
    fun forwardListIsAnsweredLocallyWithoutStartingAnOperation() {
        assertEquals(DesktopCommandStart.Answered("forward-list"), run("adb forward --list"))
        assertEquals(listOf("forward-list"), calls)
    }

    private class Targets(private val calls: MutableList<String>) : DesktopCommandTargets {
        override fun fastbootCommand(command: String) = Unit
        override fun fastbootPlan(commands: List<String>, slot: String?) = Unit
        override fun fastbootVariable(name: String?) = Unit
        override fun fastbootDownloadThen(
            then: List<String>,
            slot: String?,
            sparseLimit: Long?,
            origin: () -> ArtifactSource,
        ) = Unit
        override fun fastbootReceive(step: TerminalPlanStep, destination: () -> ArtifactSink) = Unit
        override fun fastbootFlashAll(step: TerminalPlanStep.FastbootFlashAll, picked: PickedFile) = Unit
        override fun fastbootWipeSuper(slot: String, sparseLimit: Long?, origin: () -> ArtifactSource) = Unit
        override fun fastbootFormat(step: TerminalPlanStep) = Unit
        override fun fastbootVendorRamdisk(
            ramdisk: String,
            slot: String,
            sparseLimit: Long?,
            origin: () -> ArtifactSource,
        ) = Unit
        override fun adbShell(command: String) = Unit
        override fun adbInteractiveShell(firstLine: String?, mode: ShellMode) = Unit
        override fun adbService(service: String) = Unit
        override fun adbReboot(target: String) = Unit
        override fun adbForwardAdd(localPort: Int, remote: String) {
            calls += "forward+ $localPort $remote"
        }
        override fun adbForwardRemove(localPort: Int) {
            calls += "forward- $localPort"
        }
        override fun adbForwardList(): String = "forward-list".also { calls += it }
        override fun adbReverseAdd(remote: String, local: String) {
            calls += "reverse+ $remote $local"
        }
        override fun adbReverseList() {
            calls += "reverse-list"
        }
        override fun adbReverseRemoveAll() {
            calls += "reverse-clear"
        }
        override fun adbPull(
            remotePaths: List<String>,
            localName: String?,
            keepAttributes: Boolean,
            compression: String,
            folder: () -> LocalFolder,
        ) = Unit
        override fun adbPush(
            localNames: List<String>,
            picked: PickedFile,
            remotePath: String,
            sync: Boolean,
            dryRun: Boolean,
            compression: String,
        ) = Unit
        override fun adbSync(
            partition: String,
            listOnly: Boolean,
            dryRun: Boolean,
            compression: String,
            productOut: () -> LocalFolder,
        ) = Unit
        override fun adbSideload(origin: () -> ArtifactSource) = Unit
        override fun adbInstall(origins: List<() -> ArtifactSource>, options: List<String>, several: Boolean) = Unit
        override fun localReport(tool: TerminalParser.Tool, kind: LocalReportKind): String = ""
    }
}
