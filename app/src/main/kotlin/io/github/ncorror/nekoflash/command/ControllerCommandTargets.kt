package io.github.ncorror.nekoflash.command

import com.ncorror.nekoflash.pro.engine.LocalReportKind
import com.ncorror.nekoflash.pro.engine.ShellMode
import com.ncorror.nekoflash.pro.engine.TerminalParser
import io.github.ncorror.nekoflash.adb.AdbLinkController
import io.github.ncorror.nekoflash.core.artifact.ArtifactSink
import io.github.ncorror.nekoflash.core.artifact.ArtifactSource
import io.github.ncorror.nekoflash.core.model.SessionGeneration
import io.github.ncorror.nekoflash.fastboot.FastbootLinkController
import io.github.ncorror.nekoflash.protocol.adb.AdbShellMode
import io.github.ncorror.nekoflash.usb.api.UsbSession
import java.io.File

/**
 * Входы движков для плана команды — те же вызовы, что у кнопок.
 *
 * Отдельный класс, а не лямбды в разметке: исполнитель проверяется тестом на
 * подставных входах, и в production ему нужен ровно этот перечень.
 */
internal class ControllerCommandTargets(
    private val adb: AdbLinkController,
    private val fastboot: FastbootLinkController,
    private val stagingDirectory: File,
    /** USB-сессии приложения: по ним `devices` называет серийный номер и порт. */
    private val sessions: () -> List<UsbSession>,
) : DesktopCommandTargets {
    override fun fastbootCommand(command: String) = fastboot.runCommand(command)
    override fun fastbootPlan(commands: List<String>, slot: String?) = fastboot.runPlan(commands, slot)
    override fun fastbootVariable(name: String) = fastboot.readVariable(name)
    override fun fastbootAllVariables() = fastboot.readAllVariables()
    override fun fastbootDownloadThen(
        then: List<String>,
        slot: String?,
        sparseLimit: Long?,
        origin: () -> ArtifactSource,
    ) = fastboot.downloadFrom(then, slot, sparseLimit, origin)
    override fun fastbootFetch(partition: String, slot: String?, destination: () -> ArtifactSink) =
        fastboot.fetchPartitionTo(partition, slot, destination)
    override fun adbShell(command: String) = adb.runCommand(command)
    override fun adbInteractiveShell(firstLine: String?, mode: ShellMode) = adb.startShell(
        firstLine,
        when (mode) {
            ShellMode.PTY -> AdbShellMode.PTY
            ShellMode.RAW -> AdbShellMode.RAW
            ShellMode.LEGACY -> AdbShellMode.LEGACY
        },
    )
    override fun adbService(service: String) = adb.callRawService(service)
    override fun adbReboot(target: String) = adb.requestReboot(target)
    override fun adbPull(remotePath: String, destination: () -> ArtifactSink) =
        adb.storage.readTo(remotePath, destination)
    override fun adbPush(remotePath: String, origin: () -> ArtifactSource) = adb.storage.writeFrom(remotePath, origin)
    override fun adbSideload(origin: () -> ArtifactSource) = adb.recovery.sideloadFrom(stagingDirectory, origin)
    override fun adbInstall(origins: List<() -> ArtifactSource>, options: List<String>, several: Boolean) =
        adb.packages.install(origins, options, several)

    override fun localReport(tool: TerminalParser.Tool, kind: LocalReportKind): String {
        val identity = { generation: SessionGeneration ->
            sessions().firstOrNull { it.generation == generation }?.identity
        }
        return when (tool) {
            TerminalParser.Tool.ADB -> LocalReports.adb(kind, adb.state.value, identity)
            TerminalParser.Tool.FASTBOOT -> LocalReports.fastboot(kind, fastboot.state.value, identity)
        }
    }
}
