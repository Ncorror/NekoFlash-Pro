package io.github.ncorror.nekoflash.usb.api

import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticSink
import io.github.ncorror.nekoflash.core.model.SessionGeneration
import java.time.Instant

/** Test-only convenience: production must always provide a real diagnostic sink. */
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
