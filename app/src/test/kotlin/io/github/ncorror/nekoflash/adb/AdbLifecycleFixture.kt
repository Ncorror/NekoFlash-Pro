package io.github.ncorror.nekoflash.adb

import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticEvent
import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticSink
import io.github.ncorror.nekoflash.core.model.SessionGeneration
import io.github.ncorror.nekoflash.protocol.adb.AdbCommand
import io.github.ncorror.nekoflash.protocol.adb.AdbConnection
import io.github.ncorror.nekoflash.protocol.adb.AdbHeaderDecoding
import io.github.ncorror.nekoflash.protocol.adb.AdbKeyStore
import io.github.ncorror.nekoflash.protocol.adb.AdbPacketHeader
import io.github.ncorror.nekoflash.usb.api.UsbClaimResult
import io.github.ncorror.nekoflash.usb.api.UsbDeviceDescriptor
import io.github.ncorror.nekoflash.usb.api.UsbEndpointDescriptor
import io.github.ncorror.nekoflash.usb.api.UsbEndpointDirection
import io.github.ncorror.nekoflash.usb.api.UsbHost
import io.github.ncorror.nekoflash.usb.api.UsbInterfaceCandidate
import io.github.ncorror.nekoflash.usb.api.UsbInterfaceDescriptor
import io.github.ncorror.nekoflash.usb.api.UsbSessionCoordinator
import io.github.ncorror.nekoflash.usb.api.UsbTransferFailure
import io.github.ncorror.nekoflash.usb.api.UsbTransferResult
import io.github.ncorror.nekoflash.usb.api.UsbTransferType
import io.github.ncorror.nekoflash.usb.api.UsbTransportHandle
import java.io.File
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Real coordinator, handshake and shell; only the USB platform and task scheduling are substituted. */
internal class AdbLifecycleFixture(directory: File) : AutoCloseable {
    val tasks = AdbQueuedExecutor()
    val host = AdbLifecycleHost()
    val events = CopyOnWriteArrayList<DiagnosticEvent>()
    var diagnosticHook: (DiagnosticEvent) -> Unit = { }
    var bindHook: (AdbConnection) -> Unit = { }
    val unbindings = AtomicInteger()
    private val diagnostics = DiagnosticSink { events.add(it); diagnosticHook(it) }
    val coordinator = UsbSessionCoordinator(
        host = host, onPermissionRequested = { }, diagnostics = diagnostics,
    ).also { it.start() }
    val first: SessionGeneration = coordinator.sessions.value[0].generation
    val second: SessionGeneration = coordinator.sessions.value[1].generation
    val lifecycle = AdbLinkLifecycle(
        coordinator, AdbKeyStore(directory), 36, tasks, diagnostics,
        onConnected = { bindHook(it) }, onDisconnected = { unbindings.incrementAndGet() },
    )

    fun awaitState(predicate: (AdbLinkState) -> Boolean): AdbLinkState = runBlocking {
        withTimeout(3_000) { lifecycle.state.first(predicate) }
    }

    override fun close() {
        lifecycle.disconnect(first)
        lifecycle.disconnect(second)
        coordinator.sessions.value.forEach { coordinator.release(it.generation) }
        host.handles.forEach { it.close() }
    }
}

internal class AdbQueuedExecutor : Executor {
    private val tasks = ConcurrentLinkedDeque<Runnable>()
    var rejected = false
    val size: Int get() = tasks.size
    override fun execute(command: Runnable) {
        if (rejected) throw RejectedExecutionException("private executor detail")
        tasks.addLast(command)
    }
    fun takeFirst(): Runnable = checkNotNull(tasks.pollFirst())
    fun runFirst() = takeFirst().run()
    fun runLast() = checkNotNull(tasks.pollLast()).run()
}

internal class AdbLifecycleHost : UsbHost {
    val handles = CopyOnWriteArrayList<AdbLifecycleHandle>()
    var claimHook: (AdbLifecycleHandle) -> UsbClaimResult = { UsbClaimResult.Claimed(it) }
    var prepare: (AdbLifecycleHandle) -> Unit = { }
    private val attached = listOf(device(1), device(2))
    override fun start(listener: UsbHost.Listener) = Unit
    override fun stop() = Unit
    override fun devices() = attached
    override fun hasPermission(device: UsbDeviceDescriptor) = true
    override fun requestPermission(device: UsbDeviceDescriptor) = true
    override fun claim(candidate: UsbInterfaceCandidate): UsbClaimResult {
        val handle = AdbLifecycleHandle(candidate, "claim${handles.size + 1}")
        handles.add(handle)
        prepare(handle)
        return claimHook(handle)
    }

    private fun device(id: Int) = UsbDeviceDescriptor(
        deviceId = id, deviceName = "/dev/adb-$id", vendorId = 0x18D1, productId = 0x4EE7,
        serialNumber = "target-$id", interfaces = listOf(
            UsbInterfaceDescriptor(
                id = 0, interfaceClass = 0xFF, interfaceSubclass = 0x42, interfaceProtocol = 1,
                endpoints = listOf(
                    UsbEndpointDescriptor(0x81, UsbEndpointDirection.IN, UsbTransferType.BULK, 512),
                    UsbEndpointDescriptor(0x01, UsbEndpointDirection.OUT, UsbTransferType.BULK, 512),
                ),
            ),
        ),
    )
}

internal class AdbLifecycleHandle(
    override val candidate: UsbInterfaceCandidate,
    product: String,
) : UsbTransportHandle {
    val inbound = LinkedBlockingQueue<ByteArray>()
    val sent = CopyOnWriteArrayList<Long>()
    val banner = "device::ro.product.name=$product\u0000".toByteArray()
    var receiveHook: (ByteArray) -> Unit = { }
    var payloadHook: (AdbPacketHeader, ByteArray) -> Unit = { _, _ -> }
    var closeHook: () -> Unit = { }
    var failSend: Long? = null
    @Volatile
    private var released = false
    private var pending: AdbPacketHeader? = null
    override val held: Boolean get() = !released

    init { enqueue(AdbCommand.CNXN, 0x01000001, 1024 * 1024, banner) }

    fun enqueue(command: Long, arg0: Int = 0, arg1: Int = 0, payload: ByteArray = byteArrayOf()) {
        val header = ByteArray(AdbPacketHeader.SIZE_BYTES)
        AdbPacketHeader.encode(header, command, arg0, arg1, payload, payload.sumOf { it.toInt() and 0xFF })
        inbound.add(header)
        if (payload.isNotEmpty()) inbound.add(payload)
    }

    override fun receive(destination: ByteArray, offset: Int, length: Int, timeoutMillis: Int): UsbTransferResult {
        if (!held) return UsbTransferResult.Failed(UsbTransferFailure.NOT_HELD)
        val chunk = inbound.poll(100, TimeUnit.MILLISECONDS)
            ?: return UsbTransferResult.Failed(UsbTransferFailure.NOT_COMPLETED)
        check(chunk.size <= length)
        chunk.copyInto(destination, offset)
        // A completed physical call may return after close; the owner must reject its result.
        receiveHook(chunk)
        return UsbTransferResult.Completed(chunk.size)
    }

    override fun send(source: ByteArray, offset: Int, length: Int, timeoutMillis: Int): UsbTransferResult {
        if (!held) return UsbTransferResult.Failed(UsbTransferFailure.NOT_HELD)
        val payload = source.copyOfRange(offset, offset + length)
        val header = pending
        if (header == null) {
            val decoded = AdbPacketHeader.decode(payload, Int.MAX_VALUE) as AdbHeaderDecoding.Decoded
            sent.add(decoded.header.command)
            if (failSend == decoded.header.command) return UsbTransferResult.Failed(UsbTransferFailure.NOT_COMPLETED)
            if (decoded.header.payloadLength > 0) pending = decoded.header
        } else {
            check(payload.size == header.payloadLength)
            pending = null
            if (header.command == AdbCommand.OPEN) {
                enqueue(AdbCommand.OKAY, 77, header.arg0)
                enqueue(AdbCommand.WRTE, 77, header.arg0, "reply:${String(payload).trimEnd('\u0000')}\n".toByteArray())
                enqueue(AdbCommand.CLSE, 77, header.arg0)
            }
            payloadHook(header, payload)
        }
        return UsbTransferResult.Completed(length)
    }

    override fun close() {
        if (!released) {
            released = true
            closeHook()
        }
    }
}
