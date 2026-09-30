package io.github.ncorror.nekoflash.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Подмножество xterm, на которое рассчитан `TERM=xterm-256color`. */
class VtScreenTest {
    private val esc = "\u001B"

    private fun screen(columns: Int = 10, rows: Int = 4) = VtScreen(columns, rows, historyLimit = 100)

    @Test
    fun plainTextWrapsAndScrollsIntoHistory() {
        val screen = screen(columns = 5, rows = 2)

        screen.feed("hello world\r\nnext")

        assertEquals("hello\n worl\nd\nnext", screen.snapshot().text)
    }

    @Test
    fun colorsBecomeRunsAndResetEndsThem() {
        val screen = screen()

        screen.feed("${esc}[1;32mok${esc}[0m and ${esc}[38;5;208mx${esc}[38;2;1;2;3my")

        val row = screen.snapshot().rows.first()
        assertEquals("ok and xy", row.text)
        assertEquals(VtRun(0, 2, VtStyle(foreground = 2, bold = true)), row.runs[0])
        assertEquals(VtStyle(foreground = 208), row.runs[1].style)
        assertEquals(VtStyle(foreground = VtStyle.RGB_FLAG or 0x010203), row.runs[2].style)
    }

    @Test
    fun cursorMovesAndErasesLikeXterm() {
        val screen = screen()

        screen.feed("abcdef${esc}[1;3HX${esc}[K\r\n12345${esc}[2D${esc}[P")

        assertEquals(listOf("abX", "1235"), screen.snapshot().rows.take(2).map { it.text })
    }

    @Test
    fun theAlternateScreenLeavesTheMainOneAsItWas() {
        val screen = screen()
        screen.feed("prompt$ ")

        screen.feed("${esc}[?1049h${esc}[Htop output${esc}[?25l")
        assertEquals("top output", screen.snapshot().rows.first().text)
        assertFalse(screen.snapshot().cursorVisible)

        screen.feed("${esc}[?1049l${esc}[?25h")
        assertEquals("prompt$", screen.snapshot().rows.first().text)
        assertEquals(8, screen.snapshot().cursorColumn)
    }

    @Test
    fun theScrollRegionKeepsTheLinesOutsideIt() {
        val screen = screen(rows = 4)
        screen.feed("head\r\n1\r\n2\r\nfoot")

        screen.feed("${esc}[2;3r${esc}[3;1H\n")

        assertEquals(listOf("head", "2", "", "foot"), screen.snapshot().rows.map { it.text })
    }

    @Test
    fun cursorPositionReportIsAnswered() {
        val replies = mutableListOf<String>()
        val screen = VtScreen(10, 4, reply = { replies += it })

        screen.feed("ab${esc}[6n${esc}[c")

        assertEquals(listOf("$esc[1;3R", "$esc[?1;2c"), replies)
    }

    @Test
    fun oscTitlesAreSkipped() {
        val screen = screen()

        screen.feed("${esc}]0;title\u0007a${esc}]2;t$esc\\b")

        assertEquals("ab", screen.snapshot().text)
    }

    @Test
    fun colonSeparatedTrueColorDoesNotTreatEmptySubparameterAsRed() {
        val screen = screen()

        screen.feed("\u001B[38:2::255:120:0mX")

        val style = screen.snapshot().rows.first().runs.single().style
        assertEquals(VtStyle.RGB_FLAG or 0xFF7800, style.foreground)
    }

    @Test
    fun resizeKeepsTheCursorOnScreen() {
        val screen = screen(columns = 10, rows = 4)
        screen.feed("1\r\n2\r\n3\r\n4")

        screen.resize(6, 2)

        assertEquals(listOf("1", "2", "3", "4"), screen.snapshot().rows.map { it.text })
        assertEquals(3, screen.snapshot().cursorRow)
    }
}
