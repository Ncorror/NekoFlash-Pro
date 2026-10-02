package io.github.ncorror.nekoflash.ui

import io.github.ncorror.nekoflash.adb.AdbTerminalState
import io.github.ncorror.nekoflash.adb.TerminalKey

/** Actions the Terminal v2 UI actually sends to the live ADB shell. */
data class TerminalActions(
    val onInterrupt: () -> Unit = {},
    val onResize: (Int, Int) -> Unit = { _, _ -> },
    val onKey: (TerminalKey) -> Unit = {},
    val onType: (String) -> Unit = {},
)

/** Terminal controller state and the currently selected remote shell. */
data class TerminalPanel(
    val tabs: List<AdbTerminalState> = listOf(AdbTerminalState()),
    val selected: Int = 0,
) {
    val current: AdbTerminalState get() = tabs.getOrElse(selected) { AdbTerminalState() }
}
