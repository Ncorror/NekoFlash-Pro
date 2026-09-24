package com.ncorror.nekoflash.pro.diagnostics

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import java.io.File
import java.io.IOException
import java.time.Instant

/** Small, persistent app-private log; export is initiated by the user via the system file picker. */
class DiagnosticJournal(context: Context) {
    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, "diagnostics.log")

    @Synchronized
    fun record(event: String) {
        try {
            val line = "${Instant.now()} ${event.replace(Regex("[\\r\\n\\t]+"), " ").take(500)}\n"
            file.appendText(line, Charsets.UTF_8)
            if (file.length() > MAX_BYTES) trim()
        } catch (error: IOException) {
            Log.w(TAG, "Could not write diagnostic event", error)
        }
    }

    fun recordFailure(event: String, error: Throwable) {
        record("$event: ${error.javaClass.name}: ${error.message}")
        error.stackTrace.take(24).forEach { record("at $it") }
    }

    /** Call from a background thread: the selected document provider can be slow. */
    @Synchronized
    fun export(uri: Uri) {
        val stream = appContext.contentResolver.openOutputStream(uri)
            ?: throw IOException("Could not open the selected document")
        stream.bufferedWriter(Charsets.UTF_8).use { output ->
            output.appendLine("NekoFlash Pro diagnostics")
            output.appendLine("Exported: ${Instant.now()}")
            output.appendLine("Package: ${appContext.packageName}")
            output.appendLine("Android: API ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")
            output.appendLine("Phone: ${Build.MANUFACTURER} ${Build.MODEL}")
            output.appendLine("Recent app and USB events (at most ${MAX_BYTES / 1024} KiB):")
            output.appendLine()
            if (file.exists()) {
                file.bufferedReader(Charsets.UTF_8).use { it.copyTo(output) }
            } else {
                output.appendLine("No events recorded yet.")
            }
        }
    }

    private fun trim() {
        var bytes = 0
        val lines = mutableListOf<String>()
        for (line in file.readLines(Charsets.UTF_8).asReversed()) {
            val size = line.toByteArray(Charsets.UTF_8).size + 1
            if (bytes + size > MAX_BYTES / 2) break
            lines.add(line)
            bytes += size
        }
        file.writeText(lines.asReversed().joinToString("\n", postfix = "\n"), Charsets.UTF_8)
    }

    private companion object {
        const val TAG = "NekoFlashDiagnostics"
        const val MAX_BYTES = 256 * 1024
    }
}
