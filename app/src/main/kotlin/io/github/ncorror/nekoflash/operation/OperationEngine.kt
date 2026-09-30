package io.github.ncorror.nekoflash.operation

import io.github.ncorror.nekoflash.core.model.SessionGeneration
import io.github.ncorror.nekoflash.core.model.TargetId
import io.github.ncorror.nekoflash.core.operation.MutationBoundary
import io.github.ncorror.nekoflash.core.operation.OperationArtifact
import io.github.ncorror.nekoflash.core.operation.OperationId
import io.github.ncorror.nekoflash.core.operation.OperationIntent
import io.github.ncorror.nekoflash.core.operation.OperationJournal
import io.github.ncorror.nekoflash.core.operation.OperationOutcome
import io.github.ncorror.nekoflash.core.operation.OperationProgress
import io.github.ncorror.nekoflash.core.operation.OperationRecord
import io.github.ncorror.nekoflash.core.operation.OperationRecovery
import io.github.ncorror.nekoflash.core.operation.OperationRestoration
import io.github.ncorror.nekoflash.core.operation.OperationState
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Что показывать про операцию, которую застали после перезапуска. */
public data class RestoredOperation(
    val record: OperationRecord,
    val restoration: OperationRestoration,
)

/**
 * Владелец длительных операций.
 *
 * Операция не принадлежит экрану (`06` §1): она живёт здесь, переживает поворот
 * и сворачивание, и экран на неё только подписан.
 *
 * **В хранилище пишутся не все изменения, а только те, что важны после смерти
 * процесса**: начало, пересечение границы мутации и конец. Прогресс остаётся в
 * памяти намеренно — записывать каждый подтверждённый блок значило бы стучать
 * по диску тысячи раз за передачу ради числа, которое после падения ничего не
 * решает. Решает граница, и она пишется сразу.
 */
public class OperationEngine(
    private val journal: OperationJournal,
    private val clock: () -> Instant = { Instant.now() },
    private val elapsedMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val counter = AtomicLong()
    private val records = ConcurrentHashMap<OperationId, OperationRecord>()
    private val mutableLive = MutableStateFlow<List<OperationRecord>>(emptyList())
    private val mutableHistory = MutableStateFlow(journal.history().records)

    /** Операции, идущие прямо сейчас. */
    public val live: StateFlow<List<OperationRecord>> = mutableLive.asStateFlow()

    /** Всё, что записано, от новых к старым. */
    public val history: StateFlow<List<OperationRecord>> = mutableHistory.asStateFlow()

    /** Заводит операцию и отдаёт ручку, через которую её ведут. */
    public fun begin(
        intent: OperationIntent,
        targetId: TargetId,
        generation: SessionGeneration,
        totalBytes: Long? = null,
    ): OperationHandle {
        val record = OperationRecord(
            id = OperationId("op-${clock().toEpochMilli()}-${counter.incrementAndGet()}"),
            createdAt = clock(),
            intent = intent,
            targetId = targetId,
            startedSessionGeneration = generation,
            state = OperationState(STARTED),
            progress = OperationProgress.none(totalBytes),
        )
        store(record, persist = true)
        return OperationHandle(this, record.id, elapsedMillis())
    }

    /**
     * Что сказать про операции, застигнутые перезапуском.
     *
     * Незаконченные записи читаются через [OperationRecovery] и **закрываются**:
     * операция, которую никто не ведёт, не идёт. Оставить её в истории как
     * живую значило бы показывать вечный прогресс.
     */
    public fun restoreAll(currentGeneration: SessionGeneration?): List<RestoredOperation> {
        val stale = journal.history().records.filter { !it.finished }
        val restored = stale.map { record ->
            val restoration = OperationRecovery.restore(record, currentGeneration)
            val closed = record.copy(
                state = OperationState(INTERRUPTED),
                outcome = outcomeOf(restoration),
                finishedAt = clock(),
            )
            journal.save(closed)
            RestoredOperation(closed, restoration)
        }
        mutableHistory.value = journal.history().records
        return restored
    }

    /**
     * Меняет запись.
     *
     * Изменение, которое ничего не изменило, **не пишется**: вторая отметка
     * границы мутации — обычное дело (вызывающий не обязан помнить, ставил ли
     * он её), и стучать по диску ради неё незачем.
     */
    internal fun update(id: OperationId, persist: Boolean, change: (OperationRecord) -> OperationRecord) {
        val current = records[id] ?: return
        val changed = change(current)
        if (changed == current) return
        store(changed, persist)
    }

    private fun store(record: OperationRecord, persist: Boolean) {
        if (record.finished) records.remove(record.id) else records[record.id] = record
        mutableLive.value = records.values.sortedBy { it.createdAt }
        if (persist) {
            runCatching { journal.save(record) }
            mutableHistory.value = journal.history().records
        }
    }

    private fun outcomeOf(restoration: OperationRestoration): OperationOutcome = when (restoration) {
        is OperationRestoration.Finished -> restoration.outcome
        is OperationRestoration.Abandoned -> OperationOutcome.FAILED
        // После границы мутации исход именно неизвестен, а не провален:
        // устройство могло измениться, и назвать это неудачей значило бы
        // пообещать, что не изменилось.
        is OperationRestoration.NeedsVerification -> OperationOutcome.UNKNOWN
    }

    private companion object {
        const val STARTED = "STARTED"
        const val INTERRUPTED = "INTERRUPTED"
    }
}

/**
 * Ручка идущей операции.
 *
 * Её держит тот, кто операцию ведёт. Ручка **не** знает про протокол: она
 * принимает то, что уже случилось, и не решает, что делать дальше.
 */
public class OperationHandle internal constructor(
    private val engine: OperationEngine,
    public val id: OperationId,
    private val startedAtMillis: Long,
) {
    /** Сообщает состояние машины, которой операция принадлежит. */
    public fun state(name: String) {
        engine.update(id, persist = false) { record -> record.copy(state = OperationState(name)) }
    }

    /** Сообщает прогресс. В хранилище не пишется: после падения решает не он. */
    public fun progress(servedBytes: Long, uniqueBytes: Long, nowMillis: Long) {
        engine.update(id, persist = false) { record ->
            record.copy(
                progress = OperationProgress(
                    servedBytes = servedBytes,
                    uniqueBytes = uniqueBytes,
                    totalBytes = record.progress?.totalBytes,
                    elapsedMillis = (nowMillis - startedAtMillis).coerceAtLeast(0L),
                ),
            )
        }
    }

    /**
     * Отмечает необратимую границу — и **пишет её в хранилище сразу**.
     *
     * Это единственное изменение по ходу операции, ради которого стоит стучать
     * по диску: после падения именно граница решает, можно ли сказать, что
     * устройство не тронуто.
     */
    public fun crossedMutationBoundary(detail: String, at: Instant) {
        engine.update(id, persist = true) { record ->
            if (record.mutationBoundary is MutationBoundary.Crossed) {
                record
            } else {
                record.copy(mutationBoundary = MutationBoundary.Crossed(at, detail))
            }
        }
    }

    /** Запоминает, что ответило устройство — его словами. */
    public fun peerSaid(text: String) {
        engine.update(id, persist = false) { record ->
            record.copy(peerResponses = record.peerResponses + text)
        }
    }

    /** Запоминает файл, который операция прочитала или записала. */
    public fun artifact(artifact: OperationArtifact) {
        engine.update(id, persist = false) { record ->
            record.copy(artifacts = record.artifacts + artifact)
        }
    }

    /** Закрывает операцию. */
    public fun finish(outcome: OperationOutcome, state: String, at: Instant) {
        engine.update(id, persist = true) { record ->
            record.copy(state = OperationState(state), outcome = outcome, finishedAt = at)
        }
    }
}
