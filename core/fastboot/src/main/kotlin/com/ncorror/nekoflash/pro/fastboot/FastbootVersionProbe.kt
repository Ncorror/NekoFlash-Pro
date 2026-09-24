package com.ncorror.nekoflash.pro.fastboot

import com.ncorror.nekoflash.pro.transport.FastbootPacketTransport

/** A deliberately fixed, read-only probe; no user-supplied Fastboot command reaches USB. */
class FastbootVersionProbe(private val transport: FastbootPacketTransport) {
    fun read(onMessage: (FastbootWireMessage) -> Unit = {}): FastbootWireResult =
        FastbootWireSession(transport).use { session ->
            session.execute("getvar:version", onMessage)
        }
}
