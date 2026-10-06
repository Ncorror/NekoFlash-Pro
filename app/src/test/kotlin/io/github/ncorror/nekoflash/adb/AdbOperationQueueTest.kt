package io.github.ncorror.nekoflash.adb

import io.github.ncorror.nekoflash.core.artifact.ArtifactSource
import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticSink
import io.github.ncorror.nekoflash.core.model.TargetId
import io.github.ncorror.nekoflash.operation.OperationEngine
import io.github.ncorror.nekoflash.protocol.adb.AdbCommand
import io.github.ncorror.nekoflash.protocol.adb.AdbConnection
import io.github.ncorror.nekoflash.protocol.adb.AdbPeerMode
import io.github.ncorror.nekoflash.protocol.adb.AdbRebootDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** All six public operation paths share real connections and a controllable task queue. */
@RunWith(Parameterized::class)
class AdbOperationQueueTest(private val kind: Kind) {
    @JvmField @Rule val temporary = TemporaryFolder()

    @Test fun rejectionReleasesTheSlotAndRetryCanBeQueued() = withControl { f, c ->
        val live = checkNotNull(f.lifecycle.connection)
        var providers = 0
        val origin = { providers++; error("provider must not be opened") }
        f.tasks.rejected = true
        c.start(live, origin)
        assertFalse(c.active())
        assertTrue(c.failed(c.state()))
        if (kind == Kind.REBOOT) assertEquals(AdbRebootDevice.UNTOUCHED, (c.state() as AdbRebootState.Failed).device)
        assertEquals(0, providers)
        assertEquals(listOf(AdbCommand.CNXN), f.host.handles.single().sent)
        f.tasks.rejected = false
        c.start(live, origin)
        assertTrue(c.active())
        assertEquals(1, f.tasks.size)
        f.lifecycle.disconnect(f.first)
        f.tasks.runFirst()
        assertFalse(c.active())
        assertEquals(0, providers)
        assertEquals(listOf(AdbCommand.CNXN), f.host.handles.single().sent)
    }

    @Test fun queuedOldWorkCannotSendOrOverwriteTheNewOperation() = withControl { f, c ->
        var providers = 0
        val origin = { providers++; error("provider must not be opened") }
        c.start(checkNotNull(f.lifecycle.connection), origin)
        f.lifecycle.connect(f.second)
        f.tasks.runLast()
        c.start(checkNotNull(f.lifecycle.connection), origin)
        val replacement = c.state()
        f.tasks.runFirst()
        assertSame(replacement, c.state())
        assertTrue(c.active())
        assertEquals(0, providers)
        assertEquals(listOf(AdbCommand.CNXN), f.host.handles[0].sent)
        f.lifecycle.disconnect(f.second)
        f.tasks.runFirst()
        assertFalse(c.active())
        assertEquals(0, providers)
        assertEquals(listOf(AdbCommand.CNXN), f.host.handles[1].sent)
    }

    @Test fun logicalCloseBeforeExecutionSkipsProvidersAndProtocolIo() = withControl { f, c ->
        val live = checkNotNull(f.lifecycle.connection)
        var providers = 0
        c.start(live) { providers++; error("provider must not be opened") }
        live.close()
        assertTrue(f.host.handles.single().held)
        assertFalse(live.active)
        f.tasks.runFirst()
        assertFalse(c.active())
        assertTrue(c.failed(c.state()))
        assertEquals(0, providers)
        assertEquals(listOf(AdbCommand.CNXN), f.host.handles.single().sent)
    }

    private fun withControl(work: (AdbLifecycleFixture, Control) -> Unit) {
        AdbLifecycleFixture(temporary.newFolder()).use { f ->
            val c = control(f)
            f.bindHook = { c.bind(it, false) }
            f.unbindHook = { c.bind(null, false) }
            f.replacingHook = { c.bind(null, true) }
            f.lifecycle.connect(f.first)
            f.tasks.runFirst()
            work(f, c)
        }
    }

    private fun control(f: AdbLifecycleFixture): Control {
        val diagnostics = DiagnosticSink { }
        return when (kind) {
            Kind.SYNC -> AdbSyncController(f.tasks, diagnostics).let { c ->
                Control({ live, discard -> c.bind(live, TargetId("queue-test"), discard) },
                    { live, origin -> c.writeFrom(live, "/test", origin) }, { c.active }, { c.state.value },
                    { it is AdbFileState.Failed })
            }
            Kind.TRANSFER -> AdbTransferController(f.tasks, diagnostics).let { c ->
                Control(c::bind, { live, origin ->
                    c.push(live, { listOf(PushSource.File("test", 1, null, origin)) }, "/test", false, AdbPushMode())
                }, { c.active }, { c.state.value }, { it is AdbTransferState.Failed })
            }
            Kind.INSTALL -> AdbInstallController(f.tasks, diagnostics).let { c ->
                Control(c::bind, { live, origin ->
                    c.install(live, temporary.root, listOf(origin), emptyList(), false)
                }, { c.active }, { c.state.value }, { it is AdbInstallState.Failed })
            }
            Kind.RAW -> AdbRawServiceController(f.tasks).let { c ->
                Control(c::bind, { live, _ -> c.call(live, "shell:echo queued") },
                    { c.active }, { c.state.value }, { it is AdbRawServiceState.Failed })
            }
            Kind.REBOOT -> AdbRebootController(f.tasks).let { c ->
                Control(c::bind, { live, _ -> c.request(live, "recovery") },
                    { c.active }, { c.state.value }, { it is AdbRebootState.Failed })
            }
            Kind.SIDELOAD -> AdbSideloadController(
                f.tasks, diagnostics, OperationEngine(AdbTestJournal()), { },
            ).let { c ->
                Control(c::bind, { live, origin ->
                    c.startFrom(live, AdbPeerMode.SIDELOAD, temporary.newFolder(),
                        TargetId("queue-test"), f.first, origin)
                }, { c.active }, { c.state.value }, { it is AdbSideloadState.Refused })
            }
        }
    }

    private data class Control(
        val bind: (AdbConnection?, Boolean) -> Unit,
        val start: (AdbConnection, () -> ArtifactSource) -> Unit,
        val active: () -> Boolean,
        val state: () -> Any,
        val failed: (Any) -> Boolean,
    )

    enum class Kind { SYNC, TRANSFER, INSTALL, RAW, REBOOT, SIDELOAD }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun kinds(): List<Array<Kind>> = Kind.entries.map { arrayOf(it) }
    }
}
