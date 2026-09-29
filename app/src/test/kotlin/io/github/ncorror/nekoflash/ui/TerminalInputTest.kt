package io.github.ncorror.nekoflash.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Ввод прямо в экран терминала: что уходит в оболочку от клавиатуры (D078). */
class TerminalInputTest {
    @Test
    fun typedCharactersGoAsTheyAre() {
        assertEquals("ls", typedText(" ls"))
        assertNull("ничего не набрано", typedText(" "))
    }

    /** Стёрли пробел-заглушку — ушёл `DEL`, как Backspace у терминалов. */
    @Test
    fun deletingTheSentinelSendsBackspace() {
        assertEquals("\u007F", typedText(""))
    }

    /** Enter — `CR`, как у настоящего терминала; tty сам превратит его в `NL`. */
    @Test
    fun enterBecomesCarriageReturn() {
        assertEquals("\r", typedText(" \n"))
        assertEquals("id\r", typedText(" id\n"))
    }

    @Test
    fun ctrlTurnsLettersIntoControlCodes() {
        assertEquals("\u0003", withModifiers("c", ctrl = true, alt = false))
        assertEquals("\u0004", withModifiers("D", ctrl = true, alt = false))
        assertEquals("\u001B", withModifiers("[", ctrl = true, alt = false))
        assertEquals("\u007F", withModifiers("?", ctrl = true, alt = false))
        assertEquals("\u0000", withModifiers(" ", ctrl = true, alt = false))
    }

    /** Модификатор действует на первый символ, остальное уходит как есть. */
    @Test
    fun modifiersApplyToTheFirstCharacterOnly() {
        assertEquals("\u0003at", withModifiers("cat", ctrl = true, alt = false))
        assertEquals("\u001Bb", withModifiers("b", ctrl = false, alt = true))
        assertEquals("\u001B\u0018", withModifiers("x", ctrl = true, alt = true))
        assertEquals("ok", withModifiers("ok", ctrl = false, alt = false))
    }

    @Test
    fun digitsAreNotControlCharacters() {
        assertEquals('1', controlOf('1'))
    }
}
