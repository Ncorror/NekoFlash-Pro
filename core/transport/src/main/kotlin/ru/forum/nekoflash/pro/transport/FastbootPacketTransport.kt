package ru.forum.nekoflash.pro.transport

/** One owner of an already-open Fastboot session. Android USB implements this port later.
 * readPacket must return a complete protocol packet, or throw on disconnect/cancellation.
 * Closing it must interrupt an outstanding read. */
interface FastbootPacketTransport : AutoCloseable {
    fun writePacket(packet: ByteArray)
    fun readPacket(maxBytes: Int): ByteArray
}
