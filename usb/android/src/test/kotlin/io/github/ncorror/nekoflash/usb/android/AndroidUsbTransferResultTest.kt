package io.github.ncorror.nekoflash.usb.android

import io.github.ncorror.nekoflash.usb.api.UsbTransferFailure
import io.github.ncorror.nekoflash.usb.api.UsbTransferResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidUsbTransferResultTest {
    @Test
    fun positiveTransferKeepsExactByteCount() {
        assertEquals(UsbTransferResult.Completed(123), bulkTransferResult(123))
    }

    @Test
    fun zeroIsACompletedTransferBecauseAndroidReportsItAsNonNegative() {
        assertEquals(UsbTransferResult.Completed(0), bulkTransferResult(0))
    }

    @Test
    fun negativeTransferStaysUnknownPlatformFailure() {
        val result = bulkTransferResult(-1)
        assertTrue(result is UsbTransferResult.Failed)
        assertEquals(UsbTransferFailure.NOT_COMPLETED, (result as UsbTransferResult.Failed).reason)
    }
}
