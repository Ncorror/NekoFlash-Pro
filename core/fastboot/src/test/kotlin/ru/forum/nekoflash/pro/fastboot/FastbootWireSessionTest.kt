package ru.forum.nekoflash.pro.fastboot

import java.io.EOFException
import java.util.ArrayDeque
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import ru.forum.nekoflash.pro.transport.FastbootPacketTransport

class FastbootWireSessionTest {
    private class Transcript(vararg responses: String) : FastbootPacketTransport {
        val reads = ArrayDeque(responses.map { it.toByteArray(Charsets.UTF_8) })
        val writes = mutableListOf<String>()
        var closed = false

        override fun writePacket(packet: ByteArray) {
            check(!closed)
            writes += packet.toString(Charsets.UTF_8)
        }

        override fun readPacket(maxBytes: Int): ByteArray {
            check(!closed)
            if (reads.isEmpty()) throw EOFException("device disconnected")
            return reads.removeFirst().also { check(it.size <= maxBytes) }
        }

        override fun close() { closed = true }
    }

    @Test
    fun infoAndTextStreamBeforeSuccess() {
        val transport = Transcript("INFOwriting", "TEXTraw\u0000", "OKAYdone")
        val seen = mutableListOf<FastbootWireMessage>()
        val session = FastbootWireSession(transport)
        assertEquals(FastbootWireResult.Okay("done"), session.execute("getvar:all") { seen += it })
        assertEquals(listOf("getvar:all"), transport.writes)
        assertEquals(listOf(FastbootWireMessage.Info("writing"), FastbootWireMessage.Text("raw")), seen)
        assertTrue(!transport.closed)
        session.close()
        assertTrue(transport.closed)
    }

    @Test
    fun failIsADeviceFailureRatherThanSuccess() {
        val session = FastbootWireSession(Transcript("FAILnot allowed"))
        assertEquals(FastbootWireResult.Fail("not allowed"), session.execute("erase:userdata"))
    }

    @Test
    fun dataRequestClosesIncompleteSession() {
        val transport = Transcript("DATAFFFFFFFF")
        val session = FastbootWireSession(transport)
        assertEquals(FastbootWireResult.DataPhaseRequired(4_294_967_295L), session.execute("upload"))
        assertTrue(transport.closed)
        assertFailsWith<IllegalStateException> { session.execute("getvar:version") }
    }

    @Test
    fun malformedPacketAndDisconnectCloseTransport() {
        val malformed = Transcript("NOPEbad")
        assertFailsWith<FastbootProtocolException> { FastbootWireSession(malformed).execute("getvar:version") }
        assertTrue(malformed.closed)

        val disconnected = Transcript()
        assertFailsWith<EOFException> { FastbootWireSession(disconnected).execute("getvar:version") }
        assertTrue(disconnected.closed)
    }

    @Test
    fun packetSizeIsTheProtocolLimitRatherThanLegacySixtyFourBytes() {
        val transport = Transcript("OKAY")
        val session = FastbootWireSession(transport)
        val vendorRequest = "X".repeat(128)
        assertEquals(FastbootWireResult.Okay(""), session.execute(vendorRequest))
        assertEquals(listOf(vendorRequest), transport.writes)
        assertFailsWith<IllegalArgumentException> { session.execute("X".repeat(4097)) }
    }

    @Test
    fun malformedDataLengthNeverLeavesTheConnectionInUse() {
        val transport = Transcript("DATAxyz")
        assertFailsWith<FastbootProtocolException> { FastbootWireSession(transport).execute("download:00000010") }
        assertTrue(transport.closed)
    }
}
