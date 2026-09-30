package com.ncorror.nekoflash.pro.engine

/** Слово строки и его начало в исходном тексте: хвост `adb shell` берётся как есть. */
internal data class TerminalToken(val value: String, val start: Int)

/**
 * Деление строки на слова по правилам оболочки: кавычки и обратная косая.
 *
 * Внутри одинарных кавычек обратная косая — обычный символ; снаружи и в
 * двойных она экранирует пробел, себя, закрывающую кавычку, а снаружи —
 * и любую кавычку.
 */
internal object TerminalTokens {
    /** Слова строки, или `null`, если кавычка не закрыта. */
    fun split(line: String): List<TerminalToken>? {
        val state = SplitState()
        var index = 0
        while (index < line.length) {
            index = state.take(line, index)
        }
        return if (state.quote != null) null else state.finish()
    }

    private class SplitState {
        val tokens = mutableListOf<TerminalToken>()
        val current = StringBuilder()
        var start = NONE
        var quote: Char? = null

        /** Обрабатывает символ на [index] и возвращает позицию следующего. */
        fun take(line: String, index: Int): Int {
            val ch = line[index]
            if (start == NONE && !ch.isWhitespace()) start = index
            return when {
                ch == quote -> index.also { quote = null } + 1
                ch in QUOTES && quote == null -> index.also { quote = ch } + 1
                escapes(line, index) -> index.also { current.append(line[index + 1]) } + 2
                ch.isWhitespace() && quote == null -> index.also { close() } + 1
                else -> index.also { current.append(ch) } + 1
            }
        }

        private fun escapes(line: String, index: Int): Boolean {
            val next = line.getOrNull(index + 1) ?: return false
            return line[index] == '\\' && quote != '\'' &&
                (next.isWhitespace() || next == '\\' || next == quote || (quote == null && next in QUOTES))
        }

        private fun close() {
            if (start != NONE) tokens += TerminalToken(current.toString(), start)
            current.clear()
            start = NONE
        }

        fun finish(): List<TerminalToken> = tokens.also { close() }
    }

    private const val NONE = -1
    private val QUOTES = setOf('\'', '"')
}
