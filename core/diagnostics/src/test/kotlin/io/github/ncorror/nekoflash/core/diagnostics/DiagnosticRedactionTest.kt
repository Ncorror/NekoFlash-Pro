package io.github.ncorror.nekoflash.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticRedactionTest {
    @Test
    fun everyOccurrenceInEverySectionIsReplacedByOneStableToken() {
        val sections = listOf(
            DiagnosticBundleSection("events.txt", "session_opened target=serial:eff4927c\ngetvar serialno=eff4927c\n"),
            DiagnosticBundleSection("usb.txt", "serialNumber=eff4927c\n"),
        )

        val redacted = DiagnosticRedaction.redact(sections, setOf("eff4927c"))

        val token = DiagnosticRedaction.token("eff4927c")
        assertEquals("session_opened target=serial:$token\ngetvar serialno=$token\n", redacted[0].content)
        assertEquals("serialNumber=$token\n", redacted[1].content)
        assertTrue(redacted.none { it.content.contains("eff4927c") })
    }

    @Test
    fun theTokenDistinguishesDevicesWithoutRevealingTheSerial() {
        val first = DiagnosticRedaction.token("eff4927c")

        assertEquals(first, DiagnosticRedaction.token("eff4927c"))
        assertNotEquals(first, DiagnosticRedaction.token("eff4927d"))
        assertFalse(first.contains("eff4927c"))
        assertTrue(first.matches(Regex("redacted-[0-9a-f]{8}")))
    }

    @Test
    fun aSerialInsideALongerOneDoesNotBreakTheLongerReplacement() {
        val sections = listOf(DiagnosticBundleSection("events.txt", "a=ABCD1234 b=ABCD\n"))

        val redacted = DiagnosticRedaction.redact(sections, setOf("ABCD", "ABCD1234"))

        assertEquals(
            "a=${DiagnosticRedaction.token("ABCD1234")} b=${DiagnosticRedaction.token("ABCD")}\n",
            redacted.single().content,
        )
    }

    @Test
    fun blankAndTooShortValuesAreNotTreatedAsSecrets() {
        val sections = listOf(DiagnosticBundleSection("events.txt", "id=1 mode=ADB\n"))

        assertEquals(sections, DiagnosticRedaction.redact(sections, setOf("", " ", "1", "ADB")))
    }
}
