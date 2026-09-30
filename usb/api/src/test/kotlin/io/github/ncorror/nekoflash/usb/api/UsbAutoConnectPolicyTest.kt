package io.github.ncorror.nekoflash.usb.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbAutoConnectPolicyTest {
    @Test
    fun canonicalDescriptorsAreEnoughToConnectWithoutAsking() {
        assertTrue(
            UsbAutoConnectPolicy.allowsAutomaticConnect(candidate(UsbMatchConfidence.CANONICAL)),
        )
    }

    @Test
    fun androidCompatibleInterfaceIsAlsoConnectedAutomatically() {
        assertTrue(
            UsbAutoConnectPolicy.allowsAutomaticConnect(
                candidate(UsbMatchConfidence.ANDROID_COMPATIBLE),
            ),
        )
    }

    /**
     * Под vendor-specific класс попадает не только Android: захватывать чужое
     * устройство без спроса нельзя, потому что захват исключителен.
     */
    @Test
    fun genericVendorInterfaceIsLeftToTheUser() {
        assertFalse(
            UsbAutoConnectPolicy.allowsAutomaticConnect(candidate(UsbMatchConfidence.GENERIC_VENDOR)),
        )
    }

    @Test
    fun decisionDoesNotDependOnTheProtocolKind() {
        val fastboot = candidate(UsbMatchConfidence.CANONICAL, UsbInterfaceKind.FASTBOOT)

        assertTrue(UsbAutoConnectPolicy.allowsAutomaticConnect(fastboot))
    }

    private companion object {
        fun candidate(
            confidence: UsbMatchConfidence,
            kind: UsbInterfaceKind = UsbInterfaceKind.ADB,
        ) = UsbInterfaceCandidate(
            device = UsbDeviceDescriptor(
                deviceId = 1,
                deviceName = "/dev/bus/usb/001/002",
                vendorId = 0x18D1,
                productId = 0x4EE7,
            ),
            kind = kind,
            confidence = confidence,
            interfaceIndex = 0,
            interfaceId = 0,
            interfaceClass = 0xFF,
            interfaceSubclass = 0x42,
            interfaceProtocol = 0x01,
            endpointIn = UsbEndpointDescriptor(
                address = 0x81,
                direction = UsbEndpointDirection.IN,
                transferType = UsbTransferType.BULK,
            ),
            endpointOut = UsbEndpointDescriptor(
                address = 0x01,
                direction = UsbEndpointDirection.OUT,
                transferType = UsbTransferType.BULK,
            ),
        )
    }
}
