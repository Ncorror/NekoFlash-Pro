package com.ncorror.nekoflash.pro.transport

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One exclusive USB operation across ADB and Fastboot controllers. */
class UsbSessionGate {
    private val active = AtomicReference<Lease?>(null)

    fun tryAcquire(): Lease? {
        val lease = Lease()
        return if (active.compareAndSet(null, lease)) lease else null
    }

    inner class Lease internal constructor() : AutoCloseable {
        private val released = AtomicBoolean(false)

        override fun close() {
            if (released.compareAndSet(false, true)) active.compareAndSet(this, null)
        }
    }
}
