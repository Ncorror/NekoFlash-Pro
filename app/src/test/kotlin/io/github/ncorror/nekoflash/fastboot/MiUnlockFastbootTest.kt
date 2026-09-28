package io.github.ncorror.nekoflash.fastboot

import io.github.ncorror.nekoflash.protocol.fastboot.FastbootLane
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootXiaomiUnlock
import io.github.ncorror.nekoflash.protocol.fastboot.XiaomiUnlockOutcome
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiUnlockFastbootTest {
    @Test
    fun oemUnlockIsSentOnlyAfterAllBytesWereAcknowledged() {
        val device = ScriptedBootloader(emptyMap())
        val payload = byteArrayOf(1, 2, 3, 4)
        val result = FastbootXiaomiUnlock(FastbootLane(device)).unlock(payload)

        assertEquals(XiaomiUnlockOutcome.Accepted, result)
        assertEquals(listOf("download:00000004", "oem unlock"), device.sent)
        assertArrayEquals(payload, device.downloads.single())
    }

    @Test
    fun deviceRefusalDoesNotClaimVerifiedUnlock() {
        val device = ScriptedBootloader(emptyMap(), refused = setOf("oem unlock"))
        val result = FastbootXiaomiUnlock(FastbootLane(device)).unlock(byteArrayOf(1))

        assertTrue(result is XiaomiUnlockOutcome.Refused)
        assertEquals(1, device.sent.count { it == "oem unlock" })
    }
}
