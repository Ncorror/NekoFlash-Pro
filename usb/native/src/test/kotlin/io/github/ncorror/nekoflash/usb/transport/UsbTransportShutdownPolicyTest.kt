package io.github.ncorror.nekoflash.usb.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbTransportShutdownPolicyTest {
    @Test
    fun `unfinished operation requires drain even before native activity is visible`() {
        assertTrue(
            UsbTransportShutdownPolicy.requiresDrain(
                operationCompleted = false,
                kotlinTransferActive = false,
                nativeTransferActive = false,
            ),
        )
    }

    @Test
    fun `active kotlin or native transfer requires drain`() {
        assertTrue(
            UsbTransportShutdownPolicy.requiresDrain(
                operationCompleted = true,
                kotlinTransferActive = true,
                nativeTransferActive = false,
            ),
        )
        assertTrue(
            UsbTransportShutdownPolicy.requiresDrain(
                operationCompleted = null,
                kotlinTransferActive = false,
                nativeTransferActive = true,
            ),
        )
    }

    @Test
    fun `completed or absent operation needs no drain when both transfer views are idle`() {
        assertFalse(
            UsbTransportShutdownPolicy.requiresDrain(
                operationCompleted = true,
                kotlinTransferActive = false,
                nativeTransferActive = false,
            ),
        )
        assertFalse(
            UsbTransportShutdownPolicy.requiresDrain(
                operationCompleted = null,
                kotlinTransferActive = false,
                nativeTransferActive = false,
            ),
        )
    }

    @Test
    fun `usb close is allowed only after both transfer views are idle`() {
        assertTrue(UsbTransportShutdownPolicy.canCloseUsb(false, false))
        assertFalse(UsbTransportShutdownPolicy.canCloseUsb(true, false))
        assertFalse(UsbTransportShutdownPolicy.canCloseUsb(false, true))
        assertFalse(UsbTransportShutdownPolicy.canCloseUsb(true, true))
    }
}
