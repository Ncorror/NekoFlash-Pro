package io.github.ncorror.nekoflash.terminal

/** Строка экрана: символы и оформление каждой клетки. */
internal class VtLine(columns: Int) {
    var chars: CharArray = CharArray(columns) { ' ' }
    var styles: Array<VtStyle> = Array(columns) { VtStyle.PLAIN }

    fun resize(columns: Int) {
        chars = CharArray(columns) { index -> chars.getOrElse(index) { ' ' } }
        styles = Array(columns) { index -> styles.getOrElse(index) { VtStyle.PLAIN } }
    }

    fun clear(from: Int, to: Int, style: VtStyle) {
        for (index in from.coerceAtLeast(0) until to.coerceAtMost(chars.size)) {
            chars[index] = ' '
            styles[index] = style
        }
    }

    /** Строка для показа: текст без хвостовых пробелов простого оформления и отрезки стилей. */
    fun freeze(): VtRow {
        var end = chars.size
        while (end > 0 && chars[end - 1] == ' ' && styles[end - 1] == VtStyle.PLAIN) end--
        val runs = mutableListOf<VtRun>()
        var start = 0
        while (start < end) {
            var stop = start + 1
            while (stop < end && styles[stop] == styles[start]) stop++
            if (styles[start] != VtStyle.PLAIN) runs += VtRun(start, stop, styles[start])
            start = stop
        }
        return VtRow(String(chars, 0, end), runs)
    }
}

/** Отрезок строки в одном оформлении. */
data class VtRun(val start: Int, val end: Int, val style: VtStyle)

/** Строка, готовая к показу. */
data class VtRow(val text: String, val runs: List<VtRun>)

private fun VtRow.toLine(columns: Int): VtLine = VtLine(columns).also { line ->
    val count = minOf(text.length, columns)
    for (index in 0 until count) line.chars[index] = text[index]
    for (run in runs) {
        val start = run.start.coerceIn(0, columns)
        val end = run.end.coerceIn(start, columns)
        for (index in start until end) line.styles[index] = run.style
    }
}

/**
 * Экран терминала: сетка [columns] × [rows], курсор, область прокрутки и
 * второй экран (`?1049h`), у которого нет истории, как у xterm.
 *
 * Уехавшие вверх строки основного экрана уходят в историю без искусственного
 * ограничения по числу строк. Ничего из вывода терминала не выбрасывается.
 */
internal class VtBuffer(var columns: Int, var rows: Int) {
    private var main = MutableList(rows) { VtLine(columns) }
    private var alternate: MutableList<VtLine>? = null
    val history = ArrayDeque<VtRow>()

    internal val lines: MutableList<VtLine> get() = alternate ?: main

    var row = 0
    var column = 0

    /** Курсор стоит за последней колонкой: следующий символ перенесёт строку. */
    var wrapPending = false
    var style = VtStyle.PLAIN
    var top = 0
    var bottom = rows - 1
    var autowrap = true
    var cursorVisible = true
    private var saved = Saved(0, 0, VtStyle.PLAIN)

    val onAlternate: Boolean get() = alternate != null

    fun print(char: Char) {
        if (wrapPending && autowrap) {
            column = 0
            lineFeed()
        }
        wrapPending = false
        lines[row].chars[column] = char
        lines[row].styles[column] = style
        if (column == columns - 1) wrapPending = true else column++
    }

    fun lineFeed() {
        wrapPending = false
        if (row == bottom) scrollUp(1) else if (row < rows - 1) row++
    }

    fun reverseIndex() {
        wrapPending = false
        if (row == top) scrollDown(1) else if (row > 0) row--
    }

    fun carriageReturn() {
        column = 0
        wrapPending = false
    }

    fun moveTo(newRow: Int, newColumn: Int) {
        row = newRow.coerceIn(0, rows - 1)
        column = newColumn.coerceIn(0, columns - 1)
        wrapPending = false
    }

    /** Вверх и вниз — в пределах области прокрутки, если курсор в ней. */
    fun moveVertically(by: Int) {
        val low = if (row in top..bottom) top else 0
        val high = if (row in top..bottom) bottom else rows - 1
        row = (row + by).coerceIn(low, high)
        wrapPending = false
    }

    fun tab() {
        column = ((column / TAB) + 1) * TAB
        column = column.coerceAtMost(columns - 1)
    }

    fun scrollUp(count: Int) = repeat(count.coerceAtMost(bottom - top + 1)) {
        val gone = lines.removeAt(top)
        if (top == 0 && alternate == null) archive(gone)
        lines.add(bottom, VtLine(columns).also { it.clear(0, columns, blank()) })
    }

    fun scrollDown(count: Int) = repeat(count.coerceAtMost(bottom - top + 1)) {
        lines.removeAt(bottom)
        lines.add(top, VtLine(columns).also { it.clear(0, columns, blank()) })
    }

    fun setRegion(newTop: Int, newBottom: Int) {
        if (newTop < newBottom && newBottom < rows) {
            top = newTop
            bottom = newBottom
            moveTo(0, 0)
        }
    }

    fun saveCursor() {
        saved = Saved(row, column, style)
    }

    fun restoreCursor() {
        moveTo(saved.row, saved.column)
        style = saved.style
    }

    fun useAlternate(on: Boolean) {
        if (on == (alternate != null)) return
        alternate = if (on) MutableList(rows) { VtLine(columns) } else null
        top = 0
        bottom = rows - 1
        if (on) moveTo(0, 0)
    }

    /**
     * Resize keeps the terminal grid reversible when only its height changes.
     *
     * Soft-keyboard and lifecycle transitions change rows much more often than
     * columns. Blank rows below the cursor are discarded first. If real rows
     * must leave the visible grid they enter scrollback, and an expansion pulls
     * those same rows back before adding new blanks. This mirrors the behaviour
     * users expect from Termux/NetHunter and avoids history/blank gaps after an
     * IME or background/foreground resize cycle.
     */
    fun resize(newColumns: Int, newRows: Int) {
        if (newColumns <= 0 || newRows <= 0) return
        if (newColumns == columns && alternate == null) {
            resizeMainRows(newRows)
            return
        }

        val oldRows = rows
        for (screen in listOfNotNull(main, alternate)) {
            screen.forEach { it.resize(newColumns) }
            while (screen.size > newRows) {
                val gone = screen.removeAt(0)
                if (screen === main) archive(gone)
            }
            while (screen.size < newRows) screen.add(VtLine(newColumns))
        }
        val shift = oldRows - newRows
        columns = newColumns
        rows = newRows
        top = 0
        bottom = rows - 1
        saved = saved.clamp(rows, columns, shift.coerceAtLeast(0))
        moveTo(if (shift > 0) row - shift else row, column)
    }

    private fun resizeMainRows(newRows: Int) {
        if (newRows == rows || newRows <= 0) return
        if (newRows < rows) shrinkMainRows(newRows) else expandMainRows(newRows)
        rows = newRows
        top = 0
        bottom = rows - 1
        row = row.coerceIn(0, rows - 1)
        column = column.coerceIn(0, columns - 1)
        saved = saved.clamp(rows, columns)
        wrapPending = false
    }

    private fun shrinkMainRows(newRows: Int) {
        var remaining = rows - newRows
        while (remaining > 0 && main.size > newRows && row < main.lastIndex && main.last().isVisuallyBlank()) {
            main.removeAt(main.lastIndex)
            remaining--
        }
        while (remaining > 0 && main.size > newRows) {
            archive(main.removeAt(0))
            row = (row - 1).coerceAtLeast(0)
            saved = saved.copy(row = (saved.row - 1).coerceAtLeast(0))
            remaining--
        }
    }

    private fun expandMainRows(newRows: Int) {
        var remaining = newRows - rows
        while (remaining > 0 && history.isNotEmpty()) {
            main.add(0, history.removeLast().toLine(columns))
            row++
            saved = saved.copy(row = saved.row + 1)
            remaining--
        }
        repeat(remaining) {
            main.add(VtLine(columns).also { it.clear(0, columns, blank()) })
        }
    }

    private fun VtLine.isVisuallyBlank(): Boolean = freeze().text.isEmpty()

    fun reset() {
        main = MutableList(rows) { VtLine(columns) }
        alternate = null
        history.clear()
        style = VtStyle.PLAIN
        top = 0
        bottom = rows - 1
        autowrap = true
        cursorVisible = true
        moveTo(0, 0)
    }

    /** Снимок экрана: история и текущие строки. */
    fun snapshot(): VtSnapshot = VtSnapshot(
        rows = history.toList() + lines.map { it.freeze() },
        cursorRow = history.size + row,
        cursorColumn = column,
        cursorVisible = cursorVisible,
    )

    /** Стертые клетки получают текущий фон, как у xterm (`bce`). */
    internal fun blank(): VtStyle =
        if (style.background == VtStyle.DEFAULT) VtStyle.PLAIN else VtStyle(background = style.background)

    private fun archive(line: VtLine) {
        history.add(line.freeze())
    }

    private data class Saved(val row: Int, val column: Int, val style: VtStyle) {
        fun clamp(rows: Int, columns: Int, shift: Int = 0): Saved = copy(
            row = (row - shift).coerceIn(0, rows - 1),
            column = column.coerceIn(0, columns - 1),
        )
    }

    private companion object {
        const val TAB = 8
    }
}

/** То, что показывает экран терминала. */
data class VtSnapshot(
    val rows: List<VtRow>,
    val cursorRow: Int,
    val cursorColumn: Int,
    val cursorVisible: Boolean,
) {
    /** Текст без оформления: строки через `\n`, пустой хвост отброшен. */
    val text: String
        get() = rows.map { it.text }.dropLastWhile { it.isEmpty() }.joinToString("\n")

    companion object {
        val EMPTY: VtSnapshot = VtSnapshot(emptyList(), 0, 0, true)
    }
}
