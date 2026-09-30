package io.github.ncorror.nekoflash.core.artifact

import java.security.MessageDigest

/**
 * SHA-256 на лету.
 *
 * Считается **потоком и без накопления**: образ раздела в память не помещается,
 * а `readBytes()` для него запрещён прямо (`06` §9). Отпечаток нужен для
 * identity артефакта, для preflight/evidence, для проверки стажированного
 * источника и для диагностики (`06` §8).
 *
 * Чего отпечаток **не** доказывает — что те же байты лежат на устройстве.
 * Утверждать remote verification на основании локального SHA нельзя: это
 * отдельное правило `06` §8, а не следствие из совпадения.
 */
class ArtifactDigest {
    private val digest = MessageDigest.getInstance(ALGORITHM)

    /** Сколько байт прошло через счётчик. */
    var bytes: Long = 0L
        private set

    /** Добавляет кусок. */
    fun update(chunk: ByteArray, offset: Int = 0, length: Int = chunk.size) {
        require(offset >= 0 && length >= 0 && offset + length <= chunk.size) {
            "кусок за пределами буфера: offset=$offset length=$length size=${chunk.size}"
        }
        digest.update(chunk, offset, length)
        bytes += length.toLong()
    }

    /** Отпечаток строчными шестнадцатеричными. Счётчик и состояние не сбрасываются. */
    fun hex(): String = digest.clone().let { it as MessageDigest }
        .digest()
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    companion object {
        const val ALGORITHM: String = "SHA-256"
    }
}
