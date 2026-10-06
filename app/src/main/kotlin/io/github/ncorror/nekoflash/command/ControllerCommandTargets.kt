package io.github.ncorror.nekoflash.command

import com.ncorror.nekoflash.pro.engine.LocalReportKind
import com.ncorror.nekoflash.pro.engine.ShellMode
import com.ncorror.nekoflash.pro.engine.TerminalParser
import com.ncorror.nekoflash.pro.engine.TerminalPlanStep
import io.github.ncorror.nekoflash.adb.AdbLinkController
import io.github.ncorror.nekoflash.adb.AdbLinkState
import io.github.ncorror.nekoflash.adb.AdbPushMode
import io.github.ncorror.nekoflash.adb.PullTarget
import io.github.ncorror.nekoflash.adb.PushSource
import io.github.ncorror.nekoflash.artifact.LocalFolder
import io.github.ncorror.nekoflash.artifact.ZipArchive
import io.github.ncorror.nekoflash.core.artifact.ArtifactSink
import io.github.ncorror.nekoflash.core.artifact.ArtifactSource
import io.github.ncorror.nekoflash.core.model.SessionGeneration
import io.github.ncorror.nekoflash.fastboot.FastbootLinkController
import io.github.ncorror.nekoflash.fastboot.FlashAllRequest
import io.github.ncorror.nekoflash.fastboot.FormatOptions
import io.github.ncorror.nekoflash.fastboot.FolderImageSet
import io.github.ncorror.nekoflash.fastboot.ZipImageSet
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
    override fun fastbootVariable(name: String?) =
        if (name == null) fastboot.readAllVariables() else fastboot.readVariable(name)
    override fun fastbootDownloadThen(
        then: List<String>,
        slot: String?,
        sparseLimit: Long?,
        origin: () -> ArtifactSource,
    ) = fastboot.downloadFrom(then, slot, sparseLimit, origin)
    override fun fastbootReceive(step: TerminalPlanStep, destination: () -> ArtifactSink) = when (step) {
        is TerminalPlanStep.FastbootFetch -> fastboot.fetchPartitionTo(step.partition, step.slot, destination)
        is TerminalPlanStep.FastbootGetStaged -> fastboot.buffer.uploadTo(destination)
        else -> error("Not a DATA IN step: $step")
    }

    override fun fastbootVendorRamdisk(
        ramdisk: String,
        slot: String,
        sparseLimit: Long?,
        origin: () -> ArtifactSource,
    ) = fastboot.scenarios.flashVendorRamdisk(ramdisk, slot, sparseLimit, stagingDirectory, origin)

    override fun fastbootWipeSuper(slot: String, sparseLimit: Long?, origin: () -> ArtifactSource) =
        fastboot.scenarios.wipeSuper(slot, sparseLimit, origin)

    override fun fastbootFlashAll(step: TerminalPlanStep.FastbootFlashAll, picked: PickedFile) =
        fastboot.scenarios.flashAll(
            FlashAllRequest(
                slot = step.slot,
                force = step.force,
                skipSecondary = step.skipSecondary,
                skipReboot = step.skipReboot,
                vbmetaFlags = step.vbmetaFlags,
                sparseLimit = step.sparseLimit,
                flashAll = step.archiveName == null,
                wipe = step.wipe,
                format = FormatOptions(step.sparseLimit, step.fsOptions),
            ),
        ) {
            when (picked) {
                is PickedFile.Folder -> FolderImageSet(picked.folder())
                is PickedFile.Open -> ZipImageSet(ZipArchive(picked.origin()))
                else -> error("flashall/update: $picked")
            }
        }
    override fun fastbootFormat(step: TerminalPlanStep) = when (step) {
        is TerminalPlanStep.FastbootFormat -> fastboot.scenarios.format(
            step.partition,
            step.fileSystem,
            step.size,
            step.slot,
            FormatOptions(step.sparseLimit, step.fsOptions),
        )
        is TerminalPlanStep.FastbootWipe ->
            fastboot.scenarios.wipe(step.force, step.then, FormatOptions(step.sparseLimit, step.fsOptions))
        else -> error("format: $step")
    }
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

    override val adbForwarding: AdbForwardingTargets = object : AdbForwardingTargets {
        override fun forwardAdd(localPort: Int, remote: String, noRebind: Boolean) =
            adb.forwards.add(localPort, remote, noRebind)

        override fun forwardRemove(localPort: Int) = adb.forwards.remove(localPort)

        override fun forwardRemoveAll() = adb.forwards.removeAll()

        override fun forwardList(): String {
            val serial = (adb.state.value as? AdbLinkState.Connected)?.generation
                ?.let { generation -> sessions().firstOrNull { it.generation == generation }?.identity?.serialNumber }
                ?.takeIf { it.isNotBlank() }
                ?: UNKNOWN_SERIAL
            return adb.forward.value.forwards.joinToString("\n") { entry ->
                "$serial\ttcp:${entry.localPort}\t${entry.address}"
            }
        }

        override fun reverseAdd(remote: String, local: String, noRebind: Boolean) =
            adb.reverses.add(remote, local, noRebind)

        override fun reverseRemove(remote: String) = adb.reverses.remove(remote)

        override fun reverseList() = adb.reverses.refresh()

        override fun reverseRemoveAll() = adb.reverses.removeAll()
    }

    override fun adbPull(
        remotePaths: List<String>,
        localName: String?,
        keepAttributes: Boolean,
        compression: String,
        folder: () -> LocalFolder,
    ) = adb.storage.pull(remotePaths, compression) { PullTarget(folder(), localName, keepAttributes) }

    override fun adbSync(
        partition: String,
        listOnly: Boolean,
        dryRun: Boolean,
        compression: String,
        productOut: () -> LocalFolder,
    ) = adb.storage.syncBuild(productOut, partition, listOnly, AdbPushMode(dryRun, compression))

    override fun adbPush(
        localNames: List<String>,
        picked: PickedFile,
        remotePath: String,
        sync: Boolean,
        dryRun: Boolean,
        compression: String,
    ) = adb.storage.push({ pushSources(localNames, picked) }, remotePath, sync, AdbPushMode(dryRun, compression))
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

    private companion object {
        const val UNKNOWN_SERIAL = "????????????"
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
