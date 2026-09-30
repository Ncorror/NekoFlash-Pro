package io.github.ncorror.nekoflash.vendor.xiaomi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MiUnlockDataTest {
    @Test
    fun decodesSignedPayloadBytes() {
        assertArrayEquals(byteArrayOf(0, 0x7f, 0xff.toByte()), MiUnlockData.decode("007fFF"))
    }

    @Test
    fun rejectsMalformedPayloadBeforePassingItToFastboot() {
        listOf("", "0", "00g1", "0 01", "0a0").forEach { assertNull(MiUnlockData.decode(it)) }
    }
}
