package io.github.ncorror.nekoflash.adb

import io.github.ncorror.nekoflash.protocol.adb.AdbCommand
import io.github.ncorror.nekoflash.protocol.adb.AdbConnection
import io.github.ncorror.nekoflash.protocol.adb.AdbKeyStore
import io.github.ncorror.nekoflash.protocol.adb.AdbPacketHeader
import io.github.ncorror.nekoflash.protocol.adb.AdbShellMode
import io.github.ncorror.nekoflash.usb.api.UsbDeviceDescriptor
import io.github.ncorror.nekoflash.usb.api.UsbEndpointDescriptor
import io.github.ncorror.nekoflash.usb.api.UsbEndpointDirection
import io.github.ncorror.nekoflash.usb.api.UsbInterfaceCandidate
import io.github.ncorror.nekoflash.usb.api.UsbInterfaceKind
import io.github.ncorror.nekoflash.usb.api.UsbMatchConfidence
import io.github.ncorror.nekoflash.usb.api.UsbTransferFailure
import io.github.ncorror.nekoflash.usb.api.UsbTransferResult
import io.github.ncorror.nekoflash.usb.api.UsbTransferType
import io.github.ncorror.nekoflash.usb.api.UsbTransportHandle
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbTerminalControllerTest {
    @Test
    fun startQueuesOpenInsteadOfDoingUsbOnCallerThread() = withConnection { connection, handle ->
        val reader = RecordingExecutor()
        val writer = RecordingExecutor()
        val controller = AdbTerminalController(reader, writer)

        controller.start(connection)

        assertTrue(controller.state.value.active)
        assertFalse(controller.state.value.ready)
        assertEquals(0, handle.sendCalls)
        assertEquals(1, reader.taskCount)
        assertEquals(0, writer.taskCount)
    }

    @Test
    fun stopBeforeQueuedOpenCancelsWithoutTouchingUsb() = withConnection { connection, handle ->
        val reader = RecordingExecutor()
        val writer = RecordingExecutor()
        val controller = AdbTerminalController(reader, writer)
        controller.start(connection)

        controller.stop()
        reader.runSingle()

        assertFalse(controller.active)
        assertFalse(controller.state.value.active)
        assertFalse(controller.state.value.ready)
        assertEquals("closed", controller.state.value.ended)
        assertEquals(0, handle.sendCalls)
        assertEquals(0, writer.taskCount)
    }

    @Test
    fun inputBeforeOkayIsNotQueuedForWriting() = withConnection { connection, handle ->
        val reader = RecordingExecutor()
        val writer = RecordingExecutor()
        val controller = AdbTerminalController(reader, writer)
        controller.start(connection)

        controller.sendInput("id")
        controller.interrupt()

        assertEquals(0, handle.sendCalls)
        assertEquals(0, writer.taskCount)
    }

    @Test
    fun stopDuringBlockingOpenQueuesCloseOnlyAfterOpenCompletes() {
        val handle = CountingHandle(blockFirstSend = true)
        withConnection(handle) { connection, _ ->
            val reader = Executors.newSingleThreadExecutor()
            val writer = RecordingExecutor()
            val controller = AdbTerminalController(reader, writer)
            try {
                controller.start(connection)
                assertTrue(handle.firstSendStarted.await(2, TimeUnit.SECONDS))

                controller.stop()

                assertTrue(controller.state.value.active)
                assertTrue(controller.state.value.closing)
                assertFalse(controller.state.value.ready)
                assertEquals(0, writer.taskCount)

                // OPEN is still blocked. Completing the write is not enough
                // to close: before OKAY the remoteId is unknown, so a valid
                // CLSE cannot be formed.
                handle.releaseFirstSend.countDown()
                assertTrue(eventually { handle.sendCalls >= 2 })
                assertEquals(0, writer.taskCount)

                handle.enqueueOkay(localId = 1, remoteId = 42)
                assertTrue(eventually { writer.taskCount == 1 })
                writer.runSingle()
                assertTrue(eventually { !controller.active })
                assertFalse(controller.state.value.active)
                assertFalse(controller.state.value.closing)
            } finally {
                handle.releaseFirstSend.countDown()
                reader.shutdownNow()
            }
        }
    }

    /** `adb shell -T`: оболочка открывается без PTY-профиля. */
    @Test
    fun rawShellOpensWithoutPtySetup() = withConnection { connection, handle ->
        val reader = Executors.newSingleThreadExecutor()
        val writer = RecordingExecutor()
        val controller = AdbTerminalController(reader, writer)
        try {
            controller.start(connection, mode = AdbShellMode.RAW)

            assertTrue(eventually { handle.sendCalls >= 1 })
            val open = synchronized(handle.sent) { handle.sent.joinToString("") { String(it, Charsets.ISO_8859_1) } }
            assertTrue(open.contains("shell:\u0000"))
        } finally {
            controller.stop()
            reader.shutdownNow()
        }
    }

    /**
     * `adb logcat` из поля команды: строка уходит в оболочку сразу после
     * `OKAY`, а не раньше — до подтверждения писать в поток некуда.
     */
    @Test
    fun firstLineIsWrittenOnlyAfterOkay() = withConnection { connection, handle ->
        val reader = Executors.newSingleThreadExecutor()
        val writer = RecordingExecutor()
        val controller = AdbTerminalController(reader, writer)
        try {
            controller.start(connection, firstLine = "logcat")
            assertTrue(eventually { handle.sendCalls >= 1 })
            assertEquals(0, writer.taskCount)

            handle.enqueueOkay(localId = 1, remoteId = 42)

            // У этой подставной связи нет feature shell_v2: resize не отправляется,
            // а первая строка уходит как обычные байты legacy shell: после OKAY.
            assertTrue(eventually { writer.taskCount == 2 })
            val before = handle.sendCalls
            writer.runAll()
            assertEquals(before + 2, handle.sendCalls)
            val inputPayload = synchronized(handle.sent) { handle.sent.last().copyOf() }
            assertEquals("logcat\n", inputPayload.decodeToString())
        } finally {
            controller.stop()
            reader.shutdownNow()
        }
    }

    @Test
    fun typedCommandReachesLegacyShellAfterOkay() = withConnection { connection, handle ->
        val reader = Executors.newSingleThreadExecutor()
        val writer = RecordingExecutor()
        val controller = AdbTerminalController(reader, writer)
        try {
            controller.start(connection)
            assertTrue(eventually { handle.sendCalls >= 2 })
            handle.enqueueOkay(localId = 1, remoteId = 42)
            assertTrue(eventually { controller.state.value.ready && writer.taskCount == 1 })
            writer.runSingle() // resize is unavailable without shell_v2
            controller.sendInput("id")
            assertEquals(1, writer.taskCount)
            writer.runSingle()
            val payload = synchronized(handle.sent) { handle.sent.last().copyOf() }
            assertEquals("id\n", payload.decodeToString())
        } finally {
            controller.stop()
            reader.shutdownNow()
        }
    }

    private fun withConnection(
        handle: CountingHandle = CountingHandle(),
        block: (AdbConnection, CountingHandle) -> Unit,
    ) {
        val directory = createTempDirectory("nekoflash-terminal-").toFile()
        try {
            block(
                AdbConnection(
                    handle = handle,
                    keyStore = AdbKeyStore(File(directory, "keys")),
                    apiLevel = 35,
                ),
                handle,
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun eventually(condition: () -> Boolean): Boolean {
        repeat(200) {
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    private class RecordingExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()

        val taskCount: Int
            @Synchronized get() = tasks.size

        @Synchronized
        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        @Synchronized
        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }

        @Synchronized
        fun runSingle() {
            check(tasks.size == 1) { "expected exactly one task, got ${tasks.size}" }
            tasks.removeFirst().run()
        }
    }

    private class CountingHandle(
        private val blockFirstSend: Boolean = false,
    ) : UsbTransportHandle {
        private val inbound = ArrayDeque<ByteArray>()
        override val candidate: UsbInterfaceCandidate = CANDIDATE
        override val held: Boolean = true

        val firstSendStarted = CountDownLatch(1)
        val releaseFirstSend = CountDownLatch(if (blockFirstSend) 1 else 0)

        @Volatile
        var sendCalls = 0
            private set

        /** Всё, что ушло на провод, по записям. */
        val sent: MutableList<ByteArray> = java.util.Collections.synchronizedList(mutableListOf())

        override fun receive(
            destination: ByteArray,
            offset: Int,
            length: Int,
            timeoutMillis: Int,
        ): UsbTransferResult {
            val bytes = synchronized(inbound) { inbound.removeFirstOrNull() }
                ?: return UsbTransferResult.Failed(UsbTransferFailure.NOT_COMPLETED)
            check(bytes.size <= length) { "fake inbound chunk is larger than requested receive window" }
            bytes.copyInto(destination, destinationOffset = offset)
            return UsbTransferResult.Completed(bytes.size)
        }

        fun enqueueOkay(localId: Int, remoteId: Int) {
            val header = ByteArray(AdbPacketHeader.SIZE_BYTES)
            AdbPacketHeader.encode(
                target = header,
                command = AdbCommand.OKAY,
                arg0 = remoteId,
                arg1 = localId,
                payload = ByteArray(0),
                checksum = 0,
            )
            synchronized(inbound) { inbound.addLast(header) }
        }

        override fun send(
            source: ByteArray,
            offset: Int,
            length: Int,
            timeoutMillis: Int,
        ): UsbTransferResult {
            val call = synchronized(this) {
                sendCalls += 1
                sent += source.copyOfRange(offset, offset + length)
                sendCalls
            }
            if (call == 1) {
                firstSendStarted.countDown()
                if (blockFirstSend) {
                    check(releaseFirstSend.await(2, TimeUnit.SECONDS)) { "test did not release first send" }
                }
            }
            return UsbTransferResult.Completed(length)
        }

        override fun close() = Unit
    }

    private companion object {
        val DEVICE = UsbDeviceDescriptor(
            deviceId = 1,
            deviceName = "/dev/bus/usb/test",
            vendorId = 0x18D1,
            productId = 0x4EE7,
        )
        val CANDIDATE = UsbInterfaceCandidate(
            device = DEVICE,
            kind = UsbInterfaceKind.ADB,
            confidence = UsbMatchConfidence.CANONICAL,
            interfaceIndex = 0,
            interfaceId = 0,
            interfaceClass = 0xFF,
            interfaceSubclass = 0x42,
            interfaceProtocol = 0x01,
            endpointIn = UsbEndpointDescriptor(0x81, UsbEndpointDirection.IN, UsbTransferType.BULK),
            endpointOut = UsbEndpointDescriptor(0x01, UsbEndpointDirection.OUT, UsbTransferType.BULK),
        )
    }
}
