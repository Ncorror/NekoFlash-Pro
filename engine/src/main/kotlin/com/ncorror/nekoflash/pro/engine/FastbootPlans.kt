package com.ncorror.nekoflash.pro.engine

/**
 * Команды настольного `fastboot` → шаги плана.
 *
 * Опция либо действует, либо команда кончается «не реализовано» до отправки
 * (`04` §3, правило 2). Сценарии хоста, которые требуют собрать что-то на
 * телефоне — `format`, `-w`, `wipe-super`, `update`, `flashall`, перепаковка
 * `vendor_boot`, — отправлять как wire-команду нельзя: устройство поняло бы
 * их иначе, чем настольный клиент.
 */
internal object FastbootPlans {
    fun compile(options: FastbootOptions, command: FastbootCommand): TerminalPlanResult {
        val slot = options.slot ?: CURRENT_SLOT
        val main = steps(command)?.let { withSlot(it, command, slot) }?.let { withImageOptions(it, options) }
        // Без значения `--set-active` — слот из `--slot`, а без него текущий:
        // так решает `fastboot.cpp`. Уточняется на устройстве, до первой команды.
        val activate = if (options.setActiveRequested) {
            TerminalPlanStep.FastbootWire("set_active:${options.setActiveSlot.orEmpty()}", slot)
        } else {
            null
        }
        val blocker = unsupportedOption(options, command) ?: slotBlocker(command, options.slot)
            ?: sparseBlocker(command, options)
            ?: vbmetaBlocker(command, options)
            ?: hostScenario(command).takeIf { main == null }
        return when {
            blocker != null -> notImplemented(blocker)
            // `set_active` идёт следом одной операцией полосы; у чтения
            // переменной и `fetch` такого продолжения нет, и молча его
            // потерять нельзя.
            activate != null && !main.orEmpty().first().takesFollowUps() -> notImplemented("fastboot --set-active")
            else -> TerminalPlanResult.Ready(TerminalPlan(main.orEmpty() + listOfNotNull(activate)))
        }
    }

    private fun TerminalPlanStep.takesFollowUps(): Boolean = this is TerminalPlanStep.FastbootWire ||
        this is TerminalPlanStep.FastbootFlashImage || this is TerminalPlanStep.FastbootBootImage

    /**
     * Первая опция, которой пока нечем исполниться.
     *
     * `--set-active=СЛОТ` исполняется: это `set_active` после основной команды,
     * как у настольного клиента. `--set-active` без слота значит «другой слот»
     * и требует знать текущий, а `--slot` — знать, у каких разделов слоты есть.
     */
    private fun unsupportedOption(options: FastbootOptions, command: FastbootCommand): String? = when {
        options.wipe -> "fastboot -w"
        command.flashesAll() -> null
        options.force -> "fastboot --force"
        options.skipReboot -> "fastboot --skip-reboot"
        options.skipSecondary -> "fastboot --skip-secondary"
        options.serial != null -> "fastboot -s"
        options.verbose -> "fastboot -v"
        else -> null
    }

    /**
     * `--slot` уточняет имя раздела у `flash`, `erase`, `fetch` и слот
     * `set_active`; имя уточняется на устройстве по `slot-count`, `has-slot` и
     * `current-slot`, до отправки первой команды.
     *
     * `--slot all` у `fetch` настольный клиент пишет оба раздела подряд в один
     * файл; здесь такого исполнителя нет.
     */
    private fun slotBlocker(command: FastbootCommand, slot: String?): String? = when {
        slot == null -> null
        command is FastbootCommand.Fetch && slot == ALL -> "fastboot --slot all fetch"
        command.takesSlot() -> null
        else -> "fastboot --slot"
    }

    /** `flashall` и `update` — `--force`, `--skip-*`, `-S`, флаги vbmeta у них свои. */
    private fun FastbootCommand.flashesAll(): Boolean =
        this == FastbootCommand.FlashAll || this is FastbootCommand.Update

    private fun FastbootCommand.takesSlot(): Boolean = when (this) {
        FastbootCommand.FlashAll,
        is FastbootCommand.Update,
        is FastbootCommand.Flash,
        is FastbootCommand.Erase,
        is FastbootCommand.Fetch,
        is FastbootCommand.SetActive,
        -> true
        else -> false
    }

    /** `-S` режет образ для `flash`; у прочих команд ему не к чему применяться. */
    private fun sparseBlocker(command: FastbootCommand, options: FastbootOptions): String? =
        "fastboot -S".takeIf {
            options.sparseLimit != null && command !is FastbootCommand.Flash && !command.flashesAll()
        }

    /**
     * Слот — у команд, которые настольный клиент пропускает через
     * `do_for_partitions` и `verify_slot`: `flash`, `erase`, `fetch`,
     * `set_active`. `raw` и `oem` уходят как набраны, без слота.
     */
    private fun withSlot(
        steps: List<TerminalPlanStep>,
        command: FastbootCommand,
        slot: String,
    ): List<TerminalPlanStep> =
        if (command.takesSlot()) {
            steps.map { step ->
                when (step) {
                    is TerminalPlanStep.FastbootFlashImage -> step.copy(slot = slot)
                    is TerminalPlanStep.FastbootWire -> step.copy(slot = slot)
                    is TerminalPlanStep.FastbootFetch -> step.copy(slot = slot)
                    is TerminalPlanStep.FastbootFlashAll -> step.copy(slot = slot)
                    else -> step
                }
            }
        } else {
            steps
        }

    /**
     * `--disable-verity` и `--disable-verification` — биты в заголовке образа
     * vbmeta, который прошивается. К другой команде им не к чему применяться.
     */
    private fun vbmetaBlocker(command: FastbootCommand, options: FastbootOptions): String? = when {
        !options.disableVerity && !options.disableVerification -> null
        command is FastbootCommand.Flash && command.partition.startsWith(VBMETA) -> null
        command.flashesAll() -> null
        options.disableVerity -> "fastboot --disable-verity"
        else -> "fastboot --disable-verification"
    }

    private fun withImageOptions(steps: List<TerminalPlanStep>, options: FastbootOptions): List<TerminalPlanStep> {
        val flags = (if (options.disableVerity) DISABLE_VERITY else 0) or
            (if (options.disableVerification) DISABLE_VERIFICATION else 0)
        val sparse = options.sparseLimit?.takeIf { it != 0L }
        return steps.map { step ->
            when (step) {
                is TerminalPlanStep.FastbootFlashImage -> step.copy(vbmetaFlags = flags, sparseLimit = sparse)
                is TerminalPlanStep.FastbootFlashAll -> step.copy(
                    vbmetaFlags = flags,
                    sparseLimit = sparse,
                    force = options.force,
                    skipSecondary = options.skipSecondary,
                    skipReboot = options.skipReboot,
                )
                else -> step
            }
        }
    }

    private fun steps(command: FastbootCommand): List<TerminalPlanStep>? = when (command) {
        is FastbootCommand.Flash -> listOf(TerminalPlanStep.FastbootFlashImage(command.partition, command.image))
        is FastbootCommand.Erase -> listOf(TerminalPlanStep.FastbootWire("erase:${command.partition}"))
        is FastbootCommand.Boot -> command.takeIf { it.ramdisk == null && it.second == null }
            ?.let { listOf(TerminalPlanStep.FastbootBootImage(it.kernel)) }
        is FastbootCommand.Fetch -> listOf(TerminalPlanStep.FastbootFetch(command.partition, command.destination))
        is FastbootCommand.SetActive -> listOf(TerminalPlanStep.FastbootWire("set_active:${command.slot.orEmpty()}"))
        is FastbootCommand.Getvar -> listOf(
            if (command.variable == ALL) {
                TerminalPlanStep.FastbootGetvarAll
            } else {
                TerminalPlanStep.FastbootGetvar(command.variable)
            },
        )
        is FastbootCommand.DeviceQuery -> command.takeIf { it.query == IS_LOGICAL }
            ?.let { listOf(TerminalPlanStep.FastbootGetvar("$IS_LOGICAL:${it.argument}")) }
        is FastbootCommand.Wire -> listOf(TerminalPlanStep.FastbootWire(command.payload))
        FastbootCommand.FlashAll -> listOf(TerminalPlanStep.FastbootFlashAll(archiveName = null))
        is FastbootCommand.Update -> listOf(TerminalPlanStep.FastbootFlashAll(archiveName = command.archive))
        FastbootCommand.LocalStatus -> listOf(report(LocalReportKind.STATUS))
        is FastbootCommand.Devices ->
            listOf(report(if (command.longFormat) LocalReportKind.DEVICES_LONG else LocalReportKind.DEVICES))
        else -> null
    }

    private fun report(kind: LocalReportKind) = TerminalPlanStep.LocalReport(TerminalParser.Tool.FASTBOOT, kind)

    private fun hostScenario(command: FastbootCommand): String = "fastboot " + when (command) {
        is FastbootCommand.FlashVendorBootRamdisk -> "flash vendor_boot:${command.ramdisk}"
        is FastbootCommand.Format -> "format ${command.partition}"
        FastbootCommand.Wipe -> "-w"
        is FastbootCommand.WipeSuper -> "wipe-super"
        is FastbootCommand.Boot -> "boot KERNEL RAMDISK"
        is FastbootCommand.FlashRaw -> "flash:raw"
        is FastbootCommand.Update -> "update"
        FastbootCommand.FlashAll -> "flashall"
        is FastbootCommand.UpdateSuper -> "update-super"
        is FastbootCommand.SetActive -> "--set-active"
        is FastbootCommand.DeviceQuery -> command.query
        else -> command.toString()
    }

    private const val ALL = "all"
    private const val VBMETA = "vbmeta"
    private const val DISABLE_VERITY = 1
    private const val DISABLE_VERIFICATION = 2
    private const val IS_LOGICAL = "is-logical"
}
