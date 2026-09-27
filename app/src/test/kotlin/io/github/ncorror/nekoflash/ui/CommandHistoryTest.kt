package io.github.ncorror.nekoflash.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * История набранных команд.
 *
 * Проверяются три правила, и каждое выведено из того, как такой список ломается
 * на практике, а не из вкуса.
 */
class CommandHistoryTest {
    /** Свежее сверху: ищут почти всегда последнее. */
    @Test
    fun theNewestCommandComesFirst() {
        val history = CommandHistory()
        history.add("getvar:product")
        history.add("getvar:current-slot")

        assertEquals(listOf("getvar:current-slot", "getvar:product"), history.entries())
    }

    /**
     * Повтор переезжает наверх, а не задваивается.
     *
     * Иначе история из двадцати строк оказывается тремя командами, повторёнными
     * по семь раз, — то есть бесполезной ровно тогда, когда она нужнее всего.
     */
    @Test
    fun aRepeatedCommandMovesUpInsteadOfPilingUp() {
        val history = CommandHistory()
        history.add("a")
        history.add("b")
        history.add("a")

        assertEquals(listOf("a", "b"), history.entries())
    }

    /** Пустое не запоминается: случайная отправка не должна съедать строку. */
    @Test
    fun blankInputIsNotRemembered() {
        val history = CommandHistory()
        history.add("")
        history.add("   ")

        assertTrue(history.entries().isEmpty())
    }

    /** Пробелы по краям не делают ту же команду другой. */
    @Test
    fun surroundingSpacesDoNotMakeItADifferentCommand() {
        val history = CommandHistory()
        history.add("getvar:product")
        history.add("  getvar:product  ")

        assertEquals(listOf("getvar:product"), history.entries())
    }

    /** За пределом забывается самое старое, а не самое новое. */
    @Test
    fun theOldestFallsOffTheEndAndNotTheNewest() {
        val history = CommandHistory(limit = 2)
        history.add("first")
        history.add("second")
        history.add("third")

        assertEquals(listOf("third", "second"), history.entries())
    }
}
