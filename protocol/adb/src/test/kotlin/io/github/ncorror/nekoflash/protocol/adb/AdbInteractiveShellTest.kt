package io.github.ncorror.nekoflash.protocol.adb

import io.github.ncorror.nekoflash.core.diagnostics.InMemoryDiagnosticSink
import io.github.ncorror.nekoflash.usb.api.UsbTransferFailure
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test

/**
 * Живая оболочка поверх записанного устройства.
 *
 * Утверждения смотрят на **накопленное**, а не на отдельный шаг. Пока приём
 * крутил сам `pump`, один шаг соответствовал одному принятому пакету, и тест
 * мог считать шаги. С постоянным циклом раскладки такого соответствия больше
 * нет: цикл читает сам, и сколько событий успело лечь в ящик к моменту шага —
 * вопрос планировщика. Требовать от `pump` прежней зернистости значило бы
 * проверять свойство, которого у системы больше нет, — и такие проверки уже
 * начали давать разный результат от запуска к запуску.
 */
class AdbInteractiveShellTest {
    private val harnesses = AdbDispatchHarnesses()

    @After
    fun stopDispatchLoops() {
        harnesses.stopAll()
    }

    @Test
    fun openRequestsAPtyWhenTheDeviceSupportsShellV2() {
        val device = Device()
        device.shell(useShellV2 = true).open()

        val open = device.handle.sentFrames().single()
        assertEquals(AdbCommand.OPEN, open.command)
        assertEquals("${AdbInteractiveShell.SERVICE_PTY}\u0000", open.payload.decodeToString())
    }

    @Test
    fun openFallsBackToPlainShellWithoutShellV2() {
        val device = Device()
        device.shell(useShellV2 = false).open()

        assertEquals(
            "${AdbInteractiveShell.SERVICE_LEGACY}\u0000",
            device.handle.sentFrames().single().payload.decodeToString(),
        )
    }

    @Test
    fun okayPublishesOpenedEvent() {
        val device = Device(okay())
        val shell = device.shell(useShellV2 = true)
        shell.open()

        assertEquals(listOf(AdbShellEvent.Opened), shell.collect(expected = 1))
    }

    @Test
    fun outputArrivesAsSeparateStreams() {
        val device = Device(
            okay(),
            write(AdbShellProtocol.encode(AdbShellProtocol.ID_STDOUT, "out".toByteArray())),
            write(AdbShellProtocol.encode(AdbShellProtocol.ID_STDERR, "err".toByteArray())),
        )
        val shell = device.opening()

        val events = shell.collect(expected = 3)

        assertEquals(
            listOf(AdbShellEvent.Opened, AdbShellEvent.Output("out"), AdbShellEvent.ErrorOutput("err")),
            events,
        )
    }

    /** Рамка, разорванная между пакетами, не должна теряться. */
    @Test
    fun frameSplitBetweenPacketsIsAssembledAcrossPumps() {
        val frame = AdbShellProtocol.encode(AdbShellProtocol.ID_STDOUT, "halves".toByteArray())
        val device = Device(
            okay(),
            write(frame.copyOfRange(0, 4)),
            write(frame.copyOfRange(4, frame.size)),
        )
        val shell = device.opening()

        assertEquals(
            listOf(AdbShellEvent.Opened, AdbShellEvent.Output("halves")),
            shell.collect(expected = 2),
        )
    }

    /** UTF-8 symbol may be split between two complete shell,v2 frames. */
    @Test
    fun utf8SplitBetweenShellV2FramesIsDecodedIncrementally() {
        val bytes = "кот".toByteArray(Charsets.UTF_8)
        val first = bytes.copyOfRange(0, 1)
        val second = bytes.copyOfRange(1, bytes.size)
        val device = Device(
            okay(),
            write(AdbShellProtocol.encode(AdbShellProtocol.ID_STDOUT, first)),
            write(AdbShellProtocol.encode(AdbShellProtocol.ID_STDOUT, second)),
        )
        val shell = device.opening()

        assertEquals(listOf(AdbShellEvent.Opened, AdbShellEvent.Output("кот")), shell.collect(expected = 2))
    }

    @Test
    fun shellV2StreamingDecoderStillStripsNulCharacters() {
        val device = Device(
            okay(),
            write(
                AdbShellProtocol.encode(
                    AdbShellProtocol.ID_STDOUT,
                    "a\u0000б".toByteArray(Charsets.UTF_8),
                ),
            ),
        )
        val shell = device.opening()

        assertEquals(listOf(AdbShellEvent.Opened, AdbShellEvent.Output("aб")), shell.collect(expected = 2))
    }

    /** Legacy shell has the same byte-stream rule even without shell,v2 frames. */
    @Test
    fun utf8SplitBetweenLegacyWritesIsDecodedIncrementally() {
        val bytes = "ёж".toByteArray(Charsets.UTF_8)
        val first = bytes.copyOfRange(0, 1)
        val second = bytes.copyOfRange(1, bytes.size)
        val device = Device(okay(), write(first), write(second))
        val shell = device.opening(useShellV2 = false)

        assertEquals(listOf(AdbShellEvent.Opened, AdbShellEvent.Output("ёж")), shell.collect(expected = 2))
    }

    @Test
    fun exitFrameEndsTheSessionAndCarriesTheCode() {
        val device = Device(
            okay(),
            write(AdbShellProtocol.encode(AdbShellProtocol.ID_EXIT, byteArrayOf(7))),
        )
        val shell = device.opening()

        val events = shell.collect(expected = 2)

        assertEquals(listOf(AdbShellEvent.Opened, AdbShellEvent.Exited(7)), events)
        assertFalse(shell.active)
    }

    /** После завершения качать нечего, и лишние пакеты уходить не должны. */
    @Test
    fun pumpingAfterExitDoesNothing() {
        val device = Device(
            okay(),
            write(AdbShellProtocol.encode(AdbShellProtocol.ID_EXIT, byteArrayOf(0))),
        )
        val shell = device.opening()
        shell.collect(expected = 2)
        // К этому моменту ушли три кадра: OPEN и CLSE от сессии, OKAY за
        // принятый блок — от цикла раскладки. Последних двух ждём: порядок у
        // них не определён, и без ожидания «ничего не изменилось» сравнивало бы
        // два разных момента, а не два состояния (`07` §6.49).
        val sentAfterExit = device.handle.awaitSentFrames(3).size

        assertTrue(shell.pump().isEmpty())
        assertEquals(sentAfterExit, device.handle.sentFrames().size)
    }

    @Test
    fun inputIsWrappedIntoAStdinFrame() {
        val device = Device(okay())
        val shell = device.opened()

        assertTrue(shell.sendInput("ls\n"))

        val write = device.handle.sentFrames().last { it.command == AdbCommand.WRTE }
        assertArrayEquals(
            AdbShellProtocol.encode(AdbShellProtocol.ID_STDIN, "ls\n".toByteArray()),
            write.payload,
        )
    }

    /** Без shell,v2 рамок нет: ввод уходит как есть. */
    @Test
    fun inputGoesRawWithoutShellV2() {
        val device = Device(okay())
        val shell = device.opened(useShellV2 = false)

        shell.sendInput("ls\n")

        assertArrayEquals(
            "ls\n".toByteArray(),
            device.handle.sentFrames().last { it.command == AdbCommand.WRTE }.payload,
        )
    }

    @Test
    fun controlBytesGoThroughUnchanged() {
        val device = Device(okay())
        val shell = device.opened()

        shell.sendInput(byteArrayOf(3))

        val write = device.handle.sentFrames().last { it.command == AdbCommand.WRTE }
        assertArrayEquals(
            AdbShellProtocol.encode(AdbShellProtocol.ID_STDIN, byteArrayOf(3)),
            write.payload,
        )
    }

    /** До подтверждения открытия писать некуда. */
    @Test
    fun inputBeforeOkayIsRefused() {
        val device = Device()
        val shell = device.shell(useShellV2 = true)
        shell.open()

        assertFalse(shell.sendInput("ls\n"))
    }

    @Test
    fun closeInputSendsTheCloseStdinFrame() {
        val device = Device(okay())
        val shell = device.opened()

        assertTrue(shell.closeInput())

        assertArrayEquals(
            AdbShellProtocol.closeStdinFrame(),
            device.handle.sentFrames().last { it.command == AdbCommand.WRTE }.payload,
        )
    }

    /** В обычном shell: конца ввода нет, и делать вид, что он отправлен, нельзя. */
    @Test
    fun closeInputIsRefusedWithoutShellV2() {
        val device = Device(okay())

        assertFalse(device.opened(useShellV2 = false).closeInput())
    }

    @Test
    fun deviceClosingTheStreamEndsTheSessionWithoutACode() {
        val device = Device(okay(), close())
        val shell = device.opening()

        val events = shell.collect(expected = 2)

        assertEquals(listOf(AdbShellEvent.Opened, AdbShellEvent.Exited(null)), events)
        assertFalse(shell.active)
    }

    /** Вывод, пришедший вместе с закрытием, не должен потеряться. */
    @Test
    fun outputBeforeCloseIsDeliveredWithIt() {
        val device = Device(
            okay(),
            write(AdbShellProtocol.encode(AdbShellProtocol.ID_STDOUT, "bye".toByteArray())),
            close(),
        )
        val shell = device.opening()

        val events = shell.collect(expected = 3)

        assertEquals(
            listOf(AdbShellEvent.Opened, AdbShellEvent.Output("bye"), AdbShellEvent.Exited(null)),
            events,
        )
    }

    @Test
    fun lostTransportBreaksTheSession() {
        val device = Device(okay(), failed(UsbTransferFailure.NOT_HELD))
        val shell = device.opening()

        val events = shell.collect(expected = 2)

        assertTrue((events.last() as AdbShellEvent.Broken).reason.contains("transport"))
        assertFalse(shell.active)
    }

    @Test
    fun corruptFrameBreaksTheSessionInsteadOfGuessing() {
        val device = Device(
            okay(),
            write(byteArrayOf(1, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F)),
        )
        val shell = device.opening()

        val events = shell.collect(expected = 2)

        assertTrue(events.last() is AdbShellEvent.Broken)
        assertFalse(shell.active)
    }

    /** Тишина — обычное состояние оболочки, ждущей ввода. */
    @Test
    fun silenceIsNotAnError() {
        val device = Device(okay(), silence())
        val shell = device.opened()

        assertTrue(shell.collectNothing().isEmpty())
        assertTrue(shell.active)
    }

    @Test
    fun closingSendsCloseToTheDevice() {
        val device = Device(okay())
        val shell = device.opened()

        shell.close()

        assertEquals(AdbCommand.CLSE, device.handle.sentFrames().last().command)
        assertFalse(shell.active)
    }

    @Test
    fun exitWithoutPayloadLeavesTheCodeUnknown() {
        val device = Device(
            okay(),
            write(AdbShellProtocol.encode(AdbShellProtocol.ID_EXIT, ByteArray(0))),
        )

        val events = device.opening().collect(expected = 2)

        assertEquals(listOf(AdbShellEvent.Opened), events.dropLast(1))
        assertNull((events.last() as AdbShellEvent.Exited).code)
    }

    /**
     * Закрытие по нашей просьбе называет себя в журнале.
     *
     * Без причины запись `shell_closed` не отвечает на вопрос, ради которого её
     * и читают. Прогон §6.39 на этом споткнулся: гейт требует, чтобы при обрыве
     * оба потока закрылись одной причиной, а сверять было не с чем.
     */
    @Test
    fun closingByHostNamesItselfInTheLog() {
        val sink = InMemoryDiagnosticSink()
        val device = Device(okay())
        val shell = device.shell(useShellV2 = true, diagnostics = sink)
        shell.open()
        shell.collect(expected = 1)

        shell.close()

        val closed = sink.snapshot().single { it.message == "shell_closed" }
        assertEquals(AdbInteractiveShell.CLOSED_BY_HOST, closed.fields["reason"])
    }

    /** Конец, пришедший от устройства, записывается как его конец, а не как наш. */
    @Test
    fun theDeviceClosingTheStreamIsNamedAsSuch() {
        val sink = InMemoryDiagnosticSink()
        val device = Device(okay(), close())
        val shell = device.shell(useShellV2 = true, diagnostics = sink)
        shell.open()

        shell.collect(expected = 2)

        val closed = sink.snapshot().single { it.message == "shell_closed" }
        assertEquals("device closed the stream", closed.fields["reason"])
    }

    /** Обрыв записывается своей причиной: по ней потоки и сверяются между собой. */
    @Test
    fun aBrokenSessionRecordsWhatBrokeIt() {
        val sink = InMemoryDiagnosticSink()
        val device = Device(okay(), failed(UsbTransferFailure.NOT_HELD))
        val shell = device.shell(useShellV2 = true, diagnostics = sink)
        shell.open()

        shell.collect(expected = 2)

        val closed = sink.snapshot().single { it.message == "shell_closed" }
        assertTrue(closed.fields["reason"].orEmpty().contains("transport"))
    }

    /**
     * Прерывание называет свой поток в журнале.
     *
     * Гейт §6.39 требует показать, что `Ctrl+C` задел свой поток и не тронул
     * соседний. По записи «оператор нажал Ctrl+C» этого не видно, и прогон
     * §6.41 остановился именно на ненаблюдаемости, а не на поведении.
     */
    @Test
    fun interruptNamesItsOwnStream() {
        val sink = InMemoryDiagnosticSink()
        val device = Device(okay())
        val shell = device.shell(useShellV2 = true, diagnostics = sink)
        shell.open()
        shell.collect(expected = 1)

        assertTrue(shell.interrupt())

        val event = sink.snapshot().single { it.message == "shell_interrupt" }
        assertEquals("true", event.fields["sent"])
        assertEquals(shellStreamId(device), event.fields["stream"])
    }

    /** `Ctrl+C` уходит как один байт `ETX`, завёрнутый в рамку `stdin`. */
    @Test
    fun interruptSendsEtxAsStdin() {
        val device = Device(okay())
        val shell = device.opened()

        shell.interrupt()

        val write = device.handle.sentFrames().last { it.command == AdbCommand.WRTE }
        assertArrayEquals(
            AdbShellProtocol.encode(AdbShellProtocol.ID_STDIN, byteArrayOf(AdbInteractiveShell.CTRL_C)),
            write.payload,
        )
    }

    /**
     * Копит события, пока их не наберётся [expected] или не выйдет время.
     *
     * Лишнее событие сверх ожидаемого проверку не спасает: сравнение списков
     * такое расхождение покажет.
     */
    private fun AdbInteractiveShell.collect(
        expected: Int,
        timeoutMillis: Long = COLLECT_TIMEOUT_MS,
    ): List<AdbShellEvent> {
        val events = mutableListOf<AdbShellEvent>()
        val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLI
        while (events.size < expected && System.nanoTime() < deadline && active) {
            events += pump(STEP_MS)
        }
        return events
    }

    /** Убеждается, что за отведённое время ничего не пришло. */
    private fun AdbInteractiveShell.collectNothing(): List<AdbShellEvent> {
        val events = mutableListOf<AdbShellEvent>()
        repeat(QUIET_STEPS) { events += pump(STEP_MS) }
        return events
    }

    /** Идентификатор потока из отправленного `OPEN`. */
    private fun shellStreamId(device: Device): String =
        device.handle.sentFrames().first { it.command == AdbCommand.OPEN }.arg0.toString()

    private inner class Device(vararg responses: List<FakeUsbTransportHandle.Transfer>) {
        val handle = FakeUsbTransportHandle(
            inbound = responses.flatMap { it }.toMutableList(),
            answerOnlyAfterRequest = true,
        )

        private val harness = harnesses.start(handle)

        fun shell(
            useShellV2: Boolean,
            diagnostics: InMemoryDiagnosticSink = InMemoryDiagnosticSink(),
        ) = AdbInteractiveShell(
            writer = harness.writer,
            dispatcher = harness.dispatcher,
            useShellV2 = useShellV2,
            diagnostics = diagnostics,
        )

        /**
         * Открытая и подтверждённая сессия.
         *
         * Подтверждения приходится **дожидаться**: раньше первый `pump` сам
         * читал `OKAY` и потому не мог его не увидеть, а теперь его кладёт в
         * ящик цикл раскладки, и успел он или нет — вопрос планировщика.
         */
        /**
         * Открытая, но ещё не разобранная сессия.
         *
         * Нужна там, где за подтверждением сразу идёт вывод: цикл раскладки
         * кладёт в ящик всё сразу, один шаг забирает всё лежащее, и [opened]
         * съел бы вместе с подтверждением то, ради чего тест написан. Поэтому
         * такие тесты проверяют **всю** последовательность, начиная с
         * [AdbShellEvent.Opened].
         */
        fun opening(useShellV2: Boolean = true): AdbInteractiveShell =
            shell(useShellV2).also { shell -> shell.open() }

        fun opened(useShellV2: Boolean = true): AdbInteractiveShell =
            shell(useShellV2).also { shell ->
                shell.open()
                shell.collect(expected = 1)
            }
    }

    private companion object {
        const val REMOTE_ID = 42
        const val LOCAL_ID = 1

        /** Сколько ждать события, которое обязано прийти. */
        const val COLLECT_TIMEOUT_MS = 5_000L

        /** Шаг ожидания: короткий, чтобы накопление не спало лишнего. */
        const val STEP_MS = 25

        /** Сколько шагов тишины считать достаточным доказательством тишины. */
        const val QUIET_STEPS = 4

        const val NANOS_PER_MILLI = 1_000_000L

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

        fun write(payload: ByteArray) = packet(AdbCommand.WRTE, payload = payload)

        fun close() = packet(AdbCommand.CLSE)

        fun silence() = listOf<FakeUsbTransportHandle.Transfer>(
            FakeUsbTransportHandle.Transfer.Failed(UsbTransferFailure.NOT_COMPLETED),
        )

        fun failed(reason: UsbTransferFailure) =
            listOf<FakeUsbTransportHandle.Transfer>(FakeUsbTransportHandle.Transfer.Failed(reason))
    }
}
