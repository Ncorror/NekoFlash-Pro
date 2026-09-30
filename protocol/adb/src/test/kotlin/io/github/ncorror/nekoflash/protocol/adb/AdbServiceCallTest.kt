package io.github.ncorror.nekoflash.protocol.adb

import io.github.ncorror.nekoflash.core.diagnostics.InMemoryDiagnosticSink
import io.github.ncorror.nekoflash.usb.api.UsbTransferFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test

class AdbServiceCallTest {
    private val harnesses = AdbDispatchHarnesses()

    @After
    fun stopDispatchLoops() {
        harnesses.stopAll()
    }

    @Test
    fun serviceOutputIsCollectedUntilTheStreamCloses() {
        val device = ScriptedDevice(
            okay(),
            write("Linux version 5.4"),
            write(" plus the rest"),
            close(),
        )

        val outcome = device.call().run(SERVICE) as AdbServiceOutcome.Completed

        assertEquals("Linux version 5.4 plus the rest", outcome.text())
    }

    @Test
    fun openRequestCarriesTheService() {
        val device = ScriptedDevice(okay(), close())

        device.call().run(SERVICE)

        val open = device.handle.sentFrames().first()
        assertEquals(AdbCommand.OPEN, open.command)
        assertEquals("$SERVICE\u0000", open.payload.decodeToString())
    }

    /** Каждый блок вывода подтверждается: без этого устройство остановится. */
    @Test
    fun everyChunkOfOutputIsAcknowledged() {
        val device = ScriptedDevice(okay(), write("one"), write("two"), close())

        device.call().run(SERVICE)

        val acknowledgements = device.handle.sentFrames().count { it.command == AdbCommand.OKAY }
        assertEquals(2, acknowledgements)
    }

    /**
     * Закрытие устройства подтверждается ответным `CLSE`.
     *
     * Кадра приходится **дожидаться**: его пишет цикл раскладки, и пишет уже
     * после того, как разбудил вызов концом ящика. Снимок сразу после возврата
     * `run` наблюдал бы чужой поток — так эта проверка и падала (`07` §6.49).
     */
    @Test
    fun closedStreamIsAcknowledgedBack() {
        val device = ScriptedDevice(okay(), close())

        device.call().run(SERVICE)

        assertEquals(
            listOf(AdbCommand.OPEN, AdbCommand.CLSE),
            device.handle.awaitSentFrames(2).map { it.command },
        )
    }

    /** Закрытие без OKAY — отказ сервиса, а не пустой ответ. */
    @Test
    fun streamClosedBeforeOkayIsAServiceRejection() {
        val device = ScriptedDevice(close())

        val outcome = device.call().run(SERVICE) as AdbServiceOutcome.Failed

        assertEquals(AdbServiceFailure.REJECTED, outcome.reason)
    }

    @Test
    fun emptyOutputIsStillACompletedCall() {
        val device = ScriptedDevice(okay(), close())

        val outcome = device.call().run(SERVICE)

        assertEquals("", (outcome as AdbServiceOutcome.Completed).text())
    }

    /** Сервис может не закрыть поток никогда, поэтому дедлайн обязателен. */
    @Test
    fun silenceEndsWithATimeoutAndTheStreamIsClosed() {
        val device = ScriptedDevice(okay())
        val clock = FakeElapsed()

        val outcome = device.call(elapsed = clock).run(SERVICE, timeoutMillis = 1_000)

        assertEquals(AdbServiceFailure.TIMED_OUT, (outcome as AdbServiceOutcome.Failed).reason)
        assertEquals(AdbCommand.CLSE, device.handle.sentFrames().last().command)
    }

    /** `cat` большого файла не должен съесть память. */
    @Test
    fun outputBeyondTheCapStopsTheCall() {
        val device = ScriptedDevice(okay(), write("0123456789"), write("0123456789"))

        val outcome = device.call().run(SERVICE, maxOutputBytes = 15)

        assertEquals(AdbServiceFailure.OUTPUT_TOO_LARGE, (outcome as AdbServiceOutcome.Failed).reason)
        // Порядок двух последних кадров не определён: `OKAY` за второй блок
        // пишет цикл раскладки, `CLSE` — сам вызов, и это разные потоки.
        // Утверждается то, что здесь и требуется: поток закрыт ровно один раз.
        assertEquals(1, device.handle.awaitSentFrames(4).count { it.command == AdbCommand.CLSE })
    }

    @Test
    fun outputExactlyAtTheCapIsAccepted() {
        val device = ScriptedDevice(okay(), write("0123456789"), close())

        val outcome = device.call().run(SERVICE, maxOutputBytes = 10)

        assertEquals("0123456789", (outcome as AdbServiceOutcome.Completed).text())
    }

    @Test
    fun releasedInterfaceIsReportedAsClosedTransport() {
        val device = ScriptedDevice(failedRead(UsbTransferFailure.NOT_HELD))

        val outcome = device.call().run(SERVICE) as AdbServiceOutcome.Failed

        assertEquals(AdbServiceFailure.TRANSPORT_CLOSED, outcome.reason)
    }

    @Test
    fun lostFramingIsNotMistakenForATimeout() {
        val device = ScriptedDevice(shortPayload())

        val outcome = device.call().run(SERVICE) as AdbServiceOutcome.Failed

        assertEquals(AdbServiceFailure.FRAMING_LOST, outcome.reason)
    }

    /** Пакеты чужого потока не должны прекращать наш вызов. */
    @Test
    fun packetsOfAnotherStreamDoNotEndTheCall() {
        val device = ScriptedDevice(
            okay(),
            packet(AdbCommand.WRTE, arg0 = 99, arg1 = 77, payload = "not ours".toByteArray()),
            write("ours"),
            close(),
        )

        val outcome = device.call().run(SERVICE)

        assertEquals("ours", (outcome as AdbServiceOutcome.Completed).text())
        assertTrue(
            "чужому потоку должно уйти CLSE",
            device.handle.sentFrames().any { it.command == AdbCommand.CLSE && it.arg0 == 77 },
        )
    }

    @Test
    fun diagnosticsRecordTheCall() {
        val device = ScriptedDevice(okay(), write("x"), close())
        val sink = InMemoryDiagnosticSink()

        device.call(diagnostics = sink).run(SERVICE)

        assertEquals(
            listOf("service_open", "service_opened", "service_completed"),
            sink.snapshot().map { it.message },
        )
    }

    @Test
    fun failureIsRecordedToo() {
        val device = ScriptedDevice(close())
        val sink = InMemoryDiagnosticSink()

        device.call(diagnostics = sink).run(SERVICE)

        assertTrue(sink.snapshot().any { it.message == "service_failed" })
    }

    @Test
    fun nonPositiveLimitsAreRejected() {
        val device = ScriptedDevice(okay(), close())
        var rejected = 0
        try {
            device.call().run(SERVICE, maxOutputBytes = 0)
        } catch (_: IllegalArgumentException) {
            rejected++
        }
        try {
            device.call().run(SERVICE, timeoutMillis = 0)
        } catch (_: IllegalArgumentException) {
            rejected++
        }
        assertEquals(2, rejected)
    }

    /**
     * Вывод соседнего потока в результат не попадает.
     *
     * Вызов забирает **свой** ящик, а не всё, что пришло по проводу. До шага 2
     * это же обеспечивалось сравнением идентификаторов в разборе событий;
     * проверка оставлена, потому что гарантия та же, а держится теперь на
     * другом механизме.
     */
    @Test
    fun outputOfAnotherStreamIsNotCollected() {
        val device = ScriptedDevice(
            okay(),
            write("mine"),
            packet(AdbCommand.WRTE, arg0 = 99, arg1 = OTHER_LOCAL_ID, payload = "theirs".toByteArray()),
            close(),
        )

        val outcome = device.call().run(SERVICE) as AdbServiceOutcome.Completed

        assertEquals("mine", outcome.text())
    }

    /**
     * Чужой поток закрывается, а вызов продолжается.
     *
     * Пакет неизвестного потока — не повод оборвать чужую работу: устройству
     * отвечает `CLSE` маршрутизатор, а ящик вызова об этом даже не узнаёт.
     */
    @Test
    fun aPacketForAnotherStreamIsRejectedWithoutEndingTheCall() {
        val device = ScriptedDevice(
            okay(),
            packet(AdbCommand.WRTE, arg0 = 99, arg1 = OTHER_LOCAL_ID, payload = "theirs".toByteArray()),
            write("mine"),
            close(),
        )

        val outcome = device.call().run(SERVICE) as AdbServiceOutcome.Completed

        assertEquals("mine", outcome.text())
        assertTrue(device.handle.sentFrames().any { it.command == AdbCommand.CLSE })
    }

    /** Время идёт само: каждая попытка приёма отъедает у дедлайна. */
    private class FakeElapsed : () -> Long {
        private var nanos = 0L

        override fun invoke(): Long {
            nanos += 400_000_000L
            return nanos
        }
    }

    /**
     * Записанный ответ устройства с работающим циклом раскладки.
     *
     * Ответы отдаются только после первого вопроса: цикл начинает читать
     * раньше, чем вызов успевает открыть поток.
     */
    private inner class ScriptedDevice(vararg responses: List<FakeUsbTransportHandle.Transfer>) {
        val handle = FakeUsbTransportHandle(
            inbound = responses.flatMap { it }.toMutableList(),
            answerOnlyAfterRequest = true,
        )

        private val harness = harnesses.start(handle)

        fun call(
            diagnostics: InMemoryDiagnosticSink = InMemoryDiagnosticSink(),
            elapsed: (() -> Long)? = null,
        ) = AdbServiceCall(
            writer = harness.writer,
            dispatcher = harness.dispatcher,
            diagnostics = diagnostics,
            elapsedNanos = elapsed ?: { 0L },
        )
    }

    private companion object {
        const val SERVICE = "shell:getprop ro.product.device"
        const val REMOTE_ID = 42
        const val LOCAL_ID = 1

        /** Идентификатор, который этому вызову не принадлежит. */
        const val OTHER_LOCAL_ID = 7

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
