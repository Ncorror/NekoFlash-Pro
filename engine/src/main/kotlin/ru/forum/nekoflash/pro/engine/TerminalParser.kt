package ru.forum.nekoflash.pro.engine

class TerminalParser {
    enum class Tool(val prefix: String) { ADB("adb"), FASTBOOT("fastboot") }

    /** A UI tab may supply the tool; an explicit prefix in the input wins. */
    fun parse(tool: Tool, line: String): TerminalParseResult {
        val trimmed = line.trimStart()
        return if (trimmed.matches(TOOL_PREFIX)) parse(line)
            else parse("${tool.prefix} $line")
    }

    fun parse(line: String): TerminalParseResult {
        val literal = RAW_PREFIX.matchEntire(line)
        if (literal != null) {
            val payload = literal.groupValues[3]
            if (payload.isBlank()) return TerminalParseResult.Invalid("raw needs a wire command or ADB service")
            if (literal.groupValues[1].equals("fastboot", ignoreCase = true) &&
                literal.groupValues[2].equals("service", ignoreCase = true)) {
                return TerminalParseResult.Unsupported("fastboot", "service")
            }
            return if (literal.groupValues[1].equals("fastboot", ignoreCase = true)) {
                TerminalParseResult.Parsed(
                    TerminalInvocation.Fastboot(FastbootOptions(), FastbootCommand.Wire(payload))
                )
            } else {
                TerminalParseResult.Parsed(TerminalInvocation.Adb(AdbCommand.Service(payload)))
            }
        }

        val tokens = tokenize(line) ?: return TerminalParseResult.Invalid("unclosed quote")
        if (tokens.size < 2) return TerminalParseResult.Invalid("expected adb or fastboot and a command")
        return when (tokens[0].value.lowercase()) {
            "fastboot" -> parseFastboot(tokens.drop(1))
            "adb" -> parseAdb(line, tokens.drop(1))
            else -> TerminalParseResult.Invalid("expected adb or fastboot")
        }
    }

    private fun parseFastboot(tokens: List<Token>): TerminalParseResult {
        var options = FastbootOptions()
        val args = mutableListOf<String>()
        var optionsEnded = false
        var index = 0
        while (index < tokens.size) {
            val value = tokens[index].value
            // OEM and vendor verbs own their following bytes. Host options belong
            // before the verb; a vendor argument beginning with '-' is still data.
            val firstVerb = args.firstOrNull()
            if (firstVerb == "oem" || (firstVerb != null && firstVerb !in KNOWN_FASTBOOT_HOST_COMMANDS &&
                    !firstVerb.startsWith("format:"))) {
                args += value
                index++
                continue
            }
            if (value == "--" && !optionsEnded) {
                optionsEnded = true
                index++
                continue
            }
            if (!optionsEnded) {
                fun nextValue(): String? = tokens.getOrNull(index + 1)?.value?.also { index++ }
                when {
                    value == "--slot" -> options = options.copy(slot = nextValue() ?: return invalidValue(value))
                    value.startsWith("--slot=") -> options = options.copy(slot = value.substringAfter('=').ifEmpty { return invalidValue(value) })
                    // This option has an *optional attached* argument in fastboot:
                    // --set-active=a. The next token belongs to the command.
                    value == "--set-active" -> options = options.copy(setActiveRequested = true)
                    value.startsWith("--set-active=") -> options = options.copy(
                        setActiveRequested = true,
                        setActiveSlot = value.substringAfter('=').ifEmpty { return invalidValue(value) },
                    )
                    value == "-a" -> options = options.copy(
                        setActiveRequested = true, setActiveSlot = nextValue() ?: return invalidValue(value),
                    )
                    value == "-w" -> options = options.copy(wipe = true)
                    value == "-S" -> options = options.copy(sparseLimit = nextValue()?.takeIf { it.matches(SPARSE_SIZE) }
                        ?: return invalidValue(value))
                    value.startsWith("-S") && value.length > 2 -> options = options.copy(
                        sparseLimit = value.substring(2).takeIf { it.matches(SPARSE_SIZE) } ?: return invalidValue("-S")
                    )
                    value == "-s" -> options = options.copy(serial = nextValue() ?: return invalidValue(value))
                    value == "--force" -> options = options.copy(force = true)
                    value == "--skip-reboot" -> options = options.copy(skipReboot = true)
                    value == "--skip-secondary" -> options = options.copy(skipSecondary = true)
                    value == "--disable-verity" -> options = options.copy(disableVerity = true)
                    value == "--disable-verification" -> options = options.copy(disableVerification = true)
                    value == "-v" || value == "--verbose" -> options = options.copy(verbose = true)
                    value == "--wipe" && args.firstOrNull() == "update-super" -> args += value
                    value == "-l" && args.firstOrNull() == "devices" -> args += value
                    value.startsWith("-") -> return TerminalParseResult.Unsupported("fastboot", value)
                    else -> args += value
                }
            } else {
                args += value
            }
            index++
        }
        if (args.isEmpty()) {
            val command = when {
                options.wipe -> FastbootCommand.Wipe
                options.setActiveRequested -> FastbootCommand.SetActive(options.setActiveSlot)
                else -> return TerminalParseResult.Invalid("missing fastboot command")
            }
            val normalizedOptions = when (command) {
                FastbootCommand.Wipe -> options.copy(wipe = false)
                is FastbootCommand.SetActive -> options.copy(setActiveRequested = false, setActiveSlot = null)
                else -> options
            }
            return TerminalParseResult.Parsed(TerminalInvocation.Fastboot(normalizedOptions, command))
        }
        val op = args[0]
        val operands = args.drop(1)
        fun requireSize(range: IntRange): Boolean = operands.size in range
        val command = when {
            op == "flash" && requireSize(1..2) && operands[0].startsWith("vendor_boot:") -> {
                val ramdisk = operands[0].substringAfter(':')
                if (ramdisk.isBlank()) return invalidValue("vendor_boot:RAMDISK")
                FastbootCommand.FlashVendorBootRamdisk(ramdisk, operands.getOrNull(1))
            }
            op == "flash" && requireSize(1..2) -> FastbootCommand.Flash(operands[0], operands.getOrNull(1))
            op.startsWith("format") && FORMATS.matches(op) && requireSize(1..1) -> {
                val parameters = op.split(':')
                FastbootCommand.Format(operands[0], parameters.getOrNull(1)?.ifBlank { null }, parameters.getOrNull(2)?.ifBlank { null })
            }
            op == "erase" && requireSize(1..1) -> FastbootCommand.Erase(operands[0])
            op == "wipe-super" && requireSize(0..1) -> FastbootCommand.WipeSuper(operands.firstOrNull())
            op == "boot" && requireSize(1..3) -> FastbootCommand.Boot(operands[0], operands.getOrNull(1), operands.getOrNull(2))
            op == "flash:raw" && requireSize(2..4) -> FastbootCommand.FlashRaw(
                operands[0], operands[1], operands.getOrNull(2), operands.getOrNull(3)
            )
            op == "update" && requireSize(1..1) -> FastbootCommand.Update(operands[0])
            op == "flashall" && operands.isEmpty() -> FastbootCommand.FlashAll
            op == "update-super" && requireSize(1..2) &&
                (operands.size == 1 || operands[1] == "wipe" || operands[1] == "--wipe") ->
                FastbootCommand.UpdateSuper(operands[0], operands.size == 2)
            op == "fetch" && requireSize(2..2) -> FastbootCommand.Fetch(operands[0], operands[1])
            (op == "set_active" || op == "set-active") && requireSize(1..1) -> FastbootCommand.SetActive(operands[0])
            op == "getvar" && requireSize(0..1) -> FastbootCommand.Wire("getvar:${operands.firstOrNull() ?: "all"}")
            op == "reboot" && requireSize(0..1) -> {
                val target = operands.firstOrNull()
                val wire = when (target) {
                    null -> "reboot"
                    "bootloader", "recovery", "fastboot" -> "reboot-$target"
                    else -> return TerminalParseResult.Unsupported("fastboot", "$op $target")
                }
                FastbootCommand.Wire(wire)
            }
            (op == "is-logical" || op == "logical-info") && requireSize(1..1) -> FastbootCommand.DeviceQuery(op, operands[0])
            op == "status" && operands.isEmpty() -> FastbootCommand.LocalStatus
            op == "devices" && (operands.isEmpty() || operands == listOf("-l")) ->
                FastbootCommand.Devices(operands.isNotEmpty())
            op == "oem" && operands.isNotEmpty() -> FastbootCommand.Wire("oem ${operands.joinToString(" ")}")
            op == "flashing" && operands.size == 1 -> FastbootCommand.Wire("flashing ${operands[0]}")
            op == "gsi" && operands.size == 1 -> FastbootCommand.Wire("gsi:${operands[0]}")
            op == "snapshot-update" && operands.size <= 1 -> FastbootCommand.Wire(
                "snapshot-update${operands.firstOrNull()?.let { ":$it" } ?: ""}"
            )
            (op == "create-logical-partition" || op == "resize-logical-partition") && operands.size == 2 ->
                FastbootCommand.Wire("$op:${operands[0]}:${operands[1]}")
            op == "delete-logical-partition" && operands.size == 1 -> FastbootCommand.Wire("$op:${operands[0]}")
            (op in KNOWN_FASTBOOT_HOST_COMMANDS || op.startsWith("format:")) ->
                return TerminalParseResult.Unsupported("fastboot", args.joinToString(" "))
            else -> FastbootCommand.Wire(args.joinToString(" "))
        }
        return TerminalParseResult.Parsed(TerminalInvocation.Fastboot(options, command))
    }

    private fun parseAdb(original: String, tokens: List<Token>): TerminalParseResult {
        val op = tokens[0].value.lowercase()
        val args = tokens.drop(1).map { it.value }
        val command: AdbCommand = when (op) {
            "status" -> if (args.isEmpty()) AdbCommand.LocalStatus else return invalidValue(op)
            "devices" -> if (args.isEmpty() || args == listOf("-l")) AdbCommand.Devices(args.isNotEmpty())
                else return invalidValue(op)
            "get-state" -> if (args.isEmpty()) AdbCommand.GetState else return invalidValue(op)
            "shell" -> {
                var position = 1
                var tty: String? = null
                var noExitCode = false
                while (position < tokens.size) {
                    when (tokens[position].value) {
                        "-t", "-tt", "-T" -> tty = tokens[position].value
                        "-x" -> noExitCode = true
                        "--" -> { position++; break }
                        else -> break
                    }
                    position++
                }
                if (tokens.getOrNull(position)?.value?.startsWith('-') == true &&
                    tokens[position - 1].value != "--") {
                    return TerminalParseResult.Unsupported("adb", "shell ${tokens[position].value}")
                }
                AdbCommand.Shell(tokens.getOrNull(position)?.let { original.substring(it.start).trimEnd() } ?: "", tty, noExitCode)
            }
            "exec" -> AdbCommand.Exec(tokens.getOrNull(1)?.let { original.substring(it.start).trimEnd() }
                ?: return invalidValue("exec"))
            "logcat" -> AdbCommand.Logcat(args)
            "reboot" -> {
                if (args.size > 1) return invalidValue(op)
                AdbCommand.Service("reboot:${args.firstOrNull().orEmpty()}")
            }
            "remount" -> {
                if (args.isNotEmpty() && args != listOf("-R")) return TerminalParseResult.Unsupported("adb", "$op ${args.joinToString(" ")}")
                AdbCommand.Remount(args == listOf("-R"))
            }
            "root", "unroot", "disable-verity", "enable-verity", "usb" -> {
                if (args.isNotEmpty()) return TerminalParseResult.Unsupported("adb", "$op ${args.joinToString(" ")}")
                AdbCommand.Service("$op:")
            }
            "tcpip" -> {
                val port = args.singleOrNull()?.toIntOrNull()
                if (port == null || port !in 1..65535) return invalidValue("tcpip PORT")
                AdbCommand.Service("tcpip:${args[0]}")
            }
            "push" -> return parseTransfer(op, args)
            "pull" -> return parseTransfer(op, args)
            "sync" -> return parseSync(args)
            "install", "install-multiple" -> return parseInstall(op, args)
            "sideload" -> {
                if (args.size != 1) return invalidValue(op)
                AdbCommand.Sideload(args[0])
            }
            "uninstall" -> {
                val keep = args.firstOrNull() == "-k"
                val packages = if (keep) args.drop(1) else args
                if (packages.size != 1) return invalidValue(op)
                AdbCommand.Uninstall(packages[0], keep)
            }
            in SHELL_SHORTCUTS -> AdbCommand.Shell(original.substring(tokens[0].start).trimEnd())
            else -> return TerminalParseResult.Unsupported("adb", op)
        }
        return TerminalParseResult.Parsed(TerminalInvocation.Adb(command))
    }

    private fun parseTransfer(op: String, args: List<String>): TerminalParseResult {
        var options = FileTransferOptions()
        var index = 0
        while (index < args.size) {
            when (val option = args[index]) {
                "--sync" -> if (op == "push") options = options.copy(syncOnly = true)
                    else return TerminalParseResult.Unsupported("adb", "$op $option")
                "-a" -> if (op == "pull") options = options.copy(preserveAttributes = true)
                    else return TerminalParseResult.Unsupported("adb", "$op $option")
                "-n" -> if (op == "push") options = options.copy(dryRun = true)
                    else return TerminalParseResult.Unsupported("adb", "$op $option")
                "-q" -> options = options.copy(quiet = true)
                "-z" -> {
                    val algorithm = args.getOrNull(++index)?.takeUnless { it.startsWith('-') }
                        ?: return invalidValue("-z")
                    options = options.copy(compression = algorithm)
                }
                "-Z" -> options = options.copy(noCompression = true)
                "--" -> { index++; break }
                else -> if (option.startsWith('-')) return TerminalParseResult.Unsupported("adb", "$op $option")
                    else break
            }
            index++
        }
        val paths = args.drop(index)
        val minimumPaths = if (op == "push") 2 else 1
        if (paths.size < minimumPaths) return invalidValue(op)
        val command = if (op == "push") AdbCommand.Push(paths.dropLast(1), paths.last(), options)
            else if (paths.size == 1) AdbCommand.Pull(paths, ".", options)
            else AdbCommand.Pull(paths.dropLast(1), paths.last(), options)
        return TerminalParseResult.Parsed(TerminalInvocation.Adb(command))
    }

    private fun parseSync(args: List<String>): TerminalParseResult {
        var options = FileTransferOptions()
        var listOnly = false
        var index = 0
        while (index < args.size && args[index].startsWith('-')) {
            when (val option = args[index]) {
                "-l" -> listOnly = true
                "-n" -> options = options.copy(dryRun = true)
                "-q" -> options = options.copy(quiet = true)
                "-Z" -> options = options.copy(noCompression = true)
                "-z" -> options = options.copy(compression = args.getOrNull(++index)
                    ?.takeUnless { it.startsWith('-') } ?: return invalidValue("-z"))
                "--" -> { index++; break }
                else -> return TerminalParseResult.Unsupported("adb", "sync $option")
            }
            index++
        }
        val partitions = args.drop(index)
        if (partitions.size > 1 || (partitions.isNotEmpty() && partitions[0] !in SYNC_PARTITIONS)) {
            return TerminalParseResult.Unsupported("adb", "sync ${partitions.joinToString(" ")}")
        }
        return TerminalParseResult.Parsed(TerminalInvocation.Adb(
            AdbCommand.Sync(partitions.firstOrNull(), options, listOnly)
        ))
    }

    private fun parseInstall(op: String, args: List<String>): TerminalParseResult {
        val options = mutableListOf<String>()
        var index = 0
        while (index < args.size) {
            val option = args[index]
            if (option == "--") {
                index++
                break
            }
            if (!option.startsWith('-')) break
            if ((option !in INSTALL_FLAGS && !option.matches(SHORT_INSTALL_FLAGS) && option != "--abi") ||
                (op == "install" && option.startsWith('-') && !option.startsWith("--") && 'p' in option)) {
                return TerminalParseResult.Unsupported("adb", "$op $option")
            }
            options += option
            if (option == "--abi") {
                val abi = args.getOrNull(++index)?.takeUnless { it.startsWith('-') }
                    ?: return invalidValue(option)
                options += abi
            }
            index++
        }
        val files = args.drop(index)
        if (files.isEmpty() || (op == "install" && files.size != 1)) return invalidValue(op)
        val command = if (op == "install") AdbCommand.Install(files.single(), options)
            else AdbCommand.InstallMultiple(files, options)
        return TerminalParseResult.Parsed(TerminalInvocation.Adb(command))
    }

    private fun invalidValue(value: String) = TerminalParseResult.Invalid("missing or invalid argument for $value")

    private data class Token(val value: String, val start: Int)

    private fun tokenize(line: String): List<Token>? {
        val result = mutableListOf<Token>()
        val current = StringBuilder()
        var start = -1
        var quote: Char? = null
        var index = 0
        while (index < line.length) {
            val ch = line[index]
            if (start == -1 && !ch.isWhitespace()) start = index
            when {
                ch == quote -> quote = null
                (ch == '\'' || ch == '"') && quote == null -> quote = ch
                ch == '\\' && quote != '\'' && index + 1 < line.length &&
                    (line[index + 1].isWhitespace() || line[index + 1] == '\\' || line[index + 1] == quote ||
                        (quote == null && line[index + 1] in listOf('\'', '"'))) -> current.append(line[++index])
                ch.isWhitespace() && quote == null -> {
                    if (start != -1) result += Token(current.toString(), start)
                    current.clear()
                    start = -1
                }
                else -> current.append(ch)
            }
            index++
        }
        if (quote != null) return null
        if (start != -1) result += Token(current.toString(), start)
        return result
    }

    private companion object {
        val TOOL_PREFIX = Regex("""(?is)(adb|fastboot)(?:\s+.*)?""")
        val RAW_PREFIX = Regex("""\s*(adb|fastboot)\s+(raw|service)\s+([\s\S]*)""", RegexOption.IGNORE_CASE)
        val SPARSE_SIZE = Regex("""(?:0[xX][0-9a-fA-F]+|[0-9]+)[KMGkmg]?""")
        val SYNC_PARTITIONS = setOf("all", "data", "odm", "oem", "product", "system", "system_ext", "vendor")
        val FORMATS = Regex("""format(?::[^:]*(?::[^:]*)?)?""")
        val INSTALL_FLAGS = setOf("-l", "-r", "-t", "-s", "-d", "-p", "-g", "--instant",
            "--no-streaming", "--streaming", "--fastdeploy", "--no-fastdeploy", "--force-agent",
            "--date-check-agent", "--version-check-agent", "--local-agent")
        val SHORT_INSTALL_FLAGS = Regex("""-[lrtsdpg]+""")
        val KNOWN_FASTBOOT_HOST_COMMANDS = setOf("flash", "flash:raw", "format", "erase", "wipe-super",
            "boot", "update", "flashall", "update-super", "fetch", "set_active", "set-active", "getvar",
            "reboot", "is-logical", "logical-info", "status", "devices", "oem", "flashing", "gsi",
            "snapshot-update", "create-logical-partition", "resize-logical-partition",
            "delete-logical-partition", "stage", "get_staged", "raw", "service")
        val SHELL_SHORTCUTS = setOf("getprop", "setprop", "pm", "am", "cmd", "settings", "wm", "input", "svc", "dumpsys", "cat", "ls", "cd", "pwd", "id", "su", "sh")
    }
}
