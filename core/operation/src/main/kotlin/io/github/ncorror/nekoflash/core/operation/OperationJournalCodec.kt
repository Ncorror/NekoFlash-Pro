package io.github.ncorror.nekoflash.core.operation

import io.github.ncorror.nekoflash.core.model.SessionGeneration
import io.github.ncorror.nekoflash.core.model.TargetId
import java.time.Instant
import java.util.Properties

/**
 * Перевод записи операции в текст и обратно.
 *
 * Формат — обычный `key=value`, читаемый глазами. Это не стиль: записи попадают
 * в evidence, и отчёт, который нельзя прочитать без программы, доказывает
 * меньше, чем мог бы. `Properties` берётся из JDK ради экранирования, которое
 * иначе пришлось бы писать руками и однажды написать неправильно; запись идёт
 * через `Writer`, а не через `OutputStream`, потому что второй превратил бы
 * русский текст в `\uXXXX`.
 *
 * [decode] возвращает `null` на всём, чего не понимает, и это **рабочий исход, а
 * не ошибка**. Файл, недописанный из-за смерти процесса, — ровно тот случай,
 * ради которого хранилище и заведено: такую запись надо пропустить и сказать,
 * сколько пропущено, а не уронить чтение истории целиком.
 */
object OperationJournalCodec {
    fun encode(record: OperationRecord): Properties = Properties().apply {
        setProperty(ID, record.id.value)
        setProperty(CREATED_AT, record.createdAt.toString())
        setProperty(KIND, record.intent.kind.name)
        setProperty(SUMMARY, record.intent.summary)
        setProperty(TARGET, record.targetId.value)
        setProperty(GENERATION, record.startedSessionGeneration.value.toString())
        setProperty(STATE, record.state.name)
        encodeBoundary(record.mutationBoundary)
        record.progress?.let { encodeProgress(it) }
        record.outcome?.let { setProperty(OUTCOME, it.name) }
        record.finishedAt?.let { setProperty(FINISHED_AT, it.toString()) }
        encodeArtifacts(record.artifacts)
        encodeList(PEER, record.peerResponses)
        encodeList(EVIDENCE, record.evidenceRefs)
    }

    /** `null` — прочитать не удалось; вызывающий пропускает такую запись и считает её. */
    fun decode(properties: Properties): OperationRecord? = runCatching {
        OperationRecord(
            id = OperationId(properties.required(ID)),
            createdAt = Instant.parse(properties.required(CREATED_AT)),
            intent = OperationIntent(
                kind = OperationKind.valueOf(properties.required(KIND)),
                summary = properties.required(SUMMARY),
            ),
            targetId = TargetId(properties.required(TARGET)),
            startedSessionGeneration = SessionGeneration(properties.required(GENERATION).toLong()),
            state = OperationState(properties.required(STATE)),
            mutationBoundary = properties.decodeBoundary(),
            progress = properties.decodeProgress(),
            artifacts = properties.decodeArtifacts(),
            outcome = properties.getProperty(OUTCOME)?.let { OperationOutcome.valueOf(it) },
            peerResponses = properties.decodeList(PEER),
            evidenceRefs = properties.decodeList(EVIDENCE),
            finishedAt = properties.getProperty(FINISHED_AT)?.let { Instant.parse(it) },
        )
    }.getOrNull()

    private fun Properties.encodeBoundary(boundary: MutationBoundary) {
        when (boundary) {
            is MutationBoundary.NotCrossed -> setProperty(BOUNDARY, "NOT_CROSSED")
            is MutationBoundary.Crossed -> {
                setProperty(BOUNDARY, "CROSSED")
                setProperty(BOUNDARY_AT, boundary.at.toString())
                setProperty(BOUNDARY_DETAIL, boundary.detail)
            }
        }
    }

    /**
     * Непрочитанная граница читается как **пересечённая**.
     *
     * Единственное место, где отказ здесь несимметричен, и это намеренно: не
     * узнать про пересечённую границу значит объявить нетронутым устройство,
     * которое могло измениться. Ошибиться в эту сторону дороже, чем в обратную.
     */
    private fun Properties.decodeBoundary(): MutationBoundary = when (getProperty(BOUNDARY)) {
        "NOT_CROSSED" -> MutationBoundary.NotCrossed
        null -> MutationBoundary.NotCrossed
        else -> MutationBoundary.Crossed(
            at = getProperty(BOUNDARY_AT)?.let { runCatching { Instant.parse(it) }.getOrNull() }
                ?: Instant.EPOCH,
            detail = getProperty(BOUNDARY_DETAIL) ?: "подробность границы не сохранилась",
        )
    }

    private fun Properties.encodeProgress(progress: OperationProgress) {
        setProperty(SERVED, progress.servedBytes.toString())
        setProperty(UNIQUE, progress.uniqueBytes.toString())
        progress.totalBytes?.let { setProperty(TOTAL, it.toString()) }
        setProperty(ELAPSED, progress.elapsedMillis.toString())
    }

    private fun Properties.decodeProgress(): OperationProgress? = getProperty(SERVED)?.let { served ->
        OperationProgress(
            servedBytes = served.toLong(),
            uniqueBytes = (getProperty(UNIQUE) ?: served).toLong(),
            totalBytes = getProperty(TOTAL)?.toLong(),
            elapsedMillis = getProperty(ELAPSED)?.toLong() ?: 0L,
        )
    }

    private fun Properties.encodeArtifacts(artifacts: List<OperationArtifact>) {
        setProperty("$ARTIFACT.count", artifacts.size.toString())
        artifacts.forEachIndexed { index, artifact ->
            setProperty("$ARTIFACT.$index.name", artifact.name)
            setProperty("$ARTIFACT.$index.size", artifact.sizeBytes.toString())
            artifact.sha256?.let { setProperty("$ARTIFACT.$index.sha", it) }
        }
    }

    private fun Properties.decodeArtifacts(): List<OperationArtifact> =
        (0 until (getProperty("$ARTIFACT.count")?.toIntOrNull() ?: 0)).mapNotNull { index ->
            getProperty("$ARTIFACT.$index.name")?.let { name ->
                OperationArtifact(
                    name = name,
                    sizeBytes = getProperty("$ARTIFACT.$index.size")?.toLongOrNull() ?: 0L,
                    sha256 = getProperty("$ARTIFACT.$index.sha"),
                )
            }
        }

    private fun Properties.encodeList(prefix: String, values: List<String>) {
        setProperty("$prefix.count", values.size.toString())
        values.forEachIndexed { index, value -> setProperty("$prefix.$index", value) }
    }

    private fun Properties.decodeList(prefix: String): List<String> =
        (0 until (getProperty("$prefix.count")?.toIntOrNull() ?: 0)).mapNotNull { index ->
            getProperty("$prefix.$index")
        }

    private fun Properties.required(key: String): String =
        requireNotNull(getProperty(key)) { "в записи операции нет обязательного поля $key" }

    private const val ID = "id"
    private const val CREATED_AT = "createdAt"
    private const val KIND = "kind"
    private const val SUMMARY = "summary"
    private const val TARGET = "target"
    private const val GENERATION = "generation"
    private const val STATE = "state"
    private const val BOUNDARY = "boundary"
    private const val BOUNDARY_AT = "boundary.at"
    private const val BOUNDARY_DETAIL = "boundary.detail"
    private const val SERVED = "progress.served"
    private const val UNIQUE = "progress.unique"
    private const val TOTAL = "progress.total"
    private const val ELAPSED = "progress.elapsed"
    private const val OUTCOME = "outcome"
    private const val FINISHED_AT = "finishedAt"
    private const val ARTIFACT = "artifact"
    private const val PEER = "peer"
    private const val EVIDENCE = "evidence"
}
