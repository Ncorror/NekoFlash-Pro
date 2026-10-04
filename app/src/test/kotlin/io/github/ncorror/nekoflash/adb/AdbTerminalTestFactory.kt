package io.github.ncorror.nekoflash.adb

import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticSink
import java.util.concurrent.Executor

/** Test-only convenience: production terminal controllers must receive real diagnostics. */
internal fun AdbTerminalController(
    readerExecutor: Executor,
    writerExecutor: Executor,
): AdbTerminalController = AdbTerminalController(
    readerExecutor = readerExecutor,
    writerExecutor = writerExecutor,
    diagnostics = DiagnosticSink { },
)
