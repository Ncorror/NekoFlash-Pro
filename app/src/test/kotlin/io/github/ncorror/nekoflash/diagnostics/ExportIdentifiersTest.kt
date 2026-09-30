package io.github.ncorror.nekoflash.diagnostics

import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticBundleSection
import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticEvent
import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticRedaction
import io.github.ncorror.nekoflash.core.model.TargetId
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportIdentifiersTest {
    private val fingerprint = "3f1c".repeat(16)

    private fun event(message: String, fields: Map<String, String> = emptyMap(), target: String? = null) =
        DiagnosticEvent(Instant.EPOCH, "test", message, target?.let(::TargetId), fields = fields)

    @Test
    fun everyIdentifierThatLeavesTheAppIsCollected() {
        val events = listOf(
            event("interface_claimed", target = "serial:abc123"),
            event("host_key", mapOf("origin" to "LOADED", "fingerprint" to fingerprint)),
            // Номер из `getvar:serialno` может не совпасть с номером USB.
            event("fastboot_getvar_finished", mapOf("command" to "getvar:serialno", "payload" to "fb-7788")),
            event("fastboot_getvar_finished", mapOf("command" to "getvar:product", "payload" to "vayu")),
        )

        val identifiers = ExportIdentifiers.of(
            serials = listOf("usb-5566"),
            events = events,
            host = mapOf("host.androidId" to "a1b2c3d4e5f6", "host.model" to "25053PC47G"),
        )

        assertEquals(setOf("usb-5566", "abc123", fingerprint, "fb-7788", "a1b2c3d4e5f6"), identifiers)
    }

    @Test
    fun unavailableAndroidIdIsAWordNotAValue() {
        val identifiers = ExportIdentifiers.of(emptyList(), emptyList(), mapOf("host.androidId" to "unavailable"))

        assertTrue(identifiers.isEmpty())
    }

    @Test
    fun theHostKeyFingerprintDoesNotLeaveTheApp() {
        val line = "host_key origin=LOADED fingerprint=$fingerprint"
        val events = listOf(event("host_key", mapOf("fingerprint" to fingerprint)))
        val identifiers = ExportIdentifiers.of(emptyList(), events, emptyMap())

        val redacted = DiagnosticRedaction.redact(listOf(DiagnosticBundleSection("events.txt", line)), identifiers)

        assertFalse(redacted.single().content.contains(fingerprint))
        assertTrue(redacted.single().content.contains(DiagnosticRedaction.token(fingerprint)))
    }
}
