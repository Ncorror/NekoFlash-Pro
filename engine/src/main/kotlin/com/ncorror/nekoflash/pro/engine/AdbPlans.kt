package com.ncorror.nekoflash.pro.engine

/**
 * Команды настольного `adb` → шаги плана.
 *
 * Сервис уходит тот же, что отправил бы настольный клиент: `root:`, `remount:`,
 * `exec:cmd package uninstall`. Опции, которые меняли бы поведение, но которых
 * здесь нечем исполнить, дают «не реализовано», а не молча пропадают.
 */
internal object AdbPlans {
    fun compile(command: AdbCommand): TerminalPlanResult = when (command) {
        is AdbCommand.Shell -> shell(command)
        is AdbCommand.Exec -> single(TerminalPlanStep.AdbService("exec:${command.command}"))
        is AdbCommand.Service -> service(command.name)
        is AdbCommand.Remount ->
            single(TerminalPlanStep.AdbService(if (command.rebootIfNeeded) "remount:-R" else "remount:"))
        is AdbCommand.Pull -> pull(command)
        is AdbCommand.Push -> push(command)
        is AdbCommand.Sideload -> single(TerminalPlanStep.AdbSideload(command.archive))
        is AdbCommand.Uninstall -> single(
            TerminalPlanStep.AdbService(
                "exec:cmd package uninstall " + (if (command.keepData) "-k " else "") + command.packageName,
            ),
        )
        // `logcat` без `-d` не кончается: он идёт в живой оболочке, где есть
        // Ctrl+C и окно вывода, как у настольного клиента.
        is AdbCommand.Logcat -> single(
            TerminalPlanStep.AdbInteractiveShell(
                (listOf("logcat") + command.arguments.map(::shellWord)).joinToString(" "),
            ),
        )
        is AdbCommand.Sync -> notImplemented("adb sync")
        is AdbCommand.Install -> notImplemented("adb install")
        is AdbCommand.InstallMultiple -> notImplemented("adb install-multiple")
        AdbCommand.LocalStatus -> notImplemented("adb status")
        is AdbCommand.Devices -> notImplemented("adb devices")
        AdbCommand.GetState -> notImplemented("adb get-state")
    }

    private fun shell(command: AdbCommand.Shell): TerminalPlanResult = when {
        command.noExitCode -> notImplemented("adb shell -x")
        command.tty == NO_TTY -> notImplemented("adb shell $NO_TTY")
        // Терминал и есть PTY: `-t` значит «в живой оболочке».
        command.tty != null || command.command.isEmpty() ->
            single(TerminalPlanStep.AdbInteractiveShell(command.command.ifEmpty { null }))
        else -> single(TerminalPlanStep.AdbShellCommand(command.command))
    }

    /** Аргумент для строки оболочки: простой — как есть, прочий — в одинарных кавычках. */
    private fun shellWord(word: String): String =
        if (word.matches(PLAIN_WORD)) word else "'" + word.replace("'", "'\\''") + "'"

    /** `reboot:` разбирается отдельно: после него устройство уходит, и это ожидаемо. */
    private fun service(name: String): TerminalPlanResult =
        if (name.startsWith(REBOOT)) {
            single(TerminalPlanStep.AdbReboot(name.removePrefix(REBOOT)))
        } else {
            single(TerminalPlanStep.AdbService(name))
        }

    private fun pull(command: AdbCommand.Pull): TerminalPlanResult = when {
        command.options != FileTransferOptions() -> notImplemented("adb pull OPTIONS")
        command.sources.size != 1 -> notImplemented("adb pull SEVERAL")
        else -> single(
            TerminalPlanStep.AdbPull(
                remotePath = command.sources.single(),
                localName = command.destination.takeUnless { it == "." }
                    ?: command.sources.single().substringAfterLast('/'),
            ),
        )
    }

    private fun push(command: AdbCommand.Push): TerminalPlanResult = when {
        command.options != FileTransferOptions() -> notImplemented("adb push OPTIONS")
        command.sources.size != 1 -> notImplemented("adb push SEVERAL")
        else -> {
            val source = command.sources.single()
            // Каталог назначения, названный с косой чертой, получает имя файла,
            // как у настольного клиента. Без черты путь — это путь файла:
            // проверить, каталог ли это, можно только на устройстве.
            val remote = command.destination.let { if (it.endsWith('/')) it + source.substringAfterLast('/') else it }
            single(TerminalPlanStep.AdbPush(source, remote))
        }
    }

    private const val REBOOT = "reboot:"
    private const val NO_TTY = "-T"
    private val PLAIN_WORD = Regex("""[A-Za-z0-9_./:=*+@%,-]+""")
}
