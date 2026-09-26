package com.ncorror.nekoflash.pro.fastboot

import com.ncorror.nekoflash.pro.engine.FastbootCommand
import com.ncorror.nekoflash.pro.engine.FastbootOptions
import com.ncorror.nekoflash.pro.engine.TerminalInvocation
import com.ncorror.nekoflash.pro.engine.TerminalPlanCompiler
import com.ncorror.nekoflash.pro.transport.FastbootPacketTransport

/** Only these fixed read-only variables can be sent from the USB diagnostic screen. */
enum class FastbootVariable(val wireName: String) {
    VERSION("version"),
    PRODUCT("product"),
    DOWNLOAD_SIZE("max-download-size"),
}

/** Each request owns and closes its transport, even when a device rejects the variable. */
class FastbootGetvarProbe(private val transport: FastbootPacketTransport) {
    fun read(variable: FastbootVariable, onMessage: (FastbootWireMessage) -> Unit = {}): FastbootWireResult {
        val compilation = TerminalPlanCompiler().compile(
            TerminalInvocation.Fastboot(FastbootOptions(), FastbootCommand.Getvar(variable.wireName))
        )
        return when (val result = FastbootPlanExecutor { transport }.execute(compilation, onMessage)) {
            is FastbootPlanExecution.Reply -> result.result
            is FastbootPlanExecution.Refused -> {
                transport.close() // Caller already supplied an open USB session.
                throw IllegalStateException(result.reason)
            }
        }
    }
}
