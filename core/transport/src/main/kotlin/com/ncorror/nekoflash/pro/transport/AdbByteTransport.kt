package com.ncorror.nekoflash.pro.transport

/** Exactly one ADB USB session; readExact may return only the requested number of bytes. */
interface AdbByteTransport : AutoCloseable {
    fun write(bytes: ByteArray)
    fun readExact(length: Int): ByteArray
    /** Only for waiting on an explicit user approval after sending the host public key. */
    fun readExactAwaitingApproval(length: Int): ByteArray = readExact(length)
}
