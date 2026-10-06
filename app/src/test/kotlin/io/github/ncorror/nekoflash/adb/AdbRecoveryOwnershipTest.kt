package io.github.ncorror.nekoflash.adb

import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticSink
import io.github.ncorror.nekoflash.core.model.TargetId
import io.github.ncorror.nekoflash.protocol.adb.AdbRecoveryVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AdbRecoveryOwnershipTest {
    @JvmField @Rule val temporary = TemporaryFolder()

    @Test fun sameTargetReconnectRetainsTheBaseline() = withRecovery { f, c ->
        f.host.handles.single().syncReplies { BEFORE }
        c.captureRecoveryBaseline(checkNotNull(f.lifecycle.connection))
        f.tasks.runFirst()
        assertTrue((c.state.value as AdbFileState.Verdict).baselineTaken)
        f.lifecycle.disconnect(f.first)
        f.lifecycle.connect(f.first)
        f.tasks.runFirst()
        f.host.handles.last().syncReplies { AFTER }
        c.readRecoveryVerdict(checkNotNull(f.lifecycle.connection))
        f.tasks.runFirst()
        val result = c.state.value as AdbFileState.Verdict
        assertTrue(result.baselineTaken)
        assertEquals(AdbRecoveryVerdict.SUCCESS, result.verdict)
    }

    @Test fun differentTargetCannotUseThePreviousDevicesBaseline() = withRecovery { f, c ->
        f.host.handles.single().syncReplies { BEFORE }
        c.captureRecoveryBaseline(checkNotNull(f.lifecycle.connection))
        f.tasks.runFirst()
        f.lifecycle.connect(f.second)
        f.tasks.runFirst()
        f.host.handles.last().syncReplies { AFTER }
        c.readRecoveryVerdict(checkNotNull(f.lifecycle.connection))
        f.tasks.runFirst()
        val result = c.state.value as AdbFileState.Verdict
        assertFalse(result.baselineTaken)
        assertEquals(AdbRecoveryVerdict.UNKNOWN, result.verdict)
    }

    private fun withRecovery(work: (AdbLifecycleFixture, AdbSyncController) -> Unit) {
        AdbLifecycleFixture(temporary.newFolder()).use { f ->
            val c = AdbSyncController(f.tasks, DiagnosticSink { })
            var target = TargetId("first")
            f.replacingHook = { generation ->
                target = TargetId(if (generation == f.first) "first" else "second")
                c.bind(null, target, discard = true)
            }
            f.bindHook = { c.bind(it, target) }
            f.unbindHook = { c.bind(null, target) }
            f.lifecycle.connect(f.first)
            f.tasks.runFirst()
            work(f, c)
        }
    }

    companion object {
        private const val BEFORE = "Starting ADB sideload\n"
        private const val AFTER = BEFORE + "Install completed successfully\n"
    }
}
