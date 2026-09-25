package com.ncorror.nekoflash.pro.adb

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/** AOSP's 524-byte little-endian Android public key, encoded for AUTH type 3. */
object AdbKeyEncoding {
    fun publicKeyText(key: RSAPublicKey): ByteArray {
        val n = key.modulus
        require(n.bitLength() == 2048 && key.publicExponent == BigInteger.valueOf(65537)) {
            "ADB requires a 2048-bit RSA key with exponent 65537"
        }
        val two32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = two32.subtract(n.mod(two32).modInverse(two32)).mod(two32)
        val rr = BigInteger.ONE.shiftLeft(2048).modPow(BigInteger.valueOf(2), n)
        val encoded = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(64).putInt(n0inv.toInt())
            .put(littleEndian(n, 256))
            .put(littleEndian(rr, 256))
            .putInt(65537).array()
        // Fixed, non-identifying comment; the whole string is terminated by the caller with NUL.
        return (Base64.getEncoder().encodeToString(encoded) + " nekoflash@android")
            .toByteArray(Charsets.US_ASCII)
    }

    /** RSA_sign(NID_sha1, token) signs this ASN.1 DigestInfo via PKCS#1 v1.5. */
    fun sha1DigestInfo(token: ByteArray): ByteArray {
        require(token.size == 20) { "ADB challenge must be 20 bytes" }
        return byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14) + token
    }

    private fun littleEndian(number: BigInteger, size: Int): ByteArray {
        val magnitude = number.toByteArray().dropWhile { it == 0.toByte() }
        require(magnitude.size <= size)
        return ByteArray(size).also { output ->
            magnitude.reversed().forEachIndexed { index, value -> output[index] = value }
        }
    }
}
