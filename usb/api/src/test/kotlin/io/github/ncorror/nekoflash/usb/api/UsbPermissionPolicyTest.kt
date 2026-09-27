package io.github.ncorror.nekoflash.usb.api

import io.github.ncorror.nekoflash.core.model.SessionGeneration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbPermissionPolicyTest {
    @Test
    fun exactConnectionIdWinsOverAMatchingName() {
        val registry = UsbSessionRegistry()
        val byName = pendingSession(registry, deviceA)
        val byId = pendingSession(registry, deviceB)

        val matched = UsbPermissionPolicy.matchPending(
            pending = listOf(byName, byId),
            device = deviceA.copy(deviceId = deviceB.deviceId),
        )

        assertEquals(byId.generation, matched?.generation)
    }

    @Test
    fun aRecreatedDescriptorIsStillMatchedByConnectionName() {
        val registry = UsbSessionRegistry()
        val session = pendingSession(registry, deviceA)

        val matched = UsbPermissionPolicy.matchPending(
            pending = listOf(session),
            device = deviceA.copy(deviceId = 7007),
        )

        assertEquals(session.generation, matched?.generation)
    }

    @Test
    fun connectionNameFallbackTakesTheFirstCandidateInGivenOrder() {
        val registry = UsbSessionRegistry()
        val template = pendingSession(registry, deviceA)
        val first = template.copy(generation = SessionGeneration(101L))
        val second = template.copy(generation = SessionGeneration(102L))

        val matched = UsbPermissionPolicy.matchPending(
            pending = listOf(first, second),
            device = deviceA.copy(deviceId = 7007),
        )

        assertEquals(first.generation, matched?.generation)
    }

    @Test
    fun nothingIsMatchedForAnUnrelatedConnection() {
        val registry = UsbSessionRegistry()
        val session = pendingSession(registry, deviceA)

        val matched = UsbPermissionPolicy.matchPending(
            pending = listOf(session),
            device = deviceB.copy(deviceId = 7007),
        )

        assertNull(matched)
    }

    @Test
    fun grantRebindsTheChosenInterfaceOntoTheFreshDescriptor() {
        val registry = UsbSessionRegistry()
        val session = pendingSession(registry, deviceA)
        val afterPermission = deviceA.copy(serialNumber = "MI9SERIAL")

        val decision = UsbPermissionPolicy.resolve(listOf(session), afterPermission, granted = true)

        val proceed = decision as UsbPermissionPolicy.Decision.Proceed
        assertEquals(session.generation, proceed.session?.generation)
        assertEquals("MI9SERIAL", proceed.candidate.device.normalizedSerialNumber)
        assertEquals(session.candidate.profileSignature, proceed.candidate.profileSignature)
    }

    @Test
    fun failedRebindFallsBackToThePrimaryInterfaceOfTheFreshDescriptor() {
        val registry = UsbSessionRegistry()
        val session = pendingSession(registry, deviceA)
        val replacedInterface = deviceA.copy(interfaces = listOf(fastbootInterface()))

        val decision = UsbPermissionPolicy.resolve(listOf(session), replacedInterface, granted = true)

        val proceed = decision as UsbPermissionPolicy.Decision.Proceed
        assertEquals(UsbInterfaceKind.FASTBOOT, proceed.candidate.kind)
        assertEquals(session.generation, proceed.session?.generation)
    }

    @Test
    fun grantWithoutAPendingSessionStillProceedsForARecognizedDevice() {
        val decision = UsbPermissionPolicy.resolve(emptyList(), deviceA, granted = true)

        val proceed = decision as UsbPermissionPolicy.Decision.Proceed
        assertNull(proceed.session)
        assertEquals(UsbInterfaceKind.ADB, proceed.candidate.kind)
    }

    @Test
    fun grantForAnUnrecognizedDeviceReportsNoCandidate() {
        val unrecognized = deviceA.copy(interfaces = emptyList())

        val decision = UsbPermissionPolicy.resolve(emptyList(), unrecognized, granted = true)

        assertTrue(decision is UsbPermissionPolicy.Decision.NoCandidate)
    }

    @Test
    fun grantWithoutADescriptorCannotBeMatched() {
        val decision = UsbPermissionPolicy.resolve(emptyList(), device = null, granted = true)

        assertTrue(decision is UsbPermissionPolicy.Decision.MissingDevice)
    }

    @Test
    fun denialClosesOnlyTheMatchingSession() {
        val registry = UsbSessionRegistry()
        val session = pendingSession(registry, deviceA)

        val decision = UsbPermissionPolicy.resolve(listOf(session), deviceA, granted = false)

        assertEquals(session.generation, (decision as UsbPermissionPolicy.Decision.Denied).session.generation)
    }

    @Test
    fun denialWithoutAMatchingSessionChangesNothing() {
        val registry = UsbSessionRegistry()
        val other = pendingSession(registry, deviceB)

        val decision = UsbPermissionPolicy.resolve(listOf(other), deviceA, granted = false)

        assertTrue(decision is UsbPermissionPolicy.Decision.UnmatchedDenial)
    }

    @Test
    fun timeoutReportsOnlyWhilePermissionIsStillAbsent() {
        val registry = UsbSessionRegistry()
        val session = pendingSession(registry, deviceA)

        assertEquals(
            UsbPermissionPolicy.TimeoutOutcome.CLOSE_AND_REPORT,
            UsbPermissionPolicy.onTimeout(session, permissionGrantedNow = false),
        )
        assertEquals(
            UsbPermissionPolicy.TimeoutOutcome.CLOSE_SILENTLY,
            UsbPermissionPolicy.onTimeout(session, permissionGrantedNow = true),
        )
    }

    @Test
    fun aTimeoutThatArrivesAfterTheAnswerChangesNothing() {
        val registry = UsbSessionRegistry()
        val session = pendingSession(registry, deviceA)
        val ready = (registry.markReady(session.generation) as UsbSessionTransition.Applied).session

        assertEquals(
            UsbPermissionPolicy.TimeoutOutcome.IGNORE,
            UsbPermissionPolicy.onTimeout(ready, permissionGrantedNow = false),
        )
        assertEquals(
            UsbPermissionPolicy.TimeoutOutcome.IGNORE,
            UsbPermissionPolicy.onTimeout(null, permissionGrantedNow = false),
        )
    }

    @Test
    fun theInheritedResponseTimeoutIsThirtySeconds() {
        assertEquals(30_000L, UsbPermissionPolicy.RESPONSE_TIMEOUT_MS)
    }

    private companion object {
        val deviceA = UsbDeviceDescriptor(
            deviceId = 1001,
            deviceName = "/dev/bus/usb/001/002",
            vendorId = 0x2717,
            productId = 0xFF48,
            interfaces = listOf(adbInterface()),
        )

        val deviceB = UsbDeviceDescriptor(
            deviceId = 1002,
            deviceName = "/dev/bus/usb/001/003",
            vendorId = 0x18D1,
            productId = 0x4EE0,
            interfaces = listOf(adbInterface()),
        )

        fun adbInterface() = usbInterface(UsbInterfaceClassifier.ADB_INTERFACE_PROTOCOL)

        fun fastbootInterface() = usbInterface(UsbInterfaceClassifier.FASTBOOT_INTERFACE_PROTOCOL)

        fun usbInterface(protocol: Int) = UsbInterfaceDescriptor(
            id = 0,
            interfaceClass = UsbInterfaceClassifier.USB_CLASS_VENDOR_SPECIFIC,
            interfaceSubclass = UsbInterfaceClassifier.ANDROID_USB_SUBCLASS,
            interfaceProtocol = protocol,
            endpoints = listOf(
                UsbEndpointDescriptor(0x81, UsbEndpointDirection.IN, UsbTransferType.BULK, 512),
                UsbEndpointDescriptor(0x01, UsbEndpointDirection.OUT, UsbTransferType.BULK, 512),
            ),
        )

        fun pendingSession(
            registry: UsbSessionRegistry,
            device: UsbDeviceDescriptor,
        ): UsbSession {
            val opened = registry.open(
                identity = UsbTargetIdentity.fromDescriptor(device),
                candidate = UsbInterfaceClassifier.primaryCandidate(device)!!,
            )
            return (registry.markPermissionPending(opened.generation) as UsbSessionTransition.Applied)
                .session
        }
    }
}
