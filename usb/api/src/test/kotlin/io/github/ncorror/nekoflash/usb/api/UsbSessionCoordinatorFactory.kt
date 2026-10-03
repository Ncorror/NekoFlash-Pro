package io.github.ncorror.nekoflash.usb.api

import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticSink
import io.github.ncorror.nekoflash.core.model.SessionGeneration
import java.time.Instant

/** Test-only convenience: production must always provide real runtime dependencies. */
internal fun UsbSessionCoordinator(
    host: UsbHost,
    registry: UsbSessionRegistry = UsbSessionRegistry(),
    allowGenericVendor: Boolean = true,
    onPermissionRequested: (SessionGeneration) -> Unit = {},
    clock: () -> Instant = Instant::now,
): UsbSessionCoordinator = UsbSessionCoordinator(
    host = host,
    registry = registry,
    allowGenericVendor = allowGenericVendor,
    onPermissionRequested = onPermissionRequested,
    diagnostics = DiagnosticSink { },
    clock = clock,
)

/** Test-only variant that keeps a real diagnostic sink while omitting Android timeout wiring. */
internal fun UsbSessionCoordinator(
    host: UsbHost,
    diagnostics: DiagnosticSink,
    clock: () -> Instant = Instant::now,
): UsbSessionCoordinator = UsbSessionCoordinator(
    host = host,
    onPermissionRequested = {},
    diagnostics = diagnostics,
    clock = clock,
)
