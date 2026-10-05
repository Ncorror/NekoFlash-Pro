package io.github.ncorror.nekoflash.fastboot

import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticSink
import io.github.ncorror.nekoflash.core.diagnostics.InMemoryDiagnosticSink
import io.github.ncorror.nekoflash.core.model.SessionGeneration
import io.github.ncorror.nekoflash.usb.api.UsbClaimFailure
import io.github.ncorror.nekoflash.usb.api.UsbClaimResult
import io.github.ncorror.nekoflash.usb.api.UsbTransferResult
import io.github.ncorror.nekoflash.usb.api.UsbTransportHandle
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deterministic ownership races: a queued task never acquires a newer connection's authority. */
class FastbootConnectionLifecycleTest {
    @Test
    fun aDuplicateConnectKeepsTheLiveConnectionAndItsQueuedOperation() {
        val handle = FakeFastbootHandle(listOf("OKAYno", "OKAYyes", "OKAYvayu"))
        val tasks = ArrayDeque<Runnable>()
        var claims = 0
        val controller = FastbootLinkController(
            claim = { claims++; UsbClaimResult.Claimed(handle) },
            executor = { tasks.addLast(it) },
            diagnostics = InMemoryDiagnosticSink(),
        )
        controller.connect(SessionGeneration(1))
        tasks.removeFirst().run()
        val connected = controller.state.value
        controller.readVariable("product")
        val running = controller.console.value

        controller.connect(SessionGeneration(1))

        assertEquals(1, claims)
        assertSame(connected, controller.state.value)
        assertSame(running, controller.console.value)
        assertFalse(handle.closed)
        assertEquals(1, tasks.size)
        tasks.removeFirst().run()
        assertEquals("vayu", (controller.console.value as FastbootConsoleState.Answered).payload)
    }

    @Test
    fun aDuplicateConnectDuringTheProbeDoesNotClaimAgain() {
        val handle = FakeFastbootHandle(listOf("OKAYno", "OKAYyes"))
        val tasks = ArrayDeque<Runnable>()
        var claims = 0
        val controller = FastbootLinkController(
            claim = { claims++; UsbClaimResult.Claimed(handle) },
            executor = { tasks.addLast(it) },
            diagnostics = InMemoryDiagnosticSink(),
        )

        controller.connect(SessionGeneration(1))
        controller.connect(SessionGeneration(1))

        assertEquals(1, claims)
        assertEquals(1, tasks.size)
        tasks.removeFirst().run()
        assertTrue(controller.state.value is FastbootLinkState.Connected)
    }

    @Test
    fun changingGenerationsClosesThePreviousHandleBeforeClaimingAndClearsItsConsole() {
        val first = FakeFastbootHandle(listOf("OKAYno", "OKAYyes", "OKAYold"))
        val second = FakeFastbootHandle(listOf("OKAYyes", "OKAYyes", "OKAYnew"))
        val tasks = ArrayDeque<Runnable>()
        lateinit var controller: FastbootLinkController
        controller = FastbootLinkController(
            claim = { generation ->
                if (generation.value == 1L) {
                    UsbClaimResult.Claimed(first)
                } else {
                    assertTrue(first.closed)
                    assertEquals(FastbootLinkState.Probing(SessionGeneration(2)), controller.state.value)
                    assertEquals(FastbootConsoleState.Idle, controller.console.value)
                    UsbClaimResult.Claimed(second)
                }
            },
            executor = { tasks.addLast(it) },
            diagnostics = InMemoryDiagnosticSink(),
        )
        controller.connect(SessionGeneration(1))
        tasks.removeFirst().run()
        controller.readVariable("product")
        tasks.removeFirst().run()

        controller.connect(SessionGeneration(2))
        tasks.removeFirst().run()
        controller.readVariable("product")
        tasks.removeFirst().run()

        assertEquals(SessionGeneration(2), (controller.state.value as FastbootLinkState.Connected).generation)
        assertEquals("new", (controller.console.value as FastbootConsoleState.Answered).payload)
        assertEquals(listOf("getvar:is-userspace", "getvar:unlocked", "getvar:product"), first.sent)
        assertFalse(second.closed)
    }

    @Test
    fun aFailedReplacementCannotSendThroughThePreviousLane() {
        val first = FakeFastbootHandle(listOf("OKAYno", "OKAYyes"))
        val controller = FastbootLinkController(
            claim = { generation ->
                if (generation.value == 1L) UsbClaimResult.Claimed(first)
                else UsbClaimResult.Failed(UsbClaimFailure.OPEN_REFUSED)
            },
            executor = { it.run() },
            diagnostics = InMemoryDiagnosticSink(),
        )
        controller.connect(SessionGeneration(1))

        controller.connect(SessionGeneration(2))
        controller.runCommand("oem stale")

        assertTrue(first.closed)
        assertEquals(SessionGeneration(2), (controller.state.value as FastbootLinkState.Failed).generation)
        assertTrue(controller.console.value is FastbootConsoleState.NotAnswered)
        assertEquals(listOf("getvar:is-userspace", "getvar:unlocked"), first.sent)
    }

    @Test
    fun changingGenerationsDuringAQueuedProbeClosesItAndSkipsItsIo() {
        val first = FakeFastbootHandle(listOf("OKAYno", "OKAYyes"))
        val second = FakeFastbootHandle(listOf("OKAYyes", "OKAYyes"))
        val tasks = ArrayDeque<Runnable>()
        val controller = FastbootLinkController(
            claim = { UsbClaimResult.Claimed(if (it.value == 1L) first else second) },
            executor = { tasks.addLast(it) },
            diagnostics = InMemoryDiagnosticSink(),
        )
        controller.connect(SessionGeneration(1))

        controller.connect(SessionGeneration(2))

        assertTrue(first.closed)
        tasks.removeLast().run()
        val replacement = controller.state.value
        tasks.removeFirst().run()
        assertTrue(first.sent.isEmpty())
        assertSame(replacement, controller.state.value)
        assertEquals(SessionGeneration(2), (replacement as FastbootLinkState.Connected).generation)
        assertFalse(second.closed)
    }

    @Test
    fun disconnectImmediatelyClosesTheHandleOfAQueuedProbe() {
        val handle = FakeFastbootHandle(listOf("OKAYno", "OKAYyes"))
        val tasks = ArrayDeque<Runnable>()
        val controller = FastbootLinkController(
            claim = { UsbClaimResult.Claimed(handle) },
            executor = { tasks.addLast(it) },
            diagnostics = InMemoryDiagnosticSink(),
        )
        controller.connect(SessionGeneration(1))

        controller.disconnect()

        assertTrue(handle.closed)
        tasks.removeFirst().run()
        assertTrue(handle.sent.isEmpty())
        assertEquals(FastbootLinkState.Idle, controller.state.value)
        assertEquals(FastbootConsoleState.Idle, controller.console.value)
    }

    @Test
    fun aClaimReturningAfterDisconnectIsClosedWithoutQueuingAProbe() {
        val handle = FakeFastbootHandle(listOf("OKAYno", "OKAYyes"))
        val tasks = ArrayDeque<Runnable>()
        lateinit var controller: FastbootLinkController
        controller = FastbootLinkController(
            claim = { controller.disconnect(); UsbClaimResult.Claimed(handle) },
            executor = { tasks.addLast(it) },
            diagnostics = InMemoryDiagnosticSink(),
        )

        controller.connect(SessionGeneration(1))

        assertTrue(handle.closed)
        assertTrue(tasks.isEmpty())
        assertTrue(handle.sent.isEmpty())
        assertEquals(FastbootLinkState.Idle, controller.state.value)
    }

    @Test
    fun aLateClaimCannotReplaceANewerConnection() {
        val first = FakeFastbootHandle(listOf("OKAYno", "OKAYyes"))
        val second = FakeFastbootHandle(listOf("OKAYyes", "OKAYyes"))
        lateinit var controller: FastbootLinkController
        controller = FastbootLinkController(
            claim = { generation ->
                if (generation.value == 1L) {
                    controller.connect(SessionGeneration(2))
                    UsbClaimResult.Claimed(first)
                } else {
                    UsbClaimResult.Claimed(second)
                }
            },
            executor = { it.run() },
            diagnostics = InMemoryDiagnosticSink(),
        )

        controller.connect(SessionGeneration(1))

        assertTrue(first.closed)
        assertTrue(first.sent.isEmpty())
        assertFalse(second.closed)
        assertEquals(SessionGeneration(2), (controller.state.value as FastbootLinkState.Connected).generation)
    }

    @Test
    fun aLateClaimFailureCannotOverwriteANewerConnection() {
        val second = FakeFastbootHandle(listOf("OKAYyes", "OKAYyes"))
        lateinit var controller: FastbootLinkController
        controller = FastbootLinkController(
            claim = { generation ->
                if (generation.value == 1L) {
                    controller.connect(SessionGeneration(2))
                    UsbClaimResult.Failed(UsbClaimFailure.OPEN_REFUSED)
                } else {
                    UsbClaimResult.Claimed(second)
                }
            },
            executor = { it.run() },
            diagnostics = InMemoryDiagnosticSink(),
        )

        controller.connect(SessionGeneration(1))

        assertEquals(SessionGeneration(2), (controller.state.value as FastbootLinkState.Connected).generation)
        assertFalse(second.closed)
    }

    @Test
    fun aRejectedProbeClosesItsHandleAndAllowsRetry() {
        val first = FakeFastbootHandle(listOf("OKAYno", "OKAYyes"))
        val second = FakeFastbootHandle(listOf("OKAYyes", "OKAYyes"))
        var rejected = true
        val sink = InMemoryDiagnosticSink()
        val controller = FastbootLinkController(
            claim = { UsbClaimResult.Claimed(if (rejected) first else second) },
            executor = { if (rejected) throw RejectedExecutionException("worker stopped") else it.run() },
            diagnostics = sink,
        )

        controller.connect(SessionGeneration(1))

        assertTrue(first.closed)
        assertTrue(first.sent.isEmpty())
        assertEquals("worker stopped", (controller.state.value as FastbootLinkState.Failed).detail)
        assertEquals("fastboot_probe_failed", sink.snapshot().last().message)
        rejected = false
        controller.connect(SessionGeneration(1))
        assertTrue(controller.state.value is FastbootLinkState.Connected)
        assertFalse(second.closed)
    }

    @Test
    fun disconnectDuringTheModeProbeSkipsTheLockProbe() {
        val transport = FakeFastbootHandle(listOf("OKAYno", "OKAYyes"))
        lateinit var controller: FastbootLinkController
        val handle = object : UsbTransportHandle by transport {
            override fun receive(destination: ByteArray, offset: Int, length: Int, timeoutMillis: Int): UsbTransferResult {
                controller.disconnect()
                return transport.receive(destination, offset, length, timeoutMillis)
            }
        }
        controller = FastbootLinkController(
            claim = { UsbClaimResult.Claimed(handle) },
            executor = { it.run() },
            diagnostics = InMemoryDiagnosticSink(),
        )

        controller.connect(SessionGeneration(1))

        assertTrue(transport.closed)
        assertEquals(listOf("getvar:is-userspace"), transport.sent)
        assertEquals(FastbootLinkState.Idle, controller.state.value)
    }

    @Test
    fun aQueuedCommandCannotRunAfterADirectGenerationChange() {
        val first = FakeFastbootHandle(listOf("OKAYno", "OKAYyes", "OKAYold"))
        val second = FakeFastbootHandle(listOf("OKAYyes", "OKAYyes"))
        val tasks = ArrayDeque<Runnable>()
        val controller = FastbootLinkController(
            claim = { UsbClaimResult.Claimed(if (it.value == 1L) first else second) },
            executor = { tasks.addLast(it) },
            diagnostics = InMemoryDiagnosticSink(),
        )
        controller.connect(SessionGeneration(1))
        tasks.removeFirst().run()
        controller.readVariable("product")

        controller.connect(SessionGeneration(2))
        tasks.removeLast().run()
        tasks.removeFirst().run()

        assertEquals(listOf("getvar:is-userspace", "getvar:unlocked"), first.sent)
        assertEquals(SessionGeneration(2), (controller.state.value as FastbootLinkState.Connected).generation)
        assertEquals(FastbootConsoleState.Idle, controller.console.value)
    }

    @Test
    fun droppingAUsbSessionCannotDisconnectAConnectionOpenedByTheNotification() {
        val first = FakeFastbootHandle(listOf("OKAYno", "OKAYyes"))
        val second = FakeFastbootHandle(listOf("OKAYyes", "OKAYyes"))
        lateinit var controller: FastbootLinkController
        val sink = DiagnosticSink { event ->
            if (event.message == "fastboot_link_dropped") controller.connect(SessionGeneration(2))
        }
        controller = FastbootLinkController(
            claim = { UsbClaimResult.Claimed(if (it.value == 1L) first else second) },
            executor = { it.run() },
            diagnostics = sink,
        )
        controller.connect(SessionGeneration(1))

        controller.onUsbSessionsChanged(emptyList())

        assertTrue(first.closed)
        assertFalse(second.closed)
        assertEquals(SessionGeneration(2), (controller.state.value as FastbootLinkState.Connected).generation)
    }
}
