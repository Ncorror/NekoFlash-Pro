package io.github.ncorror.nekoflash.adb

import io.github.ncorror.nekoflash.core.artifact.ArtifactAccess
import io.github.ncorror.nekoflash.core.artifact.ArtifactCommit
import io.github.ncorror.nekoflash.core.artifact.ArtifactIdentity
import io.github.ncorror.nekoflash.core.artifact.ArtifactSink
import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticSink
import io.github.ncorror.nekoflash.core.model.TargetId
import io.github.ncorror.nekoflash.core.operation.OperationOutcome
import io.github.ncorror.nekoflash.operation.OperationEngine
import io.github.ncorror.nekoflash.protocol.adb.AdbCommand
import io.github.ncorror.nekoflash.protocol.adb.AdbPeerMode
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AdbOperationResourcesTest {
    @JvmField @Rule val temporary = TemporaryFolder()

    @Test fun installWriterRejectionClosesTheFileAndTheOpenedStream() = withFixture { f ->
        val c = AdbInstallController(f.tasks, DiagnosticSink { })
        val live = checkNotNull(f.lifecycle.connection)
        c.bind(live)
        var closed = false
        val artifact = object : AdbTestArtifact() {
            override fun open(): InputStream = object : ByteArrayInputStream(byteArrayOf(1)) {
                override fun close() { closed = true; super.close() }
            }
        }
        f.host.handles.single().serviceHook = { header, _ ->
            f.host.handles.single().enqueue(AdbCommand.OKAY, 77, header.arg0)
        }
        c.install(live, listOf({ artifact }), emptyList(), false)
        val worker = f.tasks.takeFirst()
        f.tasks.rejected = true
        worker.run()
        assertTrue(closed)
        assertFalse(c.active)
        assertTrue(c.state.value is AdbInstallState.Failed)
        assertTrue(AdbCommand.CLSE in f.host.handles.single().sent)
    }

    @Test fun aThrowingDownloadSinkIsAbandonedAndItsStreamIsClosed() = withFixture { f ->
        val c = AdbSyncController(f.tasks, DiagnosticSink { })
        val live = checkNotNull(f.lifecycle.connection)
        c.bind(live, TargetId("first"))
        f.host.handles.single().syncReplies()
        val sink = BrokenSink()
        c.readTo(live, "/log") { sink }
        f.tasks.runFirst()
        assertTrue(sink.closed)
        assertTrue(sink.abandoned)
        assertFalse(sink.committed)
        assertFalse(c.active)
        assertTrue(c.state.value is AdbFileState.SaveFailed)
        assertTrue(AdbCommand.CLSE in f.host.handles.single().sent)
    }

    @Test fun pushSourceReturningAfterReplacementIsClosedWithoutSendingItsBytes() = withFixture { f ->
        val c = AdbSyncController(f.tasks, DiagnosticSink { })
        f.bindHook = { c.bind(it, TargetId("current")) }
        f.unbindHook = { c.bind(null, TargetId("current")) }
        f.replacingHook = { c.bind(null, TargetId("replacement"), discard = true) }
        val live = checkNotNull(f.lifecycle.connection)
        c.bind(live, TargetId("first"))
        val old = f.host.handles.single()
        old.syncReplies()
        var closed = false
        val source = object : AdbTestArtifact() {
            override fun open(): InputStream {
                f.lifecycle.connect(f.second)
                f.tasks.runFirst()
                return object : ByteArrayInputStream(byteArrayOf(1)) {
                    override fun close() { closed = true; super.close() }
                }
            }
        }
        c.writeFrom(live, "/test") { source }
        f.tasks.runFirst()
        assertTrue(closed)
        assertFalse(c.active)
        assertEquals(AdbFileState.None, c.state.value)
        // Opening sync is allowed before replacement; SEND/DATA are never sent.
        assertEquals(0, old.sent.count { it == AdbCommand.WRTE })
    }

    @Test fun sideloadStagingInterruptedByReplacementRemovesTheCopyAndFinishesItsOwnRecord() = withFixture { f ->
        val engine = OperationEngine(AdbTestJournal())
        var holds = 0
        val c = AdbSideloadController(f.tasks, DiagnosticSink { }, engine, { holds++ })
        f.bindHook = { c.bind(it) }
        f.unbindHook = { c.bind(null) }
        f.replacingHook = { c.bind(null, discard = true) }
        val live = checkNotNull(f.lifecycle.connection)
        c.bind(live)
        val folder = temporary.newFolder()
        var closed = false
        val source = object : AdbTestArtifact() {
            override val identity = ArtifactIdentity("test.zip", 1, ArtifactAccess.NON_SEEKABLE)
            override fun open(): InputStream = object : ByteArrayInputStream(byteArrayOf(1)) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    f.lifecycle.connect(f.second)
                    f.tasks.runFirst()
                    return super.read(buffer, offset, length)
                }
                override fun close() { closed = true; super.close() }
            }
        }
        c.startFrom(live, AdbPeerMode.SIDELOAD, folder, TargetId("first"), f.first) { source }
        f.tasks.runFirst()
        assertTrue(closed)
        assertTrue(folder.listFiles().orEmpty().isEmpty())
        assertFalse(c.active)
        assertEquals(AdbSideloadState.None, c.state.value)
        assertEquals(1, holds)
        assertTrue(engine.live.value.isEmpty())
        assertEquals(TargetId("first"), engine.history.value.single().targetId)
        assertEquals(OperationOutcome.FAILED, engine.history.value.single().outcome)
    }

    @Test fun rejectedSideloadDoesNotCreateAnUnfinishedOperation() = withFixture { f ->
        val engine = OperationEngine(AdbTestJournal())
        var holds = 0
        val c = AdbSideloadController(f.tasks, DiagnosticSink { }, engine, { holds++ })
        val live = checkNotNull(f.lifecycle.connection)
        c.bind(live)
        f.tasks.rejected = true
        c.startFrom(live, AdbPeerMode.SIDELOAD, temporary.newFolder(), TargetId("first"), f.first) {
            error("never opens")
        }
        assertFalse(c.active)
        assertTrue(c.state.value is AdbSideloadState.Refused)
        assertEquals(0, holds)
        assertTrue(engine.live.value.isEmpty())
        assertTrue(engine.history.value.isEmpty())
    }

    @Test fun sideloadDetachAtTheMutationBoundaryRetainsAnUnknownOutcome() = mutationInterrupted(false)

    @Test fun sideloadReplacementAtTheMutationBoundaryFinishesOnlyTheOldTargetsHistory() = mutationInterrupted(true)

    private fun mutationInterrupted(replace: Boolean) = withFixture { f ->
        val engine = OperationEngine(AdbTestJournal())
        var boundary = false
        val diagnostics = DiagnosticSink { event ->
            if (event.message == "sideload_mutation_boundary") {
                boundary = true
                f.lifecycle.disconnect(f.first)
                if (replace) {
                    f.lifecycle.connect(f.second)
                    f.tasks.runFirst()
                }
            }
        }
        val c = AdbSideloadController(f.tasks, diagnostics, engine, { })
        f.bindHook = { c.bind(it) }
        f.unbindHook = { c.bind(null) }
        f.replacingHook = { c.bind(null, discard = true) }
        val live = checkNotNull(f.lifecycle.connection)
        c.bind(live)
        val handle = f.host.handles.single()
        handle.serviceHook = { header, _ ->
            handle.enqueue(AdbCommand.OKAY, 77, header.arg0)
            handle.enqueue(AdbCommand.WRTE, 77, header.arg0, "00000000".toByteArray())
        }
        c.startFrom(live, AdbPeerMode.SIDELOAD, temporary.newFolder(), TargetId("first"), f.first) {
            AdbTestArtifact()
        }
        f.tasks.runFirst()
        assertTrue(boundary)
        assertFalse(c.active)
        assertTrue(engine.live.value.isEmpty())
        assertEquals(OperationOutcome.UNKNOWN, engine.history.value.single().outcome)
        assertEquals(TargetId("first"), engine.history.value.single().targetId)
        if (replace) assertEquals(AdbSideloadState.None, c.state.value) else {
            assertTrue((c.state.value as AdbSideloadState.Finished).verificationPending)
        }
    }

    private fun withFixture(work: (AdbLifecycleFixture) -> Unit) {
        AdbLifecycleFixture(temporary.newFolder()).use { f ->
            f.lifecycle.connect(f.first)
            f.tasks.runFirst()
            work(f)
        }
    }

    private class BrokenSink : ArtifactSink {
        override val destination = "test"
        override val atomicCommit = true
        var closed = false
        var abandoned = false
        var committed = false
        override fun open() = object : OutputStream() {
            override fun write(value: Int) { error("provider write failure") }
            override fun close() { closed = true }
        }
        override fun commit(): ArtifactCommit { committed = true; return ArtifactCommit.Done(destination, true) }
        override fun abandon(reason: String) { abandoned = true }
    }
}
