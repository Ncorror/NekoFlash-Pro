package io.github.ncorror.nekoflash.command

import com.ncorror.nekoflash.pro.engine.TerminalFileNeed
import com.ncorror.nekoflash.pro.engine.TerminalPlan
import com.ncorror.nekoflash.pro.engine.TerminalPlanStep
import com.ncorror.nekoflash.pro.engine.fileNeed
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
    fun fastbootPlan(commands: List<String>)
    fun fastbootVariable(name: String)
    fun fastbootAllVariables()
    fun fastbootDownloadThen(then: List<String>, origin: () -> ArtifactSource)
    fun fastbootFetch(partition: String, destination: () -> ArtifactSink)
    fun adbShell(command: String)
    fun adbInteractiveShell(firstLine: String?)
    fun adbService(service: String)
    fun adbReboot(target: String)
    fun adbPull(remotePath: String, destination: () -> ArtifactSink)
    fun adbPush(remotePath: String, origin: () -> ArtifactSource)
    fun adbSideload(origin: () -> ArtifactSource)
}

/** Файл, выбранный через Android для шага плана. */
sealed interface PickedFile {
    data class Open(val origin: () -> ArtifactSource) : PickedFile
    data class Create(val destination: () -> ArtifactSink) : PickedFile
}

/** Где искать исход: у Fastboot-консоли или у соответствующей части ADB. */
enum class DesktopCommandArea { FASTBOOT, ADB_SHELL, ADB_TERMINAL, ADB_SERVICE, ADB_REBOOT, ADB_FILES, ADB_SIDELOAD }

/** Чем кончилась попытка отдать план движкам. */
sealed interface DesktopCommandStart {
    data class Started(val area: DesktopCommandArea) : DesktopCommandStart

    /** Плану нужен файл, а выбран другой или никакой: ничего не отправлено. */
    data object FileMissing : DesktopCommandStart
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
        val need = plan.fileStep?.fileNeed ?: TerminalFileNeed.NONE
        val matches = when (need) {
            TerminalFileNeed.NONE -> true
            TerminalFileNeed.OPEN -> file is PickedFile.Open
            TerminalFileNeed.CREATE -> file is PickedFile.Create
        }
        if (!matches) return DesktopCommandStart.FileMissing
        val first = plan.steps.first()
        return DesktopCommandStart.Started(
            if (first.isFastboot()) fastboot(plan.steps, file) else adb(first, file),
        )
    }

    private fun fastboot(steps: List<TerminalPlanStep>, file: PickedFile?): DesktopCommandArea {
        val wires = steps.filterIsInstance<TerminalPlanStep.FastbootWire>().map { it.command }
        when (val first = steps.first()) {
            is TerminalPlanStep.FastbootFlashImage ->
                targets.fastbootDownloadThen(listOf("flash:${first.partition}") + wires, file.open())
            is TerminalPlanStep.FastbootBootImage -> targets.fastbootDownloadThen(listOf("boot") + wires, file.open())
            is TerminalPlanStep.FastbootFetch -> targets.fastbootFetch(first.partition, file.create())
            is TerminalPlanStep.FastbootGetvar -> targets.fastbootVariable(first.variable)
            TerminalPlanStep.FastbootGetvarAll -> targets.fastbootAllVariables()
            else -> if (wires.size == 1) targets.fastbootCommand(wires.single()) else targets.fastbootPlan(wires)
        }
        return DesktopCommandArea.FASTBOOT
    }

    private fun adb(step: TerminalPlanStep, file: PickedFile?): DesktopCommandArea = when (step) {
        is TerminalPlanStep.AdbShellCommand -> DesktopCommandArea.ADB_SHELL.also { targets.adbShell(step.command) }
        is TerminalPlanStep.AdbInteractiveShell ->
            DesktopCommandArea.ADB_TERMINAL.also { targets.adbInteractiveShell(step.firstLine) }
        is TerminalPlanStep.AdbService -> DesktopCommandArea.ADB_SERVICE.also { targets.adbService(step.service) }
        is TerminalPlanStep.AdbReboot -> DesktopCommandArea.ADB_REBOOT.also { targets.adbReboot(step.target) }
        is TerminalPlanStep.AdbPull ->
            DesktopCommandArea.ADB_FILES.also { targets.adbPull(step.remotePath, file.create()) }
        is TerminalPlanStep.AdbPush ->
            DesktopCommandArea.ADB_FILES.also { targets.adbPush(step.remotePath, file.open()) }
        is TerminalPlanStep.AdbSideload -> DesktopCommandArea.ADB_SIDELOAD.also { targets.adbSideload(file.open()) }
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
    -> true

    else -> false
}

private fun PickedFile?.open(): () -> ArtifactSource = (this as PickedFile.Open).origin

private fun PickedFile?.create(): () -> ArtifactSink = (this as PickedFile.Create).destination
