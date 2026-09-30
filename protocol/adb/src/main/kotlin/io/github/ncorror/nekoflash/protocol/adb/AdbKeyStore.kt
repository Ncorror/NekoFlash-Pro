package io.github.ncorror.nekoflash.protocol.adb

import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/**
 * Постоянный ключ хоста для авторизации ADB.
 *
 * Ключ обязан переживать перезапуск приложения: устройство запоминает
 * авторизованный хост по отпечатку публичного ключа, и новый ключ на каждом
 * запуске означал бы диалог «разрешить отладку» каждый раз, а на устройстве в
 * Recovery — тупик, потому что подтвердить его там некому.
 *
 * Формат файлов взят из архивов и совпадает с platform-tools: приватный ключ в
 * PKCS#8 и base64, публичный — строка mincrypt. Имена `adbkey.pk8` и
 * `adbkey.pub` тоже сохранены: по ним ключ, созданный старой версией
 * приложения, продолжает работать.
 */
/** Откуда взялся ключ хоста в этом запуске. */
public enum class AdbKeyOrigin {
    /** Ключ ещё не понадобился. */
    NOT_TOUCHED,

    /** Прочитан с диска: устройство, знавшее его раньше, узнает его снова. */
    LOADED,

    /**
     * Создан заново, потому что файла не было.
     *
     * Для устройства это **новый хост**: оно спросит подтверждения, даже если
     * прошлый ключ уже был авторизован. Чаще всего означает, что каталог
     * приложения был очищен — например, переустановкой.
     */
    GENERATED,
}

/** Что известно о ключе хоста после того, как он понадобился. */
public data class AdbKeyProvenance(
    val origin: AdbKeyOrigin,
    val fingerprint: String,
)

public class AdbKeyStore(private val directory: File) {
    private val privateKeyFile = File(directory, PRIVATE_KEY_FILE_NAME)
    private val publicKeyFile = File(directory, PUBLIC_KEY_FILE_NAME)

    @Volatile
    private var cached: KeyPair? = null

    /**
     * Откуда взялся ключ.
     *
     * Нужно журналу: без этого невозможно отличить «устройство забыло хост» от
     * «хост потерял ключ», а вопрос этот возникает каждый раз, когда диалог
     * авторизации появляется там, где его не ждали.
     */
    @Volatile
    public var origin: AdbKeyOrigin = AdbKeyOrigin.NOT_TOUCHED
        private set

    /**
     * Возвращает ключ хоста, создавая его при первом обращении.
     *
     * Синхронизировано: первое обращение может прийти одновременно из потока
     * рукопожатия и из UI, а генерация RSA-2048 достаточно долгая, чтобы это
     * успело совпасть.
     *
     * @throws IllegalStateException если каталог ключей не удалось создать —
     * без него работать невозможно, и молча продолжать нельзя.
     */
    @Synchronized
    public fun keyPair(): KeyPair {
        cached?.let { return it }
        check(directory.exists() || directory.mkdirs()) {
            "Could not create the ADB key folder: ${directory.absolutePath}"
        }

        val keyPair = if (privateKeyFile.exists()) {
            origin = AdbKeyOrigin.LOADED
            loadKeyPair()
        } else {
            origin = AdbKeyOrigin.GENERATED
            generateAndSaveKeyPair()
        }
        writePublicKeyFileIfNeeded(keyPair)
        cached = keyPair
        return keyPair
    }

    /** Публичный ключ хоста. */
    public fun publicKey(): RSAPublicKey = keyPair().public as RSAPublicKey

    /** Payload для `AUTH RSAPUBLICKEY`. */
    public fun authPayload(): ByteArray = AdbPublicKeyFormat.authPayload(publicKey())

    /** Подписывает токен из `AUTH TOKEN`. */
    public fun signToken(token: ByteArray): ByteArray = AdbTokenSigner.sign(token, keyPair().private)

    /** Путь к файлу публичного ключа: показывается пользователю, не содержит секрета. */
    public fun publicKeyPath(): String = publicKeyFile.absolutePath

    /** Отпечаток публичного ключа для сличения прогонов. */
    public fun fingerprint(): String = AdbPublicKeyFormat.fingerprint(publicKey())

    /**
     * Происхождение ключа и его отпечаток вместе.
     *
     * Одним вызовом, потому что порознь их легко перепутать местами: [origin]
     * известно только после того, как ключ действительно понадобился, и
     * прочитанное раньше времени значение сказало бы `NOT_TOUCHED` про ключ,
     * который вот-вот будет создан. Ровно на этом споткнулась первая версия.
     */
    public fun provenance(): AdbKeyProvenance {
        val fingerprint = fingerprint()
        return AdbKeyProvenance(origin = origin, fingerprint = fingerprint)
    }

    private fun loadKeyPair(): KeyPair {
        val encoded = Base64.getDecoder().decode(privateKeyFile.readText().trim())
        val keyFactory = KeyFactory.getInstance("RSA")
        val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(encoded)) as RSAPrivateCrtKey
        val publicKey = keyFactory.generatePublic(
            RSAPublicKeySpec(privateKey.modulus, privateKey.publicExponent),
        ) as RSAPublicKey
        return KeyPair(publicKey, privateKey)
    }

    private fun generateAndSaveKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(
            RSAKeyGenParameterSpec(AdbPublicKeyFormat.RSA_BITS, RSAKeyGenParameterSpec.F4),
        )
        return generator.generateKeyPair().also { savePrivateKey(it.private) }
    }

    /**
     * Права снимаются со всех и возвращаются только владельцу.
     *
     * Порядок именно такой: `setReadable(false, false)` действует на всех, а
     * следующий вызов с `true, true` возвращает доступ только владельцу файла.
     * Обратный порядок оставил бы файл читаемым для всех.
     */
    private fun savePrivateKey(privateKey: PrivateKey) {
        privateKeyFile.writeText(Base64.getEncoder().encodeToString(privateKey.encoded))
        privateKeyFile.setReadable(false, false)
        privateKeyFile.setWritable(false, false)
        privateKeyFile.setReadable(true, true)
        privateKeyFile.setWritable(true, true)
    }

    private fun writePublicKeyFileIfNeeded(keyPair: KeyPair) {
        val keyLine = AdbPublicKeyFormat.keyLine(keyPair.public as RSAPublicKey)
        if (!publicKeyFile.exists() || publicKeyFile.readText().trim() != keyLine) {
            publicKeyFile.writeText(keyLine)
        }
    }

    public companion object {
        /** Имя из platform-tools: старый ключ приложения продолжает работать. */
        public const val PRIVATE_KEY_FILE_NAME: String = "adbkey.pk8"

        /** Имя из platform-tools. */
        public const val PUBLIC_KEY_FILE_NAME: String = "adbkey.pub"
    }
}
