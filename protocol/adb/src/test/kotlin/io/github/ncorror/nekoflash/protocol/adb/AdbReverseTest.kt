package io.github.ncorror.nekoflash.protocol.adb

import io.github.ncorror.nekoflash.usb.api.UsbTransferFailure
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Запрос обратного проброса поверх живого транспорта.
 *
 * Ответы устройства здесь воспроизводятся дословно по `07` §6.61 и §6.62 —
 * именно они и проверяются. Разбор самого текста живёт в
 * [AdbReverseProtocolTest]; здесь важно, что до разбора он доходит **сырым**.
 */
class AdbReverseTest {
    private val harnesses = AdbDispatchHarnesses()

    @After
    fun stopDispatchLoops() {
        harnesses.stopAll()
    }

    @Test
    fun theRequestNamesTheDeviceSideFirst() {
        val device = ScriptedDevice(okay(), write("OKAY00047777"), close())

        device.reverse().forward(onDevice = "tcp:7777", onHost = "tcp:8888")

        val open = device.handle.awaitSentFrames(1).first()
        assertEquals("reverse:forward:tcp:7777;tcp:8888" + NUL, open.payload.decodeToString())
    }

    /** Наблюдено: устройство отвечает назначенным портом. */
    @Test
    fun theAssignedPortComesBackFromTheDevice() {
        val device = ScriptedDevice(okay(), write("OKAY00047777"), close())

        val outcome = device.reverse().forward("tcp:7777", "tcp:8888")

        assertEquals(AdbReverseOutcome.Accepted("reverse:forward:tcp:7777;tcp:8888", "7777"), outcome)
    }

    /**
     * Наблюдено: непустой список приходит с завершающим переводом строки.
     *
     * Проверка здесь, а не только в разборе, потому что ломается это **между**
     * ними: `text()` срезал бы перевод строки, длина перестала бы сходиться, и
     * верный ответ пришёл бы как непонятый (`07` §6.62).
     */
    @Test
    fun theListReplyReachesTheParserWithItsNewlineIntact() {
        val device = ScriptedDevice(okay(), write("0019UsbFfs tcp:7777 tcp:8888\n"), close())

        val outcome = device.reverse().list()

        assertEquals(
            AdbReverseOutcome.Accepted(AdbReverseService.LIST, "UsbFfs tcp:7777 tcp:8888\n"),
            outcome,
        )
    }

    /** Наблюдено: снятие отвечает одним подтверждением без тела. */
    @Test
    fun killingEverythingIsAcceptedWithoutABody() {
        val device = ScriptedDevice(okay(), write("OKAY"), close())

        assertEquals(
            AdbReverseOutcome.Accepted(AdbReverseService.KILL_ALL, ""),
            device.reverse().killAll(),
        )
    }

    /** Пустой список — принятый ответ, а не отказ. */
    @Test
    fun anEmptyListIsAcceptedAndEmpty() {
        val device = ScriptedDevice(okay(), write("0000"), close())

        assertEquals(AdbReverseOutcome.Accepted(AdbReverseService.LIST, ""), device.reverse().list())
    }

    /** Сервиса нет — это ответ устройства, а не наша ошибка. */
    @Test
    fun aServiceTheDeviceDoesNotKnowIsUnreachable() {
        val device = ScriptedDevice(close())

        val outcome = device.reverse().list()

        assertEquals(AdbServiceFailure.REJECTED, (outcome as AdbReverseOutcome.Unreachable).reason)
    }

    /** Умерший транспорт называется собой, а не отказом устройства. */
    @Test
    fun aDeadTransportIsNotADeviceRefusal() {
        val device = ScriptedDevice(okay(), failedRead(UsbTransferFailure.NOT_HELD))

        val outcome = device.reverse().list()

        assertEquals(
            AdbServiceFailure.TRANSPORT_CLOSED,
            (outcome as AdbReverseOutcome.Unreachable).reason,
        )
    }

    /** Непонятый ответ не выдаётся за успех: текст виден целиком. */
    @Test
    fun anUnreadableReplyIsAFailureThatKeepsTheText() {
        val device = ScriptedDevice(okay(), write("OKAYzzzz"), close())

        val outcome = device.reverse().list() as AdbReverseOutcome.Failed

        assertTrue("текст должен сохраниться: ${outcome.detail}", outcome.detail.contains("zzzz"))
    }

    /** Семейство не заперто тремя запросами: произвольный тоже уходит как набран. */
    @Test
    fun anyReverseRequestIsPassedThroughUnchanged() {
        val device = ScriptedDevice(okay(), write("OKAY"), close())

        device.reverse().request("reverse:killforward:tcp:7777")

        assertEquals(
            "reverse:killforward:tcp:7777" + NUL,
            device.handle.awaitSentFrames(1).first().payload.decodeToString(),
        )
    }

    private inner class ScriptedDevice(vararg responses: List<FakeUsbTransportHandle.Transfer>) {
        val handle = FakeUsbTransportHandle(
            inbound = responses.flatMap { it }.toMutableList(),
            answerOnlyAfterRequest = true,
        )

        private val harness = harnesses.start(handle)

        fun reverse() = AdbReverse(
            AdbServiceCall(writer = harness.writer, dispatcher = harness.dispatcher),
        )
    }

    private companion object {
        const val REMOTE_ID = 42
        const val LOCAL_ID = 1
        val NUL = Char(0).toString()

        fun packet(
            command: Long,
            arg0: Int = 0,
            arg1: Int = 0,
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

        fun okay() = packet(AdbCommand.OKAY, arg0 = REMOTE_ID, arg1 = LOCAL_ID)

        fun write(text: String) =
            packet(AdbCommand.WRTE, arg0 = REMOTE_ID, arg1 = LOCAL_ID, payload = text.toByteArray())

        fun close() = packet(AdbCommand.CLSE, arg0 = REMOTE_ID, arg1 = LOCAL_ID)

        fun failedRead(reason: UsbTransferFailure) =
            listOf<FakeUsbTransportHandle.Transfer>(FakeUsbTransportHandle.Transfer.Failed(reason))
    }
}
