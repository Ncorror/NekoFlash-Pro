package io.github.ncorror.nekoflash.protocol.fastboot

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Чтение раздела — фаза DATA IN.
 *
 * Правила взяты из Legacy `fetchChunk` и `readRawDataTo` до кода (`16` §3), а не
 * выведены из симметрии с отправкой: симметрии тут нет. Проверяется не «байты
 * пришли», а что частичное чтение нигде не выдаётся за полное.
 */
class FastbootFetchTest {
    /**
     * Смещение и размер — **десятичные**, а не восемь шестнадцатеричных цифр.
     *
     * Восемью объявляется объём у `download:`; это поле другого направления, и
     * спутать их значит попросить не тот кусок.
     */
    @Test
    fun theOffsetAndSizeAreDecimal() {
        val fetch = fetch(FakeFastbootTransport())

        assertEquals("fetch:boot", fetch.command("boot"))
        assertEquals("fetch:boot:0:4096", fetch.command("boot", 0, 4096))
        assertEquals("fetch:super:65536:16384", fetch.command("super", 65536, 16384))
    }

    /** Устройство само назвало объём — читаем столько, сколько сказано. */
    @Test
    fun aWholePartitionIsReadWhenTheDeviceNamesTheSize() {
        val payload = ByteArray(16) { it.toByte() }
        val transport = FakeFastbootTransport()
            .willReply("FAILnot found") // partition-size:boot
            .willReply("FAILnot found") // max-fetch-size
            .willReply("DATA00000010")
            .willSendData(payload)
            .willReply("OKAY")
        val sink = ByteArrayOutputStream()

        val outcome = fetch(transport).fetch("boot", sink)

        assertEquals(FastbootFetchOutcome.Completed("boot", 16L, 1), outcome)
        assertTrue("байты дошли неизменёнными", payload.contentEquals(sink.toByteArray()))
        assertEquals("fetch:boot", transport.sent.last())
    }

    /**
     * Куски считаются по двум переменным устройства.
     *
     * `partition-size:` даёт сколько всего, `max-fetch-size` — сколько за раз.
     * Проверяется не только результат, но и что попросили: смещения обязаны
     * идти подряд, иначе раздел соберётся с дырой, выглядя целым.
     */
    @Test
    fun theChunksFollowThePartitionSizeAndTheFetchLimit() {
        val transport = FakeFastbootTransport()
            .willReply("OKAY24") // partition-size:boot
            .willReply("OKAY16") // max-fetch-size
            .willReply("DATA00000010").willSendData(ByteArray(16) { 1 }).willReply("OKAY")
            .willReply("DATA00000008").willSendData(ByteArray(8) { 2 }).willReply("OKAY")
        val sink = ByteArrayOutputStream()

        val outcome = fetch(transport).fetch("boot", sink)

        assertEquals(FastbootFetchOutcome.Completed("boot", 24L, 2), outcome)
        assertEquals(
            listOf("getvar:partition-size:boot", "getvar:max-fetch-size", "fetch:boot:0:16", "fetch:boot:16:8"),
            transport.sent,
        )
    }

    /** Размер приходит и шестнадцатеричным — так пишет часть загрузчиков. */
    @Test
    fun aHexadecimalSizeIsUnderstood() {
        assertEquals(4096L, FastbootSize.of("0x1000"))
        assertEquals(4096L, FastbootSize.of(" 4096 "))
        assertEquals(0L, FastbootSize.of("0"))
        assertNull(FastbootSize.of("-1"))
        assertNull(FastbootSize.of("не число"))
        assertNull(FastbootSize.of(null))
    }

    /**
     * Отказ до данных — это отказ, и он отделён от неполного чтения.
     *
     * На запертом загрузчике он ожидаем: `fetch:` живёт в `fastbootd` и обычно
     * требует разблокированного состояния. Legacy предупреждает об этом и не
     * запрещает — решение принимает устройство.
     */
    @Test
    fun aRefusalBeforeAnyDataIsARefusal() {
        val transport = FakeFastbootTransport()
            .willReply("FAILnot found")
            .willReply("FAILnot found")
            .willReply("FAILFetch is not allowed in Lock State")

        val outcome = fetch(transport).fetch("boot", ByteArrayOutputStream())

        val refused = outcome as FastbootFetchOutcome.Refused
        assertTrue(refused.detail.contains("Lock State"))
    }

    /**
     * Оборванное чтение — частичное, и называется частичным.
     *
     * Выдать его за раздел нельзя: недостающий кусок снаружи не отличить от
     * нулей внутри.
     */
    @Test
    fun anInterruptedReadIsPartialNotComplete() {
        val transport = FakeFastbootTransport()
            .willReply("FAILnot found")
            .willReply("FAILnot found")
            .willReply("DATA00000010")
            .willSendData(ByteArray(8) { 7 })
            .willBeSilent(BEYOND_PATIENCE_READS)
        val sink = ByteArrayOutputStream()

        val outcome = fetchOnVirtualClock(transport).fetch("boot", sink)

        val partial = outcome as FastbootFetchOutcome.Partial
        assertEquals("принято ровно столько, сколько пришло", 8L, partial.bytesReceived)
        assertEquals(8, sink.size())
    }

    /**
     * Пустой кусок останавливает чтение, а не крутит цикл.
     *
     * Legacy называет это ошибкой прямо: без проверки прогресс стоял бы на
     * месте, выглядя живым.
     */
    @Test
    fun anEmptyChunkStopsTheReadInsteadOfSpinning() {
        val transport = FakeFastbootTransport()
            .willReply("OKAY32") // partition-size
            .willReply("OKAY16") // max-fetch-size
            .willReply("DATA00000010").willSendData(ByteArray(16) { 3 }).willReply("OKAY")
            .willReply("DATA00000000").willReply("OKAY")

        val outcome = fetch(transport).fetch("boot", ByteArrayOutputStream())

        val partial = outcome as FastbootFetchOutcome.Partial
        assertEquals(16L, partial.bytesReceived)
        assertEquals(32L, partial.expectedBytes)
        assertTrue(partial.detail.contains("пустой кусок"))
    }

    /** Отказ на середине — не «ничего не было»: часть уже прочитана и она неполна. */
    @Test
    fun aRefusalMidwayIsPartialRatherThanARefusal() {
        val transport = FakeFastbootTransport()
            .willReply("OKAY32")
            .willReply("OKAY16")
            .willReply("DATA00000010").willSendData(ByteArray(16) { 4 }).willReply("OKAY")
            .willReply("FAILgone")

        val outcome = fetch(transport).fetch("boot", ByteArrayOutputStream())

        val partial = outcome as FastbootFetchOutcome.Partial
        assertEquals(16L, partial.bytesReceived)
    }

    /**
     * `FAIL` **после** данных обесценивает прочитанное.
     *
     * Байты пришли, а устройство сказало «нет» — значит верить им нельзя, и это
     * его слово, а не наша оценка.
     */
    @Test
    fun aRefusalAfterTheDataInvalidatesWhatCame() {
        val transport = FakeFastbootTransport()
            .willReply("FAILnot found")
            .willReply("FAILnot found")
            .willReply("DATA00000010")
            .willSendData(ByteArray(16) { 5 })
            .willReply("FAILchecksum")

        val outcome = fetch(transport).fetch("boot", ByteArrayOutputStream())

        assertTrue(outcome is FastbootFetchOutcome.Partial)
    }

    /** Короткий приём законен и дочитывается: устройство вправе отдать меньше за раз. */
    @Test
    fun aShortReceiveIsCompletedRatherThanTreatedAsTheEnd() {
        val transport = FakeFastbootTransport()
            .willReply("FAILnot found")
            .willReply("FAILnot found")
            .willReply("DATA00000010")
            .willSendData(ByteArray(6) { 8 })
            .willSendData(ByteArray(10) { 9 })
            .willReply("OKAY")
        val sink = ByteArrayOutputStream()

        val outcome = fetch(transport).fetch("boot", sink)

        assertEquals(16L, (outcome as FastbootFetchOutcome.Completed).bytesReceived)
        assertEquals(16, sink.size())
    }

    /** Устройство молчит вместо байтов — это обрыв, а не конец раздела. */
    @Test
    fun silenceInsteadOfBytesIsABreakNotTheEnd() {
        val transport = FakeFastbootTransport()
            .willReply("FAILnot found")
            .willReply("FAILnot found")
            .willReply("DATA00000010")
            .willReceiveEmpty()

        val outcome = fetch(transport).fetch("boot", ByteArrayOutputStream())

        val partial = outcome as FastbootFetchOutcome.Partial
        assertTrue(partial.detail.contains("перестало слать"))
    }

    /**
     * Терпение на байты не кончается на первом же неуспешном чтении.
     *
     * Пустое чтение на этом хосте возвращается мгновенно (`07` §6.93), и
     * сдаваться по одному такому ответу значило бы объявлять обрывом то, что
     * ещё не начиналось. Решает бюджет бездействия, а не отдельное чтение.
     */
    @Test
    fun oneFailedReadIsNotTheEndOfTheData() {
        val transport = FakeFastbootTransport()
            .willReply("FAILnot found")
            .willReply("FAILnot found")
            .willReply("DATA00000010")
            .willBeSilent(3)
            .willSendData(ByteArray(16) { 5 })
            .willReply("OKAY")
        val sink = ByteArrayOutputStream()

        val outcome = fetchOnVirtualClock(transport).fetch("boot", sink)

        assertEquals(16L, (outcome as FastbootFetchOutcome.Completed).bytesReceived)
        assertEquals(16, sink.size())
    }

    /**
     * Причина обрыва называет и место, и объявленный объём.
     *
     * «Не состоялся на 0» без второго числа не отличает отказ транспорта от
     * бессмысленного объёма, названного устройством. По выгрузке `07` §6.89
     * пришлось гадать ровно об этом.
     */
    @Test
    fun theBreakNamesBothTheOffsetAndTheDeclaredSize() {
        val transport = FakeFastbootTransport()
            .willReply("FAILnot found")
            .willReply("FAILnot found")
            .willReply("DATA00000010")
            .willReceiveEmpty()

        val outcome = fetchOnVirtualClock(transport).fetch("boot", ByteArrayOutputStream())

        val partial = outcome as FastbootFetchOutcome.Partial
        assertTrue(partial.detail, partial.detail.contains("на 0 из 16"))
    }

    /** Принимать в неоткрытую фазу данных нельзя, и приёмник об этом говорит. */
    @Test
    fun receivingWithoutADataPhaseIsRefused() {
        val lane = FastbootLane(FakeFastbootTransport())

        val outcome = lane.receiveData(ByteArrayOutputStream(), 16)

        assertEquals(FastbootReceiveOutcome.NotReady(FastbootLaneState.IDLE), outcome)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aNegativeSizeIsAProgrammerError() {
        FastbootLane(FakeFastbootTransport()).receiveData(ByteArrayOutputStream(), -1)
    }

    private companion object {
        /** Столько пустых чтений подряд заведомо переживает любой бюджет. */
        const val BEYOND_PATIENCE_READS = 2_000
    }

    private fun fetch(transport: FakeFastbootTransport): FastbootFetch {
        val lane = FastbootLane(transport)
        return FastbootFetch(lane, FastbootGetVar(lane))
    }

    /**
     * То же, но на управляемых часах.
     *
     * Нужно там, где проверяется исчерпание терпения: на настоящих часах такой
     * тест ждал бы две минуты, и «медленно» быстро становится «выключено».
     */
    private fun fetchOnVirtualClock(transport: FakeFastbootTransport): FastbootFetch {
        var now = 0L
        val lane = FastbootLane(
            transport,
            elapsedMillis = { now },
            pauseMillis = { now += it },
        )
        return FastbootFetch(lane, FastbootGetVar(lane))
    }
}
