package io.github.ncorror.nekoflash.usb.api

import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticEvent
import io.github.ncorror.nekoflash.core.model.SessionGeneration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbDiagnosticReportTest {
    @Test
    fun theReportAlwaysHasTheSameThreeSectionsInTheSameOrder() {
        val sections = UsbDiagnosticReport.sections(
            host = emptyMap(),
            sessions = emptyList(),
            events = emptyList(),
            droppedEvents = 0L,
        )

        assertEquals(
            listOf(
                UsbDiagnosticReport.HOST_SECTION,
                UsbDiagnosticReport.SESSIONS_SECTION,
                UsbDiagnosticReport.EVENTS_SECTION,
            ),
            sections.map { it.name },
        )
    }

    @Test
    fun hostFactsAreSortedSoTwoReportsCompareLineByLine() {
        val text = sectionText(
            host = linkedMapOf("z" to "last", "a" to "first", "m" to "middle"),
        )

        assertEquals(listOf("a=first", "m=middle", "z=last"), text.trimEnd('\n').lines())
    }

    @Test
    fun aSessionDisclosesEverythingTheAppKnows() {
        val session = readySession()

        val block = sectionText(
            sessions = listOf(session),
            name = UsbDiagnosticReport.SESSIONS_SECTION,
        )
        val fields = block.trimEnd('\n').lines().associate { it.substringBefore('=') to it.substringAfter('=') }

        assertEquals("1", fields["generation"])
        assertEquals("serial:MI9SERIAL", fields["targetId"])
        assertEquals("SERIAL", fields["identitySource"])
        assertEquals("true", fields["survivesReattachment"])
        assertEquals("MI9SERIAL", fields["serialNumber"])
        assertEquals("READY", fields["state"])
        assertEquals("0x2717", fields["vendorId"])
        assertEquals("0xFF48", fields["productId"])
        assertEquals("ADB", fields["interfaceKind"])
        assertEquals("CANONICAL", fields["matchConfidence"])
        assertEquals("0x0081", fields["endpointIn"])
        assertEquals("512", fields["maxPacketSizeIn"])
    }

    @Test
    fun theProtocolModeIsNeverGuessed() {
        val block = sectionText(
            sessions = listOf(readySession()),
            name = UsbDiagnosticReport.SESSIONS_SECTION,
        )

        assertTrue(block.contains("protocolMode=unknown_until_handshake"))
        assertFalse(block.contains("protocolMode=ADB"))
    }

    @Test
    fun anAbsentFieldSaysWhyInsteadOfBeingOmitted() {
        val session = readySession(serialNumber = null, productName = null)

        val block = sectionText(
            sessions = listOf(session),
            name = UsbDiagnosticReport.SESSIONS_SECTION,
        )

        assertTrue(block.contains("serialNumber=not_reported_by_device"))
        assertTrue(block.contains("productName=not_reported_by_device"))
        assertTrue(block.contains("closureReason=none"))
    }

    @Test
    fun droppedEventsAreStatedEvenWhenNoneWereDropped() {
        val withLoss = sectionText(droppedEvents = 7L, name = UsbDiagnosticReport.EVENTS_SECTION)
        val withoutLoss = sectionText(droppedEvents = 0L, name = UsbDiagnosticReport.EVENTS_SECTION)

        assertTrue(withLoss.startsWith("# droppedEvents=7\n"))
        assertTrue(withoutLoss.startsWith("# droppedEvents=0\n"))
    }

    @Test
    fun eventsKeepTheOrderTheyHappened() {
        val events = listOf(
            DiagnosticEvent(Instant.EPOCH, "usb", "first"),
            DiagnosticEvent(Instant.EPOCH.plusSeconds(1), "usb", "second"),
        )

        val text = sectionText(events = events, name = UsbDiagnosticReport.EVENTS_SECTION)

        assertEquals(
            listOf(
                "# droppedEvents=0",
                "1970-01-01T00:00:00Z usb first",
                "1970-01-01T00:00:01Z usb second",
            ),
            text.trimEnd('\n').lines(),
        )
    }

    @Test
    fun emptinessIsExplainedRatherThanLeftBlank() {
        assertTrue(sectionText().contains("no host facts"))
        assertTrue(sectionText(name = UsbDiagnosticReport.SESSIONS_SECTION).contains("no sessions"))
        assertTrue(sectionText(name = UsbDiagnosticReport.EVENTS_SECTION).contains("no events"))
    }

    @Test
    fun aNewlineInsideAValueCannotBreakTheFormat() {
        val text = sectionText(host = mapOf("note" to "broken\nvalue"))

        assertEquals(listOf("note=broken value"), text.trimEnd('\n').lines())
    }

    private companion object {
        fun sectionText(
            host: Map<String, String> = emptyMap(),
            sessions: List<UsbSession> = emptyList(),
            events: List<DiagnosticEvent> = emptyList(),
            droppedEvents: Long = 0L,
            name: String = UsbDiagnosticReport.HOST_SECTION,
        ): String = UsbDiagnosticReport
            .sections(host, sessions, events, droppedEvents)
            .single { it.name == name }
            .content

        fun readySession(
            serialNumber: String? = "MI9SERIAL",
            productName: String? = "POCO F3",
        ): UsbSession {
            val device = UsbDeviceDescriptor(
                deviceId = 1001,
                deviceName = "/dev/bus/usb/001/002",
                vendorId = 0x2717,
                productId = 0xFF48,
                productName = productName,
                serialNumber = serialNumber,
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
            return UsbSession(
                generation = SessionGeneration(1L),
                targetId = UsbTargetIdentity.fromDescriptor(device).id,
                identity = UsbTargetIdentity.fromDescriptor(device),
                candidate = UsbInterfaceClassifier.primaryCandidate(device)!!,
                state = UsbSessionState.READY,
            )
        }
    }
}
