package io.github.ncorror.nekoflash.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Строка терминала и управляющие клавиши; многострочный буфер не выполняет вторую команду. */
class TerminalInputTest {
    @Test
    fun pastedCommandStaysOneLine() {
        assertEquals("id", clipboardLine("id"))
        assertEquals("getprop ro.product.device", clipboardLine("getprop ro.product.device\nreboot"))
        assertEquals("ls /system", clipboardLine("ls /system\r\nrm -rf /"))
    }

    @Test
    fun emptyClipboardIsAnEmptyDraft() {
        assertEquals("", clipboardLine(""))
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

    @Test
    fun imeNewlineBecomesTerminalEnter() {
        assertEquals("id\r", normalizeCommittedTerminalText("id\n", "\r"))
        assertEquals("id\n", normalizeCommittedTerminalText("id\r", "\n"))
        assertEquals("echo ok", normalizeCommittedTerminalText("echo ok", "\r"))
    }
}
