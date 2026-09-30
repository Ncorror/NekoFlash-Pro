package io.github.ncorror.nekoflash.core.operation

import io.github.ncorror.nekoflash.core.model.SessionGeneration
import io.github.ncorror.nekoflash.core.model.TargetId
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Что можно честно сказать про операцию после смерти процесса.
 *
 * Главное здесь — чего сказать **нельзя**: что старая транзакция продолжилась.
 * Транспорт отпущен вместе с процессом, устройство про нас забыло, и «сейчас
 * дожмём» было бы обещанием того, чего не существует (`06` §3).
 */
class OperationRecoveryTest {
    /** Законченная до перезапуска операция восстанавливается своим исходом. */
    @Test
    fun anOperationThatFinishedKeepsItsOutcome() {
        val record = record(
            outcome = OperationOutcome.SUCCEEDED,
            finishedAt = Instant.EPOCH,
        )

        val restored = OperationRecovery.restore(record, GENERATION) as OperationRestoration.Finished

        assertEquals(OperationOutcome.SUCCEEDED, restored.outcome)
    }

    /**
     * До границы мутации обрыв — это «устройство не тронуто», и так и говорится.
     *
     * Сказать так можно ровно потому, что граница отмечается **до** того, как
     * байты попадут на провод: иначе утверждение было бы догадкой.
     */
    @Test
    fun beforeTheBoundaryTheDeviceIsUntouchedAndItIsSaidPlainly() {
        val restored = OperationRecovery.restore(record(), GENERATION)

        val abandoned = restored as OperationRestoration.Abandoned
        assertTrue(abandoned.detail.contains("устройство не тронуто"))
    }

    /**
     * После границы — `Unknown` с тем, чем это проверить.
     *
     * «Неизвестно» без следующего шага оставляет оператора там же, где он был.
     */
    @Test
    fun afterTheBoundaryTheAnswerIsUnknownAndNamesHowToCheckIt() {
        val record = record(
            kind = OperationKind.ADB_SIDELOAD,
            boundary = MutationBoundary.Crossed(Instant.EPOCH, "первый блок ушёл"),
        )

        val restored = OperationRecovery.restore(record, GENERATION)

        val unknown = restored as OperationRestoration.NeedsVerification
        assertTrue(unknown.detail.contains("первый блок ушёл"))
        assertTrue(unknown.verify.contains("вердикт Recovery"))
    }

    /**
     * Маленький процент неизвестность **не** уменьшает.
     *
     * Устройство начинает меняться с первых полученных данных, и «передано
     * мало» не означает «ничего не изменилось». Процент называется — и тут же
     * оговаривается.
     */
    @Test
    fun aSmallPercentDoesNotMakeTheOutcomeAnyMoreKnown() {
        val record = record(
            boundary = MutationBoundary.Crossed(Instant.EPOCH, "первый блок ушёл"),
            progress = OperationProgress(
                servedBytes = 1L,
                uniqueBytes = 1L,
                totalBytes = 100L,
            ),
        )

        val restored = OperationRecovery.restore(record, GENERATION) as OperationRestoration.NeedsVerification

        assertTrue(restored.detail.contains("1%"))
        assertTrue(restored.detail.contains("ничего не говорит о состоянии устройства"))
    }

    /** Совет зависит от рода операции: общего ответа на «что проверить» нет. */
    @Test
    fun theAdviceDependsOnWhatWasBeingDone() {
        val push = OperationRecovery.restore(
            record(kind = OperationKind.ADB_PUSH, boundary = crossed()),
            GENERATION,
        ) as OperationRestoration.NeedsVerification
        val flash = OperationRecovery.restore(
            record(kind = OperationKind.FASTBOOT_MUTATION, boundary = crossed()),
            GENERATION,
        ) as OperationRestoration.NeedsVerification

        assertTrue(push.verify.contains("файл назначения на устройстве"))
        assertTrue(flash.verify.contains("раздела"))
    }

    /** Без устройства совет начинается с того, что его надо подключить. */
    @Test
    fun withoutADeviceTheAdviceStartsByAskingForOne() {
        val restored = OperationRecovery.restore(
            record(boundary = crossed()),
            currentGeneration = null,
        ) as OperationRestoration.NeedsVerification

        assertTrue(restored.verify.startsWith("подключите устройство"))
    }

    /**
     * То же поколение продолжения **не** возвращает.
     *
     * Соблазн ровно здесь: устройство то же, поколение то же — почему бы не
     * дожать? Потому что транспорт был отпущен вместе с процессом, и
     * транзакции, которую предлагается продолжить, не существует ни на одной из
     * сторон.
     */
    @Test
    fun theSameGenerationStillDoesNotResumeAnything() {
        val restored = OperationRecovery.restore(record(boundary = crossed()), GENERATION)

        assertTrue(restored is OperationRestoration.NeedsVerification)
    }

    /** У незаконченной записи нет времени окончания, и это проверяется типом. */
    @Test
    fun anUnfinishedRecordCannotCarryAFinishTime() {
        assertFalse(record().finished)
        assertNull(record().outcome)
        assertTrue(
            runCatching {
                record(outcome = OperationOutcome.SUCCEEDED, finishedAt = null)
            }.exceptionOrNull() is IllegalArgumentException,
        )
    }

    private fun crossed() = MutationBoundary.Crossed(Instant.EPOCH, "байты пошли")

    private fun record(
        kind: OperationKind = OperationKind.ADB_SIDELOAD,
        boundary: MutationBoundary = MutationBoundary.NotCrossed,
        progress: OperationProgress? = null,
        outcome: OperationOutcome? = null,
        finishedAt: Instant? = null,
    ) = OperationRecord(
        id = OperationId("op-1"),
        createdAt = Instant.EPOCH,
        intent = OperationIntent(kind, "отдать пакет"),
        targetId = TargetId("serial:eff4927c"),
        startedSessionGeneration = GENERATION,
        state = OperationState("RUNNING"),
        mutationBoundary = boundary,
        progress = progress,
        outcome = outcome,
        finishedAt = finishedAt,
    )

    private companion object {
        val GENERATION = SessionGeneration(3L)
    }
}
