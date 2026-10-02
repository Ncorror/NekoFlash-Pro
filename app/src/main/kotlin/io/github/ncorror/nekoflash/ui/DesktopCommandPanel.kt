package io.github.ncorror.nekoflash.ui

import com.ncorror.nekoflash.pro.engine.TerminalParser
import com.ncorror.nekoflash.pro.engine.TerminalPlan
import io.github.ncorror.nekoflash.adb.AdbCommandState
import io.github.ncorror.nekoflash.adb.AdbInstallState
import io.github.ncorror.nekoflash.adb.AdbRawServiceState
import io.github.ncorror.nekoflash.adb.AdbRebootState
import io.github.ncorror.nekoflash.adb.AdbSideloadState
import io.github.ncorror.nekoflash.adb.AdbTerminalState
import io.github.ncorror.nekoflash.adb.AdbTransferState
import io.github.ncorror.nekoflash.command.DesktopCommandArea
import io.github.ncorror.nekoflash.command.DesktopCommandStart
import io.github.ncorror.nekoflash.fastboot.FastbootConsoleState
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootLockStatus

/** Wiring contract between the host terminal and command engines. */
data class DesktopCommandPanel(
    val defaultTool: TerminalParser.Tool = TerminalParser.Tool.ADB,
    val lock: FastbootLockStatus? = null,
    val onRun: (TerminalPlan, Boolean) -> Unit = { _, _ -> },
    val onEdit: () -> Unit = {},
    val area: DesktopCommandArea? = null,
    val fileProblem: DesktopCommandStart? = null,
    val answer: String? = null,
    val outcomes: DesktopCommandOutcomes = DesktopCommandOutcomes(),
)

/** Last settled/running outcomes owned by the command engines. */
data class DesktopCommandOutcomes(
    val fastboot: FastbootConsoleState = FastbootConsoleState.Idle,
    val shell: AdbCommandState = AdbCommandState.None,
    val service: AdbRawServiceState = AdbRawServiceState.None,
    val reboot: AdbRebootState = AdbRebootState.None,
    val sideload: AdbSideloadState = AdbSideloadState.None,
    val install: AdbInstallState = AdbInstallState.None,
    val transfer: AdbTransferState = AdbTransferState.None,
    val terminal: AdbTerminalState = AdbTerminalState(),
)
