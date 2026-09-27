package io.github.ncorror.nekoflash.operation

import io.github.ncorror.nekoflash.core.model.SessionGeneration
import io.github.ncorror.nekoflash.core.model.TargetId
import io.github.ncorror.nekoflash.core.operation.InMemoryOperationJournal
import io.github.ncorror.nekoflash.core.operation.MutationBoundary
import io.github.ncorror.nekoflash.core.operation.OperationIntent
import io.github.ncorror.nekoflash.core.operation.OperationKind
import io.github.ncorror.nekoflash.core.operation.OperationOutcome
import io.github.ncorror.nekoflash.core.operation.OperationRestoration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Владелец длительных операций.
 *
 * Главное здесь — **что и когда попадает в хранилище**. Прогресс не попадает
 * намеренно: после падения он ничего не решает, а стучать по диску тысячи раз
 * за передачу ради него не стоит. Граница мутации попадает сразу, потому что
 * решает именно она.
 */
class OperationEngineTest {
    @Test
    fun aStartedOperationIsLiveAndAlreadyRecorded() {
        val journal = InMemoryOperationJournal()
        val engine = OperationEngine(journal, clock = { NOW })

        engine.begin(intent(), TARGET, GENERATION, totalBytes = 100L)

        assertEquals(1, engine.live.value.size)
        assertEquals(1, journal.history().records.size)
    }

    /** Прогресс виден на экране и **не** пишется на диск. */
    @Test
    fun progressIsShownButNotWrittenToDisk() {
        val journal = CountingJournal()
        val engine = OperationEngine(journal, clock = { NOW })
        val handle = engine.begin(intent(), TARGET, GENERATION, totalBytes = 100L)
        val afterStart = journal.saves

        repeat(50) { step -> handle.progress(step + 1L, step + 1L, nowMillis = 0L) }

        assertEquals(50L, engine.live.value.single().progress?.servedBytes)
        assertEquals("прогресс не стучит по диску", afterStart, journal.saves)
    }

    /**
     * Граница мутации пишется сразу и **только один раз**.
     *
     * Сразу — потому что после падения именно она решает, можно ли сказать, что
     * устройство не тронуто. Один раз — потому что первая отметка точнее: она
     * ближе к тому моменту, когда байты действительно пошли.
     */
    @Test
    fun theMutationBoundaryIsWrittenAtOnceAndOnlyOnce() {
        val journal = CountingJournal()
        val engine = OperationEngine(journal, clock = { NOW })
        val handle = engine.begin(intent(), TARGET, GENERATION)
        val afterStart = journal.saves

        handle.crossedMutationBoundary("первый блок ушёл", NOW)
        handle.crossedMutationBoundary("второй блок ушёл", NOW.plusSeconds(5))

        assertEquals(afterStart + 1, journal.saves)
        val boundary = journal.history().records.single().mutationBoundary as MutationBoundary.Crossed
        assertEquals("первый блок ушёл", boundary.detail)
    }

    /** Законченная операция уходит из живых в историю. */
    @Test
    fun afinishedOperationLeavesTheLiveList() {
        val engine = OperationEngine(InMemoryOperationJournal(), clock = { NOW })
        val handle = engine.begin(intent(), TARGET, GENERATION)

        handle.finish(OperationOutcome.SUCCEEDED, "DONE", NOW)

        assertTrue(engine.live.value.isEmpty())
        assertEquals(OperationOutcome.SUCCEEDED, engine.history.value.single().outcome)
    }

    /**
     * Незаконченная запись после перезапуска **закрывается**, а не остаётся живой.
     *
     * Операция, которую никто не ведёт, не идёт; оставить её как живую значило
     * бы показывать вечный прогресс.
     */
    @Test
    fun anInterruptedOperationIsClosedRatherThanLeftRunning() {
        val journal = InMemoryOperationJournal()
        OperationEngine(journal, clock = { NOW }).begin(intent(), TARGET, GENERATION)

        val restored = OperationEngine(journal, clock = { NOW }).restoreAll(GENERATION)

        assertEquals(1, restored.size)
        assertTrue(restored.single().restoration is OperationRestoration.Abandoned)
        assertFalse(journal.history().records.single().outcome == null)
    }

    /**
     * После границы мутации исход `UNKNOWN`, а **не** `FAILED`.
     *
     * Устройство могло измениться, и назвать это неудачей значило бы пообещать,
     * что не изменилось.
     */
    @Test
    fun anInterruptionAfterTheBoundaryIsUnknownAndNotAFailure() {
        val journal = InMemoryOperationJournal()
        OperationEngine(journal, clock = { NOW })
            .begin(intent(), TARGET, GENERATION)
            .crossedMutationBoundary("первый блок ушёл", NOW)

        val restored = OperationEngine(journal, clock = { NOW }).restoreAll(GENERATION)

        assertTrue(restored.single().restoration is OperationRestoration.NeedsVerification)
        assertEquals(OperationOutcome.UNKNOWN, journal.history().records.single().outcome)
    }

    /** Скорость меряется от начала операции, а не назначается. */
    @Test
    fun theRateIsMeasuredFromTheStart() {
        val engine = OperationEngine(InMemoryOperationJournal(), clock = { NOW }, elapsedMillis = { 1_000L })
        val handle = engine.begin(intent(), TARGET, GENERATION, totalBytes = 4_000L)

        handle.progress(servedBytes = 2_000L, uniqueBytes = 2_000L, nowMillis = 3_000L)

        val progress = engine.live.value.single().progress!!
        assertEquals(2_000L, progress.elapsedMillis)
        assertEquals(1_000L, progress.bytesPerSecond)
        assertEquals(50, progress.percent)
    }

    private fun intent() = OperationIntent(OperationKind.ADB_SIDELOAD, "отдать пакет")

    /** Хранилище, которое считает, сколько раз его попросили писать. */
    private class CountingJournal : InMemoryOperationJournal() {
        var saves = 0
            private set

        override fun save(record: io.github.ncorror.nekoflash.core.operation.OperationRecord) {
            saves += 1
            super.save(record)
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-14T01:00:00Z")
        val TARGET = TargetId("serial:eff4927c")
        val GENERATION = SessionGeneration(3L)
    }
}
