package io.github.ncorror.nekoflash.adb

import io.github.ncorror.nekoflash.protocol.adb.AdbCommand
import io.github.ncorror.nekoflash.protocol.adb.AdbHandshakeFailure
import io.github.ncorror.nekoflash.usb.api.UsbClaimFailure
import io.github.ncorror.nekoflash.usb.api.UsbClaimResult
import io.github.ncorror.nekoflash.usb.api.UsbSessionState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Ownership races use real protocol outcomes, including CNXN/AUTH and completed shell output. */
class AdbLinkLifecycleTest {
    @JvmField @Rule val temporary = TemporaryFolder()

    @Test fun duplicateQueuedConnectClaimsOnce() = withFixture { f ->
        f.lifecycle.connect(f.first)
        f.lifecycle.connect(f.first)
        assertEquals(1, f.host.handles.size)
        assertEquals(1, f.tasks.size)
        f.tasks.runFirst()
        assertEquals(listOf(AdbCommand.CNXN), f.host.handles.single().sent)
        assertTrue(f.lifecycle.state.value is AdbLinkState.Connected)
    }

    @Test fun duplicateLiveConnectPreservesTheQueuedCommand() = withFixture { f ->
        f.lifecycle.connect(f.first)
        f.tasks.runFirst()
        val link = f.lifecycle.state.value
        f.lifecycle.runCommand("printf old")
        val running = f.lifecycle.command.value
        f.lifecycle.connect(f.first)
        assertSame(link, f.lifecycle.state.value)
        assertSame(running, f.lifecycle.command.value)
        assertEquals(1, f.host.handles.size)
        f.tasks.runFirst()
        assertEquals("reply:shell:printf old", (f.lifecycle.command.value as AdbCommandState.Finished).output)
    }

    @Test fun aQueuedHandshakeIsClosedImmediatelyOnDisconnect() = withFixture { f ->
        f.lifecycle.connect(f.first)
        f.lifecycle.disconnect(f.first)
        assertFalse(f.host.handles.single().held)
        assertEquals(UsbSessionState.READY, f.coordinator.sessions.value[0].state)
        f.tasks.runFirst()
        assertTrue(f.host.handles.single().sent.isEmpty())
        assertEquals(AdbLinkState.Idle, f.lifecycle.state.value)
        assertNull(f.lifecycle.connection)
    }

    @Test fun changingTargetsBeforeExecutionSkipsOldProtocolIo() = withFixture { f ->
        f.lifecycle.connect(f.first)
        f.lifecycle.connect(f.second)
        assertFalse(f.host.handles[0].held)
        f.tasks.runLast()
        val replacement = f.lifecycle.state.value
        f.tasks.runFirst()
        assertTrue(f.host.handles[0].sent.isEmpty())
        assertSame(replacement, f.lifecycle.state.value)
        assertEquals(f.second, (replacement as AdbLinkState.Connected).generation)
        assertTrue(f.host.handles[1].held)
    }

    @Test fun disconnectOfAnOlderTargetCannotDropTheCurrentLink() = withFixture { f ->
        f.lifecycle.connect(f.second)
        f.tasks.runFirst()
        val live = f.lifecycle.state.value
        f.lifecycle.disconnect(f.first)
        assertSame(live, f.lifecycle.state.value)
        assertTrue(f.host.handles.single().held)
        assertEquals(0, f.unbindings.get())
    }

    @Test fun aClaimReturningAfterDisconnectIsClosedWithoutQueuing() = withFixture { f ->
        f.host.claimHook = { f.lifecycle.disconnect(f.first); UsbClaimResult.Claimed(it) }
        f.lifecycle.connect(f.first)
        assertFalse(f.host.handles.single().held)
        assertEquals(0, f.tasks.size)
        assertEquals(AdbLinkState.Idle, f.lifecycle.state.value)
        assertEquals(UsbSessionState.READY, f.coordinator.sessions.value[0].state)
    }

    @Test fun aLateClaimCannotReplaceARetryOfTheSameGeneration() = withFixture { f ->
        var first = true
        f.host.claimHook = {
            if (first) {
                first = false
                f.lifecycle.disconnect(f.first)
                f.lifecycle.connect(f.first)
            }
            UsbClaimResult.Claimed(it)
        }
        f.lifecycle.connect(f.first)
        assertFalse(f.host.handles[0].held)
        assertTrue(f.host.handles[1].held)
        assertEquals(1, f.tasks.size)
        f.tasks.runFirst()
        assertConnected(f, f.first.value, "claim2")
        assertEquals(UsbSessionState.CLAIMED, f.coordinator.sessions.value[0].state)
    }

    @Test fun aLateClaimFailureCannotOverwriteANewerTarget() = withFixture { f ->
        f.host.claimHook = { handle ->
            if (handle === f.host.handles[0]) {
                f.lifecycle.connect(f.second)
                handle.close()
                UsbClaimResult.Failed(UsbClaimFailure.INTERFACE_REFUSED)
            } else UsbClaimResult.Claimed(handle)
        }
        f.lifecycle.connect(f.first)
        f.tasks.runFirst()
        assertConnected(f, f.second.value, "claim2")
    }

    @Test fun aLateSuccessfulHandshakeCannotResurrectAfterTargetSwitch() = withFixture { f ->
        f.lifecycle.connect(f.first)
        val old = f.host.handles.single()
        old.receiveHook = { chunk ->
            if (chunk.contentEquals(old.banner)) {
                f.lifecycle.disconnect(f.first)
                f.lifecycle.connect(f.second)
                f.tasks.runFirst()
            }
        }
        f.tasks.runFirst()
        assertConnected(f, f.second.value, "claim2")
        assertFalse(old.held)
    }

    @Test fun aLateFailedHandshakeCannotOverwriteARetry() = withFixture { f ->
        f.lifecycle.connect(f.first)
        f.host.handles.single().receiveHook = {
            f.lifecycle.disconnect(f.first)
            f.lifecycle.connect(f.first)
            f.tasks.runFirst()
            throw IllegalStateException("private platform detail")
        }
        f.tasks.runFirst()
        assertConnected(f, f.first.value, "claim2")
        assertTrue(f.host.handles[1].held)
    }

    @Test fun lateAuthorizationCallbackCannotChangeTheNewLink() = withFixture { f ->
        f.lifecycle.connect(f.first)
        val old = f.host.handles.single()
        old.inbound.clear()
        repeat(2) { old.enqueue(AdbCommand.AUTH, 1, 0, ByteArray(20) { it.toByte() }) }
        old.payloadHook = { header, _ ->
            if (header.command == AdbCommand.AUTH && header.arg0 == 3) {
                f.lifecycle.disconnect(f.first)
                f.lifecycle.connect(f.first)
                f.tasks.runFirst()
            }
        }
        f.tasks.runFirst()
        assertConnected(f, f.first.value, "claim2")
        assertEquals(2, old.sent.count { it == AdbCommand.AUTH })
    }

    @Test fun rejectedHandshakeReleasesTheClaimAndAllowsRetry() = withFixture { f ->
        f.tasks.rejected = true
        f.lifecycle.connect(f.first)
        val failure = f.lifecycle.state.value as AdbLinkState.Failed
        assertEquals("RejectedExecutionException", failure.detail)
        assertFalse(failure.afterConnect)
        assertFalse(f.host.handles.single().held)
        f.tasks.rejected = false
        f.lifecycle.connect(f.first)
        f.tasks.runFirst()
        assertConnected(f, f.first.value, "claim2")
    }

    @Test fun rejectedCommandDoesNotLeaveItsSlotRunningOrDropTheLink() = withFixture { f ->
        f.lifecycle.connect(f.first)
        f.tasks.runFirst()
        f.tasks.rejected = true
        f.lifecycle.runCommand("echo one")
        assertEquals("RejectedExecutionException", (f.lifecycle.command.value as AdbCommandState.Failed).reason)
        assertTrue(f.lifecycle.state.value is AdbLinkState.Connected)
        assertTrue(f.host.handles.single().held)
        f.tasks.rejected = false
        f.lifecycle.runCommand("echo two")
        f.tasks.runFirst()
        assertEquals("echo two", (f.lifecycle.command.value as AdbCommandState.Finished).command)
    }

    @Test fun queuedOldCommandCannotSendOrReplaceNewCommandState() = withFixture { f ->
        f.lifecycle.connect(f.first)
        f.tasks.runFirst()
        f.lifecycle.runCommand("old")
        f.lifecycle.connect(f.second)
        f.tasks.runLast()
        f.lifecycle.runCommand("new")
        f.tasks.runFirst()
        assertEquals(AdbCommandState.Running("new"), f.lifecycle.command.value)
        assertEquals(listOf(AdbCommand.CNXN), f.host.handles[0].sent)
        f.tasks.runFirst()
        assertEquals("new", (f.lifecycle.command.value as AdbCommandState.Finished).command)
    }

    @Test fun completedOldOutputCannotAppearUnderANewConnection() = withFixture { f ->
        f.lifecycle.connect(f.first)
        f.tasks.runFirst()
        var switched = false
        f.diagnosticHook = { event ->
            if (event.message == "service_completed" && !switched) {
                switched = true
                f.lifecycle.disconnect(f.first)
                f.lifecycle.connect(f.second)
                f.tasks.runFirst()
                f.lifecycle.runCommand("new")
            }
        }
        f.lifecycle.runCommand("old")
        f.tasks.runFirst()
        assertTrue(switched)
        assertConnected(f, f.second.value, "claim2")
        assertEquals(AdbCommandState.Running("new"), f.lifecycle.command.value)
        f.tasks.runFirst()
        assertEquals("reply:shell:new", (f.lifecycle.command.value as AdbCommandState.Finished).output)
    }

    @Test fun lateTransportCallbackAndCommandFailureCannotCloseTheReplacement() = withFixture { f ->
        f.lifecycle.connect(f.first)
        f.tasks.runFirst()
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        f.host.handles.single().failSend = AdbCommand.OPEN
        f.diagnosticHook = {
            if (it.message == "transport_ended") { entered.countDown(); await(resume) }
        }
        f.lifecycle.runCommand("old")
        val failure = AtomicReference<Throwable>()
        val worker = start(f.tasks.takeFirst(), failure)
        try {
            await(entered)
            f.lifecycle.disconnect(f.first)
            f.lifecycle.connect(f.second)
            f.tasks.runFirst()
            f.lifecycle.runCommand("new")
        } finally { resume.countDown(); join(worker, failure) }
        assertConnected(f, f.second.value, "claim2")
        assertEquals(AdbCommandState.Running("new"), f.lifecycle.command.value)
        assertTrue(f.host.handles[1].held)
        f.tasks.runFirst()
    }

    @Test fun transportEndWhileBindingCannotPublishConnected() = withFixture { f ->
        f.host.prepare = { handle -> handle.inbound.add(ByteArray(24)) }
        f.bindHook = { live ->
            live.reverse()
            f.awaitState { it is AdbLinkState.Failed }
        }
        f.lifecycle.connect(f.first)
        f.tasks.runFirst()
        val failure = f.lifecycle.state.value as AdbLinkState.Failed
        assertEquals(AdbHandshakeFailure.FRAMING_LOST, failure.reason)
        assertTrue(failure.afterConnect)
        assertFalse(f.host.handles.single().held)
        assertEquals(1, f.unbindings.get())
        assertEquals(1, f.events.count { it.message == "transport_ended" })
    }

    @Test fun removingTheSelectedSessionDropsTheQueuedLinkAndDoesNotReconnectIt() = withFixture { f ->
        f.lifecycle.onUsbSessionsChanged(listOf(f.coordinator.sessions.value[0]))
        f.lifecycle.onUsbSessionsChanged(emptyList())
        f.tasks.runFirst()
        f.lifecycle.onUsbSessionsChanged(listOf(f.coordinator.sessions.value[0]))
        assertEquals(AdbLinkState.Idle, f.lifecycle.state.value)
        assertEquals(1, f.host.handles.size)
        assertFalse(f.host.handles.single().held)
    }

    @Test fun disconnectDuringABlockedHandshakeInvalidatesBeforeThePhysicalCallReturns() = withFixture { f ->
        f.lifecycle.connect(f.first)
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        f.host.handles.single().receiveHook = { entered.countDown(); await(resume) }
        val failure = AtomicReference<Throwable>()
        val worker = start(f.tasks.takeFirst(), failure)
        try {
            await(entered)
            f.lifecycle.disconnect(f.first)
            assertEquals(AdbLinkState.Idle, f.lifecycle.state.value)
            assertFalse(f.host.handles.single().held)
        } finally { resume.countDown(); join(worker, failure) }
        assertEquals(AdbLinkState.Idle, f.lifecycle.state.value)
        assertEquals(listOf(AdbCommand.CNXN), f.host.handles.single().sent)
    }

    @Test fun oldBlockedCloseCannotMarkANewClaimReadyOrClearItsBinding() = withFixture { f ->
        f.lifecycle.connect(f.first)
        f.tasks.runFirst()
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        f.host.handles.single().closeHook = { entered.countDown(); await(resume) }
        val failure = AtomicReference<Throwable>()
        val worker = start(Runnable { f.lifecycle.disconnect(f.first) }, failure)
        try {
            await(entered)
            f.lifecycle.connect(f.first)
            f.tasks.runFirst()
        } finally { resume.countDown(); join(worker, failure) }
        assertConnected(f, f.first.value, "claim2")
        assertEquals(UsbSessionState.CLAIMED, f.coordinator.sessions.value[0].state)
        assertTrue(f.host.handles[1].held)
        assertEquals(1, f.unbindings.get())
    }

    @Test fun failedBindingCleansItsResourcesAndCanRetry() = withFixture { f ->
        f.bindHook = { throw IllegalStateException("private binding detail") }
        f.lifecycle.connect(f.first)
        f.tasks.runFirst()
        assertEquals("IllegalStateException", (f.lifecycle.state.value as AdbLinkState.Failed).detail)
        assertFalse(f.host.handles.single().held)
        assertEquals(1, f.unbindings.get())
        f.bindHook = { }
        f.lifecycle.connect(f.first)
        f.tasks.runFirst()
        assertConnected(f, f.first.value, "claim2")
    }

    @Test fun readySessionDuringThePhysicalClaimDoesNotCancelItsOwner() = withFixture { f ->
        f.host.claimHook = {
            f.lifecycle.onUsbSessionsChanged(listOf(f.coordinator.sessions.value[0]))
            UsbClaimResult.Claimed(it)
        }
        f.lifecycle.connect(f.first)
        f.tasks.runFirst()
        assertConnected(f, f.first.value, "claim1")
    }

    @Test fun aHandleReleasedAsCnxnReturnsCannotPublishConnected() = withFixture { f ->
        f.lifecycle.connect(f.first)
        val handle = f.host.handles.single()
        handle.receiveHook = { chunk ->
            if (chunk.contentEquals(handle.banner)) f.coordinator.release(f.first)
        }
        f.tasks.runFirst()
        assertTrue(f.lifecycle.state.value is AdbLinkState.Failed)
        assertNull(f.lifecycle.connection)
        assertFalse(handle.held)
        assertEquals(UsbSessionState.READY, f.coordinator.sessions.value[0].state)
    }

    private fun withFixture(block: (AdbLifecycleFixture) -> Unit) {
        AdbLifecycleFixture(temporary.newFolder()).use(block)
    }
    private fun assertConnected(f: AdbLifecycleFixture, generation: Long, product: String) {
        val connected = f.lifecycle.state.value as AdbLinkState.Connected
        assertEquals(generation, connected.generation.value)
        assertTrue(connected.banner.contains(product))
        assertNull((f.lifecycle.command.value as? AdbCommandState.Failed))
    }
    private fun await(latch: CountDownLatch) = assertTrue("timed out waiting for deterministic race", latch.await(3, TimeUnit.SECONDS))
    private fun start(task: Runnable, failure: AtomicReference<Throwable>): Thread = Thread {
        try { task.run() } catch (error: Throwable) { failure.set(error) }
    }.also { it.start() }
    private fun join(worker: Thread, failure: AtomicReference<Throwable>) {
        worker.join(3_000)
        assertFalse("worker did not finish", worker.isAlive)
        failure.get()?.let { throw AssertionError("worker failed", it) }
    }
}
