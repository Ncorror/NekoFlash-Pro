package io.github.ncorror.nekoflash.protocol.fastboot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FastbootXiaomiUnlockTest {
    @Test
    fun tokenFallsBackToOemInfoFragmentsWithoutASecondTransport() {
        val device = FakeFastbootTransport()
            .willReply("FAILunknown variable", "INFOtoken:abcdefgh", "INFOijklmnop", "OKAY")
        val token = FastbootXiaomiUnlock(FastbootLane(device)).readToken()

        assertEquals("abcdefghijklmnop", token)
        assertEquals(listOf("getvar:token", "oem get_token"), device.sent)
    }

    @Test
    fun unreadableGetvarDoesNotSendFallbackToStalledLane() {
        val device = FakeFastbootTransport().willBeSilent(2)
        val token = FastbootXiaomiUnlock(FastbootLane(device)).readToken(inactivityMillis = 10L)

        assertNull(token)
        assertEquals(listOf("getvar:token"), device.sent)
    }

    @Test
    fun rejectedDownloadNeverSendsOemUnlock() {
        val device = FakeFastbootTransport().willReply("FAILtoo much data")
        val result = FastbootXiaomiUnlock(FastbootLane(device)).unlock(byteArrayOf(0x01, 0x02))

        assertTrue(result is XiaomiUnlockOutcome.Refused)
        assertEquals(listOf("download:00000002"), device.sent)
    }
}
