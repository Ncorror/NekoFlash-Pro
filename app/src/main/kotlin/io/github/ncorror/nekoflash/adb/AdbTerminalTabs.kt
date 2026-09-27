package io.github.ncorror.nekoflash.adb

import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticSink
import io.github.ncorror.nekoflash.protocol.adb.AdbConnection
import io.github.ncorror.nekoflash.protocol.adb.AdbShellMode
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Вкладки терминала: у каждой своя оболочка и свой экран. */
public data class TerminalTabs(
    /** Состояния вкладок; каждое меняется своим потоком. */
    val tabs: List<StateFlow<AdbTerminalState>>,
    val selected: Int,
)

/**
 * Несколько оболочек одновременно — как несколько окон с `adb shell` у
 * настольного клиента.
 *
 * Потоки ADB у вкладок свои, читающий цикл у каждой свой, а писатель общий:
 * записи на провод всё равно идут по одной. Вкладка остаётся хотя бы одна.
 */
public class AdbTerminalTabs(
    private val readerExecutor: Executor,
    private val writerExecutor: Executor,
    private val diagnostics: DiagnosticSink,
) {
    private val lock = Any()
    private val controllers = mutableListOf(newController())
    private var selected = 0
    private val mutableState = MutableStateFlow(snapshot())

    public val state: StateFlow<TerminalTabs> = mutableState.asStateFlow()

    /** Вкладка, на которую смотрит оператор. */
    public val current: AdbTerminalController
        get() = synchronized(lock) { controllers[selected] }

    /**
     * Открывает оболочку в выбранной вкладке, а если в ней уже идёт своя, —
     * в новой: команда настольного клиента заводит свою сессию, а не пишет в
     * чужую.
     */
    public fun start(connection: AdbConnection, firstLine: String?, mode: AdbShellMode, newIfBusy: Boolean) {
        val target = synchronized(lock) {
            if (newIfBusy && controllers[selected].active) addLocked()
            controllers[selected]
        }
        target.start(connection, firstLine, mode)
        publish()
    }

    public fun add() {
        synchronized(lock) { addLocked() }
        publish()
    }

    public fun select(index: Int) {
        synchronized(lock) { if (index in controllers.indices) selected = index }
        publish()
    }

    /** Закрывает вкладку и её оболочку; последняя вкладка только очищается закрытием оболочки. */
    public fun close(index: Int) {
        val closing = synchronized(lock) {
            if (index !in controllers.indices) return
            val controller = controllers[index]
            if (controllers.size > 1) {
                controllers.removeAt(index)
                selected = selected.coerceAtMost(controllers.size - 1)
            }
            controller
        }
        closing.stop()
        publish()
    }

    /** Транспорт кончился: гаснут все вкладки. */
    public fun stopAll() {
        synchronized(lock) { controllers.toList() }.forEach { it.stop() }
    }

    private fun addLocked() {
        controllers += newController()
        selected = controllers.lastIndex
    }

    private fun newController() = AdbTerminalController(readerExecutor, writerExecutor, diagnostics)

    private fun snapshot(): TerminalTabs = synchronized(lock) { TerminalTabs(controllers.map { it.state }, selected) }

    private fun publish() {
        mutableState.value = snapshot()
    }
}
