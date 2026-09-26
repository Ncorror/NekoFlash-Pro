package com.ncorror.nekoflash.pro.fastboot

import com.ncorror.nekoflash.pro.engine.TerminalPlanResult
import com.ncorror.nekoflash.pro.engine.TerminalPlanStep
import com.ncorror.nekoflash.pro.transport.FastbootPacketTransport

sealed interface FastbootPlanExecution {
    data class Reply(val result: FastbootWireResult) : FastbootPlanExecution
    data class Refused(val reason: String) : FastbootPlanExecution
}

/** The transport is opened only after the whole plan has been accepted. */
class FastbootPlanExecutor(private val openTransport: () -> FastbootPacketTransport) {
    fun execute(
        compilation: TerminalPlanResult,
        onMessage: (FastbootWireMessage) -> Unit = {},
    ): FastbootPlanExecution = when (compilation) {
        is TerminalPlanResult.RequiresImage -> FastbootPlanExecution.Refused("Fastboot image must be selected first")
        is TerminalPlanResult.Invalid -> FastbootPlanExecution.Refused(compilation.reason)
        is TerminalPlanResult.Unavailable -> FastbootPlanExecution.Refused(compilation.reason)
        is TerminalPlanResult.Ready -> {
            val step = compilation.plan.steps.singleOrNull()
            if (step !is TerminalPlanStep.FastbootGetvar) {
                FastbootPlanExecution.Refused("Fastboot plan step has no executor")
            } else {
                val reply = FastbootWireSession(openTransport()).use { session ->
                    session.execute("getvar:${step.variable.wireName}", onMessage)
                }
                FastbootPlanExecution.Reply(reply)
            }
        }
    }
}
