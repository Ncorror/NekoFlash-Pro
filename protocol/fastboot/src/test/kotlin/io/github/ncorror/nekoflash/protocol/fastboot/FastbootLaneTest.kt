package io.github.ncorror.nekoflash.protocol.fastboot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Полоса обмена Fastboot.
 *
 * Проверяется не «команда дошла», а границы: что считается концом обмена, что
 * потерей рамки и что обычным ожиданием. Поведение сверено с Legacy
 * `FastbootProtocol.kt` и A2 `FastbootTransaction.kt` до кода (`16` §3).
 */
class FastbootLaneTest {
    @Test
    fun anOkayEndsTheExchangeAndFreesTheLane() {
        val transport = FakeFastbootTransport().willReply("OKAY0.4")
        val lane = FastbootLane(transport)

        val exchange = lane.run("getvar:version")

        assertEquals(FastbootExchange.Completed(FastbootReply.OKAY, "0.4", emptyList()), exchange)
        assertEquals("команда ушла как набрана", listOf("getvar:version"), transport.sent)
        assertEquals("полоса снова свободна", FastbootLaneState.IDLE, lane.state)
    }

    /**
     * Отказ освобождает полосу так же, как согласие.
     *
     * `FAIL` — ответ устройства, а не поломка обмена: рамка цела, и следующую
     * команду слать можно. Считать отказ аварией значило бы запретить оператору
     * работать после первого же «нет» (`03` §2, Device authority).
     */
    @Test
    fun aFailIsAnAnswerAndTheLaneStaysUsable() {
        val lane = FastbootLane(FakeFastbootTransport().willReply("FAILunknown command"))

        val exchange = lane.run("oem something")

        assertEquals(FastbootExchange.Completed(FastbootReply.FAIL, "unknown command", emptyList()), exchange)
        assertEquals(FastbootLaneState.IDLE, lane.state)
    }

    /** `INFO` копится и обмен не кончает — так отвечают долгие операции. */
    @Test
    fun infoFramesAreCollectedUntilTheTerminalOne() {
        val lane = FastbootLane(
            FakeFastbootTransport().willReply("INFOerasing", "INFOwriting", "OKAY"),
        )

        val exchange = lane.run("erase:cache") as FastbootExchange.Completed

        assertEquals(listOf("erasing", "writing"), exchange.info)
        assertEquals(FastbootReply.OKAY, exchange.reply)
    }

    /**
     * Каждый кадр продлевает ожидание.
     *
     * Смысл проверки: обмен, который идёт дольше бюджета, но не молчит, живой.
     * Считать его мёртвым по общему таймеру значило бы рвать исправную
     * операцию тем вернее, чем больше она делает.
     */
    @Test
    fun everyFrameRefreshesTheIdleBudget() {
        val transport = FakeFastbootTransport()
            .willBeSilent(1).willReply("INFOone")
            .willBeSilent(1).willReply("INFOtwo")
            .willBeSilent(1).willReply("OKAY")
        val lane = FastbootLane(transport)

        val exchange = lane.run("erase:userdata", inactivityMillis = 1_000)

        assertTrue("обмен должен дойти до конца", exchange is FastbootExchange.Completed)
        assertEquals(listOf("one", "two"), (exchange as FastbootExchange.Completed).info)
    }

    /**
     * Молчание дольше бюджета — это `Unknown`, и полоса теряет рамку.
     *
     * Команда ушла, ответа нет: что с ней стало, неизвестно. Объявить это
     * отказом было бы ложью, а слать следующую команду по той же полосе —
     * потерять соответствие ответов командам.
     */
    @Test
    fun silenceBeyondTheBudgetStallsTheLane() {
        val lane = FastbootLane(FakeFastbootTransport().willBeSilent(100))

        val exchange = lane.run("getvar:product", inactivityMillis = 100)

        assertTrue("это Unknown, а не отказ", exchange is FastbootExchange.TimedOut)
        assertEquals(FastbootLaneState.STALLED, lane.state)
    }

    /**
     * В `TimedOut` попадает **измеренное** ожидание, а не объявленный бюджет.
     *
     * Пустое чтение возвращается мгновенно, и складывать запрошенные ломти
     * значило бы писать в журнал «ждали 7000 мс» через 15 мс реального времени.
     * Так и было до `07` §6.91: цикл считал не потраченное, а желаемое.
     */
    @Test
    fun theReportedWaitIsMeasuredAndNotTheBudget() {
        var now = 0L
        val lane = FastbootLane(
            FakeFastbootTransport().willBeSilent(10),
            elapsedMillis = { now },
            pauseMillis = { now += 400L },
        )

        val exchange = lane.run("getvar:product", inactivityMillis = 250)

        assertEquals(400L, (exchange as FastbootExchange.TimedOut).waitedMillis)
    }

    /**
     * Мгновенно пустеющее чтение не съедает бюджет в ноль реального времени.
     *
     * Пауза между пустыми чтениями взята у Legacy вместе с причиной: без неё
     * цикл крутится вхолостую и изображает ожидание, которого не было.
     */
    @Test
    fun anInstantEmptyReadDoesNotBurnTheBudgetForFree() {
        var now = 0L
        var pauses = 0
        val lane = FastbootLane(
            FakeFastbootTransport().willBeSilent(10),
            elapsedMillis = { now },
            pauseMillis = { paused -> pauses += 1; now += paused },
        )

        lane.run("getvar:product", inactivityMillis = 250)

        assertEquals("100 + 100 + 50 — три паузы до конца бюджета", 3, pauses)
    }

    /** Потерявшая рамку полоса не принимает вторую команду, а называет своё состояние. */
    @Test
    fun aStalledLaneRefusesFurtherCommands() {
        val transport = FakeFastbootTransport().willBeSilent(100)
        val lane = FastbootLane(transport)
        lane.run("getvar:product", inactivityMillis = 100)

        val second = lane.run("getvar:serialno")

        assertEquals(FastbootExchange.NotReady(FastbootLaneState.STALLED), second)
        assertEquals("вторая команда не должна уйти", 1, transport.sent.size)
    }

    @Test
    fun aDataFrameOpensTheDataPhaseAndHoldsTheLane() {
        val lane = FastbootLane(FakeFastbootTransport().willReply("DATA00001000"))

        val exchange = lane.run("download:00001000")

        assertEquals(FastbootExchange.DataPhase(0x1000L, "00001000", emptyList()), exchange)
        assertEquals("полоса ждёт байты, а не команду", FastbootLaneState.AWAITING_DATA, lane.state)
    }

    /** Вошедший в фазу данных и не доведший её обязан объявить рамку потерянной. */
    @Test
    fun theDataPhaseCanBeAbandonedOnlyByStalling() {
        val lane = FastbootLane(FakeFastbootTransport().willReply("DATA00000010"))
        lane.run("download:00000010")

        lane.stall()

        assertEquals(FastbootLaneState.STALLED, lane.state)
    }

    /**
     * Короткая запись — не «почти отправили».
     *
     * Устройство получило обрезанную команду, и что оно с ней сделало, мы не
     * знаем. Рамка потеряна, и повторять ту же команду нельзя (`03` §3).
     */
    @Test
    fun aShortWriteStallsTheLaneRatherThanRetrying() {
        val transport = FakeFastbootTransport().willReply("OKAY")
        transport.shortWriteAfter = 3
        val lane = FastbootLane(transport)

        val exchange = lane.run("getvar:product")

        assertTrue(exchange is FastbootExchange.NotSent)
        assertEquals(FastbootLaneState.STALLED, lane.state)
    }

    /** Не состоявшаяся запись рамку не теряет: устройство команды не видело. */
    @Test
    fun aFailedWriteLeavesTheLaneUsable() {
        val transport = FakeFastbootTransport().willReply("OKAY")
        transport.failWrite = true
        val lane = FastbootLane(transport)

        val exchange = lane.run("getvar:product")

        assertTrue(exchange is FastbootExchange.NotSent)
        assertEquals("команда не ушла — рамка цела", FastbootLaneState.IDLE, lane.state)
    }

    /**
     * Слишком длинная команда не отправляется и не обрезается.
     *
     * Это ограничение провода, а не наше: кадр Fastboot не бывает длиннее
     * шестидесяти четырёх байт. Набрать её оператору никто не мешает — мы лишь
     * честно говорим, что отправить нечем, вместо того чтобы обрезать и сделать
     * вид, что послали (`01` §3).
     */
    @Test
    fun aCommandLongerThanTheFrameIsRefusedRatherThanTruncated() {
        val transport = FakeFastbootTransport().willReply("OKAY")
        val lane = FastbootLane(transport)

        val exchange = lane.run("oem " + "a".repeat(FastbootLane.MAX_COMMAND_BYTES))

        assertTrue(exchange is FastbootExchange.NotSent)
        assertTrue("ничего не должно уйти на устройство", transport.sent.isEmpty())
        assertEquals(FastbootLaneState.IDLE, lane.state)
    }

    @Test
    fun anEmptyCommandIsRefusedBeforeTheWire() {
        val transport = FakeFastbootTransport()
        val lane = FastbootLane(transport)

        assertTrue(lane.run("") is FastbootExchange.NotSent)
        assertTrue(transport.sent.isEmpty())
    }

    /** Успешный приём нулевой длины — молчание, а не пустой кадр. */
    @Test
    fun anEmptyReceiveCountsAsSilenceNotAsAFrame() {
        val lane = FastbootLane(
            FakeFastbootTransport().willReceiveEmpty().willReply("OKAY"),
        )

        val exchange = lane.run("getvar:product")

        assertEquals(FastbootExchange.Completed(FastbootReply.OKAY, "", emptyList()), exchange)
    }

    /** Непонятый кадр сохраняется целиком и обмен не кончает. */
    @Test
    fun anUnknownFrameIsKeptAndTheExchangeContinues() {
        val lane = FastbootLane(FakeFastbootTransport().willReply("WHATever", "OKAY"))

        val exchange = lane.run("getvar:product") as FastbootExchange.Completed

        assertEquals(listOf("ever"), exchange.info)
    }

    @Test
    fun aClosedLaneAcceptsNothing() {
        val lane = FastbootLane(FakeFastbootTransport().willReply("OKAY"))
        lane.close()

        assertEquals(FastbootExchange.NotReady(FastbootLaneState.CLOSED), lane.run("getvar:product"))
    }

    /** Ожидание дробится: один приём на весь бюджет отдал бы управление только в конце. */
    @Test
    fun theWaitIsSlicedRatherThanOneLongBlockingRead() {
        val transport = FakeFastbootTransport().willBeSilent(100)
        val lane = FastbootLane(transport)

        lane.run("getvar:product", inactivityMillis = 3_000)

        assertTrue("приёмов должно быть несколько, было ${transport.receiveCalls}", transport.receiveCalls > 1)
    }

    /**
     * Команда, которую провод не несёт, не подменяется и не отправляется.
     *
     * `toByteArray(US_ASCII)` заменяет всё неASCII вопросительным знаком молча.
     * Отправить подменённое и назвать это отправкой набранного значило бы
     * солгать о том, что ушло на устройство. Набрать оператор может что угодно
     * (`01` §3) — но уйти обязано ровно набранное либо ничего.
     */
    @Test
    fun aCommandTheWireCannotCarryIsNamedRatherThanMangled() {
        val transport = FakeFastbootTransport().willReply("OKAY")
        val lane = FastbootLane(transport)

        val exchange = lane.run("oem разблокировать")

        assertTrue(exchange is FastbootExchange.NotSent)
        assertTrue("подменённое не должно уйти", transport.sent.isEmpty())
        assertEquals("рамка цела — команда не отправлялась", FastbootLaneState.IDLE, lane.state)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aNonPositiveBudgetIsAProgrammerError() {
        FastbootLane(FakeFastbootTransport()).run("getvar:product", inactivityMillis = 0)
    }
}
