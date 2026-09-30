package io.github.ncorror.nekoflash.protocol.adb

import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbPublicKeyFormatTest {
    @Test
    fun structureHasTheSizeAdbdExpects() {
        assertEquals(524, AdbPublicKeyFormat.STRUCTURE_SIZE_BYTES)
        assertEquals(524, AdbPublicKeyFormat.encode(publicKey()).size)
    }

    @Test
    fun firstWordIsTheModulusWordCount() {
        assertEquals(64L, fields(publicKey()).wordCount)
    }

    @Test
    fun lastWordIsThePublicExponent() {
        val key = publicKey()

        assertEquals(key.publicExponent.toLong(), fields(key).exponent)
    }

    @Test
    fun modulusIsStoredAsLittleEndianWords() {
        val key = publicKey()

        assertEquals(key.modulus, fields(key).modulus)
    }

    /**
     * `n0inv` — это `-n^-1 mod 2^32`. Проверяется свойство, а не запомненное
     * число: устройство использует его для умножения Монтгомери, и неверное
     * значение проявилось бы как молча неверная подпись.
     */
    @Test
    fun n0invIsTheNegatedModularInverseOfTheLowestWord() {
        val key = publicKey()
        val decoded = fields(key)
        val two32 = BigInteger.ONE.shiftLeft(32)

        val lowestWord = key.modulus.mod(two32)
        val product = BigInteger.valueOf(decoded.n0inv).multiply(lowestWord).mod(two32)

        assertEquals(two32.subtract(BigInteger.ONE), product)
    }

    /** `rr` — это `2^(2 * 2048) mod n`, тоже вычисляемое поле. */
    @Test
    fun rrIsTheMontgomeryResidue() {
        val key = publicKey()
        val expected = BigInteger.ONE.shiftLeft(2 * AdbPublicKeyFormat.RSA_BITS).mod(key.modulus)

        assertEquals(expected, fields(key).rr)
    }

    @Test
    fun keyLineIsBase64OfTheStructureFollowedByTheHostComment() {
        val key = publicKey()

        val parts = AdbPublicKeyFormat.keyLine(key).split(" ")

        assertEquals(2, parts.size)
        assertEquals(AdbPublicKeyFormat.KEY_COMMENT, parts[1])
        assertArrayEquals(AdbPublicKeyFormat.encode(key), Base64.getDecoder().decode(parts[0]))
    }

    /** Без завершающего нуля `adbd` не покажет диалог авторизации. */
    @Test
    fun authPayloadIsNulTerminated() {
        val payload = AdbPublicKeyFormat.authPayload(publicKey())

        assertEquals(0, payload.last().toInt())
        assertEquals(AdbPublicKeyFormat.keyLine(publicKey()).length + 1, payload.size)
    }

    @Test
    fun keysLargerThanRsa2048AreRejected() {
        val oversized = KeyFactory.getInstance("RSA").generatePublic(
            RSAPublicKeySpec(BigInteger.ONE.shiftLeft(4096).nextProbablePrime(), BigInteger.valueOf(65_537L)),
        ) as RSAPublicKey

        try {
            AdbPublicKeyFormat.encode(oversized)
        } catch (expected: IllegalArgumentException) {
            return
        }
        throw AssertionError("expected IllegalArgumentException for an oversized key")
    }

    private data class Fields(
        val wordCount: Long,
        val n0inv: Long,
        val modulus: BigInteger,
        val rr: BigInteger,
        val exponent: Long,
    )

    private companion object {
        /** Один ключ на весь класс: генерация RSA-2048 дорогая. */
        val SHARED_KEY: RSAPublicKey = KeyPairGenerator.getInstance("RSA").run {
            initialize(RSAKeyGenParameterSpec(AdbPublicKeyFormat.RSA_BITS, RSAKeyGenParameterSpec.F4))
            generateKeyPair().public as RSAPublicKey
        }

        fun publicKey(): RSAPublicKey = SHARED_KEY

        fun fields(key: RSAPublicKey): Fields {
            val buffer = ByteBuffer.wrap(AdbPublicKeyFormat.encode(key)).order(ByteOrder.LITTLE_ENDIAN)
            val wordCount = buffer.readUInt32()
            val n0inv = buffer.readUInt32()
            val modulus = buffer.readWords()
            val rr = buffer.readWords()
            return Fields(wordCount, n0inv, modulus, rr, buffer.readUInt32())
        }

        fun ByteBuffer.readUInt32(): Long = int.toLong() and 0xFFFF_FFFFL

        fun ByteBuffer.readWords(): BigInteger {
            var value = BigInteger.ZERO
            repeat(AdbPublicKeyFormat.RSA_WORDS) { index ->
                value = value.or(BigInteger.valueOf(readUInt32()).shiftLeft(index * 32))
            }
            return value
        }
    }
}

class AdbTokenSignerTest {
    @Test
    fun signatureIsTheKeySize() {
        assertEquals(256, AdbTokenSigner.sign(token(), KEY_PAIR.private).size)
    }

    /**
     * Токен подписывается как готовый дайджест SHA-1, а не как сообщение:
     * подпись обязана проверяться без повторного хеширования.
     */
    @Test
    fun signatureVerifiesAgainstTheDigestInfoWithoutRehashing() {
        val token = token()
        val signature = AdbTokenSigner.sign(token, KEY_PAIR.private)

        val verified = Signature.getInstance("NONEwithRSA").run {
            initVerify(KEY_PAIR.public)
            update(AdbTokenSigner.digestInfo(token))
            verify(signature)
        }

        assertTrue(verified)
    }

    /** Именно эта ошибка ломала авторизацию: лишнее хеширование токена. */
    @Test
    fun signingTheTokenAsAMessageWouldProduceADifferentSignature() {
        val token = token()

        val wrong = Signature.getInstance("SHA1withRSA").run {
            initSign(KEY_PAIR.private)
            update(token)
            sign()
        }

        assertNotEquals(
            AdbTokenSigner.sign(token, KEY_PAIR.private).toList(),
            wrong.toList(),
        )
    }

    @Test
    fun digestInfoIsThePrefixFollowedByTheToken() {
        val token = token()
        val digestInfo = AdbTokenSigner.digestInfo(token)

        assertEquals(35, digestInfo.size)
        assertArrayEquals(token, digestInfo.copyOfRange(15, 35))
    }

    @Test
    fun tokenOfTheWrongSizeIsRejected() {
        var rejected = false
        try {
            AdbTokenSigner.sign(ByteArray(19), KEY_PAIR.private)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    private companion object {
        val KEY_PAIR = KeyPairGenerator.getInstance("RSA").run {
            initialize(RSAKeyGenParameterSpec(AdbPublicKeyFormat.RSA_BITS, RSAKeyGenParameterSpec.F4))
            generateKeyPair()
        }

        fun token() = ByteArray(AdbTokenSigner.TOKEN_SIZE_BYTES) { it.toByte() }
    }
}

class AdbKeyStoreTest {
    @Test
    fun keyMaterialSurvivesANewInstance() = withTempDirectory { directory ->
        val first = AdbKeyStore(directory).authPayload()
        val second = AdbKeyStore(directory).authPayload()

        assertArrayEquals(first, second)
    }

    @Test
    fun bothKeyFilesUsePlatformToolsNames() = withTempDirectory { directory ->
        AdbKeyStore(directory).keyPair()

        assertTrue(File(directory, AdbKeyStore.PRIVATE_KEY_FILE_NAME).isFile)
        assertTrue(File(directory, AdbKeyStore.PUBLIC_KEY_FILE_NAME).isFile)
    }

    @Test
    fun missingDirectoryIsCreated() {
        val parent = Files.createTempDirectory("nekoflash-adbkey").toFile()
        try {
            val nested = File(parent, "keys/adb")

            AdbKeyStore(nested).keyPair()

            assertTrue(nested.isDirectory)
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun privateKeyFileIsNotReadableByOthers() = withTempDirectory { directory ->
        AdbKeyStore(directory).keyPair()

        val privateKeyFile = File(directory, AdbKeyStore.PRIVATE_KEY_FILE_NAME)
        assertTrue("владелец обязан читать свой ключ", privateKeyFile.canRead())
        assertFalse(
            "приватный ключ не должен быть доступен остальным",
            privateKeyFile.toPath().toFile().let { file ->
                java.nio.file.Files.getPosixFilePermissions(file.toPath()).any { permission ->
                    permission.name.startsWith("OTHERS") || permission.name.startsWith("GROUP")
                }
            },
        )
    }

    @Test
    fun publicKeyFileHoldsTheSameLineAsTheAuthPayload() = withTempDirectory { directory ->
        val payload = AdbKeyStore(directory).authPayload()

        val fileLine = File(directory, AdbKeyStore.PUBLIC_KEY_FILE_NAME).readText().trim()

        assertEquals("$fileLine\u0000", payload.toString(Charsets.US_ASCII))
    }

    /** Первый запуск создаёт ключ, второй его читает: для устройства это тот же хост. */
    @Test
    fun originTellsWhetherTheKeyWasCreatedOrRead() = withTempDirectory { directory ->
        val first = AdbKeyStore(directory)
        assertEquals(AdbKeyOrigin.NOT_TOUCHED, first.origin)
        first.keyPair()
        assertEquals(AdbKeyOrigin.GENERATED, first.origin)

        val second = AdbKeyStore(directory)
        second.keyPair()

        assertEquals(AdbKeyOrigin.LOADED, second.origin)
    }

    @Test
    fun fingerprintSurvivesAcrossInstancesOverTheSameFolder() = withTempDirectory { directory ->
        assertEquals(AdbKeyStore(directory).fingerprint(), AdbKeyStore(directory).fingerprint())
    }

    /** Потерянный каталог означает новый ключ, и отпечаток это показывает. */
    @Test
    fun aFreshFolderProducesADifferentFingerprint() = withTempDirectory { first ->
        withTempDirectory { second ->
            assertNotEquals(AdbKeyStore(first).fingerprint(), AdbKeyStore(second).fingerprint())
        }
    }

    @Test
    fun fingerprintIsASha256Digest() = withTempDirectory { directory ->
        val fingerprint = AdbKeyStore(directory).fingerprint()

        assertEquals(64, fingerprint.length)
        assertTrue(fingerprint.all { it in "0123456789abcdef" })
    }

    @Test
    fun signedTokenVerifiesAgainstTheStoredPublicKey() = withTempDirectory { directory ->
        val keyStore = AdbKeyStore(directory)
        val token = ByteArray(AdbTokenSigner.TOKEN_SIZE_BYTES) { (it * 7).toByte() }

        val signature = keyStore.signToken(token)

        val verified = Signature.getInstance("NONEwithRSA").run {
            initVerify(keyStore.publicKey())
            update(AdbTokenSigner.digestInfo(token))
            verify(signature)
        }
        assertTrue(verified)
    }

    private fun withTempDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("nekoflash-adbkey").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
