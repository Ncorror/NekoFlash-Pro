package io.github.ncorror.nekoflash

import io.github.ncorror.nekoflash.core.model.SessionGeneration
import io.github.ncorror.nekoflash.usb.api.UsbDeviceDescriptor
import io.github.ncorror.nekoflash.usb.api.UsbEndpointDescriptor
import io.github.ncorror.nekoflash.usb.api.UsbEndpointDirection
import io.github.ncorror.nekoflash.usb.api.UsbInterfaceClassifier
import io.github.ncorror.nekoflash.usb.api.UsbInterfaceDescriptor
import io.github.ncorror.nekoflash.usb.api.UsbSession
import io.github.ncorror.nekoflash.usb.api.UsbSessionState
import io.github.ncorror.nekoflash.usb.api.UsbTargetIdentity
import io.github.ncorror.nekoflash.usb.api.UsbTransferType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetSelectionControllerTest {
    @Test
    fun oneUnambiguousTargetMayBeSelectedOnStartup() {
        val controller = TargetSelectionController()
        val only = session(1, "/dev/one", serial = "A")

        controller.reconcile(listOf(only))

        assertEquals(only.generation, controller.state.value?.generation)
        assertEquals(only.targetId, controller.state.value?.targetId)
    }

    @Test
    fun twoTargetsAreNeverResolvedByListOrder() {
        val controller = TargetSelectionController()
        val first = session(1, "/dev/one", serial = "A")
        val second = session(2, "/dev/two", serial = "B")

        controller.reconcile(listOf(first, second))
        assertNull(controller.state.value)

        controller.reconcile(listOf(second, first))
        assertNull(controller.state.value)
    }

    @Test
    fun explicitChoiceSurvivesSessionReordering() {
        val controller = TargetSelectionController()
        val first = session(1, "/dev/one", serial = "A")
        val second = session(2, "/dev/two", serial = "B")
        controller.select(second)

        controller.reconcile(listOf(first, second))
        controller.reconcile(listOf(second, first))

        assertEquals(second.generation, controller.state.value?.generation)
        assertEquals(second.targetId, controller.state.value?.targetId)
    }

    @Test
    fun sameGenerationMayRefineAttachmentIdentityToSerial() {
        val controller = TargetSelectionController()
        val beforePermission = session(7, "/dev/one", serial = null)
        val afterPermission = session(7, "/dev/one", serial = "SERIAL")
        controller.select(beforePermission)
        assertFalse(controller.state.value!!.survivesReattachment)

        controller.reconcile(listOf(afterPermission))

        assertEquals(afterPermission.targetId, controller.state.value?.targetId)
        assertEquals(afterPermission.generation, controller.state.value?.generation)
        assertTrue(controller.state.value!!.survivesReattachment)
    }

    @Test
    fun serialIdentityMayFollowOnlyTheSameTargetIntoANewGeneration() {
        val controller = TargetSelectionController()
        val old = session(3, "/dev/old", serial = "SERIAL")
        val other = session(4, "/dev/other", serial = "OTHER")
        val returned = session(5, "/dev/new", serial = "SERIAL")
        controller.select(old)

        controller.reconcile(listOf(other))
        assertNull(controller.state.value?.generation)
        assertEquals(old.targetId, controller.state.value?.targetId)

        controller.reconcile(listOf(other, returned))
        assertEquals(returned.generation, controller.state.value?.generation)
        assertEquals(returned.targetId, controller.state.value?.targetId)
    }

    @Test
    fun attachmentIdentityNeverJumpsToAnotherConnectionAfterDetach() {
        val controller = TargetSelectionController()
        val old = session(9, "/dev/old", serial = null)
        val replacement = session(10, "/dev/new", serial = null)
        controller.select(old)

        controller.reconcile(listOf(replacement))

        val selected = controller.state.value!!
        assertEquals(old.targetId, selected.targetId)
        assertNull(selected.generation)
        assertFalse(selected.survivesReattachment)
        assertNull(controller.resolve(listOf(replacement)))
    }

    private fun session(generation: Long, attachment: String, serial: String?): UsbSession {
        val device = UsbDeviceDescriptor(
            deviceId = generation.toInt(),
            deviceName = attachment,
            vendorId = VENDOR_ID,
            productId = PRODUCT_ID,
            productName = "Android",
            serialNumber = serial,
            interfaces = listOf(
                UsbInterfaceDescriptor(
                    id = 0,
                    interfaceClass = UsbInterfaceClassifier.USB_CLASS_VENDOR_SPECIFIC,
                    interfaceSubclass = UsbInterfaceClassifier.ANDROID_USB_SUBCLASS,
                    interfaceProtocol = UsbInterfaceClassifier.ADB_INTERFACE_PROTOCOL,
                    endpoints = listOf(
                        UsbEndpointDescriptor(0x81, UsbEndpointDirection.IN, UsbTransferType.BULK, 512),
                        UsbEndpointDescriptor(0x01, UsbEndpointDirection.OUT, UsbTransferType.BULK, 512),
                    ),
                ),
            ),
        )
        val identity = UsbTargetIdentity.fromDescriptor(device)
        return UsbSession(
            generation = SessionGeneration(generation),
            targetId = identity.id,
            identity = identity,
            candidate = checkNotNull(UsbInterfaceClassifier.primaryCandidate(device)),
            state = UsbSessionState.READY,
        )
    }

    private companion object {
        const val VENDOR_ID = 0x18D1
        const val PRODUCT_ID = 0x4EE7
    }
}
