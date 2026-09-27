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
        is AdbCommand.Install -> install(listOf(command.packagePath), command.options, several = false)
        is AdbCommand.InstallMultiple -> install(command.packages, command.options, several = true)
        AdbCommand.LocalStatus -> notImplemented("adb status")
        is AdbCommand.Devices -> notImplemented("adb devices")
        AdbCommand.GetState -> notImplemented("adb get-state")
    }

    private fun shell(command: AdbCommand.Shell): TerminalPlanResult = when {
        // `-x` — без протокола shell v2 и кода выхода: это старый `shell:`.
        command.noExitCode && command.command.isNotEmpty() ->
            single(TerminalPlanStep.AdbService("shell:${command.command}"))
        command.noExitCode -> notImplemented("adb shell -x")
        // `-T` — без PTY: так и так идёт разовая команда.
        command.tty == NO_TTY && command.command.isNotEmpty() ->
            single(TerminalPlanStep.AdbShellCommand(command.command))
        command.tty == NO_TTY -> notImplemented("adb shell $NO_TTY")
        // Терминал и есть PTY: `-t` значит «в живой оболочке».
        command.tty != null || command.command.isEmpty() ->
            single(TerminalPlanStep.AdbInteractiveShell(command.command.ifEmpty { null }))
        else -> single(TerminalPlanStep.AdbShellCommand(command.command))
    }

    /** Аргумент для строки оболочки: простой — как есть, прочий — в одинарных кавычках. */
    private fun shellWord(word: String): String =
        if (word.matches(PLAIN_WORD)) word else "'" + word.replace("'", "'\\''") + "'"

    /**
     * Флаги установки.
     *
     * Флаги `package` уходят ему как есть, слитые короткие разделяются: `-rg`
     * настольный клиент понимает, а `package` ждёт `-r -g`. `--streaming` —
     * это и есть наш способ, он ничего не меняет. Флаги, которые у настольного
     * клиента означают другой путь установки (`--no-streaming`, fastdeploy,
     * агент), здесь исполнить нечем.
     */
    private fun install(names: List<String>, options: List<String>, several: Boolean): TerminalPlanResult {
        val unknown = options.firstOrNull { it.startsWith("--") && it !in PACKAGE_LONG_FLAGS && it != STREAMING }
        val flags = options.filter { it != STREAMING }.flatMap { flag ->
            if (flag.matches(SHORT_FLAGS)) flag.drop(1).map { "-$it" } else listOf(flag)
        }
        return if (unknown != null) {
            notImplemented("adb ${if (several) "install-multiple" else "install"} $unknown")
        } else {
            single(TerminalPlanStep.AdbInstall(names, flags, several))
        }
    }

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
    private const val STREAMING = "--streaming"
    private val PACKAGE_LONG_FLAGS = setOf("--instant", "--abi")
    private val SHORT_FLAGS = Regex("""-[lrtsdpg]{2,}""")
    private val PLAIN_WORD = Regex("""[A-Za-z0-9_./:=*+@%,-]+""")
}
