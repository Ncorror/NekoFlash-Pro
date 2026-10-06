package io.github.ncorror.nekoflash.ui

import io.github.ncorror.nekoflash.adb.AdbTerminalState
import io.github.ncorror.nekoflash.adb.TerminalKey

/** Actions the Terminal v2 UI sends to the live ADB shell. */
data class TerminalActions(
    val onInterrupt: () -> Unit,
    val onResize: (Int, Int) -> Unit,
    val onKey: (TerminalKey) -> Unit,
    val onType: (String) -> Unit,
)

/** Terminal controller state and the currently selected remote shell. */
data class TerminalPanel(
    val tabs: List<AdbTerminalState>,
    val selected: Int,
) {
    val current: AdbTerminalState get() = tabs[selected]
}
