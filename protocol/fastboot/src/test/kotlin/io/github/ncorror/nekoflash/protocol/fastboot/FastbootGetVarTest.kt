package io.github.ncorror.nekoflash.protocol.fastboot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Чтение переменных устройства.
 *
 * Оба способа доставки значения наблюдены в Legacy
 * (`FastbootProtocol.readGetVarResponse`) до написания кода: обычно значение
 * лежит в теле `OKAY`, но часть загрузчиков шлёт пустой `OKAY`, а значение —
 * кадрами `INFO` перед ним.
 */
class FastbootGetVarTest {
    @Test
    fun theValueUsuallyArrivesInTheOkayItself() {
        val transport = FakeFastbootTransport().willReply("OKAYvayu")

        val variable = FastbootGetVar(FastbootLane(transport)).read("product")

        assertEquals(FastbootVariable.Present("product", "vayu"), variable)
        assertEquals(listOf("getvar:product"), transport.sent)
    }

    /**
     * Пустой `OKAY` не означает пустого значения.
     *
     * Часть загрузчиков отвечает так, отдав значение кадрами `INFO`.
     * Поддержать только первый способ значило бы объявить такие устройства
     * неотвечающими.
     */
    @Test
    fun anEmptyOkayFallsBackToTheInfoFrames() {
        val lane = FastbootLane(FakeFastbootTransport().willReply("INFOproduct: vayu", "OKAY"))

        assertEquals(FastbootVariable.Present("product", "vayu"), FastbootGetVar(lane).read("product"))
    }

    /** Строки `INFO` перебираются с конца: последняя относится к последнему вопросу. */
    @Test
    fun theLastInfoLineWins() {
        val lane = FastbootLane(FakeFastbootTransport().willReply("INFOstale", "INFOfresh", "OKAY"))

        assertEquals(FastbootVariable.Present("product", "fresh"), FastbootGetVar(lane).read("product"))
    }

    /** Имя переменной перед значением снимается — и через дефис, и через подчёркивание. */
    @Test
    fun aRepeatedVariableNameIsStripped() {
        assertEquals("yes", FastbootVariableValue.of("is-userspace", "is-userspace: yes"))
        assertEquals("yes", FastbootVariableValue.of("is-userspace", "is_userspace:yes"))
    }

    @Test
    fun anInfoPrefixInsideTheLineIsStripped() {
        assertEquals("vayu", FastbootVariableValue.of("product", "INFOproduct: vayu"))
    }

    /** Значение, похожее на путь, не режется по двоеточию: имя не совпало — не трогаем. */
    @Test
    fun aValueThatMerelyContainsAColonIsLeftAlone() {
        assertEquals("a:b:c", FastbootVariableValue.of("product", "a:b:c"))
    }

    /**
     * `FAIL` — это ответ устройства, а не сбой.
     *
     * Разные загрузчики знают разный набор переменных, и «такой у меня нет» —
     * осмысленный ответ, который нельзя смешивать с «спросить не удалось».
     */
    @Test
    fun aRefusalIsReportedAsUnsupportedNotAsFailure() {
        val lane = FastbootLane(FakeFastbootTransport().willReply("FAILunknown variable"))

        val variable = FastbootGetVar(lane).read("is-userspace")

        assertEquals(FastbootVariable.Unsupported("is-userspace", "unknown variable"), variable)
        assertEquals("отказ рамку не портит", FastbootLaneState.IDLE, lane.state)
    }

    @Test
    fun silenceIsUnavailableRatherThanUnsupported() {
        val lane = FastbootLane(FakeFastbootTransport().willBeSilent(100))

        val variable = FastbootGetVar(lane).read("product", inactivityMillis = 100)

        assertTrue(variable is FastbootVariable.Unavailable)
    }

    /** Занятая полоса называет своё состояние, а не молчит. */
    @Test
    fun aStalledLaneIsReportedAsUnavailableWithItsState() {
        val lane = FastbootLane(FakeFastbootTransport().willBeSilent(100))
        FastbootGetVar(lane).read("product", inactivityMillis = 100)

        val second = FastbootGetVar(lane).read("serialno") as FastbootVariable.Unavailable

        assertTrue(second.detail.contains("STALLED"))
    }

    /**
     * Фаза данных на `getvar` — потеря рамки, и молчать об этом нельзя.
     *
     * Устройство осталось ждать байты, о которых мы не договаривались.
     */
    @Test
    fun aDataPhaseOnGetVarStallsTheLane() {
        val lane = FastbootLane(FakeFastbootTransport().willReply("DATA00000004"))

        val variable = FastbootGetVar(lane).read("product")

        assertTrue(variable is FastbootVariable.Unavailable)
        assertEquals(FastbootLaneState.STALLED, lane.state)
    }

    /** Имя передаётся как набрано: какие переменные есть, знает загрузчик (`01` §3). */
    @Test
    fun anyVariableNameReachesTheDeviceUnchanged() {
        val transport = FakeFastbootTransport().willReply("OKAY")

        FastbootGetVar(FastbootLane(transport)).read("что-угодно".replace("что-угодно", "vendor-specific-thing"))

        assertEquals(listOf("getvar:vendor-specific-thing"), transport.sent)
    }

    /** `getvar:all` уходит целым именем сервиса, а не как переменная `all`. */
    @Test
    fun readAllAsksForTheWholeList() {
        val transport = FakeFastbootTransport().willReply("INFOproduct: vayu", "OKAY")

        FastbootGetVar(FastbootLane(transport)).readAll()

        assertEquals(listOf("getvar:all"), transport.sent)
    }

    @Test
    fun readAllCollectsEveryInfoLine() {
        val lane = FastbootLane(
            FakeFastbootTransport().willReply("INFOproduct: vayu", "INFOsecure: yes", "OKAY"),
        )

        val snapshot = FastbootGetVar(lane).readAll()

        assertEquals("vayu", snapshot.value("product"))
        assertEquals("yes", snapshot.value("secure"))
        assertTrue("обмен дошёл до конца", snapshot.complete)
    }

    /**
     * Оборванный список отдаётся прочитанным, но помечается неполным.
     *
     * Выдать частичный список за полный значило бы соврать о том, чего у
     * устройства нет: отсутствие переменной в таком списке ничего не означает.
     */
    @Test
    fun anInterruptedReadAllKeepsWhatArrivedAndSaysItIsPartial() {
        val transport = FakeFastbootTransport().willReply("INFOproduct: vayu").willBeSilent(100)
        val lane = FastbootLane(transport)

        val snapshot = FastbootGetVar(lane).readAll(inactivityMillis = 100)

        assertEquals("прочитанное не теряется", "vayu", snapshot.value("product"))
        assertFalse("список неполон, и это должно быть видно", snapshot.complete)
        assertEquals(FastbootLaneState.STALLED, lane.state)
    }

    /** Отказ на `getvar:all` доносится вместе с тем, что успело прийти. */
    @Test
    fun aRefusedReadAllCarriesTheRefusal() {
        val lane = FastbootLane(FakeFastbootTransport().willReply("FAILunknown command"))

        val snapshot = FastbootGetVar(lane).readAll()

        assertEquals(FastbootReply.FAIL, snapshot.finalReply)
        assertEquals("unknown command", snapshot.finalPayload)
    }

    /** Занятая полоса даёт неполный снимок с названной причиной, а не пустой успех. */
    @Test
    fun readAllOnAStalledLaneIsIncompleteWithItsReason() {
        val lane = FastbootLane(FakeFastbootTransport().willBeSilent(100))
        FastbootGetVar(lane).read("product", inactivityMillis = 100)

        val snapshot = FastbootGetVar(lane).readAll()

        assertFalse(snapshot.complete)
        assertTrue(snapshot.finalPayload.contains("STALLED"))
    }
}
