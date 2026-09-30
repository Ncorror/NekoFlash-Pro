package io.github.ncorror.nekoflash.protocol.fastboot

import io.github.ncorror.nekoflash.usb.api.UsbFileSendResult
import io.github.ncorror.nekoflash.usb.api.UsbFileSender
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Выбор пути DATA OUT (D077).
 *
 * Правила — A2 `FastbootDataTransfer.resolveMode` в режиме `AUTO` и
 * `transferNativeUsbfs`: предпроверка до `download:`, её отказ — обычный путь,
 * а не ошибка; подтверждённый объём, не равный объявленному, — неизвестное
 * состояние буфера. Сама передача URB проверяется только на телефоне.
 */
class FastbootNativeDownloadTest {
    @Test
    fun aPassedPreflightSendsTheFileNatively() {
        val transport = FakeFastbootTransport().willReply("DATA00000010", "OKAY")
        transport.dataAfterCommands = 1
        val sender = FakeFileSender(transport, confirm = 16)
        transport.nativeSender = sender
        val lane = FastbootLane(transport)

        val outcome = FastbootDownload(lane).send(payload(), 16, payloadFd = FD)

        val answered = outcome as FastbootDownloadOutcome.Answered
        assertEquals(FastbootReply.OKAY, answered.reply)
        assertEquals(16L, answered.bytesSent)
        assertEquals(FastbootDataPath.NATIVE_USBFS, answered.route.path)
        assertEquals("предпроверка — до download:", 0, sender.commandsAtPreflight)
        assertEquals(listOf(FD to 16L), sender.sends)
        assertEquals("потоком ничего не ушло", 0L, transport.dataBytes)
        assertEquals(FastbootLaneState.IDLE, lane.state)
    }

    /** Отказ предпроверки — не ошибка: байты идут тем же потоком, причина — в исходе. */
    @Test
    fun aRefusedPreflightFallsBackToTheStreamBeforeDownload() {
        val transport = FakeFastbootTransport().willReply("DATA00000010", "OKAY")
        transport.dataAfterCommands = 1
        val sender = FakeFileSender(transport, confirm = 16, refusal = "backend poisoned")
        transport.nativeSender = sender

        val outcome = FastbootDownload(FastbootLane(transport)).send(payload(), 16, payloadFd = FD)

        val answered = outcome as FastbootDownloadOutcome.Answered
        assertEquals(FastbootDataRoute(FastbootDataPath.BULK, "backend poisoned"), answered.route)
        assertEquals(16L, transport.dataBytes)
        assertTrue("нативная передача не начиналась", sender.sends.isEmpty())
    }

    @Test
    fun withoutADescriptorTheNativePathIsNotAsked() {
        val transport = FakeFastbootTransport().willReply("DATA00000010", "OKAY")
        transport.dataAfterCommands = 1
        val sender = FakeFileSender(transport, confirm = 16)
        transport.nativeSender = sender

        val outcome = FastbootDownload(FastbootLane(transport)).send(payload(), 16)

        assertEquals(FastbootDataRoute.NO_DESCRIPTOR, (outcome as FastbootDownloadOutcome.Answered).route)
        assertEquals("предпроверки не было", null, sender.commandsAtPreflight)
        assertEquals(16L, transport.dataBytes)
    }

    @Test
    fun aTransportWithoutNativeSendingNamesItAndStreams() {
        val transport = FakeFastbootTransport().willReply("DATA00000010", "OKAY")
        transport.dataAfterCommands = 1

        val outcome = FastbootDownload(FastbootLane(transport)).send(payload(), 16, payloadFd = FD)

        val route = (outcome as FastbootDownloadOutcome.Answered).route
        assertEquals(FastbootDataRoute(FastbootDataPath.BULK, FastbootLane.NO_FILE_SENDER), route)
        assertEquals(16L, transport.dataBytes)
    }

    /**
     * A2 `Native USBFS DATA length mismatch`: успех backend с другим объёмом —
     * не успех. Буфер неизвестен, рамка потеряна.
     */
    @Test
    fun aConfirmedCountOtherThanDeclaredLeavesTheBufferUnknown() {
        val transport = FakeFastbootTransport().willReply("DATA00000010", "OKAY")
        transport.nativeSender = FakeFileSender(transport, confirm = 12)
        val lane = FastbootLane(transport)

        val outcome = FastbootDownload(lane).send(payload(), 16, payloadFd = FD)

        val unknown = outcome as FastbootDownloadOutcome.Unknown
        assertEquals(12L, unknown.bytesSent)
        assertEquals(16L, unknown.expectedBytes)
        assertEquals(FastbootDataPath.NATIVE_USBFS, unknown.route.path)
        assertEquals(FastbootLaneState.STALLED, lane.state)
    }

    /** Строка backend со стадией и `errno` доходит до исхода: по ней разбирают прогон. */
    @Test
    fun aBackendFailureKeepsItsDetail() {
        val transport = FakeFastbootTransport().willReply("DATA00000010")
        transport.nativeSender = FakeFileSender(transport, confirm = 4, failure = "stage=REAP errno=19")
        val lane = FastbootLane(transport)

        val outcome = FastbootDownload(lane).send(payload(), 16, payloadFd = FD)

        val unknown = outcome as FastbootDownloadOutcome.Unknown
        assertEquals(4L, unknown.bytesSent)
        assertTrue(unknown.detail, unknown.detail.contains("stage=REAP errno=19"))
        assertEquals(FastbootLaneState.STALLED, lane.state)
    }

    /** Отказ до данных не трогает ни буфер, ни нативную передачу. */
    @Test
    fun aRefusalBeforeDataSendsNothingNatively() {
        val transport = FakeFastbootTransport().willReply("FAILtoo large")
        val sender = FakeFileSender(transport, confirm = 16)
        transport.nativeSender = sender

        val outcome = FastbootDownload(FastbootLane(transport)).send(payload(), 16, payloadFd = FD)

        assertTrue(outcome is FastbootDownloadOutcome.Refused)
        assertTrue(sender.sends.isEmpty())
    }

    @Test
    fun sendFileOutsideADataPhaseIsNotReady() {
        val transport = FakeFastbootTransport()
        transport.nativeSender = FakeFileSender(transport, confirm = 16)

        val outcome = FastbootLane(transport).sendFile(FD, 16)

        assertEquals(FastbootDataOutcome.NotReady(FastbootLaneState.IDLE), outcome)
    }

    private fun payload() = ByteArrayInputStream(ByteArray(16) { it.toByte() })

    private class FakeFileSender(
        private val transport: FakeFastbootTransport,
        private val confirm: Long,
        private val refusal: String? = null,
        private val failure: String? = null,
    ) : UsbFileSender {
        var commandsAtPreflight: Int? = null
        val sends = mutableListOf<Pair<Int, Long>>()

        override fun unavailable(payloadFd: Int, totalBytes: Long): String? {
            commandsAtPreflight = transport.sent.size
            return refusal
        }

        override fun send(payloadFd: Int, totalBytes: Long, progress: (Long) -> Unit): UsbFileSendResult {
            sends += payloadFd to totalBytes
            return UsbFileSendResult(failure == null, confirm, false, failure ?: "ok")
        }

        override fun cancel(): Boolean = false
    }

    private companion object {
        const val FD = 42
    }
}
