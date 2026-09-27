package io.github.ncorror.nekoflash.command

import io.github.ncorror.nekoflash.adb.AdbLinkController
import io.github.ncorror.nekoflash.core.artifact.ArtifactSink
import io.github.ncorror.nekoflash.core.artifact.ArtifactSource
import io.github.ncorror.nekoflash.fastboot.FastbootLinkController
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
) : DesktopCommandTargets {
    override fun fastbootCommand(command: String) = fastboot.runCommand(command)
    override fun fastbootPlan(commands: List<String>) = fastboot.runPlan(commands)
    override fun fastbootVariable(name: String) = fastboot.readVariable(name)
    override fun fastbootAllVariables() = fastboot.readAllVariables()
    override fun fastbootDownloadThen(then: List<String>, origin: () -> ArtifactSource) =
        fastboot.downloadFrom(then, origin)
    override fun fastbootFetch(partition: String, destination: () -> ArtifactSink) =
        fastboot.fetchPartitionTo(partition, destination)
    override fun adbShell(command: String) = adb.runCommand(command)
    override fun adbInteractiveShell() = adb.startShell()
    override fun adbService(service: String) = adb.callRawService(service)
    override fun adbReboot(target: String) = adb.requestReboot(target)
    override fun adbPull(remotePath: String, destination: () -> ArtifactSink) =
        adb.storage.readTo(remotePath, destination)
    override fun adbPush(remotePath: String, origin: () -> ArtifactSource) = adb.storage.writeFrom(remotePath, origin)
    override fun adbSideload(origin: () -> ArtifactSource) = adb.recovery.sideloadFrom(stagingDirectory, origin)
}
