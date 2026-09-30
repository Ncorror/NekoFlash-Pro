package io.github.ncorror.nekoflash.usb.api

import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticEvent
import io.github.ncorror.nekoflash.core.diagnostics.InMemoryDiagnosticSink
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbSessionCoordinatorTest {
    @Test
    fun anAlreadyPermittedDeviceGoesStraightToReady() {
        val host = FakeUsbHost(attached = listOf(deviceA), permitted = setOf(deviceA.deviceName))
        val coordinator = UsbSessionCoordinator(host)

        coordinator.start()

        assertEquals(listOf(UsbSessionState.READY), coordinator.sessions.value.map { it.state })
        assertTrue(host.permissionRequests.isEmpty())
    }

    @Test
    fun aDeviceWithoutPermissionWaitsForTheAnswer() {
        val host = FakeUsbHost(attached = listOf(deviceA))
        val coordinator = UsbSessionCoordinator(host)

        coordinator.start()

        assertEquals(
            listOf(UsbSessionState.PERMISSION_PENDING),
            coordinator.sessions.value.map { it.state },
        )
        assertEquals(listOf(deviceA.deviceName), host.permissionRequests)
    }

    @Test
    fun grantedPermissionMakesTheSessionReadyAndKeepsItsGeneration() {
        val host = FakeUsbHost(attached = listOf(deviceA))
        val coordinator = UsbSessionCoordinator(host)
        coordinator.start()
        val opened = coordinator.sessions.value.single()

        coordinator.onPermissionResult(deviceA, granted = true)

        val ready = coordinator.sessions.value.single()
        assertEquals(opened.generation, ready.generation)
        assertEquals(UsbSessionState.READY, ready.state)
    }

    @Test
    fun aSerialLearnedWithPermissionUpgradesTheTargetIdentity() {
        val host = FakeUsbHost(attached = listOf(deviceA))
        val coordinator = UsbSessionCoordinator(host)
        coordinator.start()
        val beforePermission = coordinator.sessions.value.single()

        coordinator.onPermissionResult(deviceA.copy(serialNumber = "MI9SERIAL"), granted = true)

        val session = coordinator.sessions.value.single()
        assertEquals(TargetIdentitySource.USB_ATTACHMENT, beforePermission.identity.source)
        assertEquals(TargetIdentitySource.SERIAL, session.identity.source)
        assertEquals(session.identity.id, session.targetId)
        assertEquals(beforePermission.generation, session.generation)
    }

    @Test
    fun deniedPermissionClosesTheSessionWithTheRealReason() {
        val host = FakeUsbHost(attached = listOf(deviceA))
        val registry = UsbSessionRegistry()
        val coordinator = UsbSessionCoordinator(host, registry)
        coordinator.start()
        val opened = coordinator.sessions.value.single()

        coordinator.onPermissionResult(deviceA, granted = false)

        assertTrue(coordinator.sessions.value.isEmpty())
        assertEquals(
            UsbSessionClosureReason.PERMISSION_DENIED,
            registry.session(opened.generation)?.closureReason,
        )
    }

    @Test
    fun aTimedOutSessionRecordsTimeoutAsItsReason() {
        val host = FakeUsbHost(attached = listOf(deviceA))
        val registry = UsbSessionRegistry()
        val coordinator = UsbSessionCoordinator(host, registry)
        coordinator.start()
        val opened = coordinator.sessions.value.single()

        coordinator.onPermissionTimeout(opened.generation, permissionGrantedNow = false)

        assertEquals(
            UsbSessionClosureReason.PERMISSION_TIMED_OUT,
            registry.session(opened.generation)?.closureReason,
        )
    }

    @Test
    fun detachClosesOnlyTheDetachedDevice() {
        val host = FakeUsbHost(
            attached = listOf(deviceA, deviceB),
            permitted = setOf(deviceA.deviceName, deviceB.deviceName),
        )
        val coordinator = UsbSessionCoordinator(host)
        coordinator.start()

        coordinator.onDeviceDetached(deviceA)

        assertEquals(
            listOf(deviceB.deviceName),
            coordinator.sessions.value.map { it.candidate.device.deviceName },
        )
    }

    @Test
    fun aDeviceWithoutAUsableInterfaceOpensNoSession() {
        val host = FakeUsbHost(attached = listOf(deviceA.copy(interfaces = emptyList())))
        val coordinator = UsbSessionCoordinator(host)

        coordinator.start()

        assertTrue(coordinator.sessions.value.isEmpty())
        assertTrue(host.permissionRequests.isEmpty())
    }

    @Test
    fun aDeviceLostBeforeTheRequestDoesNotLeaveAHangingSession() {
        val host = FakeUsbHost(attached = listOf(deviceA), requestSucceeds = false)
        val coordinator = UsbSessionCoordinator(host)

        coordinator.start()

        assertTrue(coordinator.sessions.value.isEmpty())
    }

    @Test
    fun repeatedScanDoesNotDisturbAWorkingSession() {
        val host = FakeUsbHost(attached = listOf(deviceA), permitted = setOf(deviceA.deviceName))
        val coordinator = UsbSessionCoordinator(host)
        coordinator.start()
        val first = coordinator.sessions.value.single()

        coordinator.scanAttachedDevices()

        assertEquals(first.generation, coordinator.sessions.value.single().generation)
    }

    @Test
    fun timeoutClosesTheSessionAndReportsWhyOnlyWhenPermissionIsStillAbsent() {
        val host = FakeUsbHost(attached = listOf(deviceA))
        val coordinator = UsbSessionCoordinator(host)
        coordinator.start()
        val session = coordinator.sessions.value.single()

        val outcome = coordinator.onPermissionTimeout(session.generation, permissionGrantedNow = false)

        assertEquals(UsbPermissionPolicy.TimeoutOutcome.CLOSE_AND_REPORT, outcome)
        assertTrue(coordinator.sessions.value.isEmpty())
    }

    @Test
    fun aTimeoutArrivingAfterTheAnswerChangesNothing() {
        val host = FakeUsbHost(attached = listOf(deviceA))
        val coordinator = UsbSessionCoordinator(host)
        coordinator.start()
        val session = coordinator.sessions.value.single()
        coordinator.onPermissionResult(deviceA, granted = true)

        val outcome = coordinator.onPermissionTimeout(session.generation, permissionGrantedNow = true)

        assertEquals(UsbPermissionPolicy.TimeoutOutcome.IGNORE, outcome)
        assertEquals(UsbSessionState.READY, coordinator.sessions.value.single().state)
    }

    @Test
    fun anAnswerWithoutADescriptorChangesNothing() {
        val host = FakeUsbHost(attached = listOf(deviceA))
        val coordinator = UsbSessionCoordinator(host)
        coordinator.start()

        coordinator.onPermissionResult(device = null, granted = true)

        assertEquals(
            listOf(UsbSessionState.PERMISSION_PENDING),
            coordinator.sessions.value.map { it.state },
        )
    }

    @Test
    fun stoppingKeepsOpenSessionsBecauseDevicesAreStillAttached() {
        val host = FakeUsbHost(attached = listOf(deviceA), permitted = setOf(deviceA.deviceName))
        val coordinator = UsbSessionCoordinator(host)
        coordinator.start()

        coordinator.stop()

        assertTrue(host.stopped)
        assertEquals(1, coordinator.sessions.value.size)
    }

    @Test
    fun claimingAReadySessionHoldsTheInterface() {
        val host = FakeUsbHost(attached = listOf(deviceA), permitted = setOf(deviceA.deviceName))
        val sink = InMemoryDiagnosticSink()
        val coordinator = coordinatorWith(host, sink)
        coordinator.start()
        val session = coordinator.sessions.value.single()

        val result = coordinator.claim(session.generation)

        assertTrue((result as UsbClaimResult.Claimed).handle.held)
        assertEquals(UsbSessionState.CLAIMED, coordinator.sessions.value.single().state)
        assertEquals("CLAIMED", sink.snapshot().last { it.message == "interface_claimed" }.fields["state"])
    }

    @Test
    fun releasingReturnsToReadyWithoutEndingTheSession() {
        val host = FakeUsbHost(attached = listOf(deviceA), permitted = setOf(deviceA.deviceName))
        val coordinator = coordinatorWith(host, InMemoryDiagnosticSink())
        coordinator.start()
        val session = coordinator.sessions.value.single()
        val claimed = coordinator.claim(session.generation) as UsbClaimResult.Claimed

        coordinator.release(session.generation)

        assertFalse(claimed.handle.held)
        val after = coordinator.sessions.value.single()
        assertEquals(UsbSessionState.READY, after.state)
        assertEquals(session.generation, after.generation)
    }

    @Test
    fun claimingWithoutPermissionIsRefusedBeforeTouchingTheDevice() {
        val host = FakeUsbHost(attached = listOf(deviceA))
        val coordinator = coordinatorWith(host, InMemoryDiagnosticSink())
        coordinator.start()
        val pending = coordinator.sessions.value.single()

        val result = coordinator.claim(pending.generation)

        assertTrue(result is UsbClaimResult.Failed)
        assertTrue(host.claims.isEmpty())
        assertEquals(UsbSessionState.PERMISSION_PENDING, coordinator.sessions.value.single().state)
    }

    @Test
    fun aRefusedClaimIsReportedWithThePlatformReason() {
        val host = FakeUsbHost(
            attached = listOf(deviceA),
            permitted = setOf(deviceA.deviceName),
            claimFailure = UsbClaimFailure.INTERFACE_REFUSED,
        )
        val sink = InMemoryDiagnosticSink()
        val coordinator = coordinatorWith(host, sink)
        coordinator.start()
        val session = coordinator.sessions.value.single()

        val result = coordinator.claim(session.generation)

        assertEquals(UsbClaimFailure.INTERFACE_REFUSED, (result as UsbClaimResult.Failed).reason)
        assertEquals("INTERFACE_REFUSED", sink.snapshot().last().fields["reason"])
        assertEquals(UsbSessionState.READY, coordinator.sessions.value.single().state)
    }

    @Test
    fun detachReleasesAHeldInterface() {
        val host = FakeUsbHost(attached = listOf(deviceA), permitted = setOf(deviceA.deviceName))
        val coordinator = coordinatorWith(host, InMemoryDiagnosticSink())
        coordinator.start()
        val session = coordinator.sessions.value.single()
        val claimed = coordinator.claim(session.generation) as UsbClaimResult.Claimed

        coordinator.onDeviceDetached(deviceA)

        assertFalse(claimed.handle.held)
    }

    @Test
    fun aStaleGenerationCannotBeClaimed() {
        val host = FakeUsbHost(attached = listOf(deviceA), permitted = setOf(deviceA.deviceName))
        val coordinator = coordinatorWith(host, InMemoryDiagnosticSink())
        coordinator.start()
        val session = coordinator.sessions.value.single()
        coordinator.onDeviceDetached(deviceA)

        val result = coordinator.claim(session.generation)

        assertEquals(UsbClaimFailure.DEVICE_GONE, (result as UsbClaimResult.Failed).reason)
        assertTrue(host.claims.isEmpty())
    }

    @Test
    fun releasingASessionThatHoldsNothingChangesNothing() {
        val host = FakeUsbHost(attached = listOf(deviceA), permitted = setOf(deviceA.deviceName))
        val coordinator = coordinatorWith(host, InMemoryDiagnosticSink())
        coordinator.start()
        val session = coordinator.sessions.value.single()

        coordinator.release(session.generation)

        assertEquals(UsbSessionState.READY, coordinator.sessions.value.single().state)
    }

    @Test
    fun rescanRetriesPermissionForADeviceThatIsStillAttachedAfterDenial() {
        val host = FakeUsbHost(attached = listOf(deviceA))
        val registry = UsbSessionRegistry()
        val coordinator = UsbSessionCoordinator(host, registry)
        coordinator.start()
        val denied = coordinator.sessions.value.single()
        coordinator.onPermissionResult(deviceA, granted = false)
        assertTrue(coordinator.sessions.value.isEmpty())

        coordinator.scanAttachedDevices()

        val retried = coordinator.sessions.value.single()
        assertEquals(UsbSessionState.PERMISSION_PENDING, retried.state)
        assertTrue(retried.generation.value > denied.generation.value)
        assertEquals(
            UsbSessionClosureReason.PERMISSION_DENIED,
            registry.session(denied.generation)?.closureReason,
        )
        assertEquals(listOf(deviceA.deviceName, deviceA.deviceName), host.permissionRequests)
    }

    @Test
    fun rescanDoesNotDisturbASessionThatIsStillWaitingForAnAnswer() {
        val host = FakeUsbHost(attached = listOf(deviceA))
        val coordinator = UsbSessionCoordinator(host)
        coordinator.start()
        val pending = coordinator.sessions.value.single()

        coordinator.scanAttachedDevices()

        assertEquals(pending.generation, coordinator.sessions.value.single().generation)
        assertEquals(listOf(deviceA.deviceName), host.permissionRequests)
    }

    @Test
    fun everyEventReportsTheStateAfterItsTransition() {
        val host = FakeUsbHost(attached = listOf(deviceA))
        val sink = InMemoryDiagnosticSink()
        val coordinator = coordinatorWith(host, sink)

        coordinator.start()
        coordinator.onPermissionResult(deviceA.copy(serialNumber = "MI9SERIAL"), granted = true)

        val states = sink.snapshot().associate { it.message to it.fields["state"] }
        assertEquals("PERMISSION_PENDING", states["permission_requested"])
        assertEquals("READY", states["permission_granted"])
        assertEquals("READY", states["identity_refined"])
    }

    @Test
    fun anAlreadyPermittedDeviceReportsReadyNotDiscovered() {
        val host = FakeUsbHost(attached = listOf(deviceA), permitted = setOf(deviceA.deviceName))
        val sink = InMemoryDiagnosticSink()

        coordinatorWith(host, sink).start()

        val event = sink.snapshot().single { it.message == "permission_already_granted" }
        assertEquals("READY", event.fields["state"])
    }

    @Test
    fun aDeniedPermissionReportsClosedNotPending() {
        val host = FakeUsbHost(attached = listOf(deviceA))
        val sink = InMemoryDiagnosticSink()
        val coordinator = coordinatorWith(host, sink)
        coordinator.start()

        coordinator.onPermissionResult(deviceA, granted = false)

        val event = sink.snapshot().single { it.message == "permission_denied" }
        assertEquals("CLOSED", event.fields["state"])
    }

    @Test
    fun closedSessionsStayAvailableForTheReport() {
        val host = FakeUsbHost(attached = listOf(deviceA), permitted = setOf(deviceA.deviceName))
        val coordinator = coordinatorWith(host, InMemoryDiagnosticSink())
        coordinator.start()
        val opened = coordinator.sessions.value.single()

        coordinator.onDeviceDetached(deviceA)

        assertTrue(coordinator.sessions.value.isEmpty())
        assertEquals(
            listOf(opened.generation),
            coordinator.recentlyClosedSessions().map { it.generation },
        )
        assertEquals(
            UsbSessionClosureReason.DETACHED,
            coordinator.recentlyClosedSessions().single().closureReason,
        )
    }

    @Test
    fun aPermissionRequestAnnouncesItselfSoTheTimeoutCanBeScheduled() {
        val host = FakeUsbHost(attached = listOf(deviceA))
        val scheduled = mutableListOf<Long>()
        val coordinator = UsbSessionCoordinator(
            host = host,
            onPermissionRequested = { scheduled += it.value },
        )

        coordinator.start()

        assertEquals(listOf(coordinator.sessions.value.single().generation.value), scheduled)
    }

    @Test
    fun noTimeoutIsScheduledWhenPermissionWasAlreadyGranted() {
        val host = FakeUsbHost(attached = listOf(deviceA), permitted = setOf(deviceA.deviceName))
        val scheduled = mutableListOf<Long>()
        val coordinator = UsbSessionCoordinator(
            host = host,
            onPermissionRequested = { scheduled += it.value },
        )

        coordinator.start()

        assertTrue(scheduled.isEmpty())
    }

    @Test
    fun aFullPermissionCycleIsRecordedAsEvidence() {
        val host = FakeUsbHost(attached = listOf(deviceA))
        val sink = InMemoryDiagnosticSink()
        val coordinator = coordinatorWith(host, sink)

        coordinator.start()
        coordinator.onPermissionResult(deviceA.copy(serialNumber = "MI9SERIAL"), granted = true)
        coordinator.onDeviceDetached(deviceA)

        assertEquals(
            listOf(
                // Скан теперь сообщает результат первым делом: пустой журнал
                // невозможно отличить от неработающего приложения.
                "scan_completed",
                "session_opened",
                "permission_requested",
                "permission_granted",
                "identity_refined",
                "session_closed_detached",
            ),
            sink.snapshot().map { it.message },
        )
    }

    @Test
    fun anOpenedSessionCarriesEnoughContextToBeUseful() {
        val host = FakeUsbHost(attached = listOf(deviceA))
        val sink = InMemoryDiagnosticSink()

        coordinatorWith(host, sink).start()

        val opened = sink.snapshot().first { it.message == "session_opened" }
        assertEquals("usb", opened.category)
        assertEquals(1L, opened.sessionGeneration?.value)
        assertEquals("/dev/bus/usb/001/002", opened.fields["connection"])
        assertEquals("0x2717", opened.fields["vendorId"])
        assertEquals("ADB", opened.fields["interfaceKind"])
        assertEquals("CANONICAL", opened.fields["matchConfidence"])
        assertEquals("DISCOVERED", opened.fields["state"])
        assertEquals(FIXED_TIME, opened.timestamp)
    }

    @Test
    fun aDeniedPermissionAndAnIgnoredDeviceAreBothRecorded() {
        val sink = InMemoryDiagnosticSink()
        val host = FakeUsbHost(attached = listOf(deviceA.copy(interfaces = emptyList())))
        coordinatorWith(host, sink).start()

        val withUsable = FakeUsbHost(attached = listOf(deviceA))
        val denied = InMemoryDiagnosticSink()
        val coordinator = coordinatorWith(withUsable, denied)
        coordinator.start()
        coordinator.onPermissionResult(deviceA, granted = false)

        assertEquals(listOf("scan_completed", "device_ignored_no_usable_interface"), sink.snapshot().map { it.message })
        assertEquals("permission_denied", denied.snapshot().last().message)
    }

    @Test
    fun aTimeoutRecordsWhichOutcomeWasChosen() {
        val host = FakeUsbHost(attached = listOf(deviceA))
        val sink = InMemoryDiagnosticSink()
        val coordinator = coordinatorWith(host, sink)
        coordinator.start()
        val session = coordinator.sessions.value.single()

        coordinator.onPermissionTimeout(session.generation, permissionGrantedNow = false)

        val timeout = sink.snapshot().last()
        assertEquals("permission_timeout", timeout.message)
        assertEquals("CLOSE_AND_REPORT", timeout.fields["outcome"])
    }

    @Test
    fun byDefaultNothingIsRecorded() {
        val host = FakeUsbHost(attached = listOf(deviceA))

        UsbSessionCoordinator(host).start()
    }

    private class FakeUsbHost(
        private val attached: List<UsbDeviceDescriptor>,
        private val permitted: Set<String> = emptySet(),
        private val requestSucceeds: Boolean = true,
        private val claimFailure: UsbClaimFailure? = null,
    ) : UsbHost {
        val permissionRequests = mutableListOf<String>()
        val claims = mutableListOf<String>()
        var stopped = false
            private set

        override fun start(listener: UsbHost.Listener) {
            stopped = false
        }

        override fun stop() {
            stopped = true
        }

        override fun devices(): List<UsbDeviceDescriptor> = attached

        override fun hasPermission(device: UsbDeviceDescriptor): Boolean =
            device.deviceName in permitted

        override fun requestPermission(device: UsbDeviceDescriptor): Boolean {
            if (!requestSucceeds) return false
            permissionRequests += device.deviceName
            return true
        }

        override fun claim(candidate: UsbInterfaceCandidate): UsbClaimResult {
            claims += candidate.device.deviceName
            claimFailure?.let { return UsbClaimResult.Failed(it) }
            return UsbClaimResult.Claimed(FakeHandle(candidate))
        }
    }

    /**
     * За фейком нет устройства, и ввод-вывод в координаторе не участвует:
     * передача честно не состоится, а не притворится удачной.
     */
    /**
     * Скан обязан сообщать результат и тогда, когда система не отдала ничего:
     * молчание в этом месте неотличимо от сломанного приложения.
     */
    @Test
    fun scanWithoutAnyDeviceIsStillReported() {
        val events = InMemoryDiagnosticSink()
        val coordinator = coordinatorWith(FakeUsbHost(attached = emptyList()), events)

        coordinator.scanAttachedDevices()

        assertEquals(0, coordinator.lastScan.value.visibleDevices)
        assertTrue(coordinator.lastScan.value.scanned)
        val scan = events.snapshot().single { it.message == "scan_completed" }
        assertEquals("0", scan.fields["visibleDevices"])
    }

    @Test
    fun scanCountsWhatTheSystemReturned() {
        val coordinator = UsbSessionCoordinator(FakeUsbHost(attached = listOf(deviceA, deviceB)))

        coordinator.scanAttachedDevices()

        assertEquals(2, coordinator.lastScan.value.visibleDevices)
        assertEquals(2, coordinator.lastScan.value.newDevices)
    }

    @Test
    fun repeatedScanSeparatesKnownDevicesFromNewOnes() {
        val coordinator = UsbSessionCoordinator(FakeUsbHost(attached = listOf(deviceA)))
        coordinator.scanAttachedDevices()

        coordinator.scanAttachedDevices()

        assertEquals(1, coordinator.lastScan.value.visibleDevices)
        assertEquals(0, coordinator.lastScan.value.newDevices)
        assertEquals(1, coordinator.lastScan.value.knownDevices)
    }

    /** До первого разбора «ноль устройств» сказать нельзя. */
    @Test
    fun scanSummaryStartsAsNeverScanned() {
        assertFalse(UsbSessionCoordinator(FakeUsbHost(attached = emptyList())).lastScan.value.scanned)
    }

    private class FakeHandle(
        override val candidate: UsbInterfaceCandidate,
    ) : UsbTransportHandle {
        private var released = false

        override val held: Boolean
            get() = !released

        override fun receive(
            destination: ByteArray,
            offset: Int,
            length: Int,
            timeoutMillis: Int,
        ): UsbTransferResult = transfer(destination.size, offset, length, timeoutMillis)

        override fun send(
            source: ByteArray,
            offset: Int,
            length: Int,
            timeoutMillis: Int,
        ): UsbTransferResult = transfer(source.size, offset, length, timeoutMillis)

        private fun transfer(
            bufferSize: Int,
            offset: Int,
            length: Int,
            timeoutMillis: Int,
        ): UsbTransferResult {
            UsbTransferArguments.validate(bufferSize, offset, length, timeoutMillis)
            return if (released) {
                UsbTransferResult.Failed(UsbTransferFailure.NOT_HELD)
            } else {
                UsbTransferResult.Failed(UsbTransferFailure.NOT_COMPLETED)
            }
        }

        override fun close() {
            released = true
        }
    }

    private companion object {
        val FIXED_TIME: Instant = Instant.parse("2026-08-31T10:00:00Z")

        fun coordinatorWith(host: UsbHost, sink: InMemoryDiagnosticSink) = UsbSessionCoordinator(
            host = host,
            diagnostics = sink,
            clock = { FIXED_TIME },
        )

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
    }
}
