package com.ncorror.nekoflash.pro.fastboot

import com.ncorror.nekoflash.pro.transport.FastbootPacketTransport
import java.io.EOFException
import java.util.ArrayDeque
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FastbootVersionProbeTest {
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
        assertEquals(FastbootWireResult.Okay("0.4"), FastbootVersionProbe(fake).read { info += it })
        assertEquals(listOf("getvar:version"), fake.sent)
        assertEquals(listOf<FastbootWireMessage>(FastbootWireMessage.Info("loading")), info)
        assertTrue(fake.closed)
    }

    @Test
    fun refusesDataPhaseAndClosesBeforeAnyTransfer() {
        val fake = FakeTransport("DATA00001000")
        assertEquals(FastbootWireResult.DataPhaseRequired(4096L), FastbootVersionProbe(fake).read())
        assertEquals(listOf("getvar:version"), fake.sent)
        assertTrue(fake.closed)
    }

    @Test
    fun disconnectAlsoReleasesOwnership() {
        val fake = FakeTransport()
        assertFailsWith<EOFException> { FastbootVersionProbe(fake).read() }
        assertEquals(listOf("getvar:version"), fake.sent)
        assertTrue(fake.closed)
    }
}
