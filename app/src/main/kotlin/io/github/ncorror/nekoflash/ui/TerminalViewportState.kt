package io.github.ncorror.nekoflash.ui

/**
 * Explicit terminal viewport state.
 *
 * The terminal buffer owns text/history; Compose owns only rendering. This
 * object keeps the user's place in scrollback independently from LazyListState,
 * so lifecycle/layout/programmatic scrolls cannot be mistaken for a manual
 * history gesture.
 */
internal class TerminalViewportState {
    var followLive: Boolean = true
        private set

    var historyOffset: Int = 0
        private set

    private var userScrollPending: Boolean = false
    private var previousScreenStartRow: Int? = null

    /** Marks a real user scroll gesture. Programmatic scrolls never call this. */
    fun noteUserScroll() {
        userScrollPending = true
    }

    /**
     * Commits a user scroll once Compose has settled it.
     *
     * [historyOffset] is measured from the first live screen row backwards into
     * scrollback. Zero therefore means the live terminal grid is visible.
     */
    fun settleUserScroll(firstVisibleRow: Int, screenStartRow: Int) {
        if (!userScrollPending) return
        userScrollPending = false
        previousScreenStartRow = screenStartRow
        historyOffset = (screenStartRow - firstVisibleRow).coerceAtLeast(0)
        followLive = historyOffset == 0
    }

    /**
     * Target row for composition, resume and VT-grid resize.
     *
     * When output grows while the user reads history, the live-grid origin moves
     * forward. The offset grows by the same delta so the same historical row
     * remains anchored instead of jumping with new output.
     */
    fun targetRow(screenStartRow: Int, lastRow: Int): Int {
        reconcileScreenStart(screenStartRow)
        if (lastRow < 0) return 0
        val target = if (followLive) screenStartRow else screenStartRow - historyOffset
        return target.coerceIn(0, lastRow)
    }

    fun returnToLive() {
        historyOffset = 0
        followLive = true
        userScrollPending = false
    }

    private fun reconcileScreenStart(screenStartRow: Int) {
        val previous = previousScreenStartRow
        if (previous != null && !followLive) {
            historyOffset = (historyOffset + screenStartRow - previous).coerceAtLeast(0)
            if (historyOffset == 0) followLive = true
        }
        previousScreenStartRow = screenStartRow
    }
}

/** Explicit follow-tail state for the host transcript. */
internal class TerminalTailViewportState {
    var followTail: Boolean = true
        private set

    private var userScrollPending: Boolean = false

    fun noteUserScroll() {
        userScrollPending = true
    }

    fun settleUserScroll(lastVisibleRow: Int, lastRow: Int) {
        if (!userScrollPending) return
        userScrollPending = false
        followTail = lastRow < 0 || lastVisibleRow >= lastRow - 1
    }

    fun returnToTail() {
        followTail = true
        userScrollPending = false
    }
}
