package io.github.ncorror.nekoflash.adb

import io.github.ncorror.nekoflash.core.artifact.ArtifactAccess
import io.github.ncorror.nekoflash.core.artifact.ArtifactIdentity
import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticSink
import io.github.ncorror.nekoflash.core.model.TargetId
import io.github.ncorror.nekoflash.core.operation.OperationOutcome
import io.github.ncorror.nekoflash.operation.OperationEngine
import io.github.ncorror.nekoflash.protocol.adb.AdbCommand
import io.github.ncorror.nekoflash.protocol.adb.AdbPeerMode
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AdbStagingSpaceTest {
    @JvmField @Rule val temporary = TemporaryFolder()

    @Test fun aSeekableSourceDoesNotNeedStorageAllocation() = withFixture { f, engine, folder ->
        val c = controller(f, engine) { _, _ -> error("direct access must not query or allocate staging space") }
        c.startFrom(checkNotNull(f.lifecycle.connection), AdbPeerMode.DEVICE, folder, TargetId("first"), f.first) {
            AdbTestArtifact()
        }
        f.tasks.runFirst()
        assertTrue(c.state.value is AdbSideloadState.Finished)
        assertFalse(c.active)
        assertTrue(engine.live.value.isEmpty())
        assertEquals(listOf(AdbCommand.CNXN), f.host.handles.single().sent)
    }

    @Test fun allocationFailureStopsBeforeOpeningTheCopyOrTouchingTheDevice() = withFixture { f, engine, folder ->
        val c = controller(f, engine) { _, bytes ->
            assertEquals(1L, bytes)
            error("storage allocation failed")
        }
        val source = object : AdbTestArtifact() {
            override val identity = ArtifactIdentity("test.zip", 1, ArtifactAccess.NON_SEEKABLE)
            override fun open(): InputStream = error("copy must not open after allocation failure")
        }
        c.startFrom(checkNotNull(f.lifecycle.connection), AdbPeerMode.SIDELOAD, folder, TargetId("first"), f.first) {
            source
        }
        f.tasks.runFirst()
        assertTrue(c.state.value is AdbSideloadState.Refused)
        assertFalse(c.active)
        assertTrue(folder.listFiles().orEmpty().isEmpty())
        assertTrue(engine.live.value.isEmpty())
        assertEquals(OperationOutcome.FAILED, engine.history.value.single().outcome)
        assertEquals(listOf(AdbCommand.CNXN), f.host.handles.single().sent)
    }

    @Test fun aKnownSizeIsReservedBeforeCopyingAndTheStagedFileIsRemoved() = withFixture { f, engine, folder ->
        var reserved = false
        var closed = false
        val c = controller(f, engine) { directory, bytes ->
            assertEquals(folder, directory)
            assertEquals(1L, bytes)
            reserved = true
            1L
        }
        val source = object : AdbTestArtifact() {
            override val identity = ArtifactIdentity("test.zip", 1, ArtifactAccess.NON_SEEKABLE)
            override fun open(): InputStream {
                assertTrue(reserved)
                return object : ByteArrayInputStream(byteArrayOf(1)) {
                    override fun close() { closed = true; super.close() }
                }
            }
        }
        c.startFrom(checkNotNull(f.lifecycle.connection), AdbPeerMode.DEVICE, folder, TargetId("first"), f.first) {
            source
        }
        f.tasks.runFirst()
        assertTrue(closed)
        assertTrue(c.state.value is AdbSideloadState.Finished)
        assertFalse(c.active)
        assertTrue(folder.listFiles().orEmpty().isEmpty())
        assertTrue(engine.live.value.isEmpty())
    }

    private fun controller(
        f: AdbLifecycleFixture,
        engine: OperationEngine,
        space: (File, Long?) -> Long,
    ) = AdbSideloadController(f.tasks, DiagnosticSink { }, engine, { }, space).also {
        it.bind(checkNotNull(f.lifecycle.connection))
    }

    private fun withFixture(work: (AdbLifecycleFixture, OperationEngine, File) -> Unit) {
        AdbLifecycleFixture(temporary.newFolder()).use { f ->
            f.lifecycle.connect(f.first)
            f.tasks.runFirst()
            work(f, OperationEngine(AdbTestJournal()), temporary.newFolder())
        }
    }
}
