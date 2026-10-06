package io.github.ncorror.nekoflash.adb

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AdbOperationSlotTest {
    @JvmField @Rule val temporary = TemporaryFolder()

    @Test fun aStartedLateResultAndProgressCannotOverwriteTheReplacement() = withSlot { f, slot ->
        val old = checkNotNull(slot.begin(checkNotNull(f.lifecycle.connection), "old running"))
        slot.submit(f.tasks, old, failure = { "failure" }) {
            f.lifecycle.connect(f.second)
            f.tasks.runFirst()
            checkNotNull(slot.begin(checkNotNull(f.lifecycle.connection), "new running"))
            assertFalse(old.publish("old progress"))
            "old finished"
        }
        f.tasks.runFirst()
        assertEquals("new running", slot.state.value)
        assertTrue(slot.active)
    }

    @Test fun detachKeepsAStartedMutationOutcomeUntilReplacement() = withSlot { f, slot ->
        val pending = checkNotNull(slot.begin(checkNotNull(f.lifecycle.connection), "running", retainOutcome = true))
        slot.submit(f.tasks, pending, failure = { "failure" }) {
            f.lifecycle.disconnect(f.first)
            assertTrue(slot.active)
            "accepted"
        }
        f.tasks.runFirst()
        assertEquals("accepted", slot.state.value)
        assertFalse(slot.active)
        slot.bind(null)
        assertEquals("accepted", slot.state.value)
        f.lifecycle.connect(f.second)
        assertEquals("none", slot.state.value)
    }

    @Test fun detachAfterCompletionAlsoKeepsTheMutationOutcome() = withSlot { f, slot ->
        val pending = checkNotNull(slot.begin(checkNotNull(f.lifecycle.connection), "running", retainOutcome = true))
        slot.submit(f.tasks, pending, failure = { "failure" }) { "accepted" }
        f.tasks.runFirst()
        f.lifecycle.disconnect(f.first)
        assertEquals("accepted", slot.state.value)
        assertFalse(slot.active)
    }

    @Test fun oldCancellationDoesNotCancelANewRequest() = withSlot { f, slot ->
        val old = checkNotNull(slot.begin(checkNotNull(f.lifecycle.connection), "old"))
        f.lifecycle.connect(f.second)
        f.tasks.runFirst()
        val current = checkNotNull(slot.begin(checkNotNull(f.lifecycle.connection), "new"))
        old.cancel()
        assertTrue(old.cancellationRequested)
        assertFalse(current.cancellationRequested)
        slot.cancel()
        assertTrue(current.cancellationRequested)
    }

    @Test fun rejectionReturningAfterReplacementCannotPublishAnOldFailure() = withSlot { f, slot ->
        val old = checkNotNull(slot.begin(checkNotNull(f.lifecycle.connection), "old"))
        val executor = Executor {
            f.lifecycle.connect(f.second)
            f.tasks.runFirst()
            checkNotNull(slot.begin(checkNotNull(f.lifecycle.connection), "new"))
            throw RejectedExecutionException("private detail")
        }
        slot.submit(executor, old, failure = { "old failure" }) { error("never starts") }
        assertEquals("new", slot.state.value)
        assertTrue(slot.active)
    }

    @Test fun duplicateBindingKeepsTheQueuedRequestAndSingleAdmission() = withSlot { f, slot ->
        val live = checkNotNull(f.lifecycle.connection)
        val pending = checkNotNull(slot.begin(live, "running"))
        slot.bind(live)
        assertEquals(null, slot.begin(live, "duplicate"))
        slot.submit(f.tasks, pending, failure = { "failure" }) { "finished" }
        f.tasks.runFirst()
        assertEquals("finished", slot.state.value)
        assertFalse(slot.active)
    }

    private fun withSlot(work: (AdbLifecycleFixture, AdbOperationSlot<String>) -> Unit) {
        AdbLifecycleFixture(temporary.newFolder()).use { f ->
            val slot = AdbOperationSlot("none")
            f.bindHook = { slot.bind(it) }
            f.unbindHook = { slot.bind(null) }
            f.replacingHook = { slot.bind(null, discard = true) }
            f.lifecycle.connect(f.first)
            f.tasks.runFirst()
            work(f, slot)
        }
    }
}
