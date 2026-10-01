package io.github.ncorror.nekoflash.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalViewportStateTest {
    @Test
    fun followsLiveScreenOriginByDefault() {
        val state = TerminalViewportState()

        assertTrue(state.followLive)
        assertEquals(24, state.targetRow(screenStartRow = 24, lastRow = 47))
        assertEquals(31, state.targetRow(screenStartRow = 31, lastRow = 54))
    }

    @Test
    fun manualHistoryScrollKeepsOffsetAcrossLiveMovement() {
        val state = TerminalViewportState()

        state.noteUserScroll()
        state.settleUserScroll(firstVisibleRow = 10, screenStartRow = 24)

        assertFalse(state.followLive)
        assertEquals(14, state.historyOffset)
        assertEquals(17, state.targetRow(screenStartRow = 31, lastRow = 54))
    }

    @Test
    fun programmaticScrollCannotDisableLiveFollow() {
        val state = TerminalViewportState()

        state.settleUserScroll(firstVisibleRow = 3, screenStartRow = 24)

        assertTrue(state.followLive)
        assertEquals(0, state.historyOffset)
        assertEquals(24, state.targetRow(screenStartRow = 24, lastRow = 47))
    }

    @Test
    fun scrollingBackToLiveReenablesFollowing() {
        val state = TerminalViewportState()
        state.noteUserScroll()
        state.settleUserScroll(firstVisibleRow = 8, screenStartRow = 24)

        state.noteUserScroll()
        state.settleUserScroll(firstVisibleRow = 24, screenStartRow = 24)

        assertTrue(state.followLive)
        assertEquals(0, state.historyOffset)
    }

    @Test
    fun targetIsAlwaysInsideSnapshot() {
        val state = TerminalViewportState()
        state.noteUserScroll()
        state.settleUserScroll(firstVisibleRow = 0, screenStartRow = 100)

        assertEquals(0, state.targetRow(screenStartRow = 5, lastRow = 9))
        assertEquals(0, state.targetRow(screenStartRow = 0, lastRow = -1))
    }

    @Test
    fun hostTailOnlyChangesAfterARealUserScroll() {
        val state = TerminalTailViewportState()

        state.settleUserScroll(lastVisibleRow = 3, lastRow = 20)
        assertTrue(state.followTail)

        state.noteUserScroll()
        state.settleUserScroll(lastVisibleRow = 3, lastRow = 20)
        assertFalse(state.followTail)
    }

    @Test
    fun hostTailReenablesWhenUserReturnsToBottom() {
        val state = TerminalTailViewportState()
        state.noteUserScroll()
        state.settleUserScroll(lastVisibleRow = 3, lastRow = 20)

        state.noteUserScroll()
        state.settleUserScroll(lastVisibleRow = 19, lastRow = 20)

        assertTrue(state.followTail)
    }
}
