package io.github.ncorror.nekoflash.protocol.adb

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Разбор ответа на запрос обратного проброса.
 *
 * Первые три проверки — это дословно то, что ответило устройство в `07` §6.61,
 * и они здесь главные: остальные описывают, как разбор ведёт себя за пределами
 * наблюдённого, и любая из них может быть пересмотрена новым наблюдением.
 */
class AdbReverseProtocolTest {
    /** Наблюдено: `reverse:forward:tcp:7777;tcp:8888` ответил `OKAY00047777`. */
    @Test
    fun theObservedForwardReplyCarriesTheAssignedPort() {
        val reply = AdbReverseProtocol.parse("OKAY00047777")

        assertEquals(AdbReverseReply.Accepted("7777"), reply)
    }

    /** Наблюдено: пустой список отвечает `0000` — без подтверждения, но с длиной. */
    @Test
    fun theObservedEmptyListIsAcceptedAndEmpty() {
        val reply = AdbReverseProtocol.parse("0000")

        assertEquals(AdbReverseReply.Accepted(""), reply)
    }

    /** Наблюдено: `reverse:killforward-all` ответил `OKAY` — без длины вовсе. */
    @Test
    fun theObservedKillReplyHasNoBodyAtAll() {
        val reply = AdbReverseProtocol.parse("OKAY")

        assertEquals(AdbReverseReply.Accepted(""), reply)
    }

    /**
     * Наблюдено: непустой список приходит с завершающим переводом строки.
     *
     * Объявлено `0019` — двадцать пять символов, — а видимых двадцать четыре;
     * двадцать пятый и есть перевод строки. Проверка стоит здесь затем, чтобы
     * зафиксировать: разбирать надо сырой вывод. Срезав перевод строки, как
     * делает `text()`, верный ответ устройства пришлось бы объявить непонятым
     * (`07` §6.62).
     */
    @Test
    fun theObservedNonEmptyListKeepsItsTrailingNewline() {
        val reply = AdbReverseProtocol.parse("0019UsbFfs tcp:7777 tcp:8888\n")

        assertEquals(AdbReverseReply.Accepted("UsbFfs tcp:7777 tcp:8888\n"), reply)
    }

    /** А без перевода строки та же строка уже не сходится — и объявляется непонятой. */
    @Test
    fun theSameListWithoutItsNewlineNoLongerAddsUp() {
        val reply = AdbReverseProtocol.parse("0019UsbFfs tcp:7777 tcp:8888")

        assertEquals(AdbReverseReply.Unreadable("0019UsbFfs tcp:7777 tcp:8888"), reply)
    }

    /** Тело длиннее одной строки разбирается так же: длина решает, а не содержимое. */
    @Test
    fun aLongerBodyIsTakenByItsDeclaredLength() {
        val body = "tcp:7777 tcp:8888"
        val reply = AdbReverseProtocol.parse("%04x".format(body.length) + body)

        assertEquals(AdbReverseReply.Accepted(body), reply)
    }

    /**
     * Непонятый ответ отдаётся целиком, а не подгоняется под ожидание.
     *
     * Потерять сказанное устройством хуже, чем не понять его: по тексту видно,
     * что наблюдение было неполным, а по подогнанному ответу — нет.
     */
    @Test
    fun aLengthThatDoesNotAddUpIsKeptWhole() {
        val reply = AdbReverseProtocol.parse("OKAY00ff7777")

        assertEquals(AdbReverseReply.Unreadable("00ff7777"), reply)
    }

    @Test
    fun aLengthThatIsNotHexIsKeptWhole() {
        val reply = AdbReverseProtocol.parse("OKAYzzzz7777")

        assertEquals(AdbReverseReply.Unreadable("zzzz7777"), reply)
    }

    /**
     * Отказ узнаётся по префиксу, хотя на устройстве его не видели.
     *
     * Форма догадана и может оказаться иной; цена ошибки несимметрична —
     * принять отказ за успех хуже, чем не узнать его и отдать текстом.
     */
    @Test
    fun aRefusalIsRecognisedByItsPrefix() {
        val reply = AdbReverseProtocol.parse("FAIL000bcannot bind")

        assertEquals(AdbReverseReply.Refused("cannot bind"), reply)
    }

    /** Отказ без разбираемой длины всё равно остаётся отказом, а не успехом. */
    @Test
    fun aRefusalWithoutAReadableLengthStaysARefusal() {
        val reply = AdbReverseProtocol.parse("FAILwhatever")

        assertEquals(AdbReverseReply.Refused("FAILwhatever"), reply)
    }

    /** Порядок сторон — наблюдение: сначала устройство, потом хост. */
    @Test
    fun theServiceNamesTheDeviceSideFirst() {
        assertEquals(
            "reverse:forward:tcp:7777;tcp:8888",
            AdbReverseService.forward(onDevice = "tcp:7777", onHost = "tcp:8888"),
        )
    }

    /** Пробелы по краям снимаются, остальное передаётся как набрано. */
    @Test
    fun theAddressesAreNotRewrittenBeyondTrimming() {
        assertEquals(
            "reverse:forward:localabstract:что угодно;tcp:1",
            AdbReverseService.forward(onDevice = "  localabstract:что угодно ", onHost = " tcp:1 "),
        )
    }
}
