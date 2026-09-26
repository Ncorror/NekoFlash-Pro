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

class AdbAuthorizationSessionTest {
    private class FakeTransport(vararg incoming: ByteArray) : AdbByteTransport {
        val chunks = ArrayDeque(incoming.toList())
        val sent = mutableListOf<ByteArray>()
        var waitingReads = 0
        var closed = false

        override fun write(bytes: ByteArray) { check(!closed); sent += bytes.copyOf() }
        override fun readExact(length: Int): ByteArray {
            check(!closed)
            if (length == 0) return byteArrayOf()
            if (chunks.isEmpty()) throw EOFException("device disconnected")
            return chunks.removeFirst().also { assertEquals(length, it.size) }
        }
        override fun readExactAwaitingApproval(length: Int): ByteArray =
            readExact(length).also { waitingReads++ }
        override fun close() { closed = true }
    }

    private class FakeKey : AdbHostKey {
        var signedChallenge: ByteArray? = null
        override fun signChallenge(token: ByteArray): ByteArray {
            signedChallenge = token.copyOf()
            return ByteArray(256) { 0x55.toByte() }
        }
        override fun publicKeyText() = "public-key nekoflash@android".toByteArray()
    }

    private fun header(command: Int, arg0: Int, arg1: Int, body: ByteArray): ByteArray =
        ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command).putInt(arg0).putInt(arg1).putInt(body.size)
            .putInt(body.sumOf { it.toInt() and 0xff }).putInt(command.inv()).array()

    private fun tokenPacket(token: ByteArray) =
        arrayOf(header(AdbWire.AUTH, 1, 0, token), token)

    @Test
    fun unknownKeySignsFirstTokenThenOffersPublicKeyAndWaitsForApproval() {
        val first = ByteArray(20) { it.toByte() }
        val second = ByteArray(20) { (it + 30).toByte() }
        val banner = "device::serial=private".toByteArray()
        val fake = FakeTransport(*tokenPacket(first), *tokenPacket(second),
            header(AdbWire.CNXN, AdbWire.VERSION, 262144, banner), banner)
        val key = FakeKey()
        var waiting = 0
        val answer = AdbAuthorizationSession(fake, key).connect { waiting++ }

        assertEquals(AdbHandshakeResult.Connected(AdbWire.VERSION.toLong(), 262144,
            AdbAuthenticationPath.PUBLIC_KEY), answer)
        assertEquals(1, waiting)
        assertEquals(1, fake.waitingReads)
        assertContentEquals(first, key.signedChallenge)
        assertEquals(6, fake.sent.size) // CNXN, AUTH SIGNATURE and AUTH RSAPUBLICKEY
        assertContentEquals(header(AdbWire.AUTH, 2, 0, fake.sent[3]), fake.sent[2])
        assertContentEquals(header(AdbWire.AUTH, 3, 0, fake.sent[5]), fake.sent[4])
        assertTrue(fake.sent[5].last() == 0.toByte())
        assertFalse(fake.sent.any { it.contentEquals(first) || it.contentEquals(second) })
        assertFalse(answer.toString().contains("private"))
        assertTrue(fake.closed)
    }

    @Test
    fun knownKeyCanConnectAfterSignatureWithoutSendingPublicKey() {
        val token = ByteArray(20) { it.toByte() }
        val fake = FakeTransport(*tokenPacket(token),
            header(AdbWire.CNXN, AdbWire.VERSION, 4096, byteArrayOf()))
        val answer = AdbAuthorizationSession(fake, FakeKey()).connect { error("must not wait") }
        assertEquals(AdbHandshakeResult.Connected(AdbWire.VERSION.toLong(), 4096,
            AdbAuthenticationPath.SIGNATURE), answer)
        assertEquals(4, fake.sent.size)
        assertEquals(0, fake.waitingReads)
        assertTrue(fake.closed)
    }

    @Test
    fun peerCanConnectWithoutRequestingHostAuthentication() {
        val fake = FakeTransport(header(AdbWire.CNXN, AdbWire.VERSION, 4096, byteArrayOf()))
        val answer = AdbAuthorizationSession(fake, FakeKey()).connect { error("must not wait") }
        assertEquals(AdbHandshakeResult.Connected(AdbWire.VERSION.toLong(), 4096,
            AdbAuthenticationPath.NO_CHALLENGE), answer)
        assertEquals(2, fake.sent.size)
        assertTrue(fake.closed)
    }

    @Test
    fun rejectsFurtherTokenAndClosesSession() {
        val first = ByteArray(20) { it.toByte() }
        val second = ByteArray(20) { (it + 30).toByte() }
        val third = ByteArray(20) { (it + 60).toByte() }
        val fake = FakeTransport(*tokenPacket(first), *tokenPacket(second), *tokenPacket(third))
        val failure = assertFailsWith<AdbProtocolException> {
            AdbAuthorizationSession(fake, FakeKey()).connect {}
        }
        assertEquals("ADB authorization not accepted: cmd=0x48545541 arg0=1 arg1=0 size=20",
            failure.message)
        assertEquals(1, fake.waitingReads)
        assertEquals(6, fake.sent.size)
        assertTrue(fake.closed)
    }

    @Test
    fun invalidChallengeNeverReachesSigner() {
        val token = ByteArray(19)
        val fake = FakeTransport(*tokenPacket(token))
        val key = FakeKey()
        assertFailsWith<AdbProtocolException> {
            AdbAuthorizationSession(fake, key).connect {}
        }
        assertEquals(null, key.signedChallenge)
        assertEquals(2, fake.sent.size)
        assertTrue(fake.closed)
    }

    @Test
    fun unexpectedPacketReportsHeaderOnly() {
        val privatePayload = "private-banner".toByteArray()
        val fake = FakeTransport(header(AdbWire.WRTE, 2, 1, privatePayload), privatePayload)
        val failure = assertFailsWith<AdbProtocolException> {
            AdbAuthorizationSession(fake, FakeKey()).connect {}
        }
        assertEquals("Unexpected ADB reply during authorization: " +
            "cmd=0x45545257 arg0=2 arg1=1 size=${privatePayload.size}", failure.message)
        assertTrue(fake.closed)
    }
}
