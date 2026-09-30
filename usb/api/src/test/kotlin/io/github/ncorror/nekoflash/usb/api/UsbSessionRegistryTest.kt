package io.github.ncorror.nekoflash.usb.api

import io.github.ncorror.nekoflash.core.model.SessionGeneration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbSessionRegistryTest {
    @Test
    fun everyOpenAllocatesAFreshMonotonicGeneration() {
        val registry = UsbSessionRegistry()

        val first = registry.open(identityOf(deviceA), candidateOf(deviceA))
        registry.close(first.generation, UsbSessionClosureReason.RELEASED)
        val second = registry.open(identityOf(deviceA), candidateOf(deviceA))

        assertNotEquals(first.generation, second.generation)
        assertTrue(second.generation.value > first.generation.value)
    }

    @Test
    fun reattachedDeviceNeverReusesThePreviousGeneration() {
        val registry = UsbSessionRegistry()
        val before = registry.open(identityOf(deviceA), candidateOf(deviceA))
        registry.markReady(before.generation)
        registry.markClaimed(before.generation)

        registry.closeDetached(deviceA)
        val reattached = deviceA.copy(deviceId = 2002, deviceName = "/dev/bus/usb/001/077")
        val after = registry.open(identityOf(reattached), candidateOf(reattached))

        assertNotEquals(before.generation, after.generation)
        assertEquals(UsbSessionState.DISCOVERED, after.state)
    }

    @Test
    fun openingASecondSessionForTheSameTargetSupersedesTheFirst() {
        val registry = UsbSessionRegistry()
        val identity = identityOf(deviceWithSerial)

        val first = registry.open(identity, candidateOf(deviceWithSerial))
        val second = registry.open(identity, candidateOf(deviceWithSerial))

        val superseded = registry.session(first.generation)
        assertEquals(UsbSessionState.CLOSED, superseded?.state)
        assertEquals(UsbSessionClosureReason.SUPERSEDED, superseded?.closureReason)
        assertEquals(listOf(second.generation), registry.activeSessions.value.map { it.generation })
    }

    @Test
    fun differentTargetsKeepIndependentSessions() {
        val registry = UsbSessionRegistry()

        val one = registry.open(identityOf(deviceA), candidateOf(deviceA))
        val other = registry.open(identityOf(deviceB), candidateOf(deviceB))

        assertEquals(
            listOf(one.generation, other.generation),
            registry.activeSessions.value.map { it.generation },
        )
    }

    @Test
    fun aClosedSessionNeverComesBackToLife() {
        val registry = UsbSessionRegistry()
        val session = registry.open(identityOf(deviceA), candidateOf(deviceA))
        registry.close(session.generation, UsbSessionClosureReason.DETACHED)

        val revived = registry.markReady(session.generation)
        val claimed = registry.markClaimed(session.generation)

        assertRejected(revived, UsbSessionRejection.ALREADY_CLOSED)
        assertRejected(claimed, UsbSessionRejection.ALREADY_CLOSED)
        assertFalse(registry.session(session.generation)!!.usable)
    }

    @Test
    fun aStaleGenerationLearnsWhyItsSessionEnded() {
        val registry = UsbSessionRegistry()
        val session = registry.open(identityOf(deviceA), candidateOf(deviceA))
        registry.markReady(session.generation)
        registry.markClaimed(session.generation)

        registry.closeDetached(deviceA)
        val rejected = registry.markClaimed(session.generation)

        val reported = (rejected as UsbSessionTransition.Rejected).session
        assertEquals(UsbSessionRejection.ALREADY_CLOSED, rejected.reason)
        assertEquals(UsbSessionClosureReason.DETACHED, reported?.closureReason)
    }

    @Test
    fun theFirstClosureReasonIsNotOverwritten() {
        val registry = UsbSessionRegistry()
        val session = registry.open(identityOf(deviceA), candidateOf(deviceA))
        registry.close(session.generation, UsbSessionClosureReason.PERMISSION_DENIED)

        val second = registry.close(session.generation, UsbSessionClosureReason.RELEASED)

        assertRejected(second, UsbSessionRejection.ALREADY_CLOSED)
        assertEquals(
            UsbSessionClosureReason.PERMISSION_DENIED,
            registry.session(session.generation)?.closureReason,
        )
    }

    @Test
    fun anUnknownGenerationIsDistinguishedFromAClosedOne() {
        val registry = UsbSessionRegistry()

        val rejected = registry.markReady(SessionGeneration(9_999L))

        assertRejected(rejected, UsbSessionRejection.UNKNOWN_GENERATION)
        assertNull((rejected as UsbSessionTransition.Rejected).session)
    }

    @Test
    fun claimingRequiresPermissionFirst() {
        val registry = UsbSessionRegistry()
        val session = registry.open(identityOf(deviceA), candidateOf(deviceA))

        val tooEarly = registry.markClaimed(session.generation)

        assertRejected(tooEarly, UsbSessionRejection.ILLEGAL_TRANSITION)
        assertEquals(UsbSessionState.DISCOVERED, registry.session(session.generation)?.state)
    }

    @Test
    fun alreadyGrantedPermissionSkipsThePendingState() {
        val registry = UsbSessionRegistry()
        val session = registry.open(identityOf(deviceA), candidateOf(deviceA))

        val ready = registry.markReady(session.generation)
        val claimed = registry.markClaimed(session.generation)

        assertEquals(UsbSessionState.READY, (ready as UsbSessionTransition.Applied).session.state)
        assertTrue((claimed as UsbSessionTransition.Applied).session.usable)
    }

    @Test
    fun detachClosesOnlySessionsOfThatAttachment() {
        val registry = UsbSessionRegistry()
        val kept = registry.open(identityOf(deviceB), candidateOf(deviceB))
        val lost = registry.open(identityOf(deviceA), candidateOf(deviceA))

        val closedSessions = registry.closeDetached(deviceA)

        assertEquals(listOf(lost.generation), closedSessions.map { it.generation })
        assertEquals(listOf(kept.generation), registry.activeSessions.value.map { it.generation })
    }

    @Test
    fun publishedStateFollowsEveryChange() {
        val registry = UsbSessionRegistry()
        assertTrue(registry.activeSessions.value.isEmpty())

        val session = registry.open(identityOf(deviceA), candidateOf(deviceA))
        assertEquals(listOf(UsbSessionState.DISCOVERED), registry.activeSessions.value.map { it.state })

        registry.markReady(session.generation)
        assertEquals(listOf(UsbSessionState.READY), registry.activeSessions.value.map { it.state })

        registry.close(session.generation, UsbSessionClosureReason.RELEASED)
        assertTrue(registry.activeSessions.value.isEmpty())
    }

    @Test
    fun closedHistoryIsBoundedAndForgetsTheOldestFirst() {
        val registry = UsbSessionRegistry(closedHistoryLimit = 2)
        val generations = (1..3).map {
            val session = registry.open(identityOf(deviceA), candidateOf(deviceA))
            registry.close(session.generation, UsbSessionClosureReason.RELEASED)
            session.generation
        }

        assertNull(registry.session(generations[0]))
        assertEquals(UsbSessionState.CLOSED, registry.session(generations[1])?.state)
        assertEquals(UsbSessionState.CLOSED, registry.session(generations[2])?.state)
    }

    @Test
    fun aSessionCannotClaimToBeClosedWithoutAReason() {
        val session = UsbSession(
            generation = SessionGeneration(1L),
            targetId = identityOf(deviceA).id,
            identity = identityOf(deviceA),
            candidate = candidateOf(deviceA),
            state = UsbSessionState.DISCOVERED,
        )

        assertThrows(IllegalArgumentException::class.java) {
            session.copy(state = UsbSessionState.CLOSED)
        }
        assertThrows(IllegalArgumentException::class.java) {
            session.copy(closureReason = UsbSessionClosureReason.RELEASED)
        }
    }

    @Test
    fun closedIsTheOnlyTerminalState() {
        val terminal = UsbSessionState.entries.filter { it.terminal }

        assertEquals(listOf(UsbSessionState.CLOSED), terminal)
        UsbSessionState.entries.forEach { state ->
            assertFalse(UsbSessionStateMachine.allows(UsbSessionState.CLOSED, state))
        }
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

        val deviceWithSerial = deviceA.copy(serialNumber = "MI9SERIAL")

        fun adbInterface() = UsbInterfaceDescriptor(
            id = 0,
            interfaceClass = UsbInterfaceClassifier.USB_CLASS_VENDOR_SPECIFIC,
            interfaceSubclass = UsbInterfaceClassifier.ANDROID_USB_SUBCLASS,
            interfaceProtocol = UsbInterfaceClassifier.ADB_INTERFACE_PROTOCOL,
            endpoints = listOf(
                UsbEndpointDescriptor(0x81, UsbEndpointDirection.IN, UsbTransferType.BULK, 512),
                UsbEndpointDescriptor(0x01, UsbEndpointDirection.OUT, UsbTransferType.BULK, 512),
            ),
        )

        fun identityOf(device: UsbDeviceDescriptor) = UsbTargetIdentity.fromDescriptor(device)

        fun candidateOf(device: UsbDeviceDescriptor) =
            UsbInterfaceClassifier.primaryCandidate(device)!!

        fun assertRejected(
            transition: UsbSessionTransition,
            expected: UsbSessionRejection,
        ) {
            assertTrue(transition is UsbSessionTransition.Rejected)
            assertEquals(expected, (transition as UsbSessionTransition.Rejected).reason)
        }
    }
}
