package io.github.ncorror.nekoflash.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class VtViewportContractTest {
    @Test
    fun snapshotMarksWhereTheLiveGridStartsAfterScrollback() {
        val screen = VtScreen(columns = 10, rows = 3)
        screen.feed("1\r\n2\r\n3\r\n4")

        val snapshot = screen.snapshot()

        assertEquals(1, snapshot.screenStartRow)
        assertEquals(listOf("2", "3", "4"), snapshot.rows.drop(snapshot.screenStartRow).map { it.text })
        assertEquals(3, snapshot.cursorRow)
    }

    @Test
    fun liveGridOriginTracksReversibleHeightResize() {
        val screen = VtScreen(columns = 10, rows = 4)
        screen.feed("1\r\n2\r\n3\r\n4")

        screen.resize(columns = 10, rows = 2)
        assertEquals(2, screen.snapshot().screenStartRow)

        screen.resize(columns = 10, rows = 4)
        assertEquals(0, screen.snapshot().screenStartRow)
    }

    @Test
    fun alternateScreenDoesNotExposeMainScrollback() {
        val esc = "\u001B"
        val screen = VtScreen(columns = 10, rows = 2)
        screen.feed("old-1\r\nold-2\r\nold-3")

        screen.feed("${esc}[?1049h${esc}[Htop")
        val alternate = screen.snapshot()

        assertEquals(0, alternate.screenStartRow)
        assertEquals(listOf("top", ""), alternate.rows.map { it.text })

        screen.feed("${esc}[?1049l")
        assertEquals("old-1", screen.snapshot().rows.first().text)
    }
}
