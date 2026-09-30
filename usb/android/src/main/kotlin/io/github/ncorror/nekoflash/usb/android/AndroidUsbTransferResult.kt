package io.github.ncorror.nekoflash.usb.android

import io.github.ncorror.nekoflash.usb.api.UsbTransferFailure
import io.github.ncorror.nekoflash.usb.api.UsbTransferResult

/** Перевод документированного результата Android `bulkTransfer` в общий контракт. */
internal fun bulkTransferResult(transferred: Int): UsbTransferResult =
    if (transferred >= 0) {
        UsbTransferResult.Completed(transferred)
    } else {
        UsbTransferResult.Failed(UsbTransferFailure.NOT_COMPLETED)
    }
