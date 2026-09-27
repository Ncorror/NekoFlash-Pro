package com.ncorror.nekoflash.pro.diagnostics

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class DiagnosticEntry(val name: String, val content: String)

/** Writes a small ZIP with only flat, known text entries. Closes the destination stream. */
object DiagnosticZipWriter {
    private val allowedName = Regex("[A-Za-z0-9][A-Za-z0-9_.-]*")

    fun write(destination: OutputStream, entries: List<DiagnosticEntry>) {
        require(entries.isNotEmpty()) { "Diagnostic archive must contain files" }
        require(entries.all { allowedName.matches(it.name) }) { "Unsafe archive entry name" }
        require(entries.map { it.name }.distinct().size == entries.size) { "Duplicate archive entry" }

        ZipOutputStream(destination).use { zip ->
            entries.forEach { entry ->
                zip.putNextEntry(ZipEntry(entry.name))
                zip.write(entry.content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }
}
