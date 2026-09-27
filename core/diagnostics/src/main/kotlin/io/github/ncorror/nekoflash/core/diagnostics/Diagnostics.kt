package io.github.ncorror.nekoflash.core.diagnostics

import io.github.ncorror.nekoflash.core.model.SessionGeneration
import io.github.ncorror.nekoflash.core.model.TargetId
import java.time.Instant

data class DiagnosticEvent(
    val timestamp: Instant,
    val category: String,
    val message: String,
    val targetId: TargetId? = null,
    val sessionGeneration: SessionGeneration? = null,
    val fields: Map<String, String> = emptyMap(),
) {
    init {
        require(category.isNotBlank()) { "Diagnostic category must not be blank" }
        require(message.isNotBlank()) { "Diagnostic message must not be blank" }
    }
}

fun interface DiagnosticSink {
    fun emit(event: DiagnosticEvent)
}

/**
 * Приёмник событий с ограниченной памятью.
 *
 * Ограничение обязательно: владелец USB живёт всё время работы приложения, и
 * неограниченный список рос бы часами.
 *
 * Число отброшенных событий сохраняется и попадает в evidence. Молча потерять
 * начало журнала — значит выдать неполный отчёт за полный, а это хуже, чем
 * честно сказать, что запись обрезана.
 */
class InMemoryDiagnosticSink(
    private val capacity: Int = DEFAULT_CAPACITY,
) : DiagnosticSink {
    init {
        require(capacity > 0) { "Diagnostic capacity must be positive" }
    }

    private val lock = Any()
    private val events = ArrayDeque<DiagnosticEvent>()
    private var dropped = 0L

    override fun emit(event: DiagnosticEvent) {
        synchronized(lock) {
            events.addLast(event)
            while (events.size > capacity) {
                events.removeFirst()
                dropped += 1L
            }
        }
    }

    fun snapshot(): List<DiagnosticEvent> = synchronized(lock) { events.toList() }

    /** Сколько самых старых событий вытеснено ограничением. */
    fun droppedCount(): Long = synchronized(lock) { dropped }

    fun clear() {
        synchronized(lock) {
            events.clear()
            dropped = 0L
        }
    }

    companion object {
        /** Сколько событий помнится по умолчанию. */
        const val DEFAULT_CAPACITY: Int = 2000
    }
}
