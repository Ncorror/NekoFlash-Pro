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
        val main = steps(command)?.let { withSlot(it, options.slot) }?.let { withVbmetaFlags(it, options) }
        // Без слота `--set-active` — «другой» слот: его уточнит устройство по
        // `current-slot` в той же операции, до первой команды.
        val activate = when {
            options.setActiveSlot != null -> TerminalPlanStep.FastbootWire("set_active:${options.setActiveSlot}")
            options.setActiveRequested -> TerminalPlanStep.FastbootWire(SET_ACTIVE_OTHER)
            else -> null
        }
        val blocker = unsupportedOption(options) ?: slotBlocker(command, options.slot)
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
    private fun unsupportedOption(options: FastbootOptions): String? = when {
        options.wipe -> "fastboot -w"
        options.sparseLimit != null -> "fastboot -S"
        options.force -> "fastboot --force"
        options.skipReboot -> "fastboot --skip-reboot"
        options.skipSecondary -> "fastboot --skip-secondary"
        options.serial != null -> "fastboot -s"
        options.verbose -> "fastboot -v"
        else -> null
    }

    /**
     * `--slot` уточняет имя раздела у `flash` и `erase`; имя уточняется на
     * устройстве по `has-slot`, до отправки первой команды.
     *
     * `--slot all` для `flash` означало бы две записи одного буфера, а
     * загрузчик вправе очистить его после первой: настольный клиент грузит
     * образ заново, здесь это пока не сделано.
     */
    private fun slotBlocker(command: FastbootCommand, slot: String?): String? = when {
        slot == null -> null
        command is FastbootCommand.Erase -> null
        command is FastbootCommand.Flash && !slot.equals(ALL, ignoreCase = true) -> null
        command is FastbootCommand.Flash -> "fastboot --slot all flash"
        else -> "fastboot --slot"
    }

    private fun withSlot(steps: List<TerminalPlanStep>, slot: String?): List<TerminalPlanStep> =
        if (slot == null) {
            steps
        } else {
            steps.map { step ->
                when (step) {
                    is TerminalPlanStep.FastbootFlashImage -> step.copy(slot = slot)
                    is TerminalPlanStep.FastbootWire -> step.copy(slot = slot)
                    else -> step
                }
            }
        }

    /**
     * `--disable-verity` и `--disable-verification` — биты в заголовке образа
     * vbmeta, который прошивается. К другой команде им не к чему применяться.
     */
    private fun vbmetaBlocker(command: FastbootCommand, options: FastbootOptions): String? = when {
        !options.disableVerity && !options.disableVerification -> null
        command is FastbootCommand.Flash && command.partition.startsWith(VBMETA) -> null
        options.disableVerity -> "fastboot --disable-verity"
        else -> "fastboot --disable-verification"
    }

    private fun withVbmetaFlags(steps: List<TerminalPlanStep>, options: FastbootOptions): List<TerminalPlanStep> {
        val flags = (if (options.disableVerity) DISABLE_VERITY else 0) or
            (if (options.disableVerification) DISABLE_VERIFICATION else 0)
        return steps.map { step ->
            if (step is TerminalPlanStep.FastbootFlashImage && flags != 0) step.copy(vbmetaFlags = flags) else step
        }
    }

    private fun steps(command: FastbootCommand): List<TerminalPlanStep>? = when (command) {
        is FastbootCommand.Flash -> listOf(TerminalPlanStep.FastbootFlashImage(command.partition, command.image))
        is FastbootCommand.Erase -> listOf(TerminalPlanStep.FastbootWire("erase:${command.partition}"))
        is FastbootCommand.Boot -> command.takeIf { it.ramdisk == null && it.second == null }
            ?.let { listOf(TerminalPlanStep.FastbootBootImage(it.kernel)) }
        is FastbootCommand.Fetch -> listOf(TerminalPlanStep.FastbootFetch(command.partition, command.destination))
        is FastbootCommand.SetActive -> listOf(TerminalPlanStep.FastbootWire("set_active:${command.slot ?: OTHER}"))
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
        else -> null
    }

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
        FastbootCommand.LocalStatus -> "status"
        is FastbootCommand.Devices -> "devices"
        else -> command.toString()
    }

    private const val ALL = "all"
    private const val OTHER = "other"
    private const val VBMETA = "vbmeta"
    private const val DISABLE_VERITY = 1
    private const val DISABLE_VERIFICATION = 2
    private const val IS_LOGICAL = "is-logical"
}
