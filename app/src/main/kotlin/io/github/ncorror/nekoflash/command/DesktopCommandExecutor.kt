package io.github.ncorror.nekoflash.command

import com.ncorror.nekoflash.pro.engine.AdbForwardAddStep
import com.ncorror.nekoflash.pro.engine.AdbForwardListStep
import com.ncorror.nekoflash.pro.engine.AdbForwardRemoveStep
import com.ncorror.nekoflash.pro.engine.AdbReverseAddStep
import com.ncorror.nekoflash.pro.engine.AdbReverseListStep
import com.ncorror.nekoflash.pro.engine.AdbReverseRemoveAllStep
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
    /** `getvar ИМЯ`; `null` — `getvar all`: всё, что устройство готово рассказать. */
    fun fastbootVariable(name: String?)
    fun fastbootDownloadThen(then: List<String>, slot: String?, sparseLimit: Long?, origin: () -> ArtifactSource)
    /**
     * DATA IN в выбранный файл: [TerminalPlanStep.FastbootFetch] (`fetch:РАЗДЕЛ`)
     * или [TerminalPlanStep.FastbootGetStaged] (`upload` — буфер устройства).
     */
    fun fastbootReceive(step: TerminalPlanStep, destination: () -> ArtifactSink)

    /** `flashall` — [picked] папка; `update` — выбранный архив. */
    fun fastbootFlashAll(step: TerminalPlanStep.FastbootFlashAll, picked: PickedFile)

    /** `wipe-super`: выбранный `super_empty.img`. */
    fun fastbootWipeSuper(slot: String, sparseLimit: Long?, origin: () -> ArtifactSource)

    /**
     * Файловая система собирается на телефоне: [TerminalPlanStep.FastbootFormat]
     * (`format[:ТИП[:РАЗМЕР]] РАЗДЕЛ`) или [TerminalPlanStep.FastbootWipe]
     * (`-w [reboot…]`: `userdata`, `cache`, `metadata`).
     */
    fun fastbootFormat(step: TerminalPlanStep)

    /** `flash vendor_boot:ИМЯ`: выбранный рамдиск. */
    fun fastbootVendorRamdisk(ramdisk: String, slot: String, sparseLimit: Long?, origin: () -> ArtifactSource)
    fun adbShell(command: String)
    fun adbInteractiveShell(firstLine: String?, mode: ShellMode)
    fun adbService(service: String)
    fun adbReboot(target: String)

    /** Long-lived forwarding operations are one capability surface, not six unrelated target functions. */
    val adbForwarding: AdbForwardingTargets
        get() = MissingAdbForwardingTargets

    /** `adb pull`; [keepAttributes] — `-a`; [compression] — имя из `-z` (`none` — без сжатия). */
    fun adbPull(
        remotePaths: List<String>,
        localName: String?,
        keepAttributes: Boolean,
        compression: String,
        folder: () -> LocalFolder,
    )
    fun adbPush(
        localNames: List<String>,
        picked: PickedFile,
        remotePath: String,
        sync: Boolean,
        dryRun: Boolean,
        compression: String,
    )
    fun adbSync(
        partition: String,
        listOnly: Boolean,
        dryRun: Boolean,
        compression: String,
        productOut: () -> LocalFolder,
    )
    fun adbSideload(origin: () -> ArtifactSource)
    fun adbInstall(origins: List<() -> ArtifactSource>, options: List<String>, several: Boolean)

    /** Ответ приложения о своей связи: `devices`, `get-state`, `status`. */
    fun localReport(tool: TerminalParser.Tool, kind: LocalReportKind): String
}

/** Forward/reverse command surface formerly owned by the removed ADB workspace. */
interface AdbForwardingTargets {
    fun forwardAdd(localPort: Int, remote: String)
    fun forwardRemove(localPort: Int)
    fun forwardList(): String
    fun reverseAdd(remote: String, local: String)
    fun reverseList()
    fun reverseRemoveAll()
}

private object MissingAdbForwardingTargets : AdbForwardingTargets {
    private fun missing(): Nothing = error("adb forwarding target is not wired")
    override fun forwardAdd(localPort: Int, remote: String): Unit = missing()
    override fun forwardRemove(localPort: Int): Unit = missing()
    override fun forwardList(): String = missing()
    override fun reverseAdd(remote: String, local: String): Unit = missing()
    override fun reverseList(): Unit = missing()
    override fun reverseRemoveAll(): Unit = missing()
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
    FASTBOOT,
    ADB_SHELL,
    ADB_TERMINAL,
    ADB_SERVICE,
    ADB_REBOOT,
    ADB_TRANSFER,
    ADB_SIDELOAD,
    ADB_INSTALL,
    ADB_FORWARD,
    ADB_REVERSE,
    ADB_REVERSE_LIST,
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
 * [onStarting] вызывается **до** входа в контроллер. Терминал использует это,
 * чтобы подписать ожидаемый асинхронный ответ до синхронного `remove` или
 * быстрого ответа устройства и не потерять его из-за гонки.
 */
class DesktopCommandExecutor(private val targets: DesktopCommandTargets) {
    fun execute(
        plan: TerminalPlan,
        file: PickedFile?,
        onStarting: (DesktopCommandArea) -> Unit = {},
    ): DesktopCommandStart {
        val first = plan.steps.first()
        return immediate(first, plan, file) ?: start(first, plan, file, onStarting)
    }

    private fun immediate(
        first: TerminalPlanStep,
        plan: TerminalPlan,
        file: PickedFile?,
    ): DesktopCommandStart? = when {
        first is TerminalPlanStep.LocalReport ->
            DesktopCommandStart.Answered(targets.localReport(first.tool, first.kind))
        first is AdbForwardListStep -> DesktopCommandStart.Answered(targets.adbForwarding.forwardList())
        else -> fileProblem(plan, file)
    }

    private fun start(
        first: TerminalPlanStep,
        plan: TerminalPlan,
        file: PickedFile?,
        onStarting: (DesktopCommandArea) -> Unit,
    ): DesktopCommandStart {
        val area = if (first.isFastboot()) DesktopCommandArea.FASTBOOT else adbArea(first)
        onStarting(area)
        if (first.isFastboot()) fastboot(plan.steps, file) else adb(first, file)
        return DesktopCommandStart.Started(area)
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

    private fun fastboot(steps: List<TerminalPlanStep>, file: PickedFile?) {
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
            is TerminalPlanStep.FastbootFetch, is TerminalPlanStep.FastbootGetStaged ->
                targets.fastbootReceive(first, file.create())
            // `stage`: тот же `download:`, что под `flash`, только без команды следом.
            is TerminalPlanStep.FastbootStage -> targets.fastbootDownloadThen(emptyList(), null, null, file.open())
            is TerminalPlanStep.FastbootGetvar -> targets.fastbootVariable(first.variable)
            TerminalPlanStep.FastbootGetvarAll -> targets.fastbootVariable(null)
            is TerminalPlanStep.FastbootFlashAll -> targets.fastbootFlashAll(first, checkNotNull(file))
            is TerminalPlanStep.FastbootFlashVendorRamdisk ->
                targets.fastbootVendorRamdisk(first.ramdisk, first.slot, first.sparseLimit, file.open())
            is TerminalPlanStep.FastbootWipeSuper ->
                targets.fastbootWipeSuper(first.slot, first.sparseLimit, file.open())
            is TerminalPlanStep.FastbootFormat, is TerminalPlanStep.FastbootWipe -> targets.fastbootFormat(first)
            // Слот уточняется на устройстве — это тоже план.
            else -> if (wires.size == 1 && slot == null) {
                targets.fastbootCommand(wires.single())
            } else {
                targets.fastbootPlan(wires, slot)
            }
        }
    }

    private fun adbArea(step: TerminalPlanStep): DesktopCommandArea = when (step) {
        is TerminalPlanStep.AdbShellCommand -> DesktopCommandArea.ADB_SHELL
        is TerminalPlanStep.AdbInteractiveShell -> DesktopCommandArea.ADB_TERMINAL
        is TerminalPlanStep.AdbService -> DesktopCommandArea.ADB_SERVICE
        is TerminalPlanStep.AdbReboot -> DesktopCommandArea.ADB_REBOOT
        is TerminalPlanStep.AdbPull, is TerminalPlanStep.AdbSync, is TerminalPlanStep.AdbPush ->
            DesktopCommandArea.ADB_TRANSFER
        is TerminalPlanStep.AdbSideload -> DesktopCommandArea.ADB_SIDELOAD
        is TerminalPlanStep.AdbInstall -> DesktopCommandArea.ADB_INSTALL
        is AdbForwardAddStep, is AdbForwardRemoveStep -> DesktopCommandArea.ADB_FORWARD
        is AdbReverseAddStep -> DesktopCommandArea.ADB_REVERSE
        AdbReverseRemoveAllStep -> DesktopCommandArea.ADB_REVERSE
        AdbReverseListStep -> DesktopCommandArea.ADB_REVERSE_LIST
        else -> error("Fastboot step in an ADB plan: $step")
    }

    private fun adb(step: TerminalPlanStep, file: PickedFile?) {
        when (step) {
            is TerminalPlanStep.AdbShellCommand -> targets.adbShell(step.command)
            is TerminalPlanStep.AdbInteractiveShell -> targets.adbInteractiveShell(step.firstLine, step.mode)
            is TerminalPlanStep.AdbService -> targets.adbService(step.service)
            is TerminalPlanStep.AdbReboot -> targets.adbReboot(step.target)
            is TerminalPlanStep.AdbPull -> {
                val folder = (file as PickedFile.Folder).folder
                targets.adbPull(step.remotePaths, step.localName, step.preserveAttributes, step.compression, folder)
            }
            is TerminalPlanStep.AdbSync -> {
                val folder = (file as PickedFile.Folder).folder
                targets.adbSync(step.partition, step.listOnly, step.dryRun, step.compression, folder)
            }
            is TerminalPlanStep.AdbPush -> {
                val picked = checkNotNull(file)
                targets.adbPush(step.localNames, picked, step.remotePath, step.sync, step.dryRun, step.compression)
            }
            is TerminalPlanStep.AdbSideload -> targets.adbSideload(file.open())
            is TerminalPlanStep.AdbInstall -> {
                val origins = (file as? PickedFile.OpenMany)?.origins ?: listOf(file.open())
                targets.adbInstall(origins, step.options, step.several)
            }
            is AdbForwardAddStep -> targets.adbForwarding.forwardAdd(step.localPort, step.remote)
            is AdbForwardRemoveStep -> targets.adbForwarding.forwardRemove(step.localPort)
            is AdbReverseAddStep -> targets.adbForwarding.reverseAdd(step.remote, step.local)
            AdbReverseListStep -> targets.adbForwarding.reverseList()
            AdbReverseRemoveAllStep -> targets.adbForwarding.reverseRemoveAll()
            else -> error("Fastboot step in an ADB plan: $step")
        }
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
    is TerminalPlanStep.FastbootStage,
    is TerminalPlanStep.FastbootGetStaged,
    is TerminalPlanStep.FastbootFlashAll,
    is TerminalPlanStep.FastbootWipeSuper,
    is TerminalPlanStep.FastbootFlashVendorRamdisk,
    is TerminalPlanStep.FastbootFormat,
    is TerminalPlanStep.FastbootWipe,
    -> true

    else -> false
}

private fun PickedFile?.open(): () -> ArtifactSource = (this as PickedFile.Open).origin

/** Источник с правленым заголовком vbmeta, если флаги заданы. */
private fun (() -> ArtifactSource).withVbmetaFlags(flags: Int): () -> ArtifactSource =
    if (flags == 0) this else ({ VbmetaFlagsSource(this(), flags) })

private fun PickedFile?.create(): () -> ArtifactSink = (this as PickedFile.Create).destination
