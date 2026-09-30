package io.github.ncorror.nekoflash.usb.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbInterfaceClassifierTest {
    @Test
    fun canonicalAdbSuppressesNeighbouringVendorInterfaces() {
        val device = device(
            interfaces = listOf(
                genericVendorInterface(id = 0),
                canonicalAdbInterface(id = 1),
                genericVendorInterface(id = 2),
            ),
        )

        val candidates = UsbInterfaceClassifier.candidates(device)

        assertEquals(1, candidates.size)
        assertEquals(UsbInterfaceKind.ADB, candidates.single().kind)
        assertEquals(UsbMatchConfidence.CANONICAL, candidates.single().confidence)
    }

    @Test
    fun canonicalFastbootWinsWhenCanonicalAdbIsAbsent() {
        val device = device(
            interfaces = listOf(
                androidCompatibleInterface(id = 0),
                canonicalFastbootInterface(id = 1),
                genericVendorInterface(id = 2),
            ),
        )

        val candidate = UsbInterfaceClassifier.primaryCandidate(device)

        assertEquals(UsbInterfaceKind.FASTBOOT, candidate?.kind)
        assertEquals(UsbMatchConfidence.CANONICAL, candidate?.confidence)
        assertEquals(1, candidate?.interfaceIndex)
    }

    @Test
    fun androidCompatibleIsPreferredOverGenericVendorFallback() {
        val device = device(
            interfaces = listOf(
                genericVendorInterface(id = 0),
                androidCompatibleInterface(id = 1),
            ),
        )

        val candidate = UsbInterfaceClassifier.primaryCandidate(device)

        assertEquals(UsbMatchConfidence.ANDROID_COMPATIBLE, candidate?.confidence)
    }

    @Test
    fun genericVendorFallbackIsOptionalAndNeverClaimsAdbProtocol() {
        val device = device(interfaces = listOf(genericVendorInterface(id = 0)))

        assertEquals(
            UsbMatchConfidence.GENERIC_VENDOR,
            UsbInterfaceClassifier.primaryCandidate(device, allowGenericVendor = true)?.confidence,
        )
        assertNull(UsbInterfaceClassifier.primaryCandidate(device, allowGenericVendor = false))

        val adbProtocolVendorInterface = usbInterface(
            id = 0,
            interfaceClass = UsbInterfaceClassifier.USB_CLASS_VENDOR_SPECIFIC,
            interfaceSubclass = 0x00,
            interfaceProtocol = UsbInterfaceClassifier.ADB_INTERFACE_PROTOCOL,
        )
        val nonAndroidAdbProtocol = device(interfaces = listOf(adbProtocolVendorInterface))

        assertNull(UsbInterfaceClassifier.primaryCandidate(nonAndroidAdbProtocol))
    }

    @Test
    fun candidateRequiresBulkPairAndUsesTheFirstOneDeclared() {
        val withoutOutput = usbInterface(
            id = 0,
            endpoints = listOf(bulkEndpoint(0x81, UsbEndpointDirection.IN)),
        )
        assertNull(UsbInterfaceClassifier.primaryCandidate(device(interfaces = listOf(withoutOutput))))

        val interruptOnly = usbInterface(
            id = 0,
            endpoints = listOf(
                UsbEndpointDescriptor(0x81, UsbEndpointDirection.IN, UsbTransferType.OTHER),
                UsbEndpointDescriptor(0x01, UsbEndpointDirection.OUT, UsbTransferType.OTHER),
            ),
        )
        assertNull(UsbInterfaceClassifier.primaryCandidate(device(interfaces = listOf(interruptOnly))))

        val multiplePairs = usbInterface(
            id = 0,
            endpoints = listOf(
                bulkEndpoint(0x81, UsbEndpointDirection.IN),
                bulkEndpoint(0x01, UsbEndpointDirection.OUT),
                bulkEndpoint(0x82, UsbEndpointDirection.IN),
                bulkEndpoint(0x02, UsbEndpointDirection.OUT),
            ),
        )
        val candidate = UsbInterfaceClassifier.primaryCandidate(device(interfaces = listOf(multiplePairs)))

        assertEquals(0x81, candidate?.endpointIn?.address)
        assertEquals(0x01, candidate?.endpointOut?.address)
    }

    @Test
    fun primaryCandidateKeepsTheLowestInterfaceIndexWithinTheWinningTier() {
        val device = device(
            interfaces = listOf(
                canonicalAdbInterface(id = 7),
                canonicalAdbInterface(id = 3),
            ),
        )

        val candidate = UsbInterfaceClassifier.primaryCandidate(device)

        assertEquals(0, candidate?.interfaceIndex)
        assertEquals(7, candidate?.interfaceId)
    }

    @Test
    fun attachmentKeyTracksConnectionWhileProfileSignatureTracksUsbProfile() {
        val first = UsbInterfaceClassifier.primaryCandidate(
            device(deviceName = "/dev/bus/usb/001/002", interfaces = listOf(canonicalAdbInterface())),
        )!!
        val reattached = UsbInterfaceClassifier.primaryCandidate(
            device(deviceName = "/dev/bus/usb/001/009", interfaces = listOf(canonicalAdbInterface())),
        )!!

        assertNotEquals(first.attachmentKey, reattached.attachmentKey)
        assertEquals(first.profileSignature, reattached.profileSignature)
    }

    @Test
    fun candidatesAcrossDevicesAreDeduplicatedAndOrderedDeterministically() {
        val adbDevice = device(deviceName = "/dev/bus/usb/001/002", interfaces = listOf(canonicalAdbInterface()))
        val genericDevice = device(
            deviceName = "/dev/bus/usb/001/003",
            productId = 0xD00D,
            interfaces = listOf(genericVendorInterface()),
        )

        val ordered = UsbInterfaceClassifier.candidates(listOf(genericDevice, adbDevice, adbDevice))

        assertEquals(2, ordered.size)
        assertEquals(UsbMatchConfidence.CANONICAL, ordered.first().confidence)
        assertEquals(UsbMatchConfidence.GENERIC_VENDOR, ordered.last().confidence)
        assertEquals(
            ordered.map { it.attachmentKey },
            UsbInterfaceClassifier.candidates(listOf(adbDevice, genericDevice)).map { it.attachmentKey },
        )
    }

    @Test
    fun modeSwitchAcceptsOnlyOneChangedProfileFromTheSameVendor() {
        val previous = UsbInterfaceClassifier.primaryCandidate(
            device(interfaces = listOf(canonicalAdbInterface())),
        )!!
        val fastbootAfterReboot = device(
            deviceName = "/dev/bus/usb/001/011",
            productId = 0xD00D,
            interfaces = listOf(canonicalFastbootInterface()),
        )

        val switched = UsbInterfaceClassifier.modeSwitchCandidate(
            devices = listOf(fastbootAfterReboot),
            previousProfileSignature = previous.profileSignature,
            previousVendorId = previous.device.vendorId,
        )

        assertEquals(UsbInterfaceKind.FASTBOOT, switched?.kind)
    }

    @Test
    fun modeSwitchIgnoresUnchangedProfileAndForeignVendor() {
        val previous = UsbInterfaceClassifier.primaryCandidate(
            device(interfaces = listOf(canonicalAdbInterface())),
        )!!
        val unchanged = device(deviceName = "/dev/bus/usb/001/012", interfaces = listOf(canonicalAdbInterface()))
        val otherVendor = device(
            deviceName = "/dev/bus/usb/001/013",
            vendorId = 0x1234,
            interfaces = listOf(canonicalFastbootInterface()),
        )

        val switched = UsbInterfaceClassifier.modeSwitchCandidate(
            devices = listOf(unchanged, otherVendor),
            previousProfileSignature = previous.profileSignature,
            previousVendorId = previous.device.vendorId,
        )

        assertNull(switched)
    }

    @Test
    fun ambiguousChangedProfilesAreNeverAutoSelected() {
        val previous = UsbInterfaceClassifier.primaryCandidate(
            device(interfaces = listOf(canonicalAdbInterface())),
        )!!
        val firstChanged = device(
            deviceName = "/dev/bus/usb/001/021",
            productId = 0xD00D,
            interfaces = listOf(canonicalFastbootInterface()),
        )
        val secondChanged = device(
            deviceName = "/dev/bus/usb/001/022",
            productId = 0xD00E,
            interfaces = listOf(canonicalFastbootInterface()),
        )

        val switched = UsbInterfaceClassifier.modeSwitchCandidate(
            devices = listOf(firstChanged, secondChanged),
            previousProfileSignature = previous.profileSignature,
            previousVendorId = previous.device.vendorId,
        )

        assertNull(switched)
    }

    @Test
    fun rebindPrefersTheSameInterfaceIndexOnTheSameConnection() {
        val before = device(
            interfaces = listOf(canonicalAdbInterface(id = 0), canonicalAdbInterface(id = 1)),
        )
        val previous = UsbInterfaceClassifier.candidates(before).last()
        val afterPermission = before.copy(serialNumber = "SERIAL123")

        val rebound = UsbInterfaceClassifier.rebind(afterPermission, previous)

        assertEquals(previous.interfaceIndex, rebound?.interfaceIndex)
        assertEquals("SERIAL123", rebound?.device?.normalizedSerialNumber)
    }

    @Test
    fun sameInterfaceIndexWinsOverProfileSignature() {
        val before = device(
            interfaces = listOf(otherEndpointsAdbInterface(), canonicalAdbInterface()),
        )
        val previous = UsbInterfaceClassifier.candidates(before)
            .single { it.endpointIn.address == CANONICAL_IN }
        val reordered = before.copy(
            interfaces = listOf(canonicalAdbInterface(), otherEndpointsAdbInterface()),
        )

        val rebound = UsbInterfaceClassifier.rebind(reordered, previous)

        assertEquals(previous.interfaceIndex, rebound?.interfaceIndex)
        assertNotEquals(previous.profileSignature, rebound?.profileSignature)
    }

    @Test
    fun profileSignatureIsFollowedWhenThePreviousIndexIsGone() {
        val before = device(
            interfaces = listOf(otherEndpointsAdbInterface(), canonicalAdbInterface()),
        )
        val previous = UsbInterfaceClassifier.candidates(before)
            .single { it.endpointIn.address == CANONICAL_IN }
        val shortened = before.copy(interfaces = listOf(canonicalAdbInterface()))

        val rebound = UsbInterfaceClassifier.rebind(shortened, previous)

        assertEquals(previous.profileSignature, rebound?.profileSignature)
        assertEquals(0, rebound?.interfaceIndex)
    }

    @Test
    fun rebindRejectsADifferentConnection() {
        val before = device(interfaces = listOf(canonicalAdbInterface()))
        val previous = UsbInterfaceClassifier.primaryCandidate(before)!!
        val otherConnection = before.copy(deviceName = "/dev/bus/usb/001/099")

        assertNull(UsbInterfaceClassifier.rebind(otherConnection, previous))
    }

    @Test
    fun rebindFallsBackOnlyToAUniqueSameKindAndConfidence() {
        val before = device(
            interfaces = listOf(canonicalAdbInterface(id = 0), canonicalAdbInterface(id = 1)),
        )
        val previous = UsbInterfaceClassifier.candidates(before).single { it.interfaceIndex == 1 }

        val uniqueReplacement = before.copy(
            interfaces = listOf(otherEndpointsAdbInterface(), genericVendorInterface(id = 1)),
        )
        assertTrue(UsbInterfaceClassifier.rebind(uniqueReplacement, previous) != null)

        val ambiguousReplacement = before.copy(
            interfaces = listOf(
                otherEndpointsAdbInterface(id = 0),
                genericVendorInterface(id = 1),
                otherEndpointsAdbInterface(id = 2),
            ),
        )
        assertNull(UsbInterfaceClassifier.rebind(ambiguousReplacement, previous))
    }

    @Test
    fun rawEndpointDirectionIsReadFromTheDirectionBit() {
        assertEquals(UsbEndpointDirection.IN, UsbEndpointDirection.fromRaw(0x80))
        assertEquals(UsbEndpointDirection.OUT, UsbEndpointDirection.fromRaw(0x00))
        assertEquals(UsbEndpointDirection.IN, UsbEndpointDirection.fromRaw(0x81))
        assertEquals(UsbEndpointDirection.OUT, UsbEndpointDirection.fromRaw(0x01))
    }

    @Test
    fun onlyBulkIsRecognisedAsBulk() {
        assertEquals(UsbTransferType.BULK, UsbTransferType.fromRaw(2))
        listOf(0, 1, 3).forEach { raw ->
            assertEquals(UsbTransferType.OTHER, UsbTransferType.fromRaw(raw))
        }
    }

    private companion object {
        const val CANONICAL_IN = 0x81
        const val CANONICAL_OUT = 0x01

        fun device(
            deviceId: Int = 1001,
            deviceName: String = "/dev/bus/usb/001/002",
            vendorId: Int = 0x2717,
            productId: Int = 0xFF48,
            serialNumber: String? = null,
            interfaces: List<UsbInterfaceDescriptor> = emptyList(),
        ) = UsbDeviceDescriptor(
            deviceId = deviceId,
            deviceName = deviceName,
            vendorId = vendorId,
            productId = productId,
            serialNumber = serialNumber,
            interfaces = interfaces,
        )

        fun bulkEndpoint(address: Int, direction: UsbEndpointDirection) =
            UsbEndpointDescriptor(address, direction, UsbTransferType.BULK, maxPacketSize = 512)

        fun usbInterface(
            id: Int = 0,
            interfaceClass: Int = UsbInterfaceClassifier.USB_CLASS_VENDOR_SPECIFIC,
            interfaceSubclass: Int = UsbInterfaceClassifier.ANDROID_USB_SUBCLASS,
            interfaceProtocol: Int = UsbInterfaceClassifier.ADB_INTERFACE_PROTOCOL,
            endpoints: List<UsbEndpointDescriptor> = listOf(
                bulkEndpoint(CANONICAL_IN, UsbEndpointDirection.IN),
                bulkEndpoint(CANONICAL_OUT, UsbEndpointDirection.OUT),
            ),
        ) = UsbInterfaceDescriptor(id, interfaceClass, interfaceSubclass, interfaceProtocol, endpoints)

        fun canonicalAdbInterface(id: Int = 0) = usbInterface(id = id)

        fun otherEndpointsAdbInterface(id: Int = 0) = usbInterface(
            id = id,
            endpoints = listOf(
                bulkEndpoint(0x83, UsbEndpointDirection.IN),
                bulkEndpoint(0x03, UsbEndpointDirection.OUT),
            ),
        )

        fun canonicalFastbootInterface(id: Int = 0) = usbInterface(
            id = id,
            interfaceProtocol = UsbInterfaceClassifier.FASTBOOT_INTERFACE_PROTOCOL,
        )

        fun androidCompatibleInterface(id: Int = 0) = usbInterface(id = id, interfaceProtocol = 0x07)

        fun genericVendorInterface(id: Int = 0) = usbInterface(
            id = id,
            interfaceSubclass = 0x00,
            interfaceProtocol = 0x00,
        )
    }
}
