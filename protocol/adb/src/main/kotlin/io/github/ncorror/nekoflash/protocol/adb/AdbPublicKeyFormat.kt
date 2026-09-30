package io.github.ncorror.nekoflash.protocol.adb

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey

/**
 * Публичный ключ хоста в том виде, в каком его ждёт `adbd`.
 *
 * ADB не принимает обычную строку X.509. На проводе и в `~/.android/adbkey.pub`
 * лежит base64 от исторической структуры `android_pubkey` из mincrypt, которую
 * используют platform-tools. Формат перенесён из Legacy `AdbKeyStore.kt` и A2
 * `adb/transport/AdbKeyStore.kt` — там он записан одинаково.
 *
 * Структура целиком little-endian и состоит из пяти полей: число 32-битных слов
 * модуля, «магическая» константа Монтгомери `n0inv`, сам модуль словами, `rr` —
 * остаток `2^(2 * 2048)` по модулю ключа, и открытая экспонента. Два
 * вычисляемых поля нужны устройству для проверки подписи без деления: пересчёт
 * их на стороне устройства не предусмотрен, поэтому ошибка здесь выглядит не
 * как отказ формата, а как молча неверная подпись.
 */
public object AdbPublicKeyFormat {
    /** Единственный поддерживаемый размер ключа ADB. */
    public const val RSA_BITS: Int = 2048

    /** Столько 32-битных слов занимает модуль. */
    public const val RSA_WORDS: Int = RSA_BITS / 32

    /** Размер структуры `android_pubkey` в байтах. */
    public const val STRUCTURE_SIZE_BYTES: Int = 4 + 4 + RSA_WORDS * 4 + RSA_WORDS * 4 + 4

    /**
     * Подпись, которой хост представляется в диалоге авторизации на устройстве.
     *
     * Значение из архивов: именно его пользователь видит рядом с отпечатком
     * ключа, и менять его — значит превратить уже авторизованный хост в
     * незнакомый.
     */
    public const val KEY_COMMENT: String = "NekoFlash@Android"

    private const val BITS_PER_WORD = 32
    private val WORD_MASK = BigInteger.valueOf(0xFFFF_FFFFL)

    /** Кодирует ключ в структуру `android_pubkey`. */
    public fun encode(publicKey: RSAPublicKey): ByteArray {
        val modulus = publicKey.modulus
        require(modulus.bitLength() <= RSA_BITS) {
            "ADB accepts RSA-$RSA_BITS only, got ${modulus.bitLength()} bits"
        }

        val two32 = BigInteger.ONE.shiftLeft(BITS_PER_WORD)
        val r = BigInteger.ONE.shiftLeft(RSA_WORDS * BITS_PER_WORD)
        val rr = r.modPow(BigInteger.TWO, modulus)
        val n0 = modulus.mod(two32)
        val n0inv = two32.subtract(n0.modInverse(two32)).mod(two32)

        return ByteBuffer.allocate(STRUCTURE_SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                putUInt32(RSA_WORDS.toLong())
                putUInt32(n0inv.toLong())
                putWords(modulus)
                putWords(rr)
                putUInt32(publicKey.publicExponent.toLong())
            }
            .array()
    }

    /** Строка для файла `adbkey.pub`: base64 структуры и подпись через пробел. */
    public fun keyLine(publicKey: RSAPublicKey): String =
        "${java.util.Base64.getEncoder().encodeToString(encode(publicKey))} $KEY_COMMENT"

    /**
     * Payload для пакета `AUTH RSAPUBLICKEY`.
     *
     * Завершающий нулевой байт обязателен: `adbd` читает ключ как C-строку, и
     * без него на устройстве не появится диалог авторизации.
     */
    public fun authPayload(publicKey: RSAPublicKey): ByteArray =
        "${keyLine(publicKey)}\u0000".toByteArray(Charsets.US_ASCII)

    /**
     * Отпечаток ключа для сличения прогонов между собой.
     *
     * SHA-256 от той же структуры, что уходит устройству. Это **не** та строка,
     * которую показывает диалог авторизации на экране устройства: её формат
     * задаёт `adbd`, и выдавать одно за другое нельзя. Отпечаток нужен для
     * другого — увидеть в журнале, тот же это ключ, что в прошлый раз, или
     * новый.
     */
    public fun fingerprint(publicKey: RSAPublicKey): String =
        MessageDigest.getInstance("SHA-256")
            .digest(encode(publicKey))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun ByteBuffer.putWords(value: BigInteger) {
        repeat(RSA_WORDS) { index ->
            putUInt32(value.shiftRight(index * BITS_PER_WORD).and(WORD_MASK).toLong())
        }
    }

    private fun ByteBuffer.putUInt32(value: Long) {
        putInt((value and 0xFFFF_FFFFL).toInt())
    }
}
