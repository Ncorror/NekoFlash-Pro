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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdbHandshakeProbeTest {
    private class FakeTransport(vararg chunks: ByteArray) : AdbByteTransport {
        val incoming = ArrayDeque(chunks.toList())
        val sent = mutableListOf<ByteArray>()
        var reads = 0
        var closed = false

        override fun write(bytes: ByteArray) { check(!closed); sent += bytes.copyOf() }
        override fun readExact(length: Int): ByteArray {
            check(!closed)
            if (length == 0) return byteArrayOf()
            reads++
            if (incoming.isEmpty()) throw EOFException("device disconnected")
            return incoming.removeFirst().also { assertEquals(length, it.size) }
        }
        override fun close() { closed = true }
    }

    private fun reply(command: Int, arg0: Int, arg1: Int, body: ByteArray, badMagic: Boolean = false): ByteArray =
        ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command).putInt(arg0).putInt(arg1)
            .putInt(body.size).putInt(body.sumOf { it.toInt() and 0xff })
            .putInt(if (badMagic) 0 else command.inv()).array()

    @Test
    fun cnxnConfirmsWireReplyWithoutExposingBanner() {
        val body = "device::serial=private-id".toByteArray()
        val fake = FakeTransport(reply(0x4e584e43, 0x01000001, 262144, body), body)
        val result = AdbHandshakeProbe(fake).read()
        assertEquals(AdbHandshakeResult.Connected(0x01000001, 262144), result)
        assertFalse(result.toString().contains("private-id"))
        assertEquals(2, fake.sent.size)
        assertContentEquals("host::".toByteArray(), fake.sent[1])
        assertEquals(24, fake.sent[0].size)
        assertTrue(fake.closed)
    }

    @Test
    fun authTokenIsNotSentBackOrSaved() {
        val token = "abcdefghijklmnopqrst".toByteArray()
        val fake = FakeTransport(reply(0x48545541, 1, 0, token), token)
        assertEquals(AdbHandshakeResult.AuthorizationRequired, AdbHandshakeProbe(fake).read())
        assertEquals(2, fake.sent.size) // CNXN header and host banner; no AUTH reply
        assertTrue(fake.sent.none { it.contentEquals(token) })
        assertTrue(fake.closed)
    }

    @Test
    fun oversizedReplyIsRejectedBeforeReadingPayload() {
        val fake = FakeTransport(
            ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x4e584e43).putInt(0x01000001).putInt(4096)
                .putInt(4097).putInt(0).putInt(0x4e584e43.inv()).array(),
        )
        assertFailsWith<AdbProtocolException> { AdbHandshakeProbe(fake).read() }
        assertEquals(1, fake.reads)
        assertTrue(fake.closed)
    }

    @Test
    fun rejectsCorruptPayloadAndRecognizesTlsWithoutNegotiation() {
        val body = "device::".toByteArray()
        val header = reply(0x4e584e43, 0x01000001, 4096, body)
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putInt(16, 1)
        val corrupt = FakeTransport(header, body)
        assertFailsWith<AdbProtocolException> { AdbHandshakeProbe(corrupt).read() }
        assertTrue(corrupt.closed)

        val tls = FakeTransport(reply(0x534c5453, 0x01000000, 0, byteArrayOf()))
        assertEquals(AdbHandshakeResult.TlsRequired, AdbHandshakeProbe(tls).read())
        assertEquals(2, tls.sent.size)
        assertTrue(tls.closed)
    }

    @Test
    fun malformedHeaderAndDisconnectCloseTransport() {
        val invalid = FakeTransport(reply(0x48545541, 1, 0, ByteArray(20), badMagic = true))
        assertFailsWith<AdbProtocolException> { AdbHandshakeProbe(invalid).read() }
        assertTrue(invalid.closed)

        val disconnected = FakeTransport()
        assertFailsWith<EOFException> { AdbHandshakeProbe(disconnected).read() }
        assertTrue(disconnected.closed)
    }
}
