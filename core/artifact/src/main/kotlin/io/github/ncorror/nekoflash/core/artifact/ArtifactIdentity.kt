package io.github.ncorror.nekoflash.core.artifact

/**
 * Умеет ли источник читать с произвольного места.
 *
 * Три значения, а не два: `content://` у SAF не обязан быть обычным файлом, и
 * провайдер вправе не сказать о себе ничего. Считать такой источник
 * последовательным было бы догадкой в безопасную сторону, а seekable —
 * догадкой в опасную; отдельное `UNKNOWN` не даёт сделать ни того, ни другого
 * молча (`06_OPERATIONS_STORAGE_LIFECYCLE_RU.md` §6).
 */
enum class ArtifactAccess {
    SEEKABLE,
    NON_SEEKABLE,
    UNKNOWN,
}

/**
 * Чем артефакт назван и что о нём известно на момент открытия.
 *
 * Размер — `Long`, и это не придирка: образ раздела не помещается в `Int`, а
 * 32-битная арифметика над байтами запрещена прямо (`06` §9).
 */
data class ArtifactIdentity(
    val name: String,
    /**
     * Размер. `null` — провайдер его не сообщает.
     *
     * Такое бывает: `content://` поверх трубы длины не знает. Передача, которой
     * размер нужен **заранее**, — Sideload объявляет его в самом имени сервиса —
     * обязана тогда сперва стажировать источник, а не догадываться.
     */
    val sizeBytes: Long?,
    val access: ArtifactAccess,
    /** Версия у провайдера, если он её сообщает. */
    val version: String? = null,
) {
    init {
        require(name.isNotBlank()) { "имя артефакта не может быть пустым" }
        require(sizeBytes == null || sizeBytes >= 0L) {
            "размер артефакта не может быть отрицательным: $sizeBytes"
        }
    }
}

/**
 * Отпечаток источника на один момент времени.
 *
 * Снимается дважды: при выборе и **перед первым отданным байтом**. Между этими
 * двумя моментами проходит сколько угодно времени, и файл могли подменить —
 * `06` §6 требует stability checks именно для мутирующей передачи.
 */
data class ArtifactStamp(
    val sizeBytes: Long,
    /** Время изменения, если провайдер его сообщает. */
    val modifiedAtMillis: Long? = null,
    val version: String? = null,
) {
    init {
        require(sizeBytes >= 0L) { "размер не может быть отрицательным: $sizeBytes" }
    }
}

/** Тот же ли источник, что был выбран. */
sealed interface ArtifactStability {
    /** Сошлось всё, что провайдер вообще сообщает. */
    data object Unchanged : ArtifactStability

    /** Источник изменился, и названо, что именно. */
    data class Changed(val detail: String) : ArtifactStability

    /**
     * Сравнить нечем.
     *
     * **Это не то же, что «не изменился».** Провайдер не сообщает ни времени
     * изменения, ни версии, а совпадение размера их не заменяет: файл той же
     * длины бывает другим. Вызывающий обязан решить сам, отдавать ли такой
     * источник в мутирующую передачу, — но решать он будет, зная, что
     * доказательства нет.
     */
    data class Unverifiable(val detail: String) : ArtifactStability
}

/** Сравнение двух отпечатков одного источника. */
object ArtifactStamps {
    /**
     * Что стало с источником между [before] и [after].
     *
     * Порядок проверок существенный. Размер сравнивается первым, потому что
     * это единственное, что провайдер сообщает всегда. Дальше — время и версия;
     * расхождение любого из них означает `Changed`, а **отсутствие обоих** —
     * `Unverifiable`, а не `Unchanged`.
     */
    fun compare(before: ArtifactStamp, after: ArtifactStamp): ArtifactStability = when {
        before.sizeBytes != after.sizeBytes -> ArtifactStability.Changed(
            "размер изменился: было ${before.sizeBytes}, стало ${after.sizeBytes}",
        )

        before.version != after.version -> ArtifactStability.Changed(
            "версия изменилась: была ${before.version ?: "не сообщалась"}, " +
                "стала ${after.version ?: "не сообщается"}",
        )

        before.modifiedAtMillis != after.modifiedAtMillis -> ArtifactStability.Changed(
            "время изменения другое: было ${before.modifiedAtMillis ?: "не сообщалось"}, " +
                "стало ${after.modifiedAtMillis ?: "не сообщается"}",
        )

        before.modifiedAtMillis == null && before.version == null -> ArtifactStability.Unverifiable(
            "провайдер не сообщает ни времени изменения, ни версии: совпадение размера " +
                "${before.sizeBytes} доказательством неизменности не является",
        )

        else -> ArtifactStability.Unchanged
    }
}
