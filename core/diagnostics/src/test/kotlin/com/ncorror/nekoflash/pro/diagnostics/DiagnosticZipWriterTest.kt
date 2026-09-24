package com.ncorror.nekoflash.pro.diagnostics

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DiagnosticZipWriterTest {
    @Test
    fun archiveCanBeOpenedAndRetainsUtf8EventData() {
        val output = ByteArrayOutputStream()
        DiagnosticZipWriter.write(output, listOf(
            DiagnosticEntry("README.txt", "No serial numbers"),
            DiagnosticEntry("events.log", "Доступ к USB разрешён\nVID=18D1 PID=4EE7\n"),
        ))

        val files = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                files[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
            }
        }
        assertEquals("No serial numbers", files["README.txt"])
        assertEquals("Доступ к USB разрешён\nVID=18D1 PID=4EE7\n", files["events.log"])
    }

    @Test
    fun unsafePathsAndDuplicateNamesAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            DiagnosticZipWriter.write(ByteArrayOutputStream(), listOf(DiagnosticEntry("../data", "x")))
        }
        assertFailsWith<IllegalArgumentException> {
            DiagnosticZipWriter.write(ByteArrayOutputStream(), listOf(
                DiagnosticEntry("events.log", "one"), DiagnosticEntry("events.log", "two"),
            ))
        }
    }
}
