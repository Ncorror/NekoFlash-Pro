package io.github.ncorror.nekoflash.protocol.adb

import io.github.ncorror.nekoflash.core.diagnostics.InMemoryDiagnosticSink
import io.github.ncorror.nekoflash.usb.api.UsbTransferFailure
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class AdbForwardStreamTest {
    private val harnesses = AdbDispatchHarnesses()
    private val workers = mutableListOf<Thread>()

    @After
    fun stopEverything() {
        workers.forEach { worker -> worker.join(JOIN_MS) }
        workers.clear()
        harnesses.stopAll()
    }

    @Test
    fun openCarriesTheAddressAsTypedAndWaitsForConfirmation() {
        val device = ScriptedDevice(okay())

        val live = device.forward().open(ADDRESS)

        assertNull("подтверждённый проброс не имеет итога", live)
        val open = device.handle.awaitSentFrames(1).single()
        assertEquals(AdbCommand.OPEN, open.command)
        assertEquals("$ADDRESS\u0000", open.payload.decodeToString())
    }

    /** Адрес не разбирается и не проверяется: что бывает на той стороне, знает устройство. */
    @Test
    fun anyAddressIsPassedThroughUnchanged() {
        val device = ScriptedDevice(okay())

        device.forward().open("localabstract:chrome_devtools_remote")

        assertEquals(
            "localabstract:chrome_devtools_remote\u0000",
            device.handle.awaitSentFrames(1).single().payload.decodeToString(),
        )
    }

    /** Закрытие до подтверждения — отказ адреса, а не пустое соединение. */
    @Test
    fun anAddressThatDoesNotAnswerIsARefusal() {
        val device = ScriptedDevice(close())

        val outcome = device.forward().open(ADDRESS)

        assertEquals(AdbForwardEnd.REJECTED, outcome?.end)
    }

    /** Молчание устройства — тоже отказ: ждать открытия вечно нельзя. */
    @Test
    fun silenceOnOpenEndsWithARefusal() {
        val device = ScriptedDevice()

        val outcome = device.forward().open(ADDRESS, timeoutMillis = OPEN_MS)

        assertEquals(AdbForwardEnd.REJECTED, outcome?.end)
    }

    @Test
    fun bytesFromTheDeviceReachTheClient() {
        val device = ScriptedDevice(okay(), write("hello"), write(" there"), close())
        val channel = RecordingChannel()
        val forward = device.forward(channel)
        forward.open(ADDRESS)

        val outcome = forward.fromDevice()

        assertEquals("hello there", channel.written())
        assertEquals(AdbForwardEnd.DEVICE_CLOSED, outcome.end)
    }

    @Test
    fun bytesFromTheClientReachTheDevice() {
        val device = ScriptedDevice(okay())
        val channel = RecordingChannel(reads = listOf("GET / HTTP/1.1"))
        val forward = device.forward(channel)
        forward.open(ADDRESS)

        pump(forward)

        val write = device.handle.awaitSentFrames(2).last { it.command == AdbCommand.WRTE }
        assertEquals("GET / HTTP/1.1", write.payload.decodeToString())
    }

    /** Кадр не должен превышать объявленный peer'ом `maxdata`. */
    @Test
    fun clientBytesAreSplitByTheDeclaredChunk() {
        val device = ScriptedDevice(okay())
        val channel = RecordingChannel(reads = listOf("0123456789"))
        val forward = device.forward(channel, maxPayload = 4)
        forward.open(ADDRESS)

        pump(forward)

        val writes = device.handle.awaitSentFrames(4).filter { it.command == AdbCommand.WRTE }
        assertTrue("кадр крупнее объявленного: $writes", writes.all { it.payload.size <= 4 })
        assertEquals("0123456789", writes.joinToString("") { it.payload.decodeToString() })
    }

    @Test
    fun theClientClosingClosesTheStream() {
        val device = ScriptedDevice(okay())
        val channel = RecordingChannel(reads = listOf("bye"))
        val forward = device.forward(channel)
        forward.open(ADDRESS)

        pump(forward)

        assertEquals(AdbForwardEnd.CLIENT_CLOSED, forward.finished?.end)
        assertTrue(
            "потоку устройства должно уйти CLSE",
            device.handle.awaitSentFrames(3).any { it.command == AdbCommand.CLSE },
        )
    }

    @Test
    fun theDeviceClosingClosesTheClient() {
        val device = ScriptedDevice(okay(), close())
        val channel = RecordingChannel()
        val forward = device.forward(channel)
        forward.open(ADDRESS)

        forward.fromDevice()

        assertTrue("канал клиента должен быть закрыт", channel.closed)
    }

    /** Потеря кадра — не «клиент ушёл»: соединение дальше недостоверно целиком. */
    @Test
    fun aLostFrameIsNotMistakenForAClosedClient() {
        val device = ScriptedDevice(okay(), shortPayload())
        val forward = device.forward()
        forward.open(ADDRESS)

        assertEquals(AdbForwardEnd.FRAMING_LOST, forward.fromDevice().end)
    }

    @Test
    fun aReleasedInterfaceIsReportedAsSuch() {
        val device = ScriptedDevice(okay(), failedRead(UsbTransferFailure.NOT_HELD))
        val forward = device.forward()
        forward.open(ADDRESS)

        assertEquals(AdbForwardEnd.TRANSPORT_CLOSED, forward.fromDevice().end)
    }

    /**
     * Первая названная причина побеждает.
     *
     * Встречное направление узнаёт о конце по закрытому каналу, и его
     * `CLIENT_FAILED` затёр бы настоящую причину — а оператору они говорят
     * разное.
     */
    @Test
    fun theFirstReasonWinsOverTheEchoOfItself() {
        val device = ScriptedDevice(okay(), close())
        val channel = RecordingChannel(reads = listOf("still typing"))
        val forward = device.forward(channel)
        forward.open(ADDRESS)

        val outcome = forward.fromDevice()
        forward.fromClient()

        assertEquals(AdbForwardEnd.DEVICE_CLOSED, outcome.end)
        assertEquals(AdbForwardEnd.DEVICE_CLOSED, forward.finished?.end)
    }

    /** Оборванный канал клиента — не обрыв транспорта: устройство ни при чём. */
    @Test
    fun aBrokenClientChannelIsItsOwnReason() {
        val device = ScriptedDevice(okay())
        val channel = RecordingChannel(failReadWith = IOException("broken pipe"))
        val forward = device.forward(channel)
        forward.open(ADDRESS)

        forward.fromClient()

        assertEquals(AdbForwardEnd.CLIENT_FAILED, forward.finished?.end)
        assertEquals("broken pipe", forward.finished?.detail)
    }

    /**
     * Порядок здесь задан явно, а не подразумевается.
     *
     * Оба направления работают одновременно, поэтому «сначала клиент, потом
     * устройство» из расстановки вызовов не следует: кто успеет первым, решает
     * планировщик, а закрывающий записывает счётчики такими, какими они на тот
     * момент оказались. Условие [FakeUsbTransportHandle.Transfer.Gate] держит
     * ответ устройства, пока кадр клиента не ушёл, и канал не сообщает о конце
     * сам — тогда итог подводит устройство, и подводит после обеих сторон.
     */
    @Test
    fun bothDirectionsAreCounted() {
        val device = ScriptedDevice(okay(), gate(2), write("four"), close())
        val channel = RecordingChannel(reads = listOf("seven!!"), keepOpen = true)
        val forward = device.forward(channel)
        forward.open(ADDRESS)
        pumpAsync(forward)

        val outcome = forward.fromDevice()

        assertEquals(AdbForwardEnd.DEVICE_CLOSED, outcome.end)
        assertEquals(7L, outcome.fromClient)
        assertEquals(4L, outcome.fromDevice)
    }

    @Test
    fun aLiveForwardCanBeTakenDownFromOutside() {
        val device = ScriptedDevice(okay())
        val channel = RecordingChannel()
        val forward = device.forward(channel)
        forward.open(ADDRESS)

        forward.cancel("operator removed the forward")

        assertEquals(AdbForwardEnd.CLIENT_CLOSED, forward.finished?.end)
        assertTrue(channel.closed)
    }

    @Test
    fun theJournalNamesBothEndsOfTheConnection() {
        val sink = InMemoryDiagnosticSink()
        val device = ScriptedDevice(okay(), write("x"), close())
        val forward = device.forward(diagnostics = sink)
        forward.open(ADDRESS)
        forward.fromDevice()

        assertEquals(
            listOf("forward_open", "forward_closed"),
            sink.snapshot().map { it.message },
        )
    }

    /** Качает сторону клиента и дожидается её конца. */
    private fun pump(forward: AdbForwardStream) {
        pumpAsync(forward).join(JOIN_MS)
    }

    /** Качает сторону клиента, не дожидаясь: конец подведёт встречное направление. */
    private fun pumpAsync(forward: AdbForwardStream): Thread {
        val worker = thread(name = "forward-client-test", isDaemon = true) { forward.fromClient() }
        workers += worker
        return worker
    }

    /** Канал клиента, не знающий ни про сокеты, ни про устройство. */
    private class RecordingChannel(
        reads: List<String> = emptyList(),
        private val failReadWith: IOException? = null,
        /**
         * Не сообщать о конце, исчерпав записанное.
         *
         * Настоящий сокет молчит, пока его не закроют, и тест, которому важен
         * порядок, обязан вести себя так же: иначе сторона клиента объявит
         * конец соединения просто потому, что ей больше нечего сказать.
         */
        private val keepOpen: Boolean = false,
    ) : AdbByteChannel {
        private val pending = ArrayBlockingQueue<ByteArray>(QUEUE, false, reads.map { it.toByteArray() })
        private val received = StringBuilder()
        private val shut = CountDownLatch(1)

        @Volatile
        var closed = false
            private set

        override fun read(destination: ByteArray): Int {
            failReadWith?.let { failure -> throw failure }
            val next = pending.poll(0, TimeUnit.MILLISECONDS) ?: return exhausted()
            val moved = minOf(next.size, destination.size)
            next.copyInto(destination, 0, 0, moved)
            if (moved < next.size) pending.offer(next.copyOfRange(moved, next.size))
            return moved
        }

        private fun exhausted(): Int {
            if (keepOpen) shut.await(JOIN_MS, TimeUnit.MILLISECONDS)
            return -1
        }

        @Synchronized
        override fun write(source: ByteArray, length: Int) {
            received.append(String(source, 0, length, Charsets.UTF_8))
        }

        override fun close() {
            closed = true
            shut.countDown()
        }

        @Synchronized
        fun written(): String = received.toString()

        private companion object {
            const val QUEUE = 16
        }
    }

    private inner class ScriptedDevice(vararg responses: List<FakeUsbTransportHandle.Transfer>) {
        val handle = FakeUsbTransportHandle(
            inbound = responses.flatMap { it }.toMutableList(),
            answerOnlyAfterRequest = true,
        )

        private val harness = harnesses.start(handle)

        fun forward(
            channel: AdbByteChannel = RecordingChannel(),
            maxPayload: Int = AdbForwardStream.MAX_CHUNK_BYTES,
            diagnostics: InMemoryDiagnosticSink = InMemoryDiagnosticSink(),
        ) = AdbForwardStream(
            writer = harness.writer,
            dispatcher = harness.dispatcher,
            channel = channel,
            maxPayload = maxPayload,
            diagnostics = diagnostics,
        )
    }

    private companion object {
        const val ADDRESS = "tcp:5555"
        const val REMOTE_ID = 42
        const val LOCAL_ID = 1
        const val OPEN_MS = 60
        const val JOIN_MS = 5_000L

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

        /** Держит следующий ответ, пока host не отправит [frames] кадров. */
        fun gate(frames: Int) =
            listOf<FakeUsbTransportHandle.Transfer>(FakeUsbTransportHandle.Transfer.Gate(frames))

        fun failedRead(reason: UsbTransferFailure) =
            listOf<FakeUsbTransportHandle.Transfer>(FakeUsbTransportHandle.Transfer.Failed(reason))

        /** Объявленный payload не пришёл целиком: рамка потеряна. */
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
