package com.ncorror.nekoflash.pro.transport

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class UsbSessionGateTest {
    @Test
    fun leaseBlocksOtherProtocolUntilReleasedAndOldCloseCannotReleaseNewLease() {
        val gate = UsbSessionGate()
        val adb = assertNotNull(gate.tryAcquire())
        assertNull(gate.tryAcquire())
        adb.close()
        val fastboot = assertNotNull(gate.tryAcquire())
        adb.close() // A stale controller must never free another controller's claim.
        assertNull(gate.tryAcquire())
        fastboot.close()
        assertNotNull(gate.tryAcquire()).close()
    }
}
