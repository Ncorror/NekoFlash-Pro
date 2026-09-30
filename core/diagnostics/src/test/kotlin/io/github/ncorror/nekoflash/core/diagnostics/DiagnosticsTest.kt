package io.github.ncorror.nekoflash.core.diagnostics

import java.time.Instant
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsTest {
    @Test
    fun sinkPreservesStructuredEvents() {
        val sink = InMemoryDiagnosticSink()
        val event = DiagnosticEvent(
            timestamp = Instant.EPOCH,
            category = "bootstrap",
            message = "phase1",
            fields = mapOf("status" to "ready"),
        )

        sink.emit(event)

        assertEquals(listOf(event), sink.snapshot())
    }

    @Test
    fun theSinkForgetsTheOldestEventsAndAdmitsHowMany() {
        val sink = InMemoryDiagnosticSink(capacity = 2)

        repeat(5) { index ->
            sink.emit(DiagnosticEvent(Instant.EPOCH, "usb", "event$index"))
        }

        assertEquals(listOf("event3", "event4"), sink.snapshot().map { it.message })
        assertEquals(3L, sink.droppedCount())
    }

    @Test
    fun nothingIsDroppedWhileTheLimitIsNotReached() {
        val sink = InMemoryDiagnosticSink(capacity = 4)

        sink.emit(DiagnosticEvent(Instant.EPOCH, "usb", "only"))

        assertEquals(1, sink.snapshot().size)
        assertEquals(0L, sink.droppedCount())
    }

    @Test
    fun clearingForgetsTheDropCountToo() {
        val sink = InMemoryDiagnosticSink(capacity = 1)
        sink.emit(DiagnosticEvent(Instant.EPOCH, "usb", "first"))
        sink.emit(DiagnosticEvent(Instant.EPOCH, "usb", "second"))

        sink.clear()

        assertTrue(sink.snapshot().isEmpty())
        assertEquals(0L, sink.droppedCount())
    }

    @Test
    fun aNonPositiveCapacityIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { InMemoryDiagnosticSink(capacity = 0) }
        assertThrows(IllegalArgumentException::class.java) { InMemoryDiagnosticSink(capacity = -1) }
    }
}
