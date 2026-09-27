package io.github.ncorror.nekoflash.protocol.fastboot

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Фаза DATA OUT.
 *
 * Правила счёта байтов сверены с A2 `FastbootDataTransfer.transferSyncBulk` и
 * Legacy `FastbootProtocol` до написания кода (`16` §3). Здесь проверяется не
 * «байты дошли», а границы: что считается неизвестным состоянием приёмника, что
 * отказом до данных, и где повтор запрещён.
 */
class FastbootDownloadTest {
    /** Объём объявляется восемью цифрами. Четыре — длина ответа, другое поле. */
    @Test
    fun theSizeIsDeclaredWithEightHexDigits() {
        val download = FastbootDownload(FastbootLane(FakeFastbootTransport()))

        assertEquals("download:00001000", download.command(0x1000))
        assertEquals("download:00000000", download.command(0))
    }

    @Test
    fun theWholePayloadReachesTheDeviceAndItAnswers() {
        val transport = FakeFastbootTransport().willReply("DATA00000010", "OKAY")
        transport.dataAfterCommands = 1
        val lane = FastbootLane(transport)

        val outcome = FastbootDownload(lane).send(payload(16), 16)

        assertEquals(FastbootDownloadOutcome.Answered(FastbootReply.OKAY, "", emptyList(), 16), outcome)
        assertEquals("на устройство ушло ровно объявленное", 16L, transport.dataBytes)
        assertEquals("полоса свободна", FastbootLaneState.IDLE, lane.state)
        assertEquals(listOf("download:00000010"), transport.sent)
    }

    /**
     * Отказ **до** данных — единственный исход, о котором можно сказать
     * «ничего не изменилось».
     *
     * Ни одного байта не отправлено, буфер устройства не тронут, рамка цела.
     */
    @Test
    fun aRefusalBeforeTheDataPhaseLeavesTheBufferUntouched() {
        val transport = FakeFastbootTransport().willReply("FAILtoo large")
        val lane = FastbootLane(transport)

        val outcome = FastbootDownload(lane).send(payload(16), 16)

        assertTrue(outcome is FastbootDownloadOutcome.Refused)
        assertEquals("ни одного байта данных", 0L, transport.dataBytes)
        assertEquals("рамка цела", FastbootLaneState.IDLE, lane.state)
    }

    /**
     * Отказ **после** полной передачи — это ответ устройства, а не обрыв.
     *
     * Байты дошли, а принимать их устройство отказалось. Путать это с
     * оборванной передачей нельзя: в первом случае известно, что ушло всё.
     */
    @Test
    fun aRefusalAfterTheFullTransferIsAnAnswerNotAnInterruption() {
        val transport = FakeFastbootTransport().willReply("DATA00000010", "FAILchecksum")
        transport.dataAfterCommands = 1

        val outcome = FastbootDownload(FastbootLane(transport)).send(payload(16), 16)

        val answered = outcome as FastbootDownloadOutcome.Answered
        assertEquals(FastbootReply.FAIL, answered.reply)
        assertEquals(16L, answered.bytesSent)
    }

    /**
     * Короткая запись законна и дописывается.
     *
     * Хост → устройство дробится и повторяется, пока не отправлено всё; это
     * прямо разрешено контрактом транспорта и не путается с приёмом, где
     * дробление объявленного payload разрушало рамку.
     */
    @Test
    fun aShortWriteIsCompletedRatherThanTreatedAsFailure() {
        val transport = FakeFastbootTransport().willReply("DATA00000010", "OKAY")
        transport.dataAfterCommands = 1
        transport.writeAtMost = 5

        val outcome = FastbootDownload(FastbootLane(transport)).send(payload(16), 16)

        assertTrue("передача должна дойти до конца", outcome is FastbootDownloadOutcome.Answered)
        assertEquals(16L, (outcome as FastbootDownloadOutcome.Answered).bytesSent)
    }

    /**
     * Запись больше запрошенного неоднозначна, и повтора не будет.
     *
     * A2 называет её `Ambiguous`. Повторить те же байты нельзя: доказать, что
     * предыдущая попытка не дошла, нечем (`03` §3).
     */
    @Test
    fun anOverlongWriteIsUnknownRatherThanRetried() {
        val transport = FakeFastbootTransport().willReply("DATA00000010", "OKAY")
        transport.dataAfterCommands = 1
        transport.overlongWrite = true
        val lane = FastbootLane(transport)

        val outcome = FastbootDownload(lane).send(payload(16), 16)

        val unknown = outcome as FastbootDownloadOutcome.Unknown
        assertTrue("причина должна называть неоднозначность", unknown.detail.contains("неоднозначная"))
        assertEquals("рамка потеряна", FastbootLaneState.STALLED, lane.state)
    }

    /** Источник кончился раньше объявленного — провал, а не успех. */
    @Test
    fun aSourceThatEndsEarlyIsUnknownNotSuccess() {
        val transport = FakeFastbootTransport().willReply("DATA00000010", "OKAY")
        transport.dataAfterCommands = 1
        val lane = FastbootLane(transport)

        val outcome = FastbootDownload(lane).send(payload(8), 16)

        val unknown = outcome as FastbootDownloadOutcome.Unknown
        assertEquals(16L, unknown.expectedBytes)
        assertTrue(unknown.bytesSent < 16L)
        assertEquals(FastbootLaneState.STALLED, lane.state)
    }

    /**
     * Не состоявшаяся запись посреди данных оставляет приёмник неизвестным.
     *
     * Команда при этом ушла, фаза открыта, и часть байтов могла дойти — что
     * именно, мы не знаем. Это `Unknown`, а не отказ.
     */
    @Test
    fun aFailedWriteMidTransferIsUnknown() {
        val transport = FakeFastbootTransport().willReply("DATA00000010", "OKAY")
        transport.dataAfterCommands = 1
        transport.failDataWrite = true
        val lane = FastbootLane(transport)

        val outcome = FastbootDownload(lane).send(payload(16), 16)

        val unknown = outcome as FastbootDownloadOutcome.Unknown
        assertTrue("причина должна называть запись", unknown.detail.contains("запись не состоялась"))
        assertEquals("рамка потеряна", FastbootLaneState.STALLED, lane.state)
    }

    /**
     * Расхождение объявленного объёма с ожиданием устройства — не передаём.
     *
     * Договорились о разном: передавать в такой обмен значило бы наполнять
     * буфер, размер которого понят иначе.
     */
    @Test
    fun aSizeMismatchStopsBeforeAnyByteIsSent() {
        val transport = FakeFastbootTransport().willReply("DATA00000020", "OKAY")
        transport.dataAfterCommands = 1
        val lane = FastbootLane(transport)

        val outcome = FastbootDownload(lane).send(payload(16), 16)

        val unknown = outcome as FastbootDownloadOutcome.Unknown
        assertEquals(0L, unknown.bytesSent)
        assertEquals("ни одного байта данных", 0L, transport.dataBytes)
        assertEquals(FastbootLaneState.STALLED, lane.state)
    }

    /** Молчание вместо ответа на `download:` — неизвестность, а не отказ. */
    @Test
    fun silenceOnTheDownloadCommandIsUnknown() {
        val lane = FastbootLane(FakeFastbootTransport().willBeSilent(100))

        val outcome = FastbootDownload(lane).send(payload(16), 16, inactivityMillis = 100)

        assertTrue(outcome is FastbootDownloadOutcome.Unknown)
        assertEquals(0L, (outcome as FastbootDownloadOutcome.Unknown).bytesSent)
    }

    /** Байты переданы, ответа нет — тоже неизвестность, и это видно по счётчику. */
    @Test
    fun silenceAfterTheBytesIsUnknownWithEverythingSent() {
        val transport = FakeFastbootTransport().willReply("DATA00000010").willBeSilent(100)
        transport.dataAfterCommands = 1
        val lane = FastbootLane(transport)

        val outcome = FastbootDownload(lane).send(payload(16), 16, inactivityMillis = 100)

        val unknown = outcome as FastbootDownloadOutcome.Unknown
        assertEquals("байты ушли все", 16L, unknown.bytesSent)
        assertTrue(unknown.detail.contains("ответа не было"))
    }

    /** Занятая полоса не начинает обмен и называет своё состояние. */
    @Test
    fun aStalledLaneDoesNotStartTheDownload() {
        val lane = FastbootLane(FakeFastbootTransport().willBeSilent(100))
        lane.run("getvar:product", inactivityMillis = 100)

        val outcome = FastbootDownload(lane).send(payload(16), 16)

        assertTrue(outcome is FastbootDownloadOutcome.NotStarted)
    }

    /** Передавать в неоткрытую фазу данных нельзя, и полоса об этом говорит. */
    @Test
    fun sendingDataWithoutTheDataPhaseIsRefused() {
        val lane = FastbootLane(FakeFastbootTransport())

        val outcome = lane.sendData(payload(16), 16)

        assertEquals(FastbootDataOutcome.NotReady(FastbootLaneState.IDLE), outcome)
    }

    /** Нулевой объём — законный обмен, а не бессмыслица. */
    @Test
    fun anEmptyPayloadIsALegitimateExchange() {
        val transport = FakeFastbootTransport().willReply("DATA00000000", "OKAY")
        transport.dataAfterCommands = 1

        val outcome = FastbootDownload(FastbootLane(transport)).send(payload(0), 0)

        assertEquals(0L, (outcome as FastbootDownloadOutcome.Answered).bytesSent)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aNegativeSizeIsAProgrammerError() {
        FastbootDownload(FastbootLane(FakeFastbootTransport())).send(payload(1), -1)
    }

    private fun payload(bytes: Int): InputStream = ByteArrayInputStream(ByteArray(bytes) { it.toByte() })
}
