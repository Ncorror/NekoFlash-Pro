package io.github.ncorror.nekoflash.artifact

import io.github.ncorror.nekoflash.core.artifact.ArtifactAccess
import io.github.ncorror.nekoflash.core.artifact.ArtifactIdentity
import io.github.ncorror.nekoflash.core.artifact.ArtifactStamp

/**
 * Что провайдер рассказал о документе.
 *
 * Отдельный тип от выводов намеренно: «что сказали» проверяется на устройстве и
 * меняется от провайдера к провайдеру, а «что из этого следует» — обычная
 * логика, и её можно проверить тестами, не заводя ни одного `content://`.
 */
internal data class SafDocumentFacts(
    /** `OpenableColumns.DISPLAY_NAME`, если провайдер его отдал. */
    val displayName: String?,
    /** `OpenableColumns.SIZE`, если провайдер его отдал. */
    val columnSize: Long?,
    /**
     * `ParcelFileDescriptor.getStatSize()`.
     *
     * `null` — дескриптор не открылся вовсе; отрицательное — открылся, но длины
     * у него нет, то есть это труба, а не файл.
     */
    val statSize: Long?,
    val lastModifiedMillis: Long? = null,
)

/**
 * Выводы о документе SAF.
 *
 * `content://` не считается обычным seekable-файлом автоматически (`06` §6), и
 * здесь решается, чем его считать. Ошибка в сторону «умеет» опаснее: она
 * выяснилась бы на первом повторном запросе блока, то есть уже за границей
 * мутации.
 */
internal object SafArtifactFacts {
    /** Имя, под которым артефакт показывается и записывается в историю. */
    fun name(facts: SafDocumentFacts, fallback: String): String =
        facts.displayName?.takeIf { it.isNotBlank() } ?: fallback

    /**
     * Размер. `null` — провайдер его не сообщает.
     *
     * Колонка важнее дескриптора: провайдер знает про документ больше, чем файл
     * под ним, а у трубы длины нет вовсе.
     */
    fun size(facts: SafDocumentFacts): Long? =
        facts.columnSize?.takeIf { it >= 0L } ?: facts.statSize?.takeIf { it >= 0L }

    /**
     * Умеет ли источник читать с произвольного места.
     *
     * Признак один и наблюдаемый: у файла длина дескриптора есть, у трубы нет.
     * Дескриптор, который не открылся, не говорит ни того, ни другого — и это
     * `UNKNOWN`, а не догадка в удобную сторону.
     */
    fun access(facts: SafDocumentFacts): ArtifactAccess = when {
        facts.statSize == null -> ArtifactAccess.UNKNOWN
        facts.statSize >= 0L -> ArtifactAccess.SEEKABLE
        else -> ArtifactAccess.NON_SEEKABLE
    }

    fun identity(facts: SafDocumentFacts, fallbackName: String): ArtifactIdentity = ArtifactIdentity(
        name = name(facts, fallbackName),
        sizeBytes = size(facts),
        access = access(facts),
    )

    /**
     * Отпечаток для проверки, что источник не подменили.
     *
     * Версии SAF не даёт, поэтому её тут нет и взяться ей неоткуда; время
     * изменения даёт не всякий провайдер. Когда нет ни того, ни другого,
     * `ArtifactStamps.compare` честно отвечает «сравнить нечем» — и это лучше,
     * чем бодрое «не изменился» на основании одного совпавшего размера.
     */
    fun stamp(facts: SafDocumentFacts): ArtifactStamp = ArtifactStamp(
        sizeBytes = size(facts) ?: 0L,
        modifiedAtMillis = facts.lastModifiedMillis,
    )
}
