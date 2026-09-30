package io.github.ncorror.nekoflash.protocol.adb

import java.security.GeneralSecurityException
import java.security.PrivateKey
import java.security.Signature
import javax.crypto.Cipher

/**
 * Подпись токена `AUTH` ключом хоста.
 *
 * Ключевая тонкость перенесена из архивов дословно, потому что ошибиться в ней
 * легко, а последствие — отказ авторизации без внятной причины. AOSP вызывает
 * `RSA_sign(NID_sha1, token, 20, ...)`, то есть **считает 20-байтный токен уже
 * готовым дайджестом SHA-1**. Подписать его через `SHA1withRSA` нельзя: это
 * захешировало бы токен ещё раз. Поэтому вручную собирается ASN.1 DigestInfo с
 * фиксированным префиксом SHA-1 и подписывается без дополнительного хеширования.
 */
public object AdbTokenSigner {
    /** Размер токена, который присылает `adbd`: длина дайджеста SHA-1. */
    public const val TOKEN_SIZE_BYTES: Int = 20

    /**
     * ASN.1 DigestInfo для SHA-1 без самого дайджеста.
     *
     * `SEQUENCE { SEQUENCE { OID 1.3.14.3.2.26, NULL }, OCTET STRING (20) }`.
     */
    private val SHA1_DIGEST_INFO_PREFIX = byteArrayOf(
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b,
        0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
    )

    /**
     * Подписывает токен приватным ключом хоста.
     *
     * Основной путь — `NONEwithRSA`. Запасной через `Cipher` с
     * `PKCS1Padding` сохранён из архивов: провайдер без `NONEwithRSA`
     * встречается, и терять на этом авторизацию нельзя. Перехватывается только
     * [GeneralSecurityException] — отсутствие алгоритма и отказ провайдера
     * приходят именно так, а всё прочее было бы ошибкой вызывающего и должно
     * быть видно.
     *
     * @throws IllegalArgumentException если длина токена не [TOKEN_SIZE_BYTES]:
     * подписать что-то другое означало бы отдать устройству подпись под
     * неизвестно чем.
     */
    public fun sign(token: ByteArray, privateKey: PrivateKey): ByteArray {
        require(token.size == TOKEN_SIZE_BYTES) {
            "ADB AUTH token must be $TOKEN_SIZE_BYTES bytes, got ${token.size}"
        }
        val digestInfo = SHA1_DIGEST_INFO_PREFIX + token
        return try {
            Signature.getInstance("NONEwithRSA").run {
                initSign(privateKey)
                update(digestInfo)
                sign()
            }
        } catch (_: GeneralSecurityException) {
            Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
                init(Cipher.ENCRYPT_MODE, privateKey)
                doFinal(digestInfo)
            }
        }
    }

    /** То же DigestInfo, что уходит под подпись: нужно проверке в тестах. */
    public fun digestInfo(token: ByteArray): ByteArray {
        require(token.size == TOKEN_SIZE_BYTES) {
            "ADB AUTH token must be $TOKEN_SIZE_BYTES bytes, got ${token.size}"
        }
        return SHA1_DIGEST_INFO_PREFIX + token
    }
}
