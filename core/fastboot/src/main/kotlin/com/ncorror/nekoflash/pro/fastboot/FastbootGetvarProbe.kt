package com.ncorror.nekoflash.pro.fastboot

import com.ncorror.nekoflash.pro.transport.FastbootPacketTransport

/** Only these fixed read-only variables can be sent from the USB diagnostic screen. */
enum class FastbootVariable(val wireName: String) {
    VERSION("version"),
    PRODUCT("product"),
}

/** Each request owns and closes its transport, even when a device rejects the variable. */
class FastbootGetvarProbe(private val transport: FastbootPacketTransport) {
    fun read(variable: FastbootVariable, onMessage: (FastbootWireMessage) -> Unit = {}): FastbootWireResult =
        FastbootWireSession(transport).use { session ->
            session.execute("getvar:${variable.wireName}", onMessage)
        }
}
