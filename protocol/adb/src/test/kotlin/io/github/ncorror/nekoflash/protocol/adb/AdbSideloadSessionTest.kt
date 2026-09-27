package io.github.ncorror.nekoflash.protocol.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Машина состояний ADB Sideload.
 *
 * Инварианты взяты из `03` §6 — они доказаны A2 на железе, и переносятся как
 * correctness evidence, а не как ограничение (`09`, Phase 7). Проверяется
 * поэтому не «байты ушли», а то, что нельзя выдать одно за другое: передачу за
 * установку, повторы за прогресс, обрыв после границы мутации за отмену.
 */
class AdbSideloadSessionTest {
    /** Объём и размер блока объявляются в самом имени сервиса. */
    @Test
    fun theServiceNameCarriesTheSizeAndTheBlock() {
        assertEquals("sideload-host:1048576:65536", AdbSideloadSession(1, 1_048_576).service)
    }

    /** Открытие подтверждается идентификатором удалённого потока. */
    @Test
    fun theStreamOpensAgainstARemoteId() {
        val session = AdbSideloadSession(3, BLOCK.toLong())
        val open = session.open()

        assertEquals(AdbCommand.OPEN, open.command)
        assertEquals(AdbSideloadTransition.OPENED, session.consume(okay(remote = 9, local = 3)).transition)
    }

    /**
     * `DONEDONE` кончает **передачу**, а не установку.
     *
     * Первый инвариант `03` §6. Назвать его успехом установки значило бы
     * объявить установленным то, чего Recovery ещё не подтвердило.
     */
    @Test
    fun doneDoneEndsTheTransferAndNotTheInstall() {
        val session = opened()

        val step = session.consume(write("DONEDONE"))

        assertEquals(AdbSideloadOutcome.TransferComplete, step.outcome)
        assertTrue(
            "проверка Recovery всё равно нужна",
            AdbSideloadContract.requiresVerification(step.outcome!!),
        )
    }

    /** Номер блока превращается в смещение и длину, а последний блок короче. */
    @Test
    fun aBlockNumberBecomesAnOffsetAndALength() {
        val session = opened(total = BLOCK.toLong() + 100L)

        val first = session.consume(write("00000000")).blockRequest!!
        assertEquals(AdbSideloadBlockRequest(0, 0L, BLOCK), first)

        session.provideBlock(first, ByteArray(BLOCK))
        session.markPayloadStarted()
        session.consume(okay(remote = 9, local = 7))

        val second = session.consume(write("00000001")).blockRequest!!
        assertEquals("последний блок короче", AdbSideloadBlockRequest(1, BLOCK.toLong(), 100), second)
    }

    /**
     * Повторный блок раздувает отданное и **не** двигает покрытие.
     *
     * Sideload управляется запросами, и Recovery вправе попросить один блок не
     * раз. Считать это прогрессом значило бы показывать больше ста процентов на
     * исправной передаче.
     */
    @Test
    fun aRepeatedBlockGrowsServedBytesButNotCoverage() {
        val session = opened(total = BLOCK.toLong() * 2)

        deliver(session, block = 0)
        val second = deliver(session, block = 0)

        val progress = second.progress!!
        assertEquals("отдано дважды", BLOCK.toLong() * 2, progress.servedBytes)
        assertEquals("покрыт один блок", BLOCK.toLong(), progress.uniqueBytes)
        assertEquals(1, progress.uniqueBlocks)
        assertEquals(2, progress.totalBlocks)
        assertEquals(50, progress.percent)
    }

    /** Процент до `DONEDONE` не доходит до ста: передачу кончает Recovery, а не счётчик. */
    @Test
    fun theCounterNeverReachesAHundredBeforeDoneDone() {
        val session = opened(total = BLOCK.toLong())

        val progress = deliver(session, block = 0).progress!!

        assertEquals(BLOCK.toLong(), progress.uniqueBytes)
        assertEquals("покрытие полное, а передача — нет", 99, progress.percent)
    }

    /** Блок `-1` — конец запросов, а не ошибка. */
    @Test
    fun blockMinusOneMeansTheRequestsAreOver() {
        val session = opened()

        val step = session.consume(write("-1"))

        assertEquals(AdbSideloadTransition.REQUESTS_COMPLETE, step.transition)
        assertEquals("подтверждение и пустая запись", 2, step.outbound.size)
        assertTrue(step.outbound.last().payload.isEmpty())
    }

    /**
     * Граница мутации — **первая попытка** отправить блок, и до неё отмена законна.
     */
    @Test
    fun cancellationIsLegalOnlyBeforeTheMutationBoundary() {
        val session = opened()
        assertFalse(session.mutated)
        assertEquals(AdbSideloadOutcome.Cancelled, session.cancel().outcome)

        val started = opened()
        val request = started.consume(write("00000000")).blockRequest!!
        started.provideBlock(request, ByteArray(BLOCK))
        assertTrue("первая отметка", started.markPayloadStarted())
        assertTrue(started.mutated)

        val refused = started.cancel()
        assertEquals(AdbSideloadTransition.CANCELLATION_REJECTED, refused.transition)
        assertNull("отменять уже нечего", refused.outcome)
    }

    /**
     * Обрыв **после** границы мутации не является ни отменой, ни обычным сбоем.
     *
     * Состояние устройства неизвестно, каким бы ни выглядел процент: Recovery
     * начинает менять устройство, как только у него появились данные.
     */
    @Test
    fun anInterruptionAfterTheBoundaryIsNeverASafeFailure() {
        val session = opened(total = BLOCK.toLong() * 10)
        deliver(session, block = 0)

        val step = session.transportClosed("кабель выдернут")

        val outcome = step.outcome as AdbSideloadOutcome.InterruptedAfterPayload
        assertEquals(10, outcome.percent)
        assertTrue(AdbSideloadContract.requiresVerification(outcome))
    }

    /** Обрыв **до** границы — обычный сбой: устройство не тронуто. */
    @Test
    fun anInterruptionBeforeTheBoundaryIsAnOrdinaryFailure() {
        val session = opened()

        val step = session.transportClosed("кабель выдернут")

        val outcome = step.outcome as AdbSideloadOutcome.Failed
        assertEquals(AdbSideloadFailure.TRANSPORT, outcome.kind)
        assertFalse("проверять нечего", AdbSideloadContract.requiresVerification(outcome))
    }

    /**
     * Закрытие при покрытии от 95% — отдельный исход, но не успех.
     *
     * Почти полное покрытие стоит сохранить как наблюдение; проверки Recovery
     * оно не отменяет.
     */
    @Test
    fun aCloseAtNinetyFivePercentIsItsOwnOutcome() {
        val session = opened(total = BLOCK.toLong() * 20)
        repeat(19) { block -> deliver(session, block = block) }

        val step = session.transportClosed("Recovery закрыло поток")

        val outcome = step.outcome as AdbSideloadOutcome.ClosedBeforeDoneDone
        assertEquals(95, outcome.percent)
        assertTrue(AdbSideloadContract.requiresVerification(outcome))
    }

    /** Запрос блока за пределами нагрузки — нарушение протокола, а не пустой блок. */
    @Test
    fun aBlockOutsideThePayloadIsAProtocolFailure() {
        val session = opened(total = BLOCK.toLong())

        val step = session.consume(write("00000009"))

        assertEquals(AdbSideloadTransition.FAILED, step.transition)
        assertTrue(step.detail.contains("вне нагрузки"))
    }

    /** Нагрузка не того размера — сбой файла, а не протокола. */
    @Test
    fun aBlockOfTheWrongSizeIsAFileFailure() {
        val session = opened()
        val request = session.consume(write("00000000")).blockRequest!!

        val step = session.provideBlock(request, ByteArray(BLOCK - 1))

        assertEquals(AdbSideloadFailure.FILE, (step.outcome as AdbSideloadOutcome.Failed).kind)
    }

    /**
     * Подтверждение нагрузки без отметки границы — нарушение порядка.
     *
     * Лучше громко сломаться, чем тихо потерять необратимую границу: после неё
     * отмены не существует, и молчаливая потеря сделала бы отмену возможной там,
     * где отменять уже нечего.
     */
    @Test
    fun anAcknowledgementWithoutTheBoundaryMarkIsARefusal() {
        val session = opened()
        val request = session.consume(write("00000000")).blockRequest!!
        session.provideBlock(request, ByteArray(BLOCK))

        val step = session.consume(okay(remote = 9, local = 7))

        assertEquals(AdbSideloadTransition.FAILED, step.transition)
        assertTrue(step.detail.contains("границы мутации"))
    }

    /** Чужой пакет не трогает состояние. */
    @Test
    fun aPacketForAnotherStreamIsIgnored() {
        val session = opened()

        val step = session.consume(okay(remote = 9, local = 1234))

        assertEquals(AdbSideloadTransition.STALE_PACKET, step.transition)
    }

    /** Начинать без транспорта, вне Sideload и с пустой нагрузкой нельзя. */
    @Test
    fun theThreeReasonsNotToStartAreCheckedBeforeAnyByte() {
        assertEquals(
            AdbSideloadRejection.TRANSPORT_UNAVAILABLE,
            AdbSideloadContract.validateStart(false, true, 1),
        )
        assertEquals(
            AdbSideloadRejection.NOT_IN_SIDELOAD_MODE,
            AdbSideloadContract.validateStart(true, false, 1),
        )
        assertEquals(AdbSideloadRejection.EMPTY_PAYLOAD, AdbSideloadContract.validateStart(true, true, 0))
        assertNull(AdbSideloadContract.validateStart(true, true, 1))
    }

    private fun opened(total: Long = BLOCK.toLong()): AdbSideloadSession {
        val session = AdbSideloadSession(LOCAL, total)
        session.open()
        session.consume(okay(remote = REMOTE, local = LOCAL))
        return session
    }

    /** Полный цикл одного блока: запрос, отдача, граница и подтверждение. */
    private fun deliver(session: AdbSideloadSession, block: Int): AdbSideloadStep {
        val request = session.consume(write("%08d".format(block))).blockRequest!!
        session.provideBlock(request, ByteArray(request.length))
        session.markPayloadStarted()
        return session.consume(okay(remote = REMOTE, local = LOCAL))
    }

    private fun okay(remote: Int, local: Int) = AdbPacket(AdbCommand.OKAY, remote, local, ByteArray(0))

    private fun write(text: String) =
        AdbPacket(AdbCommand.WRTE, REMOTE, LOCAL, text.toByteArray(Charsets.US_ASCII))

    private companion object {
        const val BLOCK = AdbSideloadContract.BLOCK_SIZE_BYTES
        const val LOCAL = 7
        const val REMOTE = 9
    }
}
