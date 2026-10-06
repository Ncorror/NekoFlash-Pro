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
        is AdbForwardCommand -> forward(command.request)
        is AdbReverseCommand -> reverse(command.request)
        // `logcat` без `-d` не кончается: он идёт в живой оболочке, где есть
        // Ctrl+C и окно вывода, как у настольного клиента.
        is AdbCommand.Logcat -> single(
            TerminalPlanStep.AdbInteractiveShell(
                (listOf("logcat") + command.arguments.map(::shellWord)).joinToString(" "),
            ),
        )
        is AdbCommand.Sync -> single(
            TerminalPlanStep.AdbSync(
                command.partition ?: "all",
                command.listOnly,
                command.options.dryRun,
                command.options.compression ?: ANY,
            ),
        )
        is AdbCommand.Install -> install(listOf(command.packagePath), command.options, several = false)
        is AdbCommand.InstallMultiple -> install(command.packages, command.options, several = true)
        AdbCommand.LocalStatus -> report(LocalReportKind.STATUS)
        is AdbCommand.Devices ->
            report(if (command.longFormat) LocalReportKind.DEVICES_LONG else LocalReportKind.DEVICES)
        AdbCommand.GetState -> report(LocalReportKind.GET_STATE)
    }

    private fun shell(command: AdbCommand.Shell): TerminalPlanResult = when {
        // `-x` — без протокола shell v2 и кода выхода: это старый `shell:`.
        command.noExitCode && command.command.isNotEmpty() ->
            single(TerminalPlanStep.AdbService("shell:${command.command}"))
        command.noExitCode -> single(TerminalPlanStep.AdbInteractiveShell(mode = ShellMode.LEGACY))
        // `-T` — без PTY: так и так идёт разовая команда.
        command.tty == NO_TTY && command.command.isNotEmpty() ->
            single(TerminalPlanStep.AdbShellCommand(command.command))
        command.tty == NO_TTY -> single(TerminalPlanStep.AdbInteractiveShell(mode = ShellMode.RAW))
        // Терминал и есть PTY: `-t` значит «в живой оболочке».
        command.tty != null || command.command.isEmpty() ->
            single(TerminalPlanStep.AdbInteractiveShell(command.command.ifEmpty { null }))
        else -> single(TerminalPlanStep.AdbShellCommand(command.command))
    }

    private fun forward(request: AdbForwardRequest): TerminalPlanResult = when (request) {
        AdbForwardRequest.List -> single(AdbForwardListStep)
        AdbForwardRequest.RemoveAll -> single(AdbForwardRemoveAllStep)
        is AdbForwardRequest.Remove -> hostTcpPort(request.local)?.let { port ->
            single(AdbForwardRemoveStep(port))
        } ?: notImplemented("adb forward --remove ${request.local}")
        is AdbForwardRequest.Add -> hostTcpPort(request.local)?.let { port ->
            single(AdbForwardAddStep(port, request.remote, request.noRebind))
        } ?: notImplemented("adb forward ${request.local} ${request.remote}")
    }

    private fun reverse(request: AdbReverseRequest): TerminalPlanResult = when (request) {
        AdbReverseRequest.List -> single(AdbReverseListStep)
        AdbReverseRequest.RemoveAll -> single(AdbReverseRemoveAllStep)
        is AdbReverseRequest.Remove -> single(AdbReverseRemoveStep(request.remote))
        is AdbReverseRequest.Add -> single(AdbReverseAddStep(request.remote, request.local, request.noRebind))
    }

    /**
     * Текущий host forward владеет loopback TCP listener. Остальные формы
     * распознаны парсером, но не притворяются рабочими до появления host-side
     * Unix/JDWP listener implementation.
     */
    private fun hostTcpPort(spec: String): Int? {
        val port = spec.takeIf { it.startsWith(TCP) }?.removePrefix(TCP)?.toIntOrNull() ?: return null
        return port.takeIf { it in HOST_PORTS }
    }

    private fun report(kind: LocalReportKind): TerminalPlanResult =
        single(TerminalPlanStep.LocalReport(TerminalParser.Tool.ADB, kind))

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

    /** Куда сохранять — по правилам `do_sync_pull`; путь назначения стал папкой и именем в ней. */
    private fun pull(command: AdbCommand.Pull): TerminalPlanResult {
        val destination = command.destination
        val intoFolder = destination == "." || destination == "./" || destination.endsWith('/')
        val name = if (intoFolder) null else destination.substringAfterLast('/')
        return single(
            TerminalPlanStep.AdbPull(
                command.sources,
                name,
                command.options.preserveAttributes,
                command.options.compression ?: NONE,
            ),
        )
    }

    /** Имена на устройстве строит исполнитель: каталог ли назначение, знает только устройство. */
    private fun push(command: AdbCommand.Push): TerminalPlanResult =
        single(
            TerminalPlanStep.AdbPush(
                command.sources,
                command.destination,
                command.options.syncOnly,
                command.options.dryRun,
                command.options.compression ?: ANY,
            ),
        )

    /** Умолчания Android 14: `push` и `sync` сжимают (`any`), `pull` — нет. */
    private const val ANY = "any"
    private const val NONE = "none"
    private const val REBOOT = "reboot:"
    private const val NO_TTY = "-T"
    private const val STREAMING = "--streaming"
    private const val TCP = "tcp:"
    private const val MAX_TCP_PORT = 65_535
    private val HOST_PORTS = 0..MAX_TCP_PORT
    private val PACKAGE_LONG_FLAGS = setOf("--instant", "--abi")
    private val SHORT_FLAGS = Regex("""-[lrtsdpg]{2,}""")
    private val PLAIN_WORD = Regex("""[A-Za-z0-9_./:=*+@%,-]+""")
}
