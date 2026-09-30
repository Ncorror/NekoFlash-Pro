package com.ncorror.nekoflash.pro.engine

/**
 * Разбор строки настольного `adb` или `fastboot` в типизированное намерение.
 *
 * Разбор отделён от исполнения намеренно: операция хоста не может случайно
 * уйти загрузчику как сырая команда (`04` §3).
 */
class TerminalParser {
    enum class Tool(val prefix: String) { ADB("adb"), FASTBOOT("fastboot") }

    /** Вкладка может подсказать инструмент; явный префикс в строке сильнее. */
    fun parse(tool: Tool, line: String): TerminalParseResult =
        if (line.trimStart().matches(TOOL_PREFIX)) parse(line) else parse("${tool.prefix} $line")

    fun parse(line: String): TerminalParseResult {
        val literal = RAW_PREFIX.matchEntire(line)
        val tokens = if (literal == null) TerminalTokens.split(line) else null
        return when {
            literal != null -> raw(literal)
            tokens == null -> TerminalParseResult.Invalid("unclosed quote")
            tokens.size < 2 -> TerminalParseResult.Invalid("expected adb or fastboot and a command")
            else -> when (tokens[0].value.lowercase()) {
                Tool.FASTBOOT.prefix -> FastbootLineParser(tokens.drop(1)).parse()
                Tool.ADB.prefix -> AdbLineParser(line, tokens.drop(1)).parse()
                else -> TerminalParseResult.Invalid("expected adb or fastboot")
            }
        }
    }

    /** `raw` — wire-запрос как есть: грамматика настольного клиента к нему не применяется. */
    private fun raw(literal: MatchResult): TerminalParseResult {
        val (tool, verb, payload) = literal.destructured
        val fastboot = tool.equals(Tool.FASTBOOT.prefix, ignoreCase = true)
        return when {
            payload.isBlank() -> TerminalParseResult.Invalid("raw needs a wire command or ADB service")
            fastboot && verb.equals("service", ignoreCase = true) ->
                TerminalParseResult.Unsupported(Tool.FASTBOOT.prefix, "service")
            fastboot -> TerminalParseResult.Parsed(
                TerminalInvocation.Fastboot(FastbootOptions(), FastbootCommand.Wire(payload)),
            )
            else -> TerminalParseResult.Parsed(TerminalInvocation.Adb(AdbCommand.Service(payload)))
        }
    }

    private companion object {
        val TOOL_PREFIX = Regex("""(?is)(adb|fastboot)(?:\s+.*)?""")
        val RAW_PREFIX = Regex("""\s*(adb|fastboot)\s+(raw|service)\s+([\s\S]*)""", RegexOption.IGNORE_CASE)
    }
}

/**
 * Первая причина, по которой строку нельзя принять.
 *
 * Разбор идёт до конца и спрашивается в конце: так у функций разбора один
 * выход, а причина — ровно та, что встретилась первой.
 */
internal class ParseVerdict {
    var failure: TerminalParseResult? = null
        private set

    fun invalid(what: String): Nothing? {
        if (failure == null) failure = TerminalParseResult.Invalid("missing or invalid argument for $what")
        return null
    }

    fun unsupported(tool: String, command: String): Nothing? {
        if (failure == null) failure = TerminalParseResult.Unsupported(tool, command)
        return null
    }

    /** Итог: причина отказа или разобранное намерение. */
    fun result(invocation: TerminalInvocation?): TerminalParseResult =
        failure ?: invocation?.let(TerminalParseResult::Parsed) ?: TerminalParseResult.Invalid("not parsed")
}
