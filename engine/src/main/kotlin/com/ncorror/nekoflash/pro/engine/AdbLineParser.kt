package com.ncorror.nekoflash.pro.engine

/**
 * Разбор слов после `adb`.
 *
 * Неизвестный глагол **не** становится `shell` (`04` §3, правило 3): у
 * настольного `adb` нет `adb getprop`, и притворяться, что есть, значило бы
 * отправить устройству то, чего пользователь не писал.
 */
internal class AdbLineParser(private val original: String, private val tokens: List<TerminalToken>) {
    private val verdict = ParseVerdict()
    private val op = tokens[0].value.lowercase()
    private val args = tokens.drop(1).map { it.value }

    fun parse(): TerminalParseResult {
        val handler = HANDLERS[op]
        val command = if (handler == null) verdict.unsupported(TOOL, op) else handler(this)
        return verdict.result(command?.let(TerminalInvocation::Adb))
    }

    private fun noArgs(command: AdbCommand): AdbCommand? = if (args.isEmpty()) command else verdict.invalid(op)

    private fun devices(): AdbCommand? =
        if (args.isEmpty() || args == LONG) AdbCommand.Devices(args.isNotEmpty()) else verdict.invalid(op)

    /** Хвост строки после [position]-го слова — как написан, с кавычками и пробелами. */
    private fun tail(position: Int): String? =
        tokens.getOrNull(position)?.let { original.substring(it.start).trimEnd() }

    private fun shell(): AdbCommand? {
        var position = 1
        var tty: String? = null
        var noExitCode = false
        var scanning = true
        while (scanning && position < tokens.size) {
            when (tokens[position].value) {
                in TTY_FLAGS -> tty = tokens[position].value
                "-x" -> noExitCode = true
                END_OF_OPTIONS -> scanning = false
                else -> scanning = false.also { position-- }
            }
            position++
        }
        val first = tokens.getOrNull(position)?.value
        val afterEnd = tokens[position - 1].value == END_OF_OPTIONS
        return if (first?.startsWith('-') == true && !afterEnd) {
            verdict.unsupported(TOOL, "shell $first")
        } else {
            AdbCommand.Shell(tail(position) ?: "", tty, noExitCode)
        }
    }

    private fun reboot(): AdbCommand? =
        if (args.size > 1) verdict.invalid(op) else AdbCommand.Service("reboot:${args.firstOrNull().orEmpty()}")

    private fun remount(): AdbCommand? = when {
        args.isEmpty() -> AdbCommand.Remount(false)
        args == listOf("-R") -> AdbCommand.Remount(true)
        else -> verdict.unsupported(TOOL, "$op ${args.joinToString(" ")}")
    }

    private fun simpleService(): AdbCommand? =
        if (args.isEmpty()) AdbCommand.Service("$op:") else verdict.unsupported(TOOL, "$op ${args.joinToString(" ")}")

    private fun tcpip(): AdbCommand? =
        args.singleOrNull()?.takeIf { it.toIntOrNull() in PORTS }?.let { AdbCommand.Service("tcpip:$it") }
            ?: verdict.invalid("tcpip PORT")

    private fun uninstall(): AdbCommand? {
        val keep = args.firstOrNull() == "-k"
        val packages = if (keep) args.drop(1) else args
        return packages.singleOrNull()?.let { AdbCommand.Uninstall(it, keep) } ?: verdict.invalid(op)
    }

    /** Опции `push`/`pull`: у каждой своя сторона, чужая — «не поддержано». */
    private fun transfer(): AdbCommand? {
        var options = FileTransferOptions()
        var index = 0
        var scanning = true
        while (scanning && index < args.size && verdict.failure == null) {
            val option = args[index]
            when {
                option == END_OF_OPTIONS -> scanning = false.also { index++ }
                option == "-z" -> options = options.copy(compression = compression(index++))
                !option.startsWith('-') -> scanning = false
                else -> transferFlag(option, options)?.let { options = it } ?: verdict.unsupported(TOOL, "$op $option")
            }
            if (scanning) index++
        }
        val paths = args.drop(index)
        val minimum = if (op == PUSH) 2 else 1
        return when {
            verdict.failure != null -> null
            paths.size < minimum -> verdict.invalid(op)
            op == PUSH -> AdbCommand.Push(paths.dropLast(1), paths.last(), options)
            paths.size == 1 -> AdbCommand.Pull(paths, ".", options)
            else -> AdbCommand.Pull(paths.dropLast(1), paths.last(), options)
        }
    }

    private fun transferFlag(option: String, options: FileTransferOptions): FileTransferOptions? = when (option) {
        "--sync" -> options.takeIf { op == PUSH }?.copy(syncOnly = true)
        "-a" -> options.takeIf { op == PULL }?.copy(preserveAttributes = true)
        "-n" -> options.takeIf { op == PUSH }?.copy(dryRun = true)
        "-q" -> options.copy(quiet = true)
        "-Z" -> options.copy(compression = NO_COMPRESSION)
        else -> null
    }

    /** `parse_compression_type` без чисел: имя из `-z` или ошибка разбора. */
    private fun compression(at: Int): String? =
        value(at, "-z")?.let { name -> name.takeIf { it in COMPRESSION_TYPES } ?: verdict.invalid("-z $name") }

    /** Значение опции — следующее слово, если это не другая опция. */
    private fun value(at: Int, option: String): String? =
        args.getOrNull(at + 1)?.takeUnless { it.startsWith('-') } ?: verdict.invalid(option)

    private fun sync(): AdbCommand? {
        var options = FileTransferOptions()
        var listOnly = false
        var index = 0
        var scanning = true
        while (scanning && verdict.failure == null && args.getOrNull(index)?.startsWith('-') == true) {
            when (val option = args[index]) {
                "-l" -> listOnly = true
                "-n" -> options = options.copy(dryRun = true)
                "-q" -> options = options.copy(quiet = true)
                "-Z" -> options = options.copy(compression = NO_COMPRESSION)
                "-z" -> options = options.copy(compression = compression(index++))
                END_OF_OPTIONS -> scanning = false
                else -> verdict.unsupported(TOOL, "sync $option")
            }
            index++
        }
        val partitions = args.drop(index)
        return when {
            verdict.failure != null -> null
            partitions.size > 1 || (partitions.isNotEmpty() && partitions[0] !in SYNC_PARTITIONS) ->
                verdict.unsupported(TOOL, "sync ${partitions.joinToString(" ")}")
            else -> AdbCommand.Sync(partitions.firstOrNull(), options, listOnly)
        }
    }

    private fun install(): AdbCommand? {
        val options = mutableListOf<String>()
        var index = 0
        var scanning = true
        while (scanning && index < args.size && verdict.failure == null) {
            val option = args[index]
            when {
                option == END_OF_OPTIONS -> scanning = false.also { index++ }
                !option.startsWith('-') -> scanning = false
                !installFlag(option) -> verdict.unsupported(TOOL, "$op $option")
                option == ABI -> options += listOfNotNull(option, value(index++, option))
                else -> options += option
            }
            if (scanning) index++
        }
        val files = args.drop(index)
        return when {
            verdict.failure != null -> null
            files.isEmpty() || (op == INSTALL && files.size != 1) -> verdict.invalid(op)
            op == INSTALL -> AdbCommand.Install(files.single(), options)
            else -> AdbCommand.InstallMultiple(files, options)
        }
    }

    /**
     * Известен ли флаг установки.
     *
     * `-p` у одиночного `install` не бывает: частичная установка — это
     * `install-multiple`, и молча принять его значило бы исполнить не то.
     */
    private fun installFlag(option: String): Boolean {
        val known = option in INSTALL_FLAGS || option.matches(SHORT_INSTALL_FLAGS) || option == ABI
        val partial = op == INSTALL && !option.startsWith("--") && 'p' in option
        return known && !partial
    }

    private companion object {
        const val TOOL = "adb"
        const val END_OF_OPTIONS = "--"
        const val PUSH = "push"
        const val PULL = "pull"
        const val INSTALL = "install"
        const val ABI = "--abi"
        val LONG = listOf("-l")
        val TTY_FLAGS = setOf("-t", "-tt", "-T")
        val PORTS = 1..65_535
        val SYNC_PARTITIONS = setOf("all", "data", "odm", "oem", "product", "system", "system_ext", "vendor")
        val COMPRESSION_TYPES = setOf("any", "none", "brotli", "lz4", "zstd")
        const val NO_COMPRESSION = "none"
        val INSTALL_FLAGS = setOf(
            "-l", "-r", "-t", "-s", "-d", "-p", "-g", "--instant",
            "--no-streaming", "--streaming", "--fastdeploy", "--no-fastdeploy", "--force-agent",
            "--date-check-agent", "--version-check-agent", "--local-agent",
        )
        val SHORT_INSTALL_FLAGS = Regex("""-[lrtsdpg]+""")

        val HANDLERS: Map<String, AdbLineParser.() -> AdbCommand?> = mapOf(
            "status" to { noArgs(AdbCommand.LocalStatus) },
            "devices" to { devices() },
            "get-state" to { noArgs(AdbCommand.GetState) },
            "shell" to { shell() },
            "exec" to { tail(1)?.let(AdbCommand::Exec) ?: verdict.invalid("exec") },
            "logcat" to { AdbCommand.Logcat(args) },
            "reboot" to { reboot() },
            "remount" to { remount() },
            "root" to { simpleService() },
            "unroot" to { simpleService() },
            "disable-verity" to { simpleService() },
            "enable-verity" to { simpleService() },
            "usb" to { simpleService() },
            "tcpip" to { tcpip() },
            PUSH to { transfer() },
            PULL to { transfer() },
            "sync" to { sync() },
            INSTALL to { install() },
            "install-multiple" to { install() },
            "sideload" to { args.singleOrNull()?.let(AdbCommand::Sideload) ?: verdict.invalid(op) },
            "uninstall" to { uninstall() },
        )
    }
}
