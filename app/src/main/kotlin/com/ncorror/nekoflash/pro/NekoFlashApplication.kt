package com.ncorror.nekoflash.pro

import android.app.Application
import com.ncorror.nekoflash.pro.diagnostics.DiagnosticJournal

class NekoFlashApplication : Application() {
    lateinit var diagnostics: DiagnosticJournal
        private set

    override fun onCreate() {
        super.onCreate()
        diagnostics = DiagnosticJournal(this)
        val originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        if (originalHandler != null) {
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                try {
                    diagnostics.recordFailure("Uncaught error on ${thread.name}", error)
                } catch (_: Throwable) {
                    // Never prevent Android's original crash handler from running.
                }
                originalHandler.uncaughtException(thread, error)
            }
        }
    }
}
