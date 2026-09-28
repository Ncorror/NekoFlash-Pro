package io.github.ncorror.nekoflash.usb.transport

/** Pure fail-closed decisions used before releasing an owned USB connection. */
object UsbTransportShutdownPolicy {
    fun requiresDrain(
        operationCompleted: Boolean?,
        kotlinTransferActive: Boolean,
        nativeTransferActive: Boolean,
    ): Boolean =
        operationCompleted == false || kotlinTransferActive || nativeTransferActive

    fun canCloseUsb(
        kotlinTransferActive: Boolean,
        nativeTransferActive: Boolean,
    ): Boolean = !kotlinTransferActive && !nativeTransferActive
}
