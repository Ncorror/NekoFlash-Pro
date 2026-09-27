package io.github.ncorror.nekoflash.command

import com.ncorror.nekoflash.pro.engine.LocalReportKind
import com.ncorror.nekoflash.pro.engine.ShellMode
import com.ncorror.nekoflash.pro.engine.TerminalParser
import io.github.ncorror.nekoflash.adb.AdbLinkController
import io.github.ncorror.nekoflash.adb.PullTarget
import io.github.ncorror.nekoflash.adb.PushSource
import io.github.ncorror.nekoflash.artifact.LocalFolder
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
    override fun adbInteractiveShell(firstLine: String?, mode: ShellMode) = adb.shells.start(
        firstLine,
        when (mode) {
            ShellMode.PTY -> AdbShellMode.PTY
            ShellMode.RAW -> AdbShellMode.RAW
            ShellMode.LEGACY -> AdbShellMode.LEGACY
        },
        fromCommand = true,
    )
    override fun adbService(service: String) = adb.callRawService(service)
    override fun adbReboot(target: String) = adb.requestReboot(target)
    override fun adbPull(remotePaths: List<String>, localName: String?, folder: () -> LocalFolder) =
        adb.storage.pull(remotePaths) { PullTarget(folder(), localName) }

    override fun adbSync(partition: String, listOnly: Boolean, productOut: () -> LocalFolder) =
        adb.storage.syncBuild(productOut, partition, listOnly)

    override fun adbPush(localNames: List<String>, picked: PickedFile, remotePath: String, sync: Boolean) =
        adb.storage.push({ pushSources(localNames, picked) }, remotePath, sync)
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

/**
 * Источники `adb push`: выбранная папка или файлы — под именами из команды.
 * Размер и время спрашиваются у провайдера здесь, на рабочем потоке.
 */
internal fun pushSources(names: List<String>, picked: PickedFile): List<PushSource> = when (picked) {
    is PickedFile.Folder -> listOf(PushSource.Folder(names.single(), picked.folder()))
    is PickedFile.Open -> listOf(file(names.single(), picked.origin))
    is PickedFile.OpenMany -> names.zip(picked.origins) { name, origin -> file(name, origin) }
    is PickedFile.Create -> error("push needs a source, not a destination")
}

private fun file(name: String, origin: () -> ArtifactSource): PushSource.File {
    val source = origin()
    return PushSource.File(
        name = name,
        sizeBytes = source.identity.sizeBytes ?: -1L,
        modifiedAtMillis = source.openedStamp.modifiedAtMillis,
        open = { source },
    )
}
