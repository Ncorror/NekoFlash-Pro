package com.ncorror.nekoflash.pro.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UsbInterfaceClassifierTest {
    // Copied from two diagnostic ZIPs captured on the test phone, 2026-09-24.
    private fun descriptor(
        protocol: Int = 1,
        endpoints: List<UsbEndpointDescriptor> = listOf(
            UsbEndpointDescriptor(0x01, 2, 512),
            UsbEndpointDescriptor(0x81, 2, 512),
        ),
    ) = UsbInterfaceDescriptor(0, 0, 0, 0, 0xff, 0x42, protocol, endpoints)

    @Test
    fun capturedAdbDescriptorHasUnambiguousBulkPair() {
        val result = UsbInterfaceClassifier.inspect(descriptor())!!
        assertEquals(UsbWireProtocol.ADB, result.protocol)
        assertTrue(result.bulkPairAvailable)
        assertEquals(0x81, result.bulkInAddress)
        assertEquals(0x01, result.bulkOutAddress)
        assertEquals(0, result.configurationIndex)
        assertEquals(0, result.interfaceIndex)
    }

    @Test
    fun fastbootRequiresItsOwnProtocolCodeAndTwoBulkDirections() {
        val result = UsbInterfaceClassifier.inspect(descriptor(protocol = 3))!!
        assertEquals(UsbWireProtocol.FASTBOOT, result.protocol)
        assertTrue(result.bulkPairAvailable)
        assertNull(UsbInterfaceClassifier.inspect(descriptor(protocol = 2)))
        assertNull(UsbInterfaceClassifier.inspect(descriptor().copy(interfaceSubclass = 0x43)))
        assertNull(UsbInterfaceClassifier.inspect(descriptor().copy(interfaceClass = 0)))
    }

    @Test
    fun interruptedOrMalformedEndpointListIsReportedWithoutAUsablePair() {
        val missing = UsbInterfaceClassifier.inspect(descriptor(endpoints = listOf(
            UsbEndpointDescriptor(0x81, 2, 512),
            UsbEndpointDescriptor(0x01, 3, 512),
        )))!!
        assertEquals(UsbBulkPairStatus.MISSING_ENDPOINT, missing.bulkPairStatus)
        assertFalse(missing.bulkPairAvailable)
        assertNull(missing.bulkInAddress)
        assertNull(missing.bulkOutAddress)

        val invalid = UsbInterfaceClassifier.inspect(descriptor(endpoints = listOf(
            UsbEndpointDescriptor(0x81, 2, 0),
            UsbEndpointDescriptor(0x01, 2, 512),
        )))!!
        assertEquals(UsbBulkPairStatus.MISSING_ENDPOINT, invalid.bulkPairStatus)
    }

    @Test
    fun competingInputEndpointsDoNotChooseOneArbitrarily() {
        val result = UsbInterfaceClassifier.inspect(descriptor(endpoints = listOf(
            UsbEndpointDescriptor(0x81, 2, 512),
            UsbEndpointDescriptor(0x82, 2, 512),
            UsbEndpointDescriptor(0x01, 2, 512),
        )))!!
        assertEquals(UsbBulkPairStatus.AMBIGUOUS_ENDPOINTS, result.bulkPairStatus)
        assertNull(result.bulkInAddress)
    }

    @Test
    fun impossibleEndpointNumberIsIgnored() {
        val result = UsbInterfaceClassifier.inspect(descriptor(endpoints = listOf(
            UsbEndpointDescriptor(0x80, 2, 512),
            UsbEndpointDescriptor(0x01, 2, 512),
        )))!!
        assertEquals(UsbBulkPairStatus.MISSING_ENDPOINT, result.bulkPairStatus)
    }
}
