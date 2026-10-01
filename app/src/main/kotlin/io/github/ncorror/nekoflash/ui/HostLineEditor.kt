package io.github.ncorror.nekoflash.ui

import androidx.compose.runtime.MutableState
import io.github.ncorror.nekoflash.adb.TerminalKey

/** Line editing for the NekoFlash host command prompt. */
internal class HostLineEditor(
    private val input: MutableState<String>,
    private val cursor: MutableState<Int>,
    private val historyIndex: MutableState<Int>,
    private val history: MutableList<String>,
    private val transcript: MutableList<String>,
    private val prompt: String,
    private val onCommand: (String) -> Unit,
) {
    fun insert(text: String) {
        val at = cursor()
        input.value = input.value.substring(0, at) + text + input.value.substring(at)
        cursor.value = at + text.length
        historyIndex.value = history.size
    }

    fun backspace(byWord: Boolean) {
        if (byWord) deletePreviousWord() else deletePreviousCodePoint()
    }

    fun key(key: TerminalKey, byWord: Boolean) {
        when (key) {
            TerminalKey.ESCAPE -> Unit
            TerminalKey.TAB -> insert("\t")
            TerminalKey.LEFT -> moveLeft(byWord)
            TerminalKey.RIGHT -> moveRight(byWord)
            TerminalKey.HOME -> cursor.value = 0
            TerminalKey.END -> cursor.value = input.value.length
            TerminalKey.UP -> moveHistory(-1)
            TerminalKey.DOWN -> moveHistory(1)
            TerminalKey.PAGE_UP -> moveHistory(-HISTORY_PAGE)
            TerminalKey.PAGE_DOWN -> moveHistory(HISTORY_PAGE)
        }
    }

    fun control(text: String) {
        when (text.lowercase()) {
            "a" -> cursor.value = 0
            "c" -> cancel()
            "e" -> cursor.value = input.value.length
            "k" -> input.value = input.value.substring(0, cursor())
            "l" -> transcript.clear()
            "u" -> {
                input.value = input.value.substring(cursor())
                cursor.value = 0
            }
            "w" -> deletePreviousWord()
            else -> insert(text)
        }
    }

    fun alt(text: String) {
        when (text.lowercase()) {
            "b" -> cursor.value = previousWordStart()
            "f" -> cursor.value = nextWordEnd()
            "d" -> deleteNextWord()
            else -> insert(text)
        }
    }

    fun submit() {
        val command = input.value.trim()
        input.value = ""
        cursor.value = 0
        historyIndex.value = history.size
        if (command.isBlank()) return
        addHistory(command)
        if (command.equals("clear", ignoreCase = true) || command.equals("cls", ignoreCase = true)) {
            transcript.clear()
            return
        }
        transcript += "$prompt $command"
        onCommand(command)
    }

    private fun addHistory(command: String) {
        if (history.lastOrNull() != command) history += command
        historyIndex.value = history.size
    }

    private fun moveHistory(delta: Int) {
        if (history.isEmpty()) return
        val next = (historyIndex.value + delta).coerceIn(0, history.size)
        historyIndex.value = next
        input.value = if (next == history.size) "" else history[next]
        cursor.value = input.value.length
    }

    private fun cancel() {
        input.value = ""
        cursor.value = 0
        historyIndex.value = history.size
        transcript += "^C"
    }

    private fun deletePreviousCodePoint() {
        val at = cursor()
        if (at == 0) return
        val start = input.value.offsetByCodePoints(at, -1)
        input.value = input.value.removeRange(start, at)
        cursor.value = start
    }

    private fun deletePreviousWord() {
        val at = cursor()
        val start = previousWordStart()
        if (start < at) {
            input.value = input.value.removeRange(start, at)
            cursor.value = start
        }
    }

    private fun deleteNextWord() {
        val at = cursor()
        val end = nextWordEnd()
        if (end > at) input.value = input.value.removeRange(at, end)
    }

    private fun moveLeft(byWord: Boolean) {
        val at = cursor()
        cursor.value = when {
            at == 0 -> 0
            byWord -> previousWordStart()
            else -> input.value.offsetByCodePoints(at, -1)
        }
    }

    private fun moveRight(byWord: Boolean) {
        val at = cursor()
        cursor.value = when {
            at >= input.value.length -> input.value.length
            byWord -> nextWordEnd()
            else -> input.value.offsetByCodePoints(at, 1)
        }
    }

    private fun previousWordStart(): Int {
        var index = cursor()
        while (index > 0 && input.value[index - 1].isWhitespace()) index--
        while (index > 0 && !input.value[index - 1].isWhitespace()) index--
        return index
    }

    private fun nextWordEnd(): Int {
        var index = cursor()
        while (index < input.value.length && input.value[index].isWhitespace()) index++
        while (index < input.value.length && !input.value[index].isWhitespace()) index++
        return index
    }

    private fun cursor(): Int = cursor.value.coerceIn(0, input.value.length)

    private companion object {
        const val HISTORY_PAGE = 5
    }
}
