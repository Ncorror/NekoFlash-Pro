package io.github.ncorror.nekoflash.terminal

/*
 * Правка экрана: стирание, вставка и удаление строк и символов — `ED`, `EL`,
 * `ECH`, `IL`, `DL`, `DCH`, `ICH`. Отдельно от [VtBuffer], чтобы тот держал
 * курсор, прокрутку и экраны.
 */

/** `ED`: 0 — от курсора, 1 — до курсора, 2 — весь экран, 3 — и история. */
internal fun VtBuffer.eraseDisplay(mode: Int) {
    when (mode) {
        FROM_CURSOR -> {
            eraseLine(FROM_CURSOR)
            for (index in row + 1 until rows) lines[index].clear(0, columns, blank())
        }
        TO_CURSOR -> {
            eraseLine(TO_CURSOR)
            for (index in 0 until row) lines[index].clear(0, columns, blank())
        }
        WHOLE -> lines.forEach { it.clear(0, columns, blank()) }
        HISTORY -> history.clear()
    }
}

/** `EL`: 0 — от курсора, 1 — до курсора включительно, 2 — вся строка. */
internal fun VtBuffer.eraseLine(mode: Int) {
    when (mode) {
        FROM_CURSOR -> lines[row].clear(column, columns, blank())
        TO_CURSOR -> lines[row].clear(0, column + 1, blank())
        WHOLE -> lines[row].clear(0, columns, blank())
    }
}

internal fun VtBuffer.eraseCharacters(count: Int) = lines[row].clear(column, column + count, blank())

internal fun VtBuffer.insertLines(count: Int) {
    if (row !in top..bottom) return
    repeat(count.coerceAtMost(bottom - row + 1)) {
        lines.removeAt(bottom)
        lines.add(row, VtLine(columns).also { it.clear(0, columns, blank()) })
    }
    column = 0
}

internal fun VtBuffer.deleteLines(count: Int) {
    if (row !in top..bottom) return
    repeat(count.coerceAtMost(bottom - row + 1)) {
        lines.removeAt(row)
        lines.add(bottom, VtLine(columns).also { it.clear(0, columns, blank()) })
    }
    column = 0
}

internal fun VtBuffer.deleteCharacters(count: Int) {
    val line = lines[row]
    val shift = count.coerceAtMost(columns - column)
    for (index in column until columns) {
        val from = index + shift
        line.chars[index] = if (from < columns) line.chars[from] else ' '
        line.styles[index] = if (from < columns) line.styles[from] else blank()
    }
}

internal fun VtBuffer.insertCharacters(count: Int) {
    val line = lines[row]
    val shift = count.coerceAtMost(columns - column)
    for (index in columns - 1 downTo column) {
        val from = index - shift
        line.chars[index] = if (from >= column) line.chars[from] else ' '
        line.styles[index] = if (from >= column) line.styles[from] else blank()
    }
}

private const val FROM_CURSOR = 0
private const val TO_CURSOR = 1
private const val WHOLE = 2
private const val HISTORY = 3
