package com.ncorror.nekoflash.pro.adb

import com.ncorror.nekoflash.pro.engine.TerminalPlanResult
import com.ncorror.nekoflash.pro.engine.TerminalPlanStep
import com.ncorror.nekoflash.pro.transport.AdbByteTransport

sealed interface AdbPlanExecution {
    data class Reply(val result: AdbProductResult) : AdbPlanExecution
    data class Refused(val reason: String) : AdbPlanExecution
}

/** Plans without a complete implementation cannot create a key or claim USB. */
class AdbPlanExecutor(
    private val keyFactory: () -> AdbHostKey,
    private val openTransport: () -> AdbByteTransport,
) {
    fun execute(compilation: TerminalPlanResult, onAwaitingApproval: () -> Unit): AdbPlanExecution =
        when (compilation) {
            is TerminalPlanResult.Invalid -> AdbPlanExecution.Refused(compilation.reason)
            is TerminalPlanResult.Unavailable -> AdbPlanExecution.Refused(compilation.reason)
            is TerminalPlanResult.Ready -> {
                if (compilation.plan.steps != listOf(TerminalPlanStep.AdbProduct)) {
                    AdbPlanExecution.Refused("ADB plan step has no executor")
                } else {
                    val key = keyFactory()
                    AdbPlanExecution.Reply(AdbProductProbe(openTransport(), key).read(onAwaitingApproval))
                }
            }
        }
}
