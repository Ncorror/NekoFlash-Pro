package io.github.ncorror.nekoflash.usb.api

import org.junit.Assert.assertThrows
import org.junit.Test

class UsbTransferArgumentsTest {
    @Test
    fun fullBufferIsAValidWindow() {
        UsbTransferArguments.validate(bufferSize = 24, offset = 0, length = 24, timeoutMillis = 1_000)
    }

    @Test
    fun tailOfTheBufferIsAValidWindow() {
        UsbTransferArguments.validate(bufferSize = 24, offset = 8, length = 16, timeoutMillis = 1_000)
    }

    @Test
    fun emptyTransferIsAllowed() {
        UsbTransferArguments.validate(bufferSize = 24, offset = 24, length = 0, timeoutMillis = 1_000)
    }

    @Test
    fun unboundedTimeoutIsAllowed() {
        UsbTransferArguments.validate(bufferSize = 24, offset = 0, length = 24, timeoutMillis = 0)
    }

    @Test
    fun negativeOffsetIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            UsbTransferArguments.validate(bufferSize = 24, offset = -1, length = 4, timeoutMillis = 1_000)
        }
    }

    @Test
    fun negativeLengthIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            UsbTransferArguments.validate(bufferSize = 24, offset = 0, length = -1, timeoutMillis = 1_000)
        }
    }

    @Test
    fun offsetPastTheBufferIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            UsbTransferArguments.validate(bufferSize = 24, offset = 25, length = 0, timeoutMillis = 1_000)
        }
    }

    @Test
    fun windowLongerThanTheBufferIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            UsbTransferArguments.validate(bufferSize = 24, offset = 8, length = 17, timeoutMillis = 1_000)
        }
    }

    /**
     * Проверка верхней границы не должна переполняться: сумма смещения и длины
     * в этом случае даёт отрицательное число и пропустила бы заведомо неверное
     * окно.
     */
    @Test
    fun windowThatOverflowsIntArithmeticIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            UsbTransferArguments.validate(
                bufferSize = 24,
                offset = Int.MAX_VALUE,
                length = Int.MAX_VALUE,
                timeoutMillis = 1_000,
            )
        }
    }

    @Test
    fun negativeTimeoutIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            UsbTransferArguments.validate(bufferSize = 24, offset = 0, length = 24, timeoutMillis = -1)
        }
    }
}
