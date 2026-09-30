package io.github.ncorror.nekoflash.core.diagnostics

import io.github.ncorror.nekoflash.core.model.SessionGeneration
import io.github.ncorror.nekoflash.core.model.TargetId
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.time.Instant
import java.util.zip.ZipInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticBundleTest {
    @Test
    fun theManifestComesFirstAndDescribesEverySection() {
        val entries = readEntries(writeBundle(sections))

        assertEquals(
            listOf(DiagnosticBundle.MANIFEST_NAME, "events.txt", "build.txt"),
            entries.keys.toList(),
        )
        val manifest = entries.getValue(DiagnosticBundle.MANIFEST_NAME)
        assertTrue(manifest.contains("schema=${DiagnosticBundle.SCHEMA}"))
        assertTrue(manifest.contains("exportedAt=$EXPORTED_AT"))
        assertTrue(manifest.contains("sectionCount=2"))
        assertTrue(manifest.contains("section=events.txt bytes=11"))
    }

    @Test
    fun sectionContentSurvivesTheRoundTrip() {
        val entries = readEntries(writeBundle(sections))

        assertEquals("first line\n", entries.getValue("events.txt"))
        assertEquals("build baseline", entries.getValue("build.txt"))
    }

    @Test
    fun twoIdenticalExportsProduceIdenticalBytes() {
        val first = writeBundle(sections)
        val second = writeBundle(sections)

        assertArrayEquals(first, second)
    }

    @Test
    fun aDifferentExportTimestampChangesTheArchive() {
        val first = writeBundle(sections)
        val later = ByteArrayOutputStream().also {
            DiagnosticBundle.write(it, sections, EXPORTED_AT.plusSeconds(1))
        }.toByteArray()

        assertFalse(first.contentEquals(later))
    }

    @Test
    fun onlyTheGivenSectionsAreWritten() {
        val entries = readEntries(writeBundle(emptyList()))

        assertEquals(listOf(DiagnosticBundle.MANIFEST_NAME), entries.keys.toList())
        assertTrue(entries.getValue(DiagnosticBundle.MANIFEST_NAME).contains("sectionCount=0"))
    }

    @Test
    fun theCallerKeepsOwnershipOfTheStream() {
        val output = ClosingAwareStream()

        DiagnosticBundle.write(output, sections, EXPORTED_AT)

        assertFalse(output.closed)
        assertTrue(output.size() > 0)
    }

    @Test
    fun theResultReportsWhatWasWritten() {
        val result = ByteArrayOutputStream().let {
            DiagnosticBundle.write(it, sections, EXPORTED_AT)
        }

        assertEquals(2, result.sectionCount)
        assertTrue(result.uncompressedBytes > "first line\nbuild baseline".length)
    }

    @Test
    fun duplicateAndUnsafeSectionNamesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticBundle.write(
                ByteArrayOutputStream(),
                listOf(section("same", "a"), section("same", "b")),
                EXPORTED_AT,
            )
        }
        assertThrows(IllegalArgumentException::class.java) { section("../escape.txt", "x") }
        assertThrows(IllegalArgumentException::class.java) { section("nested/file.txt", "x") }
        assertThrows(IllegalArgumentException::class.java) { section(" ", "x") }
        assertThrows(IllegalArgumentException::class.java) {
            section(DiagnosticBundle.MANIFEST_NAME, "x")
        }
    }

    @Test
    fun anEventBecomesOneGrepFriendlyLine() {
        val line = DiagnosticEvent(
            timestamp = Instant.EPOCH,
            category = "usb",
            message = "attached",
            targetId = TargetId("serial:MI9"),
            sessionGeneration = SessionGeneration(7L),
            fields = mapOf("kind" to "ADB", "confidence" to "CANONICAL"),
        ).toEvidenceLine()

        assertEquals(
            "1970-01-01T00:00:00Z usb attached target=serial:MI9 generation=7 " +
                "confidence=CANONICAL kind=ADB",
            line,
        )
    }

    @Test
    fun newlinesInsideAnEventCannotSplitItAcrossLines() {
        val line = DiagnosticEvent(
            timestamp = Instant.EPOCH,
            category = "usb",
            message = "broken\nmessage",
            fields = mapOf("detail" to "a\r\nb"),
        ).toEvidenceLine()

        assertFalse(line.contains('\n'))
        assertFalse(line.contains('\r'))
    }

    @Test
    fun eventsBecomeASectionInTheOrderTheyHappened() {
        val events = listOf(
            DiagnosticEvent(Instant.EPOCH, "usb", "first"),
            DiagnosticEvent(Instant.EPOCH.plusSeconds(1), "usb", "second"),
        )

        val section = events.toEvidenceSection()

        assertEquals("events.txt", section.name)
        assertEquals(
            listOf(
                "1970-01-01T00:00:00Z usb first",
                "1970-01-01T00:00:01Z usb second",
            ),
            section.content.trimEnd('\n').lines(),
        )
    }

    private class ClosingAwareStream : ByteArrayOutputStream() {
        var closed = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }

    private companion object {
        val EXPORTED_AT: Instant = Instant.parse("2026-08-30T12:00:00Z")

        val sections = listOf(
            section("events.txt", "first line\n"),
            section("build.txt", "build baseline"),
        )

        fun section(name: String, content: String) = DiagnosticBundleSection(name, content)

        fun writeBundle(sections: List<DiagnosticBundleSection>): ByteArray =
            ByteArrayOutputStream().also { output: OutputStream ->
                DiagnosticBundle.write(output, sections, EXPORTED_AT)
            }.toByteArray()

        fun readEntries(archive: ByteArray): LinkedHashMap<String, String> {
            val entries = LinkedHashMap<String, String>()
            ZipInputStream(archive.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                    zip.closeEntry()
                }
            }
            return entries
        }
    }
}
