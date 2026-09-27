package com.ncorror.nekoflash.pro.engine

/**
 * Один шаг плана — одна операция движка, который уже есть в приложении.
 *
 * Шаг не знает, «опасен» ли он: это решает устройство (`01` §3). Он знает
 * только, чем исполняется и нужен ли ему файл, выбранный через Android: путь из
 * команды никогда не открывается напрямую (`04` §3, правило 7).
 */
sealed interface TerminalPlanStep {
    /** Fastboot-команда как есть — тем же входом, что и поле произвольной команды. */
    data class FastbootWire(val command: String, val slot: String? = null) : TerminalPlanStep

    /** Одна переменная по имени, любая: списка разрешённых имён нет. */
    data class FastbootGetvar(val variable: String) : TerminalPlanStep

    /** `getvar all` — весь список, который готово назвать устройство. */
    data object FastbootGetvarAll : TerminalPlanStep

    /** `download` выбранного файла и `flash:PARTITION` одной операцией. */
    data class FastbootFlashImage(
        val partition: String,
        val imageName: String?,
        val slot: String? = null,
        /** Биты `flags` заголовка vbmeta: `--disable-verity` = 1, `--disable-verification` = 2. */
        val vbmetaFlags: Int = 0,
    ) : TerminalPlanStep

    /** `download` выбранного файла и `boot`. */
    data class FastbootBootImage(val imageName: String) : TerminalPlanStep

    /** `fetch:PARTITION` в место, выбранное через Android. */
    data class FastbootFetch(val partition: String, val destinationName: String) : TerminalPlanStep

    /** Одиночная команда оболочки: вывод и код выхода. */
    data class AdbShellCommand(val command: String) : TerminalPlanStep

    /**
     * Интерактивная оболочка терминала; [firstLine] уходит в неё сразу после
     * открытия — так исполняются `adb logcat` и `adb shell -t КОМАНДА`.
     */
    data class AdbInteractiveShell(val firstLine: String? = null) : TerminalPlanStep

    /** Произвольный сервис ADB без подмены: `exec:`, `root:`, `remount:` и прочие. */
    data class AdbService(val service: String) : TerminalPlanStep

    /** `reboot:TARGET`, с разбором ухода устройства. */
    data class AdbReboot(val target: String) : TerminalPlanStep

    /** Файл устройства в место, выбранное через Android. */
    data class AdbPull(val remotePath: String, val localName: String) : TerminalPlanStep

    /** Файл, выбранный через Android, на устройство. */
    data class AdbPush(val localName: String, val remotePath: String) : TerminalPlanStep

    /** Sideload выбранного архива. */
    data class AdbSideload(val archiveName: String) : TerminalPlanStep

    /**
     * Установка пакетов: одиночная `install` или сессия `install-multiple`.
     * [options] уходят `package` как есть — это его флаги, не наши.
     */
    data class AdbInstall(val names: List<String>, val options: List<String>, val several: Boolean) :
        TerminalPlanStep
}

/**
 * `set_active` на «другой» слот: противоположный `current-slot`, который
 * уточняет исполнитель на устройстве до отправки.
 */
const val SET_ACTIVE_OTHER: String = "set_active:other"

/** Какой файл нужен шагу и в какую сторону он идёт. */
enum class TerminalFileNeed { NONE, OPEN, OPEN_MANY, CREATE }

/** Нужен ли шагу файл, выбранный через Android. */
val TerminalPlanStep.fileNeed: TerminalFileNeed
    get() = when (this) {
        is TerminalPlanStep.FastbootFlashImage,
        is TerminalPlanStep.FastbootBootImage,
        is TerminalPlanStep.AdbPush,
        is TerminalPlanStep.AdbSideload,
        -> TerminalFileNeed.OPEN

        is TerminalPlanStep.FastbootFetch,
        is TerminalPlanStep.AdbPull,
        -> TerminalFileNeed.CREATE

        is TerminalPlanStep.AdbInstall -> if (several) TerminalFileNeed.OPEN_MANY else TerminalFileNeed.OPEN

        else -> TerminalFileNeed.NONE
    }

/**
 * Меняет ли шаг содержимое раздела.
 *
 * Не разрешение и не запрет: по этому признаку интерфейс решает только, какую
 * форму подтверждения показать (ADR-0002). Команда уходит устройству без
 * изменений в любом случае.
 */
val TerminalPlanStep.writesPartition: Boolean
    get() = when (this) {
        is TerminalPlanStep.FastbootFlashImage -> true
        is TerminalPlanStep.FastbootWire -> PARTITION_WRITES.any { command.startsWith(it) }
        else -> false
    }

private val PARTITION_WRITES = listOf("flash:", "erase:", "format:")

/**
 * План — ровно то, что будет исполнено.
 *
 * Preview строится из этих же шагов (`04` §3, правило 6): показать одно, а
 * исполнить другое здесь негде.
 */
class TerminalPlan(steps: List<TerminalPlanStep>) {
    val steps: List<TerminalPlanStep> = steps.toList()

    init {
        require(this.steps.isNotEmpty()) { "A terminal plan must have at least one step" }
        require(this.steps.count { it.fileNeed != TerminalFileNeed.NONE } <= 1) {
            "A terminal plan binds at most one picked file"
        }
    }

    /** Шаг, которому нужен выбранный файл, если такой есть. */
    val fileStep: TerminalPlanStep?
        get() = steps.firstOrNull { it.fileNeed != TerminalFileNeed.NONE }
}

sealed interface TerminalPlanResult {
    data class Ready(val plan: TerminalPlan) : TerminalPlanResult

    /** Строка не разобрана: ошибка ввода, до устройства дело не дошло. */
    data class Invalid(val reason: String) : TerminalPlanResult

    /**
     * Команда или опция известна, а исполнителя у неё пока нет.
     *
     * Честное «не реализовано» до отправки (`04` §3, правила 2–3), а не тихая
     * подмена другой командой.
     */
    data class NotImplemented(val what: String) : TerminalPlanResult
}

/**
 * Превращает разобранную команду настольного клиента в план.
 *
 * Разбор — не доказательство, что исполнитель есть: известная команда без
 * исполнителя получает [TerminalPlanResult.NotImplemented], а не ближайшую
 * похожую операцию.
 */
class TerminalPlanCompiler(private val parser: TerminalParser = TerminalParser()) {
    fun compile(line: String): TerminalPlanResult = compile(parser.parse(line))

    fun compile(tool: TerminalParser.Tool, line: String): TerminalPlanResult = compile(parser.parse(tool, line))

    fun compile(parsed: TerminalParseResult): TerminalPlanResult = when (parsed) {
        is TerminalParseResult.Parsed -> compile(parsed.invocation)
        is TerminalParseResult.Invalid -> TerminalPlanResult.Invalid(parsed.reason)
        is TerminalParseResult.Unsupported -> TerminalPlanResult.NotImplemented("${parsed.tool} ${parsed.command}")
    }

    fun compile(invocation: TerminalInvocation): TerminalPlanResult = when (invocation) {
        is TerminalInvocation.Adb -> AdbPlans.compile(invocation.command)
        is TerminalInvocation.Fastboot -> FastbootPlans.compile(invocation.options, invocation.command)
    }
}

/** План из одного шага — самый частый случай. */
internal fun single(step: TerminalPlanStep): TerminalPlanResult = TerminalPlanResult.Ready(TerminalPlan(listOf(step)))

internal fun notImplemented(what: String): TerminalPlanResult = TerminalPlanResult.NotImplemented(what)
