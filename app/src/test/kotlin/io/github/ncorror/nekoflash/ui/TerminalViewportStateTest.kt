package io.github.ncorror.nekoflash.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalViewportStateTest {
    @Test
    fun followsLiveScreenOriginByDefault() {
        val state = TerminalViewportState()

        assertTrue(state.followLive)
        assertEquals(24, state.targetRow(screenStartRow = 24, lastRow = 47))
        assertEquals(31, state.targetRow(screenStartRow = 31, lastRow = 54))
        assertEquals(0, state.targetScrollOffsetPx())
    }

    @Test
    fun manualHistoryScrollKeepsTheSameHistoricalRowAsLiveMoves() {
        val state = TerminalViewportState()

        state.noteUserScroll()
        state.settleUserScroll(
            firstVisibleRow = 10,
            firstVisibleScrollOffsetPx = 7,
            screenStartRow = 24,
        )

        assertFalse(state.followLive)
        assertEquals(14, state.historyOffset)
        assertEquals(7, state.historyScrollOffsetPx)
        assertEquals(10, state.targetRow(screenStartRow = 31, lastRow = 54))
        assertEquals(21, state.historyOffset)
        assertEquals(7, state.targetScrollOffsetPx())
    }

    @Test
    fun programmaticScrollCannotDisableLiveFollow() {
        val state = TerminalViewportState()

        state.settleUserScroll(
            firstVisibleRow = 3,
            firstVisibleScrollOffsetPx = 6,
            screenStartRow = 24,
        )

        assertTrue(state.followLive)
        assertEquals(0, state.historyOffset)
        assertEquals(0, state.historyScrollOffsetPx)
        assertEquals(24, state.targetRow(screenStartRow = 24, lastRow = 47))
    }

    @Test
    fun scrollingBackToLiveReenablesFollowing() {
        val state = TerminalViewportState()
        state.noteUserScroll()
        state.settleUserScroll(
            firstVisibleRow = 8,
            firstVisibleScrollOffsetPx = 5,
            screenStartRow = 24,
        )

        state.noteUserScroll()
        state.settleUserScroll(
            firstVisibleRow = 24,
            firstVisibleScrollOffsetPx = 3,
            screenStartRow = 24,
        )

        assertTrue(state.followLive)
        assertEquals(0, state.historyOffset)
        assertEquals(0, state.historyScrollOffsetPx)
        assertEquals(0, state.targetScrollOffsetPx())
    }

    @Test
    fun shrinkingLiveOriginCanReturnHistoryViewToLive() {
        val state = TerminalViewportState()
        state.noteUserScroll()
        state.settleUserScroll(
            firstVisibleRow = 20,
            firstVisibleScrollOffsetPx = 9,
            screenStartRow = 24,
        )

        assertEquals(0, state.targetRow(screenStartRow = 0, lastRow = 7))
        assertTrue(state.followLive)
        assertEquals(0, state.historyOffset)
        assertEquals(0, state.historyScrollOffsetPx)
    }

    @Test
    fun targetIsAlwaysInsideSnapshot() {
        val state = TerminalViewportState()
        state.noteUserScroll()
        state.settleUserScroll(
            firstVisibleRow = 0,
            firstVisibleScrollOffsetPx = 0,
            screenStartRow = 100,
        )

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
