package io.github.ncorror.nekoflash.protocol.adb

import io.github.ncorror.nekoflash.core.diagnostics.InMemoryDiagnosticSink
import io.github.ncorror.nekoflash.usb.api.UsbTransferFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test

/**
 * Запись файла на устройство.
 *
 * Проверяется не только удачный путь: главное здесь — что об изменении файла
 * назначения говорится ровно столько, сколько доказано.
 */
class AdbSyncSendTest {
    private val harnesses = AdbDispatchHarnesses()

    @After
    fun stopDispatchLoops() {
        harnesses.stopAll()
    }

    @Test
    fun requestCarriesPathAndModeSeparatedByComma() {
        val device = Device(okay(), verdict(AdbSyncProtocol.ID_OKAY))

        device.opened().send("/sdcard/a.bin", modifiedAtSeconds = 7, mode = 0x1A4) { 0 }

        val spec = device.syncFrames().first { it.id == AdbSyncProtocol.ID_SEND }
        assertEquals("/sdcard/a.bin,420", spec.payload.toString(Charsets.UTF_8))
    }

    /** Имена файлов бывают любые, поэтому путь идёт в UTF-8. */
    @Test
    fun requestPathIsEncodedAsUtf8() {
        val device = Device(okay(), verdict(AdbSyncProtocol.ID_OKAY))

        device.opened().send("/sdcard/файл.bin", modifiedAtSeconds = 1) { 0 }

        val spec = device.syncFrames().first { it.id == AdbSyncProtocol.ID_SEND }
        assertTrue(spec.payload.toString(Charsets.UTF_8).startsWith("/sdcard/файл.bin,"))
    }

    @Test
    fun contentIsStreamedAsDataChunks() {
        val device = Device(okay(), verdict(AdbSyncProtocol.ID_OKAY))
        val content = ByteArray(3) { index -> (index + 1).toByte() }

        val outcome = device.opened().send("/sdcard/a.bin", 1, source = once(content))

        assertTrue(outcome is AdbSyncSendOutcome.Committed)
        val data = device.syncFrames().filter { it.id == AdbSyncProtocol.ID_DATA }
        assertEquals(1, data.size)
        assertEquals(content.toList(), data.single().payload.toList())
    }

    /** Источник отдаёт содержимое частями; каждая часть — свой блок. */
    @Test
    fun severalReadsBecomeSeveralChunks() {
        val device = Device(okay(), verdict(AdbSyncProtocol.ID_OKAY))
        val parts = mutableListOf(byteArrayOf(1, 2), byteArrayOf(3))

        val outcome = device.opened().send("/sdcard/a.bin", 1) { buffer ->
            val part = parts.removeFirstOrNull() ?: return@send 0
            part.copyInto(buffer)
            part.size
        }

        assertEquals(3L, outcome.bytesSent)
        assertEquals(2, device.syncFrames().count { it.id == AdbSyncProtocol.ID_DATA })
    }

    @Test
    fun transferEndsWithDoneCarryingModificationTime() {
        val device = Device(okay(), verdict(AdbSyncProtocol.ID_OKAY))

        device.opened().send("/sdcard/a.bin", modifiedAtSeconds = 1_700_000_000) { 0 }

        val done = device.syncFrames().single { it.id == AdbSyncProtocol.ID_DONE }
        assertEquals(1_700_000_000, done.value)
    }

    /** Пустой файл — это ноль блоков `DATA`, но всё тот же обмен. */
    @Test
    fun emptyContentStillCommits() {
        val device = Device(okay(), verdict(AdbSyncProtocol.ID_OKAY))

        val outcome = device.opened().send("/sdcard/empty", 1) { 0 }

        assertTrue(outcome is AdbSyncSendOutcome.Committed)
        assertEquals(0L, outcome.bytesSent)
        assertEquals(0, device.syncFrames().count { it.id == AdbSyncProtocol.ID_DATA })
    }

    @Test
    fun okayIsTheOnlyProofOfACommittedFile() {
        val device = Device(okay(), verdict(AdbSyncProtocol.ID_OKAY))

        val outcome = device.opened().send("/sdcard/a.bin", 1, source = once(byteArrayOf(9)))

        assertEquals(AdbSyncDestination.COMMITTED, outcome.destination)
    }

    /** Отпечаток считается по тому, что хост отдал в USB. */
    @Test
    fun commitReportsTheHashOfWhatWasSent() {
        val device = Device(okay(), verdict(AdbSyncProtocol.ID_OKAY))

        val outcome = device.opened()
            .send("/sdcard/a.bin", 1, source = once("abc".toByteArray())) as AdbSyncSendOutcome.Committed

        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            outcome.sha256,
        )
    }

    /**
     * Отказ устройства не доказывает, что файл цел.
     *
     * `FAIL` — честный ответ peer'а о том, что операция не удалась. О самом
     * файле он не говорит ничего: устройство могло уже создать и обрезать его.
     */
    @Test
    fun deviceRefusalLeavesTheDestinationUnknown() {
        val message = "couldn't create file".toByteArray()
        val device = Device(
            okay(),
            data(AdbSyncProtocol.message(AdbSyncProtocol.ID_FAIL, message)),
        )

        val outcome = device.opened()
            .send("/system/x", 1, source = once(byteArrayOf(1))) as AdbSyncSendOutcome.Failed

        assertEquals(AdbSyncFailure.DEVICE_REFUSED, outcome.reason)
        assertEquals(AdbSyncDestination.UNKNOWN, outcome.destination)
        assertTrue(outcome.detail.contains("couldn't create file"))
    }

    @Test
    fun transportLossAfterTheRequestLeavesTheDestinationUnknown() {
        // Условие держит `CLSE` до тех пор, пока запрос `SEND` не ушёл: без
        // него цикл раскладки вычитал бы закрытие раньше запроса, и проверялся
        // бы не обрыв после границы мутации, а отказ до неё.
        val device = Device(okay(), gate(afterFrames = 2), close())

        val outcome = device.opened()
            .send("/sdcard/a.bin", 1, source = once(byteArrayOf(1))) as AdbSyncSendOutcome.Failed

        assertEquals(AdbSyncDestination.UNKNOWN, outcome.destination)
    }

    @Test
    fun unexpectedVerdictIsNotTreatedAsSuccess() {
        val device = Device(okay(), verdict(AdbSyncProtocol.ID_DENT))

        val outcome = device.opened().send("/sdcard/a.bin", 1) { 0 } as AdbSyncSendOutcome.Failed

        assertEquals(AdbSyncFailure.UNEXPECTED_RESPONSE, outcome.reason)
        assertEquals(AdbSyncDestination.UNKNOWN, outcome.destination)
    }

    /**
     * Путь с `NUL` непредставим, и отказ приходит до того, как что-либо ушло.
     *
     * Это единственная проверка пути на стороне хоста: байт оборвал бы строку
     * на устройстве, и запрос означал бы не то, что просили.
     */
    @Test
    fun pathWithNulIsRefusedBeforeAnythingLeavesTheHost() {
        val device = Device(okay())
        val session = device.opened()
        val before = device.handle.sentBytes.size

        val outcome = session.send("/sdcard/a\u0000b", 1) { 0 } as AdbSyncSendOutcome.Failed

        assertEquals(AdbSyncFailure.UNREPRESENTABLE_PATH, outcome.reason)
        assertEquals(AdbSyncDestination.UNTOUCHED, outcome.destination)
        assertEquals(before, device.handle.sentBytes.size)
    }

    /**
     * Пустой путь отдаётся устройству, а не отклоняется хостом.
     *
     * Он представим, поэтому отвечать на него должно устройство. Проверка на
     * хосте была бы подменой ответа peer'а собственным решением — тем самым,
     * что отменено решением D031.
     */
    @Test
    fun emptyPathIsLeftForTheDeviceToRefuse() {
        val device = Device(
            okay(),
            data(AdbSyncProtocol.message(AdbSyncProtocol.ID_FAIL, "invalid path".toByteArray())),
        )

        val outcome = device.opened().send("", 1) { 0 } as AdbSyncSendOutcome.Failed

        assertEquals(AdbSyncFailure.DEVICE_REFUSED, outcome.reason)
        assertNotEquals(AdbSyncFailure.UNREPRESENTABLE_PATH, outcome.reason)
        assertTrue(device.syncFrames().any { it.id == AdbSyncProtocol.ID_SEND })
    }

    @Test
    fun closedSessionTouchesNothing() {
        val device = Device(okay())
        val session = device.opened()
        session.close()

        val outcome = session.send("/sdcard/a.bin", 1) { 0 } as AdbSyncSendOutcome.Failed

        assertEquals(AdbSyncFailure.NOT_OPEN, outcome.reason)
        assertEquals(AdbSyncDestination.UNTOUCHED, outcome.destination)
    }

    /**
     * Обрыв до того, как хоть байт запроса ушёл в USB, оставляет файл нетронутым.
     *
     * Это единственный случай после открытия сессии, в котором целость
     * назначения действительно доказуема.
     */
    @Test
    fun requestThatNeverLeftTheHostLeavesTheDestinationUntouched() {
        val device = Device(
            inbound = listOf(okay()),
            outbound = listOf(
                sendOk(),
                sendOk(),
                FakeUsbTransportHandle.Transfer.Failed(UsbTransferFailure.NOT_COMPLETED),
            ),
        )

        val outcome = device.opened().send("/sdcard/a.bin", 1) { 0 } as AdbSyncSendOutcome.Failed

        assertEquals(AdbSyncFailure.SEND_FAILED, outcome.reason)
        assertEquals(AdbSyncDestination.UNTOUCHED, outcome.destination)
        assertEquals(0L, outcome.bytesSent)
    }

    /**
     * Оборвавшийся блок не засчитывается отправленным.
     *
     * Прогон 2026-09-08 (`07` §6.36) отчитался ровно на один блок больше, чем
     * ушло в провод: счётчик прибавлялся до проверки исхода записи. Врать в
     * этом поле нельзя — ради честности именно этого числа тип и заведён.
     */
    @Test
    fun chunkWhoseFrameFailedIsNotCounted() {
        val device = Device(
            inbound = listOf(okay()),
            outbound = listOf(
                sendOk(), sendOk(),                     // OPEN
                sendOk(), sendOk(),                     // SEND
                sendOk(), sendOk(),                     // первый блок DATA целиком
                FakeUsbTransportHandle.Transfer.Failed(UsbTransferFailure.NOT_COMPLETED),
            ),
        )
        val parts = mutableListOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5))

        val outcome = device.opened().send("/sdcard/a.bin", 1) { buffer ->
            val part = parts.removeFirstOrNull() ?: return@send 0
            part.copyInto(buffer)
            part.size
        } as AdbSyncSendOutcome.Failed

        assertEquals(AdbSyncFailure.SEND_FAILED, outcome.reason)
        assertEquals(AdbSyncDestination.UNKNOWN, outcome.destination)
        assertEquals(3L, outcome.bytesSent)
    }

    @Test
    fun diagnosticsRecordTheBoundaryAndTheResult() {
        val sink = InMemoryDiagnosticSink()
        val device = Device(okay(), verdict(AdbSyncProtocol.ID_OKAY))
        val session = device.session(sink)
        session.open()

        session.send("/sdcard/a.bin", 1, source = once(byteArrayOf(1)))

        val messages = sink.snapshot().map { it.message }
        assertTrue(messages.contains("sync_send_started"))
        assertTrue(messages.contains("sync_sent"))
    }

    @Test
    fun diagnosticsNameTheDestinationStateOnFailure() {
        val sink = InMemoryDiagnosticSink()
        val device = Device(okay(), gate(afterFrames = 2), close())
        val session = device.session(sink)
        session.open()

        session.send("/sdcard/a.bin", 1, source = once(byteArrayOf(1)))

        val failure = sink.snapshot().single { it.message == "sync_send_failed" }
        assertEquals(AdbSyncDestination.UNKNOWN.name, failure.fields["destination"])
    }

    private inner class Device(
        inbound: List<List<FakeUsbTransportHandle.Transfer>>,
        outbound: List<FakeUsbTransportHandle.Transfer>,
    ) {
        constructor(vararg responses: List<FakeUsbTransportHandle.Transfer>) :
            this(responses.toList(), emptyList())

        val handle = FakeUsbTransportHandle(
            inbound = inbound.flatten().toMutableList(),
            outbound = outbound.toMutableList(),
            answerOnlyAfterRequest = true,
        )

        private val harness = harnesses.start(handle)

        fun session(diagnostics: InMemoryDiagnosticSink = InMemoryDiagnosticSink()) = AdbSyncSession(
            writer = harness.writer,
            dispatcher = harness.dispatcher,
            diagnostics = diagnostics,
            elapsedNanos = StepwiseClock(),
        )

        fun opened(): AdbSyncSession = session().also { it.open() }

        /** Рамки `sync:`, вынутые из полезной части отправленных `WRTE`. */
        fun syncFrames(): List<SyncFrame> = handle.sentFrames()
            .filter { it.command == AdbCommand.WRTE }
            .map { packet ->
                val header = AdbSyncProtocol.decodeHeader(packet.payload)
                SyncFrame(
                    id = header.id,
                    value = header.value,
                    payload = packet.payload.copyOfRange(
                        AdbSyncProtocol.HEADER_SIZE_BYTES,
                        packet.payload.size,
                    ),
                )
            }
    }

    private data class SyncFrame(val id: String, val value: Int, val payload: ByteArray)

    /** Время идёт само: каждая попытка приёма отъедает у дедлайна. */
    private class StepwiseClock : () -> Long {
        private var nanos = 0L

        override fun invoke(): Long {
            val current = nanos
            nanos += 300_000_000L
            return current
        }
    }

    private companion object {
        const val REMOTE_ID = 42
        const val LOCAL_ID = 1

        /** Источник, отдающий содержимое одним куском и затем конец. */
        fun once(content: ByteArray): (ByteArray) -> Int {
            var served = false
            return { buffer ->
                if (served) {
                    0
                } else {
                    served = true
                    content.copyInto(buffer)
                    content.size
                }
            }
        }

        fun sendOk() = FakeUsbTransportHandle.Transfer.Completed(Int.MAX_VALUE)

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

        fun data(payload: ByteArray) = packet(AdbCommand.WRTE, payload = payload)

        /** Условие: следующее отдаётся, когда host отправил столько кадров. */
        fun gate(afterFrames: Int) =
            listOf<FakeUsbTransportHandle.Transfer>(FakeUsbTransportHandle.Transfer.Gate(afterFrames))

        fun close() = packet(AdbCommand.CLSE)

        /** Вердикт без сообщения: заголовок `sync:` с нулевым числом. */
        fun verdict(id: String) = data(AdbSyncProtocol.header(id, 0))
    }
}
