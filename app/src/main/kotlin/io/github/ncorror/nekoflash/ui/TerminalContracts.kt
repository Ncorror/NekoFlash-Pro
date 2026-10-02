package io.github.ncorror.nekoflash.ui

import io.github.ncorror.nekoflash.adb.AdbTerminalState
import io.github.ncorror.nekoflash.adb.TerminalKey

/** Actions exposed by the live terminal controller to the UI. */
data class TerminalActions(
    val onStart: () -> Unit = {},
    val onSend: (String) -> Unit = {},
    val onInterrupt: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onResize: (Int, Int) -> Unit = { _, _ -> },
    val onKey: (TerminalKey) -> Unit = {},
    val onType: (String) -> Unit = {},
    val onAddTab: () -> Unit = {},
    val onSelectTab: (Int) -> Unit = {},
    val onCloseTab: (Int) -> Unit = {},
)

/** Terminal tabs and the currently selected remote shell state. */
data class TerminalPanel(
    val tabs: List<AdbTerminalState> = listOf(AdbTerminalState()),
    val selected: Int = 0,
) {
    val current: AdbTerminalState get() = tabs.getOrElse(selected) { AdbTerminalState() }
}
