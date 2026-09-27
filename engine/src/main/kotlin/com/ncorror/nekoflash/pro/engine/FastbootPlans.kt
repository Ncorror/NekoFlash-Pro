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
        val main = steps(command)
        val activate = options.setActiveSlot?.let { slot -> TerminalPlanStep.FastbootWire("set_active:$slot") }
        val blocker = unsupportedOption(options) ?: hostScenario(command).takeIf { main == null }
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
        options.setActiveRequested && options.setActiveSlot == null -> "fastboot --set-active"
        options.slot != null -> "fastboot --slot"
        options.wipe -> "fastboot -w"
        options.sparseLimit != null -> "fastboot -S"
        options.force -> "fastboot --force"
        options.skipReboot -> "fastboot --skip-reboot"
        options.skipSecondary -> "fastboot --skip-secondary"
        options.disableVerity -> "fastboot --disable-verity"
        options.disableVerification -> "fastboot --disable-verification"
        options.serial != null -> "fastboot -s"
        options.verbose -> "fastboot -v"
        else -> null
    }

    private fun steps(command: FastbootCommand): List<TerminalPlanStep>? = when (command) {
        is FastbootCommand.Flash -> listOf(TerminalPlanStep.FastbootFlashImage(command.partition, command.image))
        is FastbootCommand.Erase -> listOf(TerminalPlanStep.FastbootWire("erase:${command.partition}"))
        is FastbootCommand.Boot -> command.takeIf { it.ramdisk == null && it.second == null }
            ?.let { listOf(TerminalPlanStep.FastbootBootImage(it.kernel)) }
        is FastbootCommand.Fetch -> listOf(TerminalPlanStep.FastbootFetch(command.partition, command.destination))
        is FastbootCommand.SetActive -> command.slot?.let { listOf(TerminalPlanStep.FastbootWire("set_active:$it")) }
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
    private const val IS_LOGICAL = "is-logical"
}
