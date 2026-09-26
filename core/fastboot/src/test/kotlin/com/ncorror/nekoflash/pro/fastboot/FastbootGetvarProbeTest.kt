package com.ncorror.nekoflash.pro.fastboot

import com.ncorror.nekoflash.pro.transport.FastbootPacketTransport
import java.io.EOFException
import java.util.ArrayDeque
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FastbootGetvarProbeTest {
    private class FakeTransport(vararg response: String) : FastbootPacketTransport {
        val incoming = ArrayDeque(response.map { it.toByteArray(Charsets.US_ASCII) })
        val sent = mutableListOf<String>()
        var closed = false

        override fun writePacket(packet: ByteArray) {
            check(!closed)
            sent += packet.toString(Charsets.US_ASCII)
        }

        override fun readPacket(maxBytes: Int): ByteArray {
            check(!closed)
            if (incoming.isEmpty()) throw EOFException("device disconnected")
            return incoming.removeFirst().also { check(it.size <= maxBytes) }
        }

        override fun close() { closed = true }
    }

    @Test
    fun readsVersionOnlyAndClosesTransport() {
        val fake = FakeTransport("INFOloading", "OKAY0.4")
        val info = mutableListOf<FastbootWireMessage>()
        assertEquals(FastbootWireResult.Okay("0.4"),
            FastbootGetvarProbe(fake).read(FastbootVariable.VERSION) { info += it })
        assertEquals(listOf("getvar:version"), fake.sent)
        assertEquals(listOf<FastbootWireMessage>(FastbootWireMessage.Info("loading")), info)
        assertTrue(fake.closed)
    }

    @Test
    fun productIsTheOnlyAdditionalRequestAndFailIsADeviceReply() {
        val fake = FakeTransport("FAILGetVar Variable Not found")
        assertEquals(FastbootWireResult.Fail("GetVar Variable Not found"),
            FastbootGetvarProbe(fake).read(FastbootVariable.PRODUCT))
        assertEquals(listOf("getvar:product"), fake.sent)
        assertTrue(fake.closed)
    }

    @Test
    fun refusesDataPhaseAndClosesBeforeAnyTransfer() {
        val fake = FakeTransport("DATA00001000")
        assertEquals(FastbootWireResult.DataPhaseRequired(4096L),
            FastbootGetvarProbe(fake).read(FastbootVariable.PRODUCT))
        assertEquals(listOf("getvar:product"), fake.sent)
        assertTrue(fake.closed)
    }

    @Test
    fun disconnectAlsoReleasesOwnership() {
        val fake = FakeTransport()
        assertFailsWith<EOFException> { FastbootGetvarProbe(fake).read(FastbootVariable.VERSION) }
        assertEquals(listOf("getvar:version"), fake.sent)
        assertTrue(fake.closed)
    }

    @Test
    fun readsDownloadLimitWithoutStartingDataPhaseAndSkipsEmptyUsbPackets() {
        val fake = FakeTransport("", "OKAY0x08000000")
        assertEquals(FastbootWireResult.Okay("0x08000000"),
            FastbootGetvarProbe(fake).read(FastbootVariable.DOWNLOAD_SIZE))
        assertEquals(listOf("getvar:max-download-size"), fake.sent)
        assertTrue(fake.closed)
    }

    @Test
    fun readsRecoveryPartitionSizeWithoutStartingDownload() {
        val fake = FakeTransport("OKAY0x08000000")
        assertEquals(FastbootWireResult.Okay("0x08000000"),
            FastbootGetvarProbe(fake).read(FastbootVariable.RECOVERY_SIZE))
        assertEquals(listOf("getvar:partition-size:recovery"), fake.sent)
        assertTrue(fake.closed)
    }
}
