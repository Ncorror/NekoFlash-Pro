package io.github.ncorror.nekoflash.payload

import io.github.ncorror.nekoflash.core.artifact.ArtifactDigest
import java.io.OutputStream

/**
 * Приёмник, который считает байты и отпечаток, но не хранит содержимое.
 *
 * Зеркало [GeneratedPayload]: тот порождает содержимое, не собирая его целиком,
 * этот принимает содержимое, не собирая его целиком. Причина та же и здесь
 * серьёзнее: раздел бывает на несколько гигабайт, и положить его в кучу значит
 * убить приложение на первом же большом чтении.
 *
 * Приём тот же, что уже принят для ADB `pull` (`07` §6.32), где прочитанный
 * файл подтверждается количеством байт и SHA-256, а не сохранением: сохранение
 * в пользовательское место требует artifact sink из Phase 8.
 *
 * Отпечаток здесь доказывает **прочитанное**, а не содержимое раздела на
 * устройстве: совпадение двух чтений подряд говорит, что путь чтения
 * устойчив, и ничего не говорит о том, что именно лежит в разделе.
 */
internal class DigestingSink : OutputStream() {
    private val digest = ArtifactDigest()

    /** Сколько байт принято. */
    val bytes: Long
        get() = digest.bytes

    override fun write(value: Int) {
        digest.update(byteArrayOf(value.toByte()))
    }

    override fun write(source: ByteArray, offset: Int, length: Int) {
        digest.update(source, offset, length)
    }

    /** Отпечаток принятого. Спросить можно не закрывая счёт. */
    fun sha256(): String = digest.hex()
}
