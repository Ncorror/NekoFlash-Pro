package io.github.ncorror.nekoflash.terminal

/**
 * Эмулятор терминала — подмножество xterm, на которое рассчитан
 * `TERM=xterm-256color`: управляющие символы, `ESC`-последовательности, `CSI`
 * с курсором, стиранием, вставкой и удалением, областью прокрутки, SGR,
 * режимами `?7`, `?25`, `?47`/`?1047`/`?1049`, и `OSC`, который пропускается.
 *
 * Запросы, на которые терминал обязан ответить (`DSR`, первичный `DA`),
 * получают ответ через [reply] — он уходит в оболочку, как ввод.
 * Ширина символа — одна клетка: широкие символы CJK занимают одну.
 */
class VtScreen(
    columns: Int = DEFAULT_COLUMNS,
    rows: Int = DEFAULT_ROWS,
    historyLimit: Int = DEFAULT_HISTORY,
    private val reply: (String) -> Unit = {},
) {
    private val buffer = VtBuffer(columns, rows, historyLimit)
    private var state = State.GROUND
    private val params = StringBuilder()
    private var privateMarker: Char? = null

    val columns: Int get() = buffer.columns
    val rows: Int get() = buffer.rows

    /** Включены ли стрелки в режиме приложения (`?1h`): так их и надо слать. */
    var applicationCursorKeys: Boolean = false
        private set

    fun feed(text: String) {
        for (char in text) step(char)
    }

    fun resize(columns: Int, rows: Int) {
        val changed = columns != buffer.columns || rows != buffer.rows
        if (columns > 0 && rows > 0 && changed) buffer.resize(columns, rows)
    }

    fun snapshot(): VtSnapshot = buffer.snapshot()

    private fun step(char: Char) {
        when (state) {
            State.GROUND -> ground(char)
            State.ESCAPE -> escape(char)
            State.CSI -> csi(char)
            State.OSC -> if (char == BEL) state = State.GROUND else if (char == ESC) state = State.OSC_ESCAPE
            State.OSC_ESCAPE -> state = if (char == '\\') State.GROUND else State.OSC
            State.SKIP_ONE -> state = State.GROUND
        }
    }

    private fun ground(char: Char) {
        when (char) {
            ESC -> state = State.ESCAPE
            '\r' -> buffer.carriageReturn()
            '\n', VT, FF -> buffer.lineFeed()
            '\b' -> buffer.moveTo(buffer.row, buffer.column - 1)
            '\t' -> buffer.tab()
            BEL, SHIFT_IN, SHIFT_OUT, NUL -> Unit
            else -> if (char >= ' ' && char != DEL) buffer.print(char)
        }
    }

    private fun escape(char: Char) {
        state = State.GROUND
        when (char) {
            '[' -> {
                params.setLength(0)
                privateMarker = null
                state = State.CSI
            }
            ']' -> state = State.OSC
            '(', ')', '*', '+', '#' -> state = State.SKIP_ONE
            '7' -> buffer.saveCursor()
            '8' -> buffer.restoreCursor()
            'D' -> buffer.lineFeed()
            'M' -> buffer.reverseIndex()
            'E' -> {
                buffer.carriageReturn()
                buffer.lineFeed()
            }
            'c' -> {
                buffer.reset()
                applicationCursorKeys = false
            }
            else -> Unit
        }
    }

    private fun csi(char: Char) {
        when {
            char in '0'..'9' || char == ';' || char == ':' -> params.append(char)
            char in "?>=<" && params.isEmpty() -> privateMarker = char
            char in ' '..'/' -> Unit
            char in '@'..'~' -> {
                state = State.GROUND
                dispatch(char, parsedParams())
            }
            else -> state = State.GROUND
        }
    }

    private fun parsedParams(): List<Int> =
        if (params.isEmpty()) emptyList() else params.split(';', ':').map { it.toIntOrNull() ?: 0 }

    private fun dispatch(final: Char, values: List<Int>) {
        when (privateMarker) {
            null -> VtCsi.run(buffer, final, values, ::answer)
            '?' -> privateMode(final, values)
            else -> Unit
        }
    }

    /** `DSR` и первичный `DA`: ответы, которых ждут программы вроде `resize`. */
    private fun answer(final: Char, first: Int) {
        when {
            final == 'n' && first == STATUS -> reply("$ESC[0n")
            final == 'n' && first == CURSOR -> reply("$ESC[${buffer.row + 1};${buffer.column + 1}R")
            final == 'c' && first == 0 -> reply("$ESC[?1;2c")
        }
    }

    private fun privateMode(final: Char, values: List<Int>) {
        val on = when (final) {
            'h' -> true
            'l' -> false
            else -> return
        }
        for (mode in values) {
            when (mode) {
                APP_CURSOR -> applicationCursorKeys = on
                AUTOWRAP -> buffer.autowrap = on
                SHOW_CURSOR -> buffer.cursorVisible = on
                ALT_47, ALT_1047 -> buffer.useAlternate(on)
                ALT_1049 -> alternateWithCursor(on)
            }
        }
    }

    private fun alternateWithCursor(on: Boolean) {
        if (on) {
            buffer.saveCursor()
            buffer.useAlternate(true)
            buffer.eraseDisplay(ERASE_ALL)
        } else {
            buffer.useAlternate(false)
            buffer.restoreCursor()
        }
    }

    private enum class State { GROUND, ESCAPE, CSI, OSC, OSC_ESCAPE, SKIP_ONE }

    companion object {
        const val DEFAULT_COLUMNS: Int = 80
        const val DEFAULT_ROWS: Int = 24
        const val DEFAULT_HISTORY: Int = 1000

        /** Тип терминала, который уходит устройству в `TERM`. */
        const val TERM: String = "xterm-256color"

        private const val ESC = '\u001B'
        private const val BEL = '\u0007'
        private const val VT = '\u000B'
        private const val FF = '\u000C'
        private const val SHIFT_OUT = '\u000E'
        private const val SHIFT_IN = '\u000F'
        private const val NUL = '\u0000'
        private const val DEL = '\u007F'
        private const val STATUS = 5
        private const val CURSOR = 6
        private const val APP_CURSOR = 1
        private const val AUTOWRAP = 7
        private const val SHOW_CURSOR = 25
        private const val ALT_47 = 47
        private const val ALT_1047 = 1047
        private const val ALT_1049 = 1049
        private const val ERASE_ALL = 2
    }
}

/** `CSI` без частного маркера: курсор, стирание, вставка, прокрутка, SGR. */
internal object VtCsi {
    fun run(buffer: VtBuffer, final: Char, values: List<Int>, answer: (Char, Int) -> Unit) {
        val first = values.firstOrNull() ?: 0
        val count = first.coerceAtLeast(1)
        when (final) {
            'A' -> buffer.moveVertically(-count)
            'B', 'e' -> buffer.moveVertically(count)
            'C', 'a' -> buffer.moveTo(buffer.row, buffer.column + count)
            'D' -> buffer.moveTo(buffer.row, buffer.column - count)
            'E' -> buffer.moveTo(buffer.row + count, 0)
            'F' -> buffer.moveTo(buffer.row - count, 0)
            'G', '`' -> buffer.moveTo(buffer.row, count - 1)
            'H', 'f' -> buffer.moveTo(count - 1, (values.getOrNull(1) ?: 1).coerceAtLeast(1) - 1)
            'd' -> buffer.moveTo(count - 1, buffer.column)
            'J' -> buffer.eraseDisplay(first)
            'K' -> buffer.eraseLine(first)
            'X' -> buffer.eraseCharacters(count)
            'L' -> buffer.insertLines(count)
            'M' -> buffer.deleteLines(count)
            'P' -> buffer.deleteCharacters(count)
            '@' -> buffer.insertCharacters(count)
            'S' -> buffer.scrollUp(count)
            'T' -> buffer.scrollDown(count)
            'm' -> buffer.style = VtSgr.apply(buffer.style, values)
            'r' -> buffer.setRegion(count - 1, (values.getOrNull(1) ?: buffer.rows).coerceAtLeast(1) - 1)
            's' -> buffer.saveCursor()
            'u' -> buffer.restoreCursor()
            'n', 'c' -> answer(final, first)
            else -> Unit
        }
    }
}
