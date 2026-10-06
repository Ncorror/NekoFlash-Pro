package io.github.ncorror.nekoflash.protocol.adb

import io.github.ncorror.nekoflash.usb.api.UsbTransferFailure
import io.github.ncorror.nekoflash.usb.api.UsbTransferResult
import io.github.ncorror.nekoflash.usb.api.UsbTransportHandle
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbTransportLifecycleTest {
    @Test
    fun lostInboundFrameStopsBothDirectionsAndKeepsTheFirstReason() {
        val handle = shortPayloadHandle()
        val transport = Transport(handle)
        val first = transport.dispatcher.open("shell:logcat").first
        val second = transport.dispatcher.open("sync:").first

        transport.loop.run()
        transport.loop.stop()

        assertEquals(AdbMailboxEnd.FRAMING_LOST, transport.loop.transportEndedBy)
        assertEnd(first, AdbMailboxEnd.FRAMING_LOST, "SHORT_PAYLOAD")
        assertEnd(second, AdbMailboxEnd.FRAMING_LOST, "SHORT_PAYLOAD")
        assertEnd(transport.dispatcher.open("shell:id").first, AdbMailboxEnd.FRAMING_LOST, "SHORT_PAYLOAD")
        assertTrue(transport.router.activeStreamIds.isEmpty())
        transport.dispatcher.inboundStreams(object : AdbInboundStreams {
            override fun wants(service: String): Boolean = error("late packet reached the handler")
            override fun accepted(service: String, mailbox: AdbStreamMailbox) = error("late stream was accepted")
        })
        assertTrue(transport.dispatcher.dispatch(AdbPacket(AdbCommand.OPEN, 7, 0, "late\u0000".toByteArray())).isEmpty())
        assertTrue(transport.dispatcher.activeMailboxes.isEmpty())
        assertEquals(AdbWriteOutcome.Closed, transport.writer.write(AdbCommand.CLSE, 1, 1))
        assertEquals(AdbReadOutcome.Closed, transport.reader.read())
        assertEquals(listOf(24, 64), handle.receiveWindows)
        assertTrue(handle.sentBytes.isEmpty())
        assertEquals(1, transport.ends.size)
    }

    @Test
    fun serviceWriteFailureEndsOtherStreamsAndCannotSendCleanupFrames() {
        val handle = FakeUsbTransportHandle(
            outbound = mutableListOf(
                completed(ByteArray(24)),
                failed(UsbTransferFailure.NOT_COMPLETED),
            ),
        )
        val transport = Transport(handle)
        val peer = transport.dispatcher.open("shell:logcat").first
        val call = AdbServiceCall(transport.writer, transport.dispatcher)

        val outcome = call.run("shell:id") as AdbServiceOutcome.Failed

        assertEquals(AdbServiceFailure.SEND_FAILED, outcome.reason)
        assertEnd(peer, AdbMailboxEnd.FRAMING_LOST, "write interrupted after 24 bytes")
        assertFalse(transport.loop.active)
        assertEquals(AdbMailboxEnd.FRAMING_LOST, transport.loop.transportEndedBy)
        assertEquals(listOf(24), handle.sentBytes.map { it.size })
        assertEquals(AdbWriteOutcome.Closed, transport.writer.write(AdbCommand.OPEN, 3, 0))
        assertEquals(1, transport.ends.size)
    }

    @Test
    fun closedOutputEndsTheWholeTransportWithoutFurtherUsbCalls() {
        val handle = FakeUsbTransportHandle(outbound = mutableListOf(failed(UsbTransferFailure.NOT_HELD)))
        val transport = Transport(handle)
        val box = transport.dispatcher.open("sync:").first

        assertEquals(AdbWriteOutcome.Closed, transport.writer.write(AdbCommand.OPEN, 1, 0))

        assertEnd(box, AdbMailboxEnd.TRANSPORT_CLOSED, "write: transport closed")
        transport.loop.run()
        assertTrue(handle.receiveWindows.isEmpty())
        assertEquals(1, transport.ends.size)
    }

    @Test
    fun invalidOutputLengthsPoisonTheWriterBeforeTheNextFrame() {
        for (invalid in listOf(0, -1, 25)) {
            val delegate = FakeUsbTransportHandle()
            val calls = AtomicInteger()
            val handle = object : UsbTransportHandle by delegate {
                override fun send(source: ByteArray, offset: Int, length: Int, timeoutMillis: Int): UsbTransferResult {
                    calls.incrementAndGet()
                    return UsbTransferResult.Completed(invalid)
                }
            }
            val transport = Transport(handle)
            val box = transport.dispatcher.open("sync:").first

            val outcome = transport.writer.write(AdbCommand.WRTE, 1, 1) as AdbWriteOutcome.Interrupted

            assertEquals(0, outcome.sentBytes)
            assertEnd(box, AdbMailboxEnd.FRAMING_LOST, "transfer returned $invalid bytes")
            assertEquals(AdbWriteOutcome.Closed, transport.writer.write(AdbCommand.CLSE, 1, 1))
            assertEquals(1, calls.get())
            assertEquals(1, transport.ends.size)
        }
    }

    @Test
    fun outputExceptionReportsConfirmedBytesAndDoesNotExportItsMessage() {
        val delegate = FakeUsbTransportHandle()
        val calls = AtomicInteger()
        val handle = object : UsbTransportHandle by delegate {
            override fun send(source: ByteArray, offset: Int, length: Int, timeoutMillis: Int): UsbTransferResult {
                if (calls.incrementAndGet() == 2) throw IllegalStateException(PRIVATE_MESSAGE)
                return delegate.send(source, offset, length, timeoutMillis)
            }
        }
        val transport = Transport(handle)
        val box = transport.dispatcher.open("shell:logcat").first

        val outcome = transport.writer.write(AdbCommand.WRTE, 1, 1, ByteArray(100)) as AdbWriteOutcome.Interrupted

        assertEquals(24, outcome.sentBytes)
        assertTrue(outcome.detail.contains("IllegalStateException"))
        assertFalse(outcome.detail.contains(PRIVATE_MESSAGE))
        assertEnd(box, AdbMailboxEnd.FRAMING_LOST, "send threw IllegalStateException")
        assertFalse(transport.ends.single().detail.contains(PRIVATE_MESSAGE))
        assertEquals(AdbWriteOutcome.Closed, transport.writer.write(AdbCommand.CLSE, 1, 1))
        assertEquals(2, calls.get())
    }

    @Test
    fun inputExceptionsInHeaderOrPayloadWakeEveryStream() {
        for (failAt in listOf(1, 2)) {
            val payload = ByteArray(64)
            val delegate = FakeUsbTransportHandle(
                inbound = mutableListOf(completed(header(AdbCommand.WRTE, 1, 1, payload))),
            )
            val calls = AtomicInteger()
            val handle = object : UsbTransportHandle by delegate {
                override fun receive(
                    destination: ByteArray,
                    offset: Int,
                    length: Int,
                    timeoutMillis: Int,
                ): UsbTransferResult {
                    if (calls.incrementAndGet() == failAt) throw IllegalStateException(PRIVATE_MESSAGE)
                    return delegate.receive(destination, offset, length, timeoutMillis)
                }
            }
            val transport = Transport(handle)
            val box = transport.dispatcher.open("shell:logcat").first

            transport.loop.run()

            assertEnd(box, AdbMailboxEnd.FRAMING_LOST, "TRANSFER_EXCEPTION receive threw IllegalStateException")
            assertFalse(transport.ends.single().detail.contains(PRIVATE_MESSAGE))
            assertEquals(AdbReadOutcome.Closed, transport.reader.read())
            assertEquals(AdbWriteOutcome.Closed, transport.writer.write(AdbCommand.CLSE, 1, 1))
            assertEquals(failAt, calls.get())
            assertTrue(delegate.sentBytes.isEmpty())
        }
    }

    @Test
    fun dispatcherExceptionAlsoClosesPendingStreams() {
        val service = "localabstract:test\u0000".toByteArray()
        val handle = FakeUsbTransportHandle(
            inbound = mutableListOf(completed(header(AdbCommand.OPEN, 7, 0, service)), completed(service)),
        )
        val transport = Transport(handle)
        val box = transport.dispatcher.open("sync:").first
        transport.dispatcher.inboundStreams(object : AdbInboundStreams {
            override fun wants(service: String): Boolean = throw IllegalStateException(PRIVATE_MESSAGE)
            override fun accepted(service: String, mailbox: AdbStreamMailbox) = Unit
        })

        transport.loop.run()

        assertEnd(box, AdbMailboxEnd.FRAMING_LOST, "dispatch threw IllegalStateException")
        assertFalse(transport.ends.single().detail.contains(PRIVATE_MESSAGE))
        assertEquals(AdbWriteOutcome.Closed, transport.writer.write(AdbCommand.CLSE, 1, 1))
        assertTrue(handle.sentBytes.isEmpty())
    }

    @Test(timeout = 5_000)
    fun requestedStopWakesMailboxesBeforeBlockedReceiveReturns() {
        val delegate = FakeUsbTransportHandle()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val handle = object : UsbTransportHandle by delegate {
            override fun receive(
                destination: ByteArray,
                offset: Int,
                length: Int,
                timeoutMillis: Int,
            ): UsbTransferResult {
                calls.incrementAndGet()
                entered.countDown()
                await(release)
                header(AdbCommand.WRTE, 1, 1, ByteArray(64)).copyInto(destination, offset)
                return UsbTransferResult.Completed(24)
            }
        }
        val transport = Transport(handle)
        val box = transport.dispatcher.open("shell:logcat").first
        val worker = thread(isDaemon = true) { transport.loop.run() }
        try {
            await(entered)
            transport.loop.stop()

            assertEnd(box, AdbMailboxEnd.TRANSPORT_CLOSED, "dispatch loop stopped")
            assertTrue(worker.isAlive)
            assertEquals(null, transport.loop.transportEndedBy)
            assertTrue(transport.ends.isEmpty())
            assertEquals(AdbWriteOutcome.Closed, transport.writer.write(AdbCommand.CLSE, 1, 1))
        } finally {
            release.countDown()
            worker.join(1_000)
        }
        assertFalse(worker.isAlive)
        assertEquals(1, calls.get())
        assertTrue(delegate.sentBytes.isEmpty())
        assertTrue(transport.ends.isEmpty())
    }

    @Test(timeout = 5_000)
    fun inputFailureCancelsRemainingOutputChunksAndQueuedFrames() {
        val delegate = shortPayloadHandle()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val handle = object : UsbTransportHandle by delegate {
            override fun send(source: ByteArray, offset: Int, length: Int, timeoutMillis: Int): UsbTransferResult {
                if (calls.incrementAndGet() == 2) {
                    entered.countDown()
                    await(release)
                }
                return delegate.send(source, offset, length, timeoutMillis)
            }
        }
        val transport = Transport(handle)
        val firstResult = AtomicReference<AdbWriteOutcome>()
        val queuedResult = AtomicReference<AdbWriteOutcome>()
        val first = thread(isDaemon = true) {
            firstResult.set(transport.writer.write(AdbCommand.WRTE, 1, 1, ByteArray(40_000)))
        }
        var queued: Thread? = null
        try {
            await(entered)
            queued = thread(isDaemon = true) {
                queuedResult.set(transport.writer.write(AdbCommand.OPEN, 2, 0, "sync:".toByteArray()))
            }
            transport.loop.run()
            assertFalse(transport.loop.active)
            assertTrue(first.isAlive)
        } finally {
            release.countDown()
            first.join(1_000)
            queued?.join(1_000)
        }
        assertFalse(first.isAlive)
        assertFalse(queued.isAlive)
        assertEquals(AdbWriteOutcome.Closed, firstResult.get())
        assertEquals(AdbWriteOutcome.Closed, queuedResult.get())
        assertEquals(listOf(24, 16_384), delegate.sentBytes.map { it.size })
        assertEquals(2, calls.get())
        assertEquals(1, transport.ends.size)
        assertTrue(transport.ends.single().detail.startsWith("SHORT_PAYLOAD"))
    }

    @Test(timeout = 5_000)
    fun failureCallbackRunsOutsideTheFrameLockAndMayCloseReentrantly() {
        val delegate = FakeUsbTransportHandle(outbound = mutableListOf(failed(UsbTransferFailure.NOT_COMPLETED)))
        lateinit var transport: Transport
        val nestedResult = AtomicReference<AdbWriteOutcome>()
        transport = Transport(delegate) {
            transport.loop.stop()
            val nested = thread(isDaemon = true) {
                nestedResult.set(transport.writer.write(AdbCommand.CLSE, 1, 1))
            }
            nested.join(1_000)
            assertFalse("callback held the writer lock", nested.isAlive)
        }

        assertTrue(transport.writer.write(AdbCommand.WRTE, 1, 1) is AdbWriteOutcome.Interrupted)

        assertEquals(AdbWriteOutcome.Closed, nestedResult.get())
        assertEquals(AdbMailboxEnd.FRAMING_LOST, transport.loop.transportEndedBy)
        assertEquals(1, transport.ends.size)
        assertTrue(delegate.sentBytes.isEmpty())
    }

    @Test
    fun invalidArgumentsBeforeUsbDoNotEndTheTransport() {
        val delegate = FakeUsbTransportHandle(inbound = mutableListOf(completed(header(AdbCommand.OKAY))))
        val transport = Transport(delegate)

        assertTrue(runCatching { transport.writer.write(AdbCommand.WRTE, 1, 1, timeoutMillis = -1) }
            .exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { transport.writer.write(-1, 1, 1) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { transport.reader.read(-1) }.exceptionOrNull() is IllegalArgumentException)

        assertTrue(transport.ends.isEmpty())
        assertTrue(transport.loop.active)
        assertTrue(delegate.receiveWindows.isEmpty())
        assertTrue(delegate.sentBytes.isEmpty())
        assertTrue(transport.reader.read() is AdbReadOutcome.Received)
        assertEquals(AdbWriteOutcome.Sent, transport.writer.write(AdbCommand.OKAY, 1, 1))
    }

    @Test(timeout = 5_000)
    fun writerFailureCanRaceWithAReentrantStopFromTheDispatcher() {
        val service = "localabstract:test\u0000".toByteArray()
        val delegate = FakeUsbTransportHandle(
            inbound = mutableListOf(completed(header(AdbCommand.OPEN, 7, 0, service)), completed(service)),
            outbound = mutableListOf(failed(UsbTransferFailure.NOT_COMPLETED)),
        )
        val transport = Transport(delegate)
        val box = transport.dispatcher.open("sync:").first
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val accepted = AtomicInteger()
        transport.dispatcher.inboundStreams(object : AdbInboundStreams {
            override fun wants(service: String): Boolean {
                entered.countDown()
                await(release)
                transport.loop.stop()
                return true
            }

            override fun accepted(service: String, mailbox: AdbStreamMailbox) {
                accepted.incrementAndGet()
            }
        })
        val reader = thread(isDaemon = true) { transport.loop.run() }
        var writer: Thread? = null
        try {
            await(entered)
            writer = thread(isDaemon = true) { transport.writer.write(AdbCommand.WRTE, 1, 1) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (transport.loop.transportEndedBy == null && System.nanoTime() < deadline) Thread.yield()
            assertEquals(AdbMailboxEnd.FRAMING_LOST, transport.loop.transportEndedBy)
        } finally {
            release.countDown()
            reader.join(1_000)
            writer?.join(1_000)
        }

        assertFalse("dispatcher and lifecycle locks deadlocked", reader.isAlive)
        assertFalse("writer could not finish", writer.isAlive)
        assertEquals(0, accepted.get())
        assertEnd(box, AdbMailboxEnd.FRAMING_LOST, "write interrupted")
        assertTrue(transport.dispatcher.activeMailboxes.isEmpty())
        assertTrue(transport.router.activeStreamIds.isEmpty())
        assertEquals(1, transport.ends.size)
        assertTrue(delegate.sentBytes.isEmpty())
    }

    @Test(timeout = 15_000)
    fun sustainedLargeOutputKeepsFrameBoundariesAndAllBytes() {
        val payload = ByteArray(65_536) { (it % 251).toByte() }
        val writeHeader = header(AdbCommand.WRTE, 7, 1, payload)
        val transfers = mutableListOf<FakeUsbTransportHandle.Transfer>(completed(header(AdbCommand.OKAY, 7, 1)))
        repeat(2_000) {
            transfers += failed(UsbTransferFailure.NOT_COMPLETED)
            transfers += completed(writeHeader)
            transfers += completed(payload)
        }
        transfers += completed(header(AdbCommand.CLSE, 7, 1))
        transfers += failed(UsbTransferFailure.NOT_HELD)
        val delegate = FakeUsbTransportHandle(inbound = transfers)
        lateinit var mailbox: AdbStreamMailbox
        var acknowledged = 0
        var receivedBytes = 0L
        val handle = object : UsbTransportHandle by delegate {
            override fun send(source: ByteArray, offset: Int, length: Int, timeoutMillis: Int): UsbTransferResult {
                val result = delegate.send(source, offset, length, timeoutMillis)
                val decoded = AdbPacketHeader.decode(source.copyOfRange(offset, offset + length), 0)
                if (decoded is AdbHeaderDecoding.Decoded && decoded.header.command == AdbCommand.OKAY) {
                    var item = mailbox.poll(0)
                    if (item is AdbMailboxItem.Opened) item = mailbox.poll(0)
                    val data = item as AdbMailboxItem.Data
                    assertArrayEquals(payload, data.payload)
                    receivedBytes += data.payload.size
                    acknowledged += 1
                }
                return result
            }
        }
        val transport = Transport(handle)
        mailbox = transport.dispatcher.open("shell:logcat").first

        transport.loop.run()

        assertEquals(2_000, acknowledged)
        assertEquals(131_072_000L, receivedBytes)
        assertEnd(mailbox, AdbMailboxEnd.COMPLETED, "device closed the stream")
        assertEquals(2_000, delegate.receiveWindows.count { it == payload.size })
        assertEquals(AdbMailboxEnd.TRANSPORT_CLOSED, transport.ends.single().reason)
    }

    private class Transport(handle: UsbTransportHandle, onEnd: () -> Unit = { }) {
        val reader = AdbPacketReader(handle, AdbInboundFraming.MODERN_MAX_PAYLOAD_BYTES)
        val writer = AdbPacketWriter(handle)
        val router = AdbStreamRouter()
        val dispatcher = AdbStreamDispatcher(router)
        val ends = ConcurrentLinkedQueue<AdbMailboxItem.Ended>()
        val loop = AdbDispatchLoop(reader, writer, dispatcher) { reason, detail ->
            ends += AdbMailboxItem.Ended(reason, detail)
            onEnd()
        }
    }

    private companion object {
        const val PRIVATE_MESSAGE = "private payload and /private/document/path"

        fun shortPayloadHandle(): FakeUsbTransportHandle {
            val payload = ByteArray(64)
            return FakeUsbTransportHandle(
                inbound = mutableListOf(
                    completed(header(AdbCommand.WRTE, 1, 1, payload)),
                    FakeUsbTransportHandle.Transfer.Completed(32, payload),
                ),
            )
        }

        fun completed(bytes: ByteArray) = FakeUsbTransportHandle.Transfer.Completed(bytes.size, bytes)

        fun failed(reason: UsbTransferFailure) = FakeUsbTransportHandle.Transfer.Failed(reason)

        fun await(latch: CountDownLatch) {
            assertTrue("test barrier timed out", latch.await(2, TimeUnit.SECONDS))
        }

        fun assertEnd(mailbox: AdbStreamMailbox, reason: AdbMailboxEnd, detail: String) {
            val end = mailbox.poll(0) as AdbMailboxItem.Ended
            assertEquals(reason, end.reason)
            assertTrue("${end.detail} did not contain $detail", end.detail.contains(detail))
        }
    }
}
