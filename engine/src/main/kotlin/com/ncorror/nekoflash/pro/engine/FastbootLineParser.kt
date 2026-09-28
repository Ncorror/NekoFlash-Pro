package com.ncorror.nekoflash.pro.engine

/**
 * Разбор слов после `fastboot`.
 *
 * Опции хоста стоят где угодно до `--`; OEM и vendor-глаголы владеют всеми
 * словами после себя — аргумент vendor-команды, начинающийся с `-`, всё равно
 * данные, а не опция хоста.
 */
internal class FastbootLineParser(private val tokens: List<TerminalToken>) {
    private val verdict = ParseVerdict()
    private var options = FastbootOptions()
    private val args = mutableListOf<String>()
    private var optionsEnded = false
    private var index = 0

    fun parse(): TerminalParseResult {
        while (index < tokens.size) {
            take(tokens[index].value)
            index++
        }
        return verdict.result(if (verdict.failure == null) invocation() else null)
    }

    private fun take(value: String) {
        val verb = args.firstOrNull()
        when {
            verb != null && ownsTheRest(verb) -> args += value
            value == END_OF_OPTIONS && !optionsEnded -> optionsEnded = true
            optionsEnded -> args += value
            else -> option(value, verb)
        }
    }

    /** OEM- и vendor-глагол забирает все слова после себя. */
    private fun ownsTheRest(verb: String): Boolean =
        verb == OEM || (verb !in KNOWN_HOST_COMMANDS && !verb.startsWith(FORMAT_PREFIX))

    private fun option(value: String, verb: String?) {
        val flag = FLAGS[value]
        val valued = VALUED[value]
        val attached = ATTACHED.entries.firstOrNull { (prefix, _) -> value.startsWith(prefix) && value != prefix }
        when {
            flag != null -> options = flag(options)
            valued != null -> options = valued(options, next()) ?: options.also { verdict.invalid(value) }
            value == "--slot=" || value == "--set-active=" || value == "--fs-options=" -> verdict.invalid(value)
            attached != null -> options = attached.value(options, value.removePrefix(attached.key))
                ?: options.also { verdict.invalid(attached.key.trimEnd('=')) }
            value == "--wipe" && verb == UPDATE_SUPER -> args += value
            value == "-l" && verb == DEVICES -> args += value
            value.startsWith('-') -> verdict.unsupported(TOOL, value)
            else -> args += value
        }
    }

    /** Значение опции — следующее слово, каким бы оно ни было. */
    private fun next(): String? = tokens.getOrNull(index + 1)?.value?.also { index++ }

    private fun invocation(): TerminalInvocation? = if (args.isEmpty()) bareOptions() else command()

    /** `fastboot -w` и `fastboot --set-active[=СЛОТ]` без глагола — сами по себе команды. */
    private fun bareOptions(): TerminalInvocation? = when {
        options.wipe -> TerminalInvocation.Fastboot(options.copy(wipe = false), FastbootCommand.Wipe)
        options.setActiveRequested -> TerminalInvocation.Fastboot(
            options.copy(setActiveRequested = false, setActiveSlot = null),
            FastbootCommand.SetActive(options.setActiveSlot),
        )
        else -> verdict.invalid("fastboot command")
    }

    private fun command(): TerminalInvocation? {
        val op = args[0]
        val operands = args.drop(1)
        val rule = if (FORMATS.matches(op)) FORMAT_RULE else RULES[op]
        val built = rule?.takeIf { operands.size in it.arity }?.build?.invoke(op, operands, verdict)
        val command = when {
            built != null -> built
            verdict.failure != null -> null
            op in KNOWN_HOST_COMMANDS || op.startsWith(FORMAT_PREFIX) ->
                verdict.unsupported(TOOL, args.joinToString(" "))
            else -> FastbootCommand.Wire(args.joinToString(" "))
        }
        return command?.let { TerminalInvocation.Fastboot(options, it) }
    }

    /** Сколько слов принимает глагол и что из них собирается; `null` — форма не та. */
    private class Rule(
        val arity: IntRange,
        val build: (String, List<String>, ParseVerdict) -> FastbootCommand?,
    )

    private companion object {
        const val TOOL = "fastboot"
        const val END_OF_OPTIONS = "--"
        const val OEM = "oem"
        const val FORMAT_PREFIX = "format:"
        const val UPDATE_SUPER = "update-super"
        const val DEVICES = "devices"
        val SPARSE_SIZE = Regex("""(0[xX][0-9a-fA-F]+|[0-9]+)([bkmgtpeBKMGTPE]?)""")
        val FORMATS = Regex("""format(?::[^:]*(?::[^:]*)?)?""")

        val FLAGS: Map<String, (FastbootOptions) -> FastbootOptions> = mapOf(
            // У опции необязательный *присоединённый* аргумент: --set-active=a.
            // Следующее слово принадлежит команде.
            "--set-active" to { it.copy(setActiveRequested = true) },
            "-w" to { it.copy(wipe = true) },
            "--force" to { it.copy(force = true) },
            "--skip-reboot" to { it.copy(skipReboot = true) },
            "--skip-secondary" to { it.copy(skipSecondary = true) },
            "--disable-verity" to { it.copy(disableVerity = true) },
            "--disable-verification" to { it.copy(disableVerification = true) },
            "-v" to { it.copy(verbose = true) },
            "--verbose" to { it.copy(verbose = true) },
        )

        val VALUED: Map<String, (FastbootOptions, String?) -> FastbootOptions?> = mapOf(
            "--slot" to { options, value -> value?.let { options.copy(slot = it) } },
            "-a" to { options, value ->
                value?.let { options.copy(setActiveRequested = true, setActiveSlot = it) }
            },
            "-S" to { options, value -> value?.let { sparse(options, it) } },
            "-s" to { options, value -> value?.let { options.copy(serial = it) } },
            "--fs-options" to { options, value -> value?.let { fsOptions(options, it) } },
        )

        val ATTACHED: Map<String, (FastbootOptions, String) -> FastbootOptions?> = mapOf(
            "--slot=" to { options, value -> options.copy(slot = value) },
            "--set-active=" to { options, value -> options.copy(setActiveRequested = true, setActiveSlot = value) },
            "-S" to { options, value -> sparse(options, value) },
            "--fs-options=" to { options, value -> fsOptions(options, value) },
        )

        /** `ParseFsOption`: через запятую `casefold`, `projid`, `compress`; прочее — ошибка разбора. */
        val FS_OPTIONS = setOf("casefold", "projid", "compress")

        fun fsOptions(options: FastbootOptions, value: String): FastbootOptions? =
            value.split(',').takeIf { words -> words.all { it in FS_OPTIONS } }
                ?.let { options.copy(fsOptions = options.fsOptions + it) }

        val ONE = 1..1
        val NONE = 0..0
        val OPTIONAL = 0..1
        val PAIR = 2..2
        val FLASH_ARITY = 1..2
        val BOOT_ARITY = 1..3
        val FLASH_RAW_ARITY = 2..4
        val ANY = 0..Int.MAX_VALUE

        val FORMAT_RULE = Rule(ONE) { op, operands, _ ->
            val parameters = op.split(':')
            FastbootCommand.Format(
                operands[0],
                parameters.getOrNull(1)?.ifBlank { null },
                parameters.getOrNull(2)?.ifBlank { null },
            )
        }

        val RULES: Map<String, Rule> = mapOf(
            "flash" to Rule(FLASH_ARITY) { _, operands, verdict -> flash(operands, verdict) },
            "erase" to Rule(ONE) { _, operands, _ -> FastbootCommand.Erase(operands[0]) },
            "wipe-super" to Rule(OPTIONAL) { _, operands, _ -> FastbootCommand.WipeSuper(operands.firstOrNull()) },
            "boot" to Rule(BOOT_ARITY) { _, operands, _ ->
                FastbootCommand.Boot(operands[0], operands.getOrNull(1), operands.getOrNull(2))
            },
            "flash:raw" to Rule(FLASH_RAW_ARITY) { _, operands, _ ->
                FastbootCommand.FlashRaw(operands[0], operands[1], operands.getOrNull(2), operands.getOrNull(3))
            },
            "update" to Rule(ONE) { _, operands, _ -> FastbootCommand.Update(operands[0]) },
            "flashall" to Rule(NONE) { _, _, _ -> FastbootCommand.FlashAll },
            UPDATE_SUPER to Rule(FLASH_ARITY) { _, operands, _ ->
                operands.takeIf { it.size == 1 || it[1] == "wipe" || it[1] == "--wipe" }
                    ?.let { FastbootCommand.UpdateSuper(it[0], it.size == 2) }
            },
            "fetch" to Rule(PAIR) { _, operands, _ -> FastbootCommand.Fetch(operands[0], operands[1]) },
            "set_active" to Rule(ONE) { _, operands, _ -> setActive(operands[0]) },
            "set-active" to Rule(ONE) { _, operands, _ -> setActive(operands[0]) },
            "getvar" to Rule(OPTIONAL) { _, operands, _ -> FastbootCommand.Getvar(operands.firstOrNull() ?: "all") },
            "reboot" to Rule(OPTIONAL) { op, operands, verdict -> reboot(op, operands.firstOrNull(), verdict) },
            "is-logical" to Rule(ONE) { op, operands, _ -> FastbootCommand.DeviceQuery(op, operands[0]) },
            "logical-info" to Rule(ONE) { op, operands, _ -> FastbootCommand.DeviceQuery(op, operands[0]) },
            "status" to Rule(NONE) { _, _, _ -> FastbootCommand.LocalStatus },
            DEVICES to Rule(OPTIONAL) { _, operands, _ ->
                operands.takeIf { it.isEmpty() || it == listOf("-l") }?.let { FastbootCommand.Devices(it.isNotEmpty()) }
            },
            OEM to Rule(ANY) { _, operands, _ ->
                operands.takeIf { it.isNotEmpty() }?.let { FastbootCommand.Wire("oem ${it.joinToString(" ")}") }
            },
            "flashing" to Rule(ONE) { _, operands, _ -> FastbootCommand.Wire("flashing ${operands[0]}") },
            "gsi" to Rule(ONE) { _, operands, _ -> FastbootCommand.Wire("gsi:${operands[0]}") },
            "snapshot-update" to Rule(OPTIONAL) { op, operands, _ ->
                FastbootCommand.Wire(op + (operands.firstOrNull()?.let { ":$it" } ?: ""))
            },
            "create-logical-partition" to Rule(PAIR) { op, operands, _ -> logical(op, operands) },
            "resize-logical-partition" to Rule(PAIR) { op, operands, _ -> logical(op, operands) },
            "delete-logical-partition" to Rule(ONE) { op, operands, _ -> logical(op, operands) },
        )

        val KNOWN_HOST_COMMANDS = RULES.keys + setOf("format", "stage", "get_staged", "raw", "service")

        /** Пустой слот `set_active` — не слот: `verify_slot` отказал бы ему. */
        fun setActive(slot: String): FastbootCommand? = slot.ifEmpty { null }?.let(FastbootCommand::SetActive)

        /** `-S` принимает размер, а не следующую опцию: `-S --force` — ошибка, а не лимит «--force». */
        fun sparse(options: FastbootOptions, value: String): FastbootOptions? =
            byteCount(value)?.let { options.copy(sparseLimit = it) }

        /**
         * Размер, как его читает AOSP `ParseByteCount`: `0x` — шестнадцатеричное,
         * иначе десятичное; буква `b`, `k`, `m`, `g`, `t`, `p`, `e` — множитель
         * 1024 в степени её места. Переполнение — ошибка ввода.
         */
        fun byteCount(value: String): Long? {
            val match = SPARSE_SIZE.matchEntire(value) ?: return null
            val digits = match.groupValues[1]
            val number = if (digits.length > 2 && digits[1].lowercaseChar() == 'x') {
                digits.drop(2).toULongOrNull(HEX)
            } else {
                digits.toULongOrNull()
            }
            val shift = BYTE_SUFFIXES.indexOf(match.groupValues[2].lowercase()).coerceAtLeast(0) * BINARY_STEP
            return number?.takeIf { it <= (Long.MAX_VALUE shr shift).toULong() }?.toLong()?.shl(shift)
        }

        const val HEX = 16
        const val BINARY_STEP = 10
        val BYTE_SUFFIXES = listOf("", "k", "m", "g", "t", "p", "e")

        fun flash(operands: List<String>, verdict: ParseVerdict): FastbootCommand? {
            val target = operands[0]
            return if (target.startsWith(VENDOR_BOOT)) {
                target.removePrefix(VENDOR_BOOT).takeIf { it.isNotBlank() }
                    ?.let { FastbootCommand.FlashVendorBootRamdisk(it, operands.getOrNull(1)) }
                    ?: verdict.invalid("vendor_boot:RAMDISK")
            } else {
                FastbootCommand.Flash(target, operands.getOrNull(1))
            }
        }

        fun reboot(op: String, target: String?, verdict: ParseVerdict): FastbootCommand? = when (target) {
            null -> FastbootCommand.Wire(op)
            in REBOOT_TARGETS -> FastbootCommand.Wire("$op-$target")
            else -> verdict.unsupported(TOOL, "$op $target")
        }

        fun logical(op: String, operands: List<String>): FastbootCommand =
            FastbootCommand.Wire((listOf(op) + operands).joinToString(":"))

        const val VENDOR_BOOT = "vendor_boot:"
        val REBOOT_TARGETS = setOf("bootloader", "recovery", "fastboot")
    }
}
