package com.ncorror.nekoflash.pro.adb

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import javax.crypto.Cipher
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdbKeyEncodingTest {
    @Test
    fun androidPublicKeyHasExpectedModulusMontgomeryParametersAndExponent() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val key = pair.public as RSAPublicKey
        val text = String(AdbKeyEncoding.publicKeyText(key), Charsets.US_ASCII)
        assertTrue(text.endsWith(" nekoflash@android"))
        val binary = Base64.getDecoder().decode(text.substringBefore(' '))
        assertEquals(524, binary.size)
        val words = ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(64, words.int)
        val n0inv = words.int.toLong() and 0xffffffffL
        val two32 = BigInteger.ONE.shiftLeft(32)
        assertEquals(two32.subtract(key.modulus.mod(two32).modInverse(two32)).mod(two32).toLong(), n0inv)
        val modulus = ByteArray(256).also { words.get(it) }
        val rr = ByteArray(256).also { words.get(it) }
        assertEquals(key.modulus, BigInteger(1, modulus.reversedArray()))
        assertEquals(BigInteger.ONE.shiftLeft(4096).mod(key.modulus),
            BigInteger(1, rr.reversedArray()))
        assertEquals(65537, words.int)
    }

    @Test
    fun digestInfoSignedWithoutHashingAgainMatchesAdbdRsaVerificationFormat() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val token = ByteArray(20) { it.toByte() }
        val digestInfo = AdbKeyEncoding.sha1DigestInfo(token)
        val signature = Signature.getInstance("NONEwithRSA").run {
            initSign(pair.private)
            update(digestInfo)
            sign()
        }
        val recovered = Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
            init(Cipher.DECRYPT_MODE, pair.public)
            doFinal(signature)
        }
        assertContentEquals(digestInfo, recovered)
        assertEquals(256, signature.size)
    }
}
