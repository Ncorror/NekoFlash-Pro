package io.github.ncorror.nekoflash.protocol.adb

import io.github.ncorror.nekoflash.core.diagnostics.InMemoryDiagnosticSink
import io.github.ncorror.nekoflash.usb.api.UsbTransferFailure
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Перезагрузка через односторонний сервис `reboot:`.
 *
 * Проверяется не «команда ушла», а два вопроса, которые задаёт `03` §3:
 * начался ли переход и **тронуто ли устройство**. Второй решает, можно ли
 * повторять, и ошибиться в нём дороже.
 */
class AdbRebootTest {
    private val harnesses = AdbDispatchHarnesses()

    @After
    fun stopDispatchLoops() {
        harnesses.stopAll()
    }

    @Test
    fun emptyTargetAsksForAPlainReboot() {
        assertEquals("reboot:", AdbRebootService.of(""))
        assertEquals("reboot:", AdbRebootService.of("   "))
    }

    /** `system` — это обычная перезагрузка, и хвоста у неё нет. */
    @Test
    fun systemIsRecognisedRegardlessOfCase() {
        assertEquals("reboot:", AdbRebootService.of("system"))
        assertEquals("reboot:", AdbRebootService.of("System"))
        assertEquals("reboot:", AdbRebootService.of("SYSTEM"))
    }

    /**
     * Регистр произвольной цели не трогается.
     *
     * Расхождение с Legacy намеренное: какие цели чувствительны к регистру,
     * знает устройство, а не хост. Молча менять набранное значило бы выполнять
     * не ту команду, которую просили.
     */
    @Test
    fun anArbitraryTargetKeepsItsCase() {
        assertEquals("reboot:bootloader", AdbRebootService.of("bootloader"))
        assertEquals("reboot:EDL", AdbRebootService.of("EDL"))
        assertEquals("reboot:recovery", AdbRebootService.of("  recovery  "))
    }

    /**
     * Односторонним сервис делает его имя, а не нажатая кнопка.
     *
     * Прямо из Legacy: оператор может набрать `reboot:` в поле произвольного
     * сервиса, и обойтись с этим надо так же. Иначе ожидаемый разрыв будет
     * показан как отказ.
     */
    @Test
    fun rebootServicesAreRecognisedByName() {
        assertTrue(AdbServicePolicy.expectsOneWayDisconnect("reboot:"))
        assertTrue(AdbServicePolicy.expectsOneWayDisconnect("reboot:bootloader"))
        assertTrue(AdbServicePolicy.expectsOneWayDisconnect("  REBOOT:recovery  "))
    }

    /** Послабление не распространяется ни на что, кроме перезагрузки. */
    @Test
    fun otherServicesAreNotOneWay() {
        assertTrue(!AdbServicePolicy.expectsOneWayDisconnect("shell:ls"))
        assertTrue(!AdbServicePolicy.expectsOneWayDisconnect("sync:"))
        assertTrue(!AdbServicePolicy.expectsOneWayDisconnect("exec:reboot"))
        assertTrue(!AdbServicePolicy.expectsOneWayDisconnect("rebooting:"))
    }

    /** Цель достаётся из имени сервиса, чтобы отдать её тому же коду. */
    @Test
    fun theTargetIsTakenOutOfTheServiceName() {
        assertEquals("bootloader", AdbServicePolicy.rebootTargetOf("reboot:bootloader"))
        assertEquals("", AdbServicePolicy.rebootTargetOf("reboot:"))
        assertEquals("recovery", AdbServicePolicy.rebootTargetOf("REBOOT:recovery"))
    }

    /** Закрытие потока устройством — признак того, что команда принята. */
    @Test
    fun theDeviceClosingTheStreamMeansAccepted() {
        val device = ScriptedDevice(okay(), close())

        val outcome = device.reboot().reboot("") as AdbRebootOutcome.Accepted

        assertEquals("reboot:", outcome.service)
    }

    /** Устройство ушло с шины, не ответив, — ровно то, ради чего команда посылалась. */
    @Test
    fun leavingTheBusMeansAccepted() {
        val device = ScriptedDevice(failedRead(UsbTransferFailure.NOT_HELD))

        val outcome = device.reboot().reboot("bootloader") as AdbRebootOutcome.Accepted

        assertEquals("reboot:bootloader", outcome.service)
    }

    /**
     * Ответ текстом — это слова устройства, а не вердикт, и состояние после
     * него **неизвестно**.
     *
     * Проверка переписана по итогам `07` §6.46. Раньше здесь утверждалось, что
     * текст означает отказ и нетронутое устройство. Живая recovery ответила
     * словом `reboot.` и ушла в перезагрузку через 336 мс — то есть прежнее
     * утверждение было ложью ровно в том месте, где цена лжи наибольшая.
     */
    @Test
    fun textFromTheDeviceLeavesTheStateUnknown() {
        val device = ScriptedDevice(okay(), write("reboot not permitted"), close())

        val outcome = device.reboot().reboot("recovery") as AdbRebootOutcome.Failed

        assertEquals(AdbRebootFailure.DEVICE_ANSWERED, outcome.reason)
        assertEquals(AdbRebootDevice.UNKNOWN, outcome.device)
        assertTrue(outcome.detail.contains("reboot not permitted"))
    }

    /**
     * Устройство ушло, а цикл кончился по просьбе — это всё равно уход.
     *
     * Регрессия на прогон `07` §6.48. Там `transportEnd` смотрел только на
     * цикл, а цикл в production останавливает владелец: об отпадении первым
     * узнаёт USB-слой. Поле цикла оставалось пустым, и уход устройства через
     * 306 мс мы не заметили.
     */
    @Test
    fun aReleasedInterfaceCountsAsLeavingTheBus() {
        assertEquals(
            AdbMailboxEnd.TRANSPORT_CLOSED,
            AdbTransportEnd.of(loopEnd = null, held = false),
        )
    }

    /** Пока интерфейс удерживается и цикл молчит — транспорт жив. */
    @Test
    fun aHeldInterfaceMeansTheTransportIsAlive() {
        assertNull(AdbTransportEnd.of(loopEnd = null, held = true))
    }

    /** Причина, которую назвал цикл, весомее: она точнее. */
    @Test
    fun theLoopsOwnReasonWins() {
        assertEquals(
            AdbMailboxEnd.FRAMING_LOST,
            AdbTransportEnd.of(loopEnd = AdbMailboxEnd.FRAMING_LOST, held = false),
        )
    }

    /**
     * Ответ словами и уход с шины — это переход, а не отказ.
     *
     * Случай §6.46: recovery сказала `reboot.` и через 336 мс ушла. Решает
     * наблюдаемое — шина, — а не толкование текста. Слова при этом не
     * теряются: оператору они нужны.
     */
    @Test
    fun wordsFollowedByLeavingTheBusAreATransition() {
        val device = ScriptedDevice(okay(), write("reboot."), close())

        val outcome = device.reboot(transportEnd = { AdbMailboxEnd.TRANSPORT_CLOSED })
            .reboot("") as AdbRebootOutcome.Accepted

        assertTrue(outcome.evidence, outcome.evidence.contains("reboot."))
        assertTrue(outcome.evidence, outcome.evidence.contains("left the bus"))
    }

    /**
     * Ответ словами при живой шине оставляет состояние неизвестным.
     *
     * Ветка, ради которой всё и делалось. На подопытном устройстве её вызвать
     * нечем — `vayu` в перезагрузке не отказывает, — и `07` §6.47 записывает
     * это прямо: проверяется она только здесь.
     */
    @Test
    fun wordsWithoutLeavingTheBusStayUnknown() {
        val device = ScriptedDevice(okay(), write("not permitted"), close())

        val outcome = device.reboot().reboot("") as AdbRebootOutcome.Failed

        assertEquals(AdbRebootFailure.DEVICE_ANSWERED, outcome.reason)
        assertEquals(AdbRebootDevice.UNKNOWN, outcome.device)
        assertTrue(outcome.detail.contains("not permitted"))
    }

    /** Потеря кадра ожидаемым разрывом не становится даже после слов. */
    @Test
    fun wordsFollowedByALostFrameAreNotATransition() {
        val device = ScriptedDevice(okay(), write("reboot."), close())

        val outcome = device.reboot(transportEnd = { AdbMailboxEnd.FRAMING_LOST })
            .reboot("") as AdbRebootOutcome.Failed

        assertEquals(AdbRebootFailure.FRAMING_LOST, outcome.reason)
        assertEquals(AdbRebootDevice.UNKNOWN, outcome.device)
    }

    /**
     * `UNTOUCHED` остаётся только до границы мутации.
     *
     * Два случая, и оба про то, что запрос не ушёл. Всё после границы —
     * `UNKNOWN`, каким бы понятным ни казался ответ.
     */
    @Test
    fun untouchedIsClaimedOnlyBeforeTheBoundary() {
        val refused = ScriptedDevice(okay(), write("no"), close())
            .reboot().reboot("") as AdbRebootOutcome.Failed
        val neverSent = ScriptedDevice(outbound = listOf(FakeUsbTransportHandle.Transfer.Completed(0)))
            .reboot().reboot("") as AdbRebootOutcome.Failed

        assertEquals(AdbRebootDevice.UNKNOWN, refused.device)
        assertEquals(AdbRebootDevice.UNTOUCHED, neverSent.device)
    }

    /**
     * Испорченный кадр не переклеивается в ожидаемый разрыв.
     *
     * Прямое требование Legacy `AdbServiceCompletionPolicy`, и оно здесь
     * сохранено: после потери кадра неизвестно ничего, в том числе и то,
     * дошёл ли запрос.
     */
    @Test
    fun aLostFrameIsNeverRelabelledAsAnExpectedDisconnect() {
        val device = ScriptedDevice(okay(), shortPayload())

        val outcome = device.reboot().reboot("") as AdbRebootOutcome.Failed

        assertEquals(AdbRebootFailure.FRAMING_LOST, outcome.reason)
        assertEquals(AdbRebootDevice.UNKNOWN, outcome.device)
    }

    /**
     * Молчание при живом транспорте — не успех.
     *
     * Здесь расхождение с Legacy: у него не было способа отличить «peer ушёл с
     * шины» от «peer молчит», и молчание засчитывалось как ожидаемое
     * завершение. Ящик потока приносит конец с причиной, поэтому отличить
     * можно — и сообщать «перезагружается», не увидев ни одного признака
     * перехода, значит выдавать надежду за наблюдение.
     */
    @Test
    fun silenceOnALiveTransportIsNotSuccess() {
        val device = ScriptedDevice(okay())

        val outcome = device.reboot().reboot("", timeoutMillis = 300) as AdbRebootOutcome.Failed

        assertEquals(AdbRebootFailure.NO_TRANSITION, outcome.reason)
        assertEquals(AdbRebootDevice.UNKNOWN, outcome.device)
    }

    /** Запрос, не ушедший в провод, оставляет устройство нетронутым. */
    @Test
    fun aRequestThatNeverLeftTheHostLeavesTheDeviceUntouched() {
        val device = ScriptedDevice(outbound = listOf(FakeUsbTransportHandle.Transfer.Failed(UsbTransferFailure.NOT_HELD)))

        val outcome = device.reboot().reboot("") as AdbRebootOutcome.Failed

        assertEquals(AdbRebootFailure.TRANSPORT_CLOSED, outcome.reason)
        assertEquals(AdbRebootDevice.UNTOUCHED, outcome.device)
    }

    /**
     * Оборванная отправка без единого байта — тоже нетронутое устройство.
     *
     * Ноль байт означает, что запроса устройство не видело; всё остальное
     * консервативно считается неизвестным.
     */
    @Test
    fun anInterruptedSendOfNothingLeavesTheDeviceUntouched() {
        val device = ScriptedDevice(
            outbound = listOf(FakeUsbTransportHandle.Transfer.Completed(0)),
        )

        val outcome = device.reboot().reboot("") as AdbRebootOutcome.Failed

        assertEquals(AdbRebootFailure.SEND_FAILED, outcome.reason)
        assertEquals(AdbRebootDevice.UNTOUCHED, outcome.device)
    }

    /** Исход и состояние устройства попадают в журнал: по ним потом разбирают прогон. */
    @Test
    fun theOutcomeAndTheDeviceStateReachTheLog() {
        val sink = InMemoryDiagnosticSink()
        val device = ScriptedDevice(okay(), close())

        device.reboot(sink).reboot("bootloader")

        val messages = sink.snapshot().map { it.message }
        assertTrue(messages.contains("reboot_requested"))
        val accepted = sink.snapshot().single { it.message == "reboot_accepted" }
        assertEquals("reboot:bootloader", accepted.fields["service"])
    }

    @Test
    fun theRefusalStateReachesTheLog() {
        val sink = InMemoryDiagnosticSink()
        val device = ScriptedDevice(okay(), write("no"), close())

        device.reboot(sink).reboot("")

        val failure = sink.snapshot().single { it.message == "reboot_failed" }
        assertEquals(AdbRebootDevice.UNKNOWN.name, failure.fields["device"])
        assertEquals(AdbRebootFailure.DEVICE_ANSWERED.name, failure.fields["reason"])
    }

    private inner class ScriptedDevice(
        vararg responses: List<FakeUsbTransportHandle.Transfer>,
        outbound: List<FakeUsbTransportHandle.Transfer> = emptyList(),
    ) {
        val handle = FakeUsbTransportHandle(
            inbound = responses.flatMap { it }.toMutableList(),
            outbound = outbound.toMutableList(),
            answerOnlyAfterRequest = true,
        )

        private val harness = harnesses.start(handle)

        fun reboot(
            diagnostics: InMemoryDiagnosticSink = InMemoryDiagnosticSink(),
            transportEnd: () -> AdbMailboxEnd? = { null },
        ) = AdbReboot(
            writer = harness.writer,
            dispatcher = harness.dispatcher,
            transportEnd = transportEnd,
            // Ждать настоящие две секунды в каждом тесте про ответ словами —
            // это десять секунд на сюиту, которые платят все и всегда.
            answerGraceMillis = GRACE_MS,
            diagnostics = diagnostics,
        )
    }

    private companion object {
        /** Окно ожидания в тестах: проверяется логика, а не терпение. */
        const val GRACE_MS = 50L

        const val REMOTE_ID = 42
        const val LOCAL_ID = 1

        fun packet(
            command: Long,
            arg0: Int = REMOTE_ID,
            arg1: Int = LOCAL_ID,
            payload: ByteArray = ByteArray(0),
        ): List<FakeUsbTransportHandle.Transfer> {
            val frames = mutableListOf<FakeUsbTransportHandle.Transfer>(
                FakeUsbTransportHandle.Transfer.Completed(
                    AdbPacketHeader.SIZE_BYTES,
                    header(command, arg0, arg1, payload),
                ),
            )
            if (payload.isNotEmpty()) {
                frames += FakeUsbTransportHandle.Transfer.Completed(payload.size, payload)
            }
            return frames
        }

        fun okay() = packet(AdbCommand.OKAY)

        fun close() = packet(AdbCommand.CLSE)

        fun write(text: String) = packet(AdbCommand.WRTE, payload = text.toByteArray())

        fun failedRead(reason: UsbTransferFailure) =
            listOf<FakeUsbTransportHandle.Transfer>(FakeUsbTransportHandle.Transfer.Failed(reason))

        /** Объявленный payload приходит короче объявленного: кадр потерян. */
        fun shortPayload(): List<FakeUsbTransportHandle.Transfer> {
            val payload = ByteArray(64) { 1 }
            return listOf(
                FakeUsbTransportHandle.Transfer.Completed(
                    AdbPacketHeader.SIZE_BYTES,
                    header(AdbCommand.WRTE, REMOTE_ID, LOCAL_ID, payload),
                ),
                FakeUsbTransportHandle.Transfer.Completed(32, payload),
            )
        }
    }
}
