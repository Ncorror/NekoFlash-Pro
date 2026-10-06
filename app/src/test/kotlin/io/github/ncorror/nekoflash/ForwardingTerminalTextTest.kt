package io.github.ncorror.nekoflash

import io.github.ncorror.nekoflash.adb.AdbForwardEntry
import io.github.ncorror.nekoflash.adb.AdbForwardState
import io.github.ncorror.nekoflash.adb.AdbReverseEntry
import io.github.ncorror.nekoflash.adb.AdbReverseState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForwardingTerminalTextTest {
    @Test
    fun forwardStateIsRenderedWithoutClaimingMoreThanTheControllerKnows() {
        val text = adbForwardTerminalText(
            AdbForwardState(
                forwards = listOf(
                    AdbForwardEntry(
                        localPort = 43210,
                        address = "localabstract:agent",
                        live = 0,
                        accepted = 0,
                        served = 0,
                        lastEnd = null,
                    ),
                ),
            ),
        )

        assertEquals("tcp:43210\tlocalabstract:agent", text)
        assertEquals("forward: no active listeners", adbForwardTerminalText(AdbForwardState.None))
    }

    @Test
    fun forwardFailureRemainsAnError() {
        assertEquals(
            "error: forward: port 5555 is busy",
            adbForwardTerminalText(AdbForwardState(failure = "port 5555 is busy")),
        )
    }

    @Test
    fun reverseActionAndDeviceListingStaySeparate() {
        val state = AdbReverseState(
            reverses = listOf(
                AdbReverseEntry(
                    onDevice = "tcp:7777",
                    onHost = "tcp:8888",
                    assigned = "7777",
                    brought = 0,
                    lastEnd = null,
                ),
            ),
            listing = "UsbFfs tcp:7777 tcp:8888\n",
        )

        assertTrue(adbReverseTerminalText(state).orEmpty().contains("tcp:7777\ttcp:8888\t7777"))
        assertEquals("UsbFfs tcp:7777 tcp:8888\n", adbReverseListTerminalText(state))
    }
}
