package io.github.ncorror.nekoflash.ui

import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticEvent

/** Live diagnostics source used by the Logs workspace. */
fun interface RecentEvents {
    fun snapshot(): List<DiagnosticEvent>
}
