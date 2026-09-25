package com.ncorror.nekoflash.pro.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UsbAutoConnectPlannerTest {
    private fun device(
        protocol: UsbWireProtocol? = UsbWireProtocol.ADB,
        granted: Boolean = false,
        pending: Boolean = false,
    ) = UsbAutoCandidate("usb-path", 1002, protocol, granted, pending)

    @Test
    fun requestsPermissionOnceThenAuthorizesOnceAfterGrant() {
        val planner = UsbAutoConnectPlanner()
        assertEquals(UsbAutoAction.RequestAccess("usb-path"), planner.observe(listOf(device())))
        assertNull(planner.observe(listOf(device(pending = true))))
        assertNull(planner.observe(listOf(device()))) // denial must not re-open the system dialog
        assertEquals(UsbAutoAction.AuthorizeAdb("usb-path", 1002),
            planner.observe(listOf(device(granted = true))))
        assertNull(planner.observe(listOf(device(granted = true)))) // resume/refresh is not a new attach
    }

    @Test
    fun detachThenSameUsbIdStartsNewPermissionSequence() {
        val planner = UsbAutoConnectPlanner()
        planner.observe(listOf(device(granted = true)))
        planner.forget("usb-path", 1002)
        assertEquals(UsbAutoAction.RequestAccess("usb-path"), planner.observe(listOf(device())))
        planner.observe(emptyList())
        assertEquals(UsbAutoAction.AuthorizeAdb("usb-path", 1002),
            planner.observe(listOf(device(granted = true))))
    }

    @Test
    fun manuallyPendingPermissionDoesNotAutoRetryAfterDenial() {
        val planner = UsbAutoConnectPlanner()
        assertNull(planner.observe(listOf(device(pending = true))))
        assertNull(planner.observe(listOf(device())))
        assertEquals(UsbAutoAction.AuthorizeAdb("usb-path", 1002),
            planner.observe(listOf(device(granted = true))))
    }

    @Test
    fun fastbootNeverExecutesCommandAndUnrelatedOrAmbiguousDevicesAreSkipped() {
        val planner = UsbAutoConnectPlanner()
        assertNull(planner.observe(listOf(device(protocol = null))))
        assertNull(planner.observe(listOf(device(), device().copy(key = "other"))))
        assertEquals(UsbAutoAction.RequestAccess("usb-path"),
            planner.observe(listOf(device(protocol = UsbWireProtocol.FASTBOOT))))
        assertNull(planner.observe(listOf(device(protocol = UsbWireProtocol.FASTBOOT,
            granted = true))))
    }
}
