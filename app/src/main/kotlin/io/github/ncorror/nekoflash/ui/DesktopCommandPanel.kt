package io.github.ncorror.nekoflash.ui

import com.ncorror.nekoflash.pro.engine.TerminalPlan
import io.github.ncorror.nekoflash.adb.AdbCommandState
import io.github.ncorror.nekoflash.adb.AdbRawServiceState
import io.github.ncorror.nekoflash.adb.AdbRebootState
import io.github.ncorror.nekoflash.fastboot.FastbootConsoleState

/** Wiring contract between the host terminal and command engines. */
data class DesktopCommandPanel(
    val onRun: (TerminalPlan, Boolean) -> Unit = { _, _ -> },
    val onEdit: () -> Unit = {},
    val answer: String? = null,
    val outcomes: DesktopCommandOutcomes = DesktopCommandOutcomes(),
)

/** Command outcomes currently rendered by the host terminal. */
data class DesktopCommandOutcomes(
    val fastboot: FastbootConsoleState = FastbootConsoleState.Idle,
    val shell: AdbCommandState = AdbCommandState.None,
    val service: AdbRawServiceState = AdbRawServiceState.None,
    val reboot: AdbRebootState = AdbRebootState.None,
)
