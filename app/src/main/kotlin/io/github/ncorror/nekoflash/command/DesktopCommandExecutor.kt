package io.github.ncorror.nekoflash.command

import com.ncorror.nekoflash.pro.engine.LocalReportKind
import com.ncorror.nekoflash.pro.engine.ShellMode
import com.ncorror.nekoflash.pro.engine.TerminalFileNeed
import com.ncorror.nekoflash.pro.engine.TerminalParser
import com.ncorror.nekoflash.pro.engine.TerminalPlan
import com.ncorror.nekoflash.pro.engine.TerminalPlanStep
import com.ncorror.nekoflash.pro.engine.fileNeed
import io.github.ncorror.nekoflash.artifact.LocalFolder
import io.github.ncorror.nekoflash.artifact.VbmetaFlagsSource
import io.github.ncorror.nekoflash.core.artifact.ArtifactSink
import io.github.ncorror.nekoflash.core.artifact.ArtifactSource

/**
 * Входы движков, которыми исполняется план команды настольного клиента.
 *
 * Ровно те же, что у кнопок и сырой консоли: второго пути к полосе Fastboot или
 * к потокам ADB план не заводит (`04` §2, D045). Интерфейс нужен, чтобы
 * сопоставление шагов с входами проверялось без устройства.
 */
interface DesktopCommandTargets {
    fun fastbootCommand(command: String)
    fun fastbootPlan(commands: List<String>, slot: String?)
    fun fastbootVariable(name: String)
    fun fastbootAllVariables()
    fun fastbootDownloadThen(then: List<String>, slot: String?, sparseLimit: Long?, origin: () -> ArtifactSource)
    fun fastbootFetch(partition: String, slot: String?, destination: () -> ArtifactSink)

    /** `flashall` — [picked] папка; `update` — выбранный архив. */
    fun fastbootFlashAll(step: TerminalPlanStep.FastbootFlashAll, picked: PickedFile)
    fun adbShell(command: String)
    fun adbInteractiveShell(firstLine: String?, mode: ShellMode)
    fun adbService(service: String)
    fun adbReboot(target: String)
    fun adbPull(remotePaths: List<String>, localName: String?, folder: () -> LocalFolder)
    fun adbPush(localNames: List<String>, picked: PickedFile, remotePath: String, sync: Boolean, dryRun: Boolean)
    fun adbSync(partition: String, listOnly: Boolean, dryRun: Boolean, productOut: () -> LocalFolder)
    fun adbSideload(origin: () -> ArtifactSource)
    fun adbInstall(origins: List<() -> ArtifactSource>, options: List<String>, several: Boolean)

    /** Ответ приложения о своей связи: `devices`, `get-state`, `status`. */
    fun localReport(tool: TerminalParser.Tool, kind: LocalReportKind): String
}

/** Файл, выбранный через Android для шага плана. */
sealed interface PickedFile {
    data class Open(val origin: () -> ArtifactSource) : PickedFile
    data class OpenMany(val origins: List<() -> ArtifactSource>) : PickedFile
    data class Create(val destination: () -> ArtifactSink) : PickedFile
    data class Folder(val folder: () -> LocalFolder) : PickedFile
}

/** Где искать исход: у Fastboot-консоли или у соответствующей части ADB. */
enum class DesktopCommandArea {
    FASTBOOT, ADB_SHELL, ADB_TERMINAL, ADB_SERVICE, ADB_REBOOT, ADB_TRANSFER, ADB_SIDELOAD, ADB_INSTALL,
}

/** Чем кончилась попытка отдать план движкам. */
sealed interface DesktopCommandStart {
    data class Started(val area: DesktopCommandArea) : DesktopCommandStart

    /** Плану нужен файл, а выбран другой или никакой: ничего не отправлено. */
    data object FileMissing : DesktopCommandStart

    /** Команда называет [expected] файлов, а выбрано [picked]: ничего не отправлено. */
    data class FileCount(val expected: Int, val picked: Int) : DesktopCommandStart

    /** Ответило само приложение; устройству ничего не отправлено. */
    data class Answered(val text: String) : DesktopCommandStart
}

/**
 * Отдаёт план движкам.
 *
 * Шаги Fastboot, идущие подряд, уходят одним планом полосы: он останавливается
 * на первом непринятом шаге и не складывает неизвестность с неизвестностью.
 * Загрузка выбранного файла и команды после неё — одна операция
 * `downloadFrom`: из недогруженного буфера не прошивают.
 */
class DesktopCommandExecutor(private val targets: DesktopCommandTargets) {
    fun execute(plan: TerminalPlan, file: PickedFile?): DesktopCommandStart {
        val first = plan.steps.first()
        if (first is TerminalPlanStep.LocalReport) {
            return DesktopCommandStart.Answered(targets.localReport(first.tool, first.kind))
        }
        return fileProblem(plan, file) ?: DesktopCommandStart.Started(
            if (first.isFastboot()) fastboot(plan.steps, file) else adb(first, file),
        )
    }

    /** Подходит ли выбор плану; `null` — подходит. */
    private fun fileProblem(plan: TerminalPlan, file: PickedFile?): DesktopCommandStart? {
        val matches = when (plan.fileStep?.fileNeed ?: TerminalFileNeed.NONE) {
            TerminalFileNeed.NONE -> true
            TerminalFileNeed.OPEN -> file is PickedFile.Open
            TerminalFileNeed.OPEN_MANY -> file is PickedFile.OpenMany
            TerminalFileNeed.CREATE -> file is PickedFile.Create
            TerminalFileNeed.FOLDER -> file is PickedFile.Folder
            TerminalFileNeed.OPEN_OR_FOLDER -> file is PickedFile.Open || file is PickedFile.Folder
        }
        val named = when (val step = plan.fileStep) {
            is TerminalPlanStep.AdbInstall -> step.names.size
            is TerminalPlanStep.AdbPush -> step.localNames.size.takeIf { it > 1 }
            else -> null
        }
        val picked = (file as? PickedFile.OpenMany)?.origins?.size
        return when {
            !matches -> DesktopCommandStart.FileMissing
            named != null && picked != null && named != picked -> DesktopCommandStart.FileCount(named, picked)
            else -> null
        }
    }

    private fun fastboot(steps: List<TerminalPlanStep>, file: PickedFile?): DesktopCommandArea {
        val wires = steps.filterIsInstance<TerminalPlanStep.FastbootWire>().map { it.command }
        // `--slot` уточняется на устройстве, одним планом с командами.
        val slot = steps.firstNotNullOfOrNull { step ->
            (step as? TerminalPlanStep.FastbootWire)?.slot ?: (step as? TerminalPlanStep.FastbootFlashImage)?.slot
        }
        when (val first = steps.first()) {
            is TerminalPlanStep.FastbootFlashImage -> targets.fastbootDownloadThen(
                listOf("flash:${first.partition}") + wires,
                slot,
                first.sparseLimit,
                file.open().withVbmetaFlags(first.vbmetaFlags),
            )
            is TerminalPlanStep.FastbootBootImage ->
                targets.fastbootDownloadThen(listOf("boot") + wires, null, null, file.open())
            is TerminalPlanStep.FastbootFetch -> targets.fastbootFetch(first.partition, first.slot, file.create())
            is TerminalPlanStep.FastbootGetvar -> targets.fastbootVariable(first.variable)
            TerminalPlanStep.FastbootGetvarAll -> targets.fastbootAllVariables()
            is TerminalPlanStep.FastbootFlashAll -> targets.fastbootFlashAll(first, checkNotNull(file))
            // Слот уточняется на устройстве — это тоже план.
            else -> if (wires.size == 1 && slot == null) {
                targets.fastbootCommand(wires.single())
            } else {
                targets.fastbootPlan(wires, slot)
            }
        }
        return DesktopCommandArea.FASTBOOT
    }

    private fun adb(step: TerminalPlanStep, file: PickedFile?): DesktopCommandArea = when (step) {
        is TerminalPlanStep.AdbShellCommand -> DesktopCommandArea.ADB_SHELL.also { targets.adbShell(step.command) }
        is TerminalPlanStep.AdbInteractiveShell ->
            DesktopCommandArea.ADB_TERMINAL.also { targets.adbInteractiveShell(step.firstLine, step.mode) }
        is TerminalPlanStep.AdbService -> DesktopCommandArea.ADB_SERVICE.also { targets.adbService(step.service) }
        is TerminalPlanStep.AdbReboot -> DesktopCommandArea.ADB_REBOOT.also { targets.adbReboot(step.target) }
        is TerminalPlanStep.AdbPull -> DesktopCommandArea.ADB_TRANSFER.also {
            targets.adbPull(step.remotePaths, step.localName, (file as PickedFile.Folder).folder)
        }
        is TerminalPlanStep.AdbSync -> DesktopCommandArea.ADB_TRANSFER.also {
            targets.adbSync(step.partition, step.listOnly, step.dryRun, (file as PickedFile.Folder).folder)
        }
        is TerminalPlanStep.AdbPush -> DesktopCommandArea.ADB_TRANSFER.also {
            targets.adbPush(step.localNames, checkNotNull(file), step.remotePath, step.sync, step.dryRun)
        }
        is TerminalPlanStep.AdbSideload -> DesktopCommandArea.ADB_SIDELOAD.also { targets.adbSideload(file.open()) }
        is TerminalPlanStep.AdbInstall -> DesktopCommandArea.ADB_INSTALL.also {
            val origins = (file as? PickedFile.OpenMany)?.origins ?: listOf(file.open())
            targets.adbInstall(origins, step.options, step.several)
        }
        else -> error("Fastboot step in an ADB plan: $step")
    }
}

/** Шаг Fastboot или ADB — по тому, чей движок его исполняет. */
fun TerminalPlanStep.isFastboot(): Boolean = when (this) {
    is TerminalPlanStep.FastbootWire,
    is TerminalPlanStep.FastbootGetvar,
    TerminalPlanStep.FastbootGetvarAll,
    is TerminalPlanStep.FastbootFlashImage,
    is TerminalPlanStep.FastbootBootImage,
    is TerminalPlanStep.FastbootFetch,
    is TerminalPlanStep.FastbootFlashAll,
    -> true

    else -> false
}

private fun PickedFile?.open(): () -> ArtifactSource = (this as PickedFile.Open).origin

/** Источник с правленым заголовком vbmeta, если флаги заданы. */
private fun (() -> ArtifactSource).withVbmetaFlags(flags: Int): () -> ArtifactSource =
    if (flags == 0) this else ({ VbmetaFlagsSource(this(), flags) })

private fun PickedFile?.create(): () -> ArtifactSink = (this as PickedFile.Create).destination
