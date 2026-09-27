package io.github.ncorror.nekoflash.core.operation

import io.github.ncorror.nekoflash.core.model.SessionGeneration
import io.github.ncorror.nekoflash.core.model.TargetId
import java.time.Instant

/**
 * Род операции.
 *
 * Перечисление намеренно: добавление рода — видимое изменение, а не строка,
 * появившаяся между делом. Список короткий, потому что сюда попадает не всякое
 * действие, а только то, что **длится** или **может пересечь границу мутации**.
 * Разовый вопрос устройству операцией не становится.
 */
enum class OperationKind {
    ADB_PULL,
    ADB_PUSH,
    ADB_SIDELOAD,
    FASTBOOT_DOWNLOAD,
    FASTBOOT_FETCH,
    FASTBOOT_MUTATION,
    FASTBOOT_PLAN,
}

/** Что оператор попросил сделать. */
data class OperationIntent(
    val kind: OperationKind,
    /** Одна строка своими словами: что именно попросили. */
    val summary: String,
) {
    init {
        require(summary.isNotBlank()) { "намерение операции должно быть названо" }
    }
}

/**
 * Состояние машины, которой операция принадлежит.
 *
 * Строка, а **не** общий `enum`, и это прямой вывод из разбора A2
 * (`09`, реестр): там один `Stage` применялся сразу к трём разным родам, и
 * `VERIFICATION_PENDING` у Sideload держал занятым слот, нужный несвязанному
 * действию Fastboot. У каждого рода своя машина состояний, и общего словаря
 * состояний у них нет.
 */
@JvmInline
value class OperationState(val name: String) {
    init {
        require(name.isNotBlank()) { "состояние операции должно быть названо" }
    }

    override fun toString(): String = name
}

/** Файл, который операция прочитала или записала. */
data class OperationArtifact(
    val name: String,
    val sizeBytes: Long,
    /** Отпечаток, если он был посчитан. */
    val sha256: String? = null,
) {
    init {
        require(name.isNotBlank()) { "артефакт должен быть назван" }
        require(sizeBytes >= 0L) { "размер артефакта не может быть отрицательным: $sizeBytes" }
    }
}

/**
 * Запись операции — модель `06_OPERATIONS_STORAGE_LIFECYCLE_RU.md` §2.
 *
 * Хранится ради истории, evidence и честного восстановления после смерти
 * процесса — и **не** ради «resume flash с середины». Это сказано в `06` §2
 * прямым текстом, и разница принципиальная: запись помнит, что операция была и
 * чем кончилась, а не даёт продолжить транзакцию, которой больше нет.
 *
 * [outcome] `null` означает, что операция не закончилась **на нашей стороне**.
 * Это не то же, что «идёт»: процесс мог умереть, и тогда она не идёт и не
 * закончилась — см. [OperationRecovery].
 */
data class OperationRecord(
    val id: OperationId,
    val createdAt: Instant,
    val intent: OperationIntent,
    val targetId: TargetId,
    val startedSessionGeneration: SessionGeneration,
    val state: OperationState,
    val mutationBoundary: MutationBoundary = MutationBoundary.NotCrossed,
    val progress: OperationProgress? = null,
    val artifacts: List<OperationArtifact> = emptyList(),
    val outcome: OperationOutcome? = null,
    /** Что ответило устройство, своими словами устройства. */
    val peerResponses: List<String> = emptyList(),
    /** Ссылки на evidence: разделы прогонов, файлы диагностики. */
    val evidenceRefs: List<String> = emptyList(),
    val finishedAt: Instant? = null,
) {
    init {
        require(outcome == null || finishedAt != null) {
            "у законченной операции должно быть время окончания"
        }
    }

    /** Закончилась ли операция на нашей стороне. */
    val finished: Boolean
        get() = outcome != null
}
