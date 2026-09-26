package com.ncorror.nekoflash.pro.adb

import com.ncorror.nekoflash.pro.transport.AdbByteTransport
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AdbProductProbeTest {
    private class FakeTransport(vararg incoming: ByteArray) : AdbByteTransport {
        private val chunks = ArrayDeque(incoming.toList())
        val sent = mutableListOf<ByteArray>()
        var closed = false
        override fun write(bytes: ByteArray) { check(!closed); sent += bytes.copyOf() }
        override fun readExact(length: Int): ByteArray {
            check(!closed)
            if (length == 0) return byteArrayOf()
            if (chunks.isEmpty()) throw EOFException("device disconnected")
            return chunks.removeFirst().also { assertEquals(length, it.size) }
        }
        override fun close() { closed = true }
    }

    private class FakeKey : AdbHostKey {
        var signatures = 0
        var publicKeys = 0
        override fun signChallenge(token: ByteArray) = ByteArray(256).also { signatures++ }
        override fun publicKeyText() = "public-key test@android".toByteArray().also { publicKeys++ }
    }

    private fun header(command: Int, arg0: Int, arg1: Int, body: ByteArray = byteArrayOf()) =
        ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command).putInt(arg0).putInt(arg1).putInt(body.size)
            .putInt(body.sumOf { it.toInt() and 0xff }).putInt(command.inv()).array()

    private fun packet(command: Int, arg0: Int, arg1: Int, value: String = ""): List<ByteArray> {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return if (bytes.isEmpty()) listOf(header(command, arg0, arg1))
            else listOf(header(command, arg0, arg1, bytes), bytes)
    }

    private fun connected() = packet(AdbWire.CNXN, AdbWire.VERSION, 4096)
    private fun opened() = packet(AdbWire.OKAY, 2, 1)
    private fun closed() = packet(AdbWire.CLSE, 2, 1)

    @Test
    fun storedKeyReadsFragmentedProductAndAcknowledgesEveryWrite() {
        val challenge = ByteArray(20) { it.toByte() }
        val fake = FakeTransport(*(
            listOf(header(AdbWire.AUTH, AdbWire.AUTH_TOKEN, 0, challenge), challenge) +
                connected() + opened() +
                packet(AdbWire.WRTE, 2, 1, "va") +
                packet(AdbWire.WRTE, 2, 1, "yu\r\n") + closed()
            ).toTypedArray())
        val key = FakeKey()
        val result = AdbProductProbe(fake, key).read { error("must not wait") }

        assertEquals("vayu", (result as AdbProductResult.Value).productCode)
        assertEquals(AdbAuthenticationPath.SIGNATURE, result.connection.authentication)
        assertEquals(1, key.signatures)
        assertEquals(0, key.publicKeys)
        assertEquals(8, fake.sent.size)
        val service = "shell:getprop ro.product.device\u0000".toByteArray(Charsets.US_ASCII)
        assertContentEquals(header(AdbWire.OPEN, 1, 0, service), fake.sent[4])
        assertContentEquals(service, fake.sent[5])
        assertContentEquals(header(AdbWire.OKAY, 1, 2), fake.sent[6])
        assertContentEquals(header(AdbWire.OKAY, 1, 2), fake.sent[7])
        assertTrue(fake.closed)
    }

    @Test
    fun closesAfterRejectedServiceWithoutRecordingAnyOutput() {
        val fake = FakeTransport(*(connected() + packet(AdbWire.CLSE, 0, 1)).toTypedArray())
        val failure = assertFailsWith<AdbProtocolException> { AdbProductProbe(fake, FakeKey()).read {} }
        assertEquals("ADB property service rejected", failure.message)
        assertEquals(4, fake.sent.size) // CNXN and one fixed OPEN; no response to CLSE
        assertTrue(fake.closed)
    }

    @Test
    fun refusesWrongStreamIdAndDoesNotAcknowledgeItsOutput() {
        val fake = FakeTransport(*(connected() + opened() +
            packet(AdbWire.WRTE, 3, 1, "private\n")).toTypedArray())
        val failure = assertFailsWith<AdbProtocolException> { AdbProductProbe(fake, FakeKey()).read {} }
        assertEquals("Invalid ADB property output", failure.message)
        assertTrue(fake.sent.size == 4 && fake.closed)
    }

    @Test
    fun rejectsMultilineOrLongOutputInsteadOfExposingIt() {
        for ((text, expected) in listOf(
            "vayu\nsecret\n" to "Invalid ADB product code",
            "x".repeat(129) to "Invalid ADB property stream packet",
            "vayu\u0000" to "Invalid ADB product code",
        )) {
            val fake = FakeTransport(*(connected() + opened() +
                packet(AdbWire.WRTE, 2, 1, text) + closed()).toTypedArray())
            val failure = assertFailsWith<AdbProtocolException> { AdbProductProbe(fake, FakeKey()).read {} }
            assertEquals(expected, failure.message)
            assertTrue(!failure.message.orEmpty().contains("secret"))
            assertTrue(fake.closed)
        }
    }

    @Test
    fun largerNegotiatedMaxDataIsAcceptedButPropertyOutputStaysBounded() {
        val huge = "x".repeat(5000)
        val fake = FakeTransport(*(packet(AdbWire.CNXN, AdbWire.VERSION, 8192) + opened() +
            packet(AdbWire.WRTE, 2, 1, huge)).toTypedArray())
        val failure = assertFailsWith<AdbProtocolException> {
            AdbProductProbe(fake, FakeKey()).read {}
        }
        assertEquals("Invalid ADB property stream packet", failure.message)
        assertTrue(fake.closed)
    }

    @Test
    fun tlsStopsBeforeOpeningService() {
        val fake = FakeTransport(*packet(AdbWire.STLS, 0, 0).toTypedArray())
        assertEquals(AdbProductResult.TlsRequired, AdbProductProbe(fake, FakeKey()).read {})
        assertEquals(2, fake.sent.size)
        assertTrue(fake.closed)
    }
}
