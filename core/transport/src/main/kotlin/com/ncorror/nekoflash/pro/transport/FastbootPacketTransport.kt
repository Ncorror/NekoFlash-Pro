package com.ncorror.nekoflash.pro.transport

/** One owner of an already-open Fastboot session. Android USB implements the port in :app.
 * readPacket must return a complete protocol packet, or throw on disconnect/cancellation.
 * Closing it must interrupt an outstanding read. */
interface FastbootPacketTransport : AutoCloseable {
    fun writePacket(packet: ByteArray)
    fun readPacket(maxBytes: Int): ByteArray
}
