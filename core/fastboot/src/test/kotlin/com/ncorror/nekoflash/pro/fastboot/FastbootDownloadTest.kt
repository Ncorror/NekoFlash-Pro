package com.ncorror.nekoflash.pro.fastboot

import com.ncorror.nekoflash.pro.transport.FastbootPacketTransport
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.util.ArrayDeque
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FastbootDownloadTest {
    private class FakeTransport(vararg replies: String) : FastbootPacketTransport {
        private val incoming = ArrayDeque(replies.map { it.toByteArray(Charsets.US_ASCII) })
        val commands = mutableListOf<String>()
        val data = mutableListOf<ByteArray>()
        var closed = false
        override fun writePacket(packet: ByteArray) {
            check(!closed)
            commands += packet.toString(Charsets.US_ASCII)
        }
        override fun writeData(bytes: ByteArray) { check(!closed); data += bytes.copyOf() }
        override fun readPacket(maxBytes: Int): ByteArray = incoming.removeFirst()
        override fun close() { closed = true }
    }

    @Test
    fun sendsExactlyTheAdvertisedBytesInBoundedChunksBeforeFinalReply() {
        val image = ByteArray(70_000) { (it and 0xff).toByte() }
        val fake = FakeTransport("INFOstarting", "DATA00011170", "TEXTdone\u0000", "OKAY")
        val progress = mutableListOf<Long>()
        val info = mutableListOf<FastbootWireMessage>()
        val result = FastbootWireSession(fake).use { session ->
            session.download(ByteArrayInputStream(image), image.size.toLong(),
                onProgress = { progress += it }, onMessage = { info += it })
        }
        assertEquals(FastbootWireResult.Okay(""), result)
        assertEquals(listOf("download:00011170"), fake.commands)
        assertEquals(listOf(65_536, 4_464), fake.data.map(ByteArray::size))
        assertContentEquals(image, fake.data.fold(byteArrayOf()) { acc, chunk -> acc + chunk })
        assertEquals(listOf(65_536L, 70_000L), progress)
        assertEquals(listOf<FastbootWireMessage>(FastbootWireMessage.Info("starting"),
            FastbootWireMessage.Text("done")), info)
        assertTrue(fake.closed)
    }

    @Test
    fun deviceCanRefuseBeforeDataWithoutReceivingImage() {
        val fake = FakeTransport("FAILtoo large")
        assertEquals(FastbootWireResult.Fail("too large"),
            FastbootWireSession(fake).use { it.download(ByteArrayInputStream(byteArrayOf(7)), 1) })
        assertTrue(fake.data.isEmpty() && fake.closed)
    }

    @Test
    fun mismatchedDataCountClosesBeforeAnyImageBytes() {
        val fake = FakeTransport("DATA00000002")
        assertFailsWith<FastbootProtocolException> {
            FastbootWireSession(fake).download(ByteArrayInputStream(byteArrayOf(7)), 1)
        }
        assertTrue(fake.data.isEmpty() && fake.closed)
    }

    @Test
    fun truncatedOrOversizedImageCannotAdvanceToTheNextWireCommand() {
        val truncated = FakeTransport("DATA00000003")
        assertFailsWith<EOFException> {
            FastbootWireSession(truncated).download(ByteArrayInputStream(byteArrayOf(1, 2)), 3)
        }
        assertEquals(listOf(2), truncated.data.map(ByteArray::size))
        assertTrue(truncated.closed)

        val oversized = FakeTransport("DATA00000001", "OKAY")
        assertFailsWith<FastbootProtocolException> {
            FastbootWireSession(oversized).download(ByteArrayInputStream(byteArrayOf(1, 2)), 1)
        }
        assertTrue(oversized.closed)
        assertEquals(listOf("download:00000001"), oversized.commands)
    }

    @Test
    fun invalidSizeNeverOpensTheWire() {
        val fake = FakeTransport()
        assertFailsWith<IllegalArgumentException> {
            FastbootWireSession(fake).download(ByteArrayInputStream(byteArrayOf()), 0)
        }
        assertTrue(fake.commands.isEmpty())
        fake.close()
    }
}
