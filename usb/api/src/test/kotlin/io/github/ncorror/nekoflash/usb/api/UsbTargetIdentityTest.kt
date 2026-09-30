package io.github.ncorror.nekoflash.usb.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbTargetIdentityTest {
    @Test
    fun serialIdentitySurvivesReattachment() {
        val attached = UsbTargetIdentity.fromDescriptor(device(serialNumber = "MI9SERIAL"))
        val reattached = UsbTargetIdentity.fromDescriptor(
            device(deviceId = 2002, deviceName = "/dev/bus/usb/001/077", serialNumber = "MI9SERIAL"),
        )

        assertEquals(TargetIdentitySource.SERIAL, attached.source)
        assertTrue(attached.survivesReattachment)
        assertEquals(attached.id, reattached.id)
    }

    @Test
    fun serialIdentityIgnoresVendorAndProductSoModeSwitchKeepsIt() {
        val inAdb = UsbTargetIdentity.fromDescriptor(
            device(vendorId = 0x2717, productId = 0xFF48, serialNumber = "MI9SERIAL"),
        )
        val inFastboot = UsbTargetIdentity.fromDescriptor(
            device(
                deviceId = 3003,
                deviceName = "/dev/bus/usb/001/078",
                vendorId = 0x18D1,
                productId = 0x4EE0,
                serialNumber = "MI9SERIAL",
            ),
        )

        assertEquals(inAdb.id, inFastboot.id)
        assertNotEquals(inAdb.vendorId, inFastboot.vendorId)
    }

    @Test
    fun withoutSerialIdentityFallsBackToAttachmentAndDoesNotClaimToSurvive() {
        val identity = UsbTargetIdentity.fromDescriptor(device(serialNumber = null))

        assertEquals(TargetIdentitySource.USB_ATTACHMENT, identity.source)
        assertFalse(identity.survivesReattachment)
        assertNull(identity.serialNumber)
        assertEquals("/dev/bus/usb/001/002", identity.attachmentName)
    }

    @Test
    fun reattachmentWithoutSerialProducesADifferentTarget() {
        val attached = UsbTargetIdentity.fromDescriptor(device())
        val reattached = UsbTargetIdentity.fromDescriptor(
            device(deviceId = 2002, deviceName = "/dev/bus/usb/001/077"),
        )

        assertNotEquals(attached.id, reattached.id)
    }

    @Test
    fun blankSerialIsTreatedAsAbsent() {
        val identity = UsbTargetIdentity.fromDescriptor(device(serialNumber = "   "))

        assertEquals(TargetIdentitySource.USB_ATTACHMENT, identity.source)
    }

    @Test
    fun serialIsTrimmedBeforeBecomingIdentity() {
        val padded = UsbTargetIdentity.fromDescriptor(device(serialNumber = "  MI9SERIAL "))
        val clean = UsbTargetIdentity.fromDescriptor(device(serialNumber = "MI9SERIAL"))

        assertEquals(clean.id, padded.id)
        assertEquals("MI9SERIAL", padded.serialNumber)
    }

    @Test
    fun protocolReportedSerialUpgradesAttachmentIdentity() {
        val beforeHandshake = UsbTargetIdentity.fromDescriptor(device())
        val afterHandshake = beforeHandshake.refinedWithSerial("MI9SERIAL")

        assertEquals(TargetIdentitySource.SERIAL, afterHandshake.source)
        assertTrue(afterHandshake.survivesReattachment)
        assertEquals(UsbTargetIdentity.fromSerial("MI9SERIAL").id, afterHandshake.id)
        assertEquals("/dev/bus/usb/001/002", afterHandshake.attachmentName)
    }

    @Test
    fun refiningDoesNotSilentlyReplaceAnExistingSerial() {
        val fromDescriptor = UsbTargetIdentity.fromDescriptor(device(serialNumber = "MI9SERIAL"))

        val refined = fromDescriptor.refinedWithSerial("DIFFERENT")

        assertEquals(fromDescriptor, refined)
    }

    @Test
    fun blankSerialIsRejectedWhenRefining() {
        val identity = UsbTargetIdentity.fromDescriptor(device())

        assertThrows(IllegalArgumentException::class.java) { identity.refinedWithSerial("  ") }
        assertThrows(IllegalArgumentException::class.java) { UsbTargetIdentity.fromSerial("") }
    }

    @Test
    fun sameAttachmentMatchesByConnectionName() {
        val current = device()

        assertTrue(isSameAttachment(current, current.copy(deviceId = 9999, productId = 0xDEAD)))
    }

    @Test
    fun sameAttachmentAlsoMatchesByExactIdVendorProductTriple() {
        val current = device()
        val renamed = current.copy(deviceName = "/dev/bus/usb/001/044")

        assertTrue(isSameAttachment(current, renamed))
    }

    @Test
    fun sameAttachmentRejectsADifferentNameAndDifferentTriple() {
        val current = device()

        assertFalse(
            isSameAttachment(
                current,
                current.copy(deviceName = "/dev/bus/usb/001/044", deviceId = 9999),
            ),
        )
        assertFalse(
            isSameAttachment(
                current,
                current.copy(deviceName = "/dev/bus/usb/001/044", vendorId = 0x1234),
            ),
        )
        assertFalse(
            isSameAttachment(
                current,
                current.copy(deviceName = "/dev/bus/usb/001/044", productId = 0xDEAD),
            ),
        )
    }

    @Test
    fun descriptorRejectsBlankConnectionName() {
        assertThrows(IllegalArgumentException::class.java) {
            UsbDeviceDescriptor(deviceId = 1, deviceName = " ", vendorId = 1, productId = 1)
        }
    }

    private companion object {
        fun device(
            deviceId: Int = 1001,
            deviceName: String = "/dev/bus/usb/001/002",
            vendorId: Int = 0x2717,
            productId: Int = 0xFF48,
            serialNumber: String? = null,
        ) = UsbDeviceDescriptor(
            deviceId = deviceId,
            deviceName = deviceName,
            vendorId = vendorId,
            productId = productId,
            serialNumber = serialNumber,
        )
    }
}
