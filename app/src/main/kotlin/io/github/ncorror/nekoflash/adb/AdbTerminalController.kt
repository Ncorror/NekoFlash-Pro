package io.github.ncorror.nekoflash.adb

import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticSink
import io.github.ncorror.nekoflash.protocol.adb.AdbConnection
import io.github.ncorror.nekoflash.protocol.adb.AdbInteractiveShell
import io.github.ncorror.nekoflash.protocol.adb.AdbShellEvent
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Состояние интерактивной оболочки. */
public data class AdbTerminalState(
    /** Идёт ли открытие, живая сессия или её корректное закрытие. */
    val active: Boolean = false,
    /** Подтвердило ли устройство `OPEN`; только после этого допустим ввод. */
    val ready: Boolean = false,
    /** Запрошено ли закрытие; reader остаётся занят до завершения CLSE. */
    val closing: Boolean = false,
    /** Накопленный вывод. */
    val output: String = "",
    /** Чем закончилась последняя сессия, если она закончилась. */
    val ended: String? = null,
)

/**
 * Владелец интерактивной сессии оболочки.
 *
 * Отделён от владельца соединения намеренно: тот следит за жизнью транспорта, а
 * этот — за жизнью одной сессии. Внутри у них разное время жизни и разные
 * причины заканчиваться.
 *
 * [readerExecutor] занят читающим циклом, пока сессия жива. Физический reader
 * остаётся ровно один. Ввод и закрытие идут через отдельный [writerExecutor]:
 * Android `bulkTransfer` блокирующий и может ждать секунды, поэтому ни одна
 * операция терминала не выполняется на UI thread. Порядок записей и состояние
 * маршрутизатора сериализует замок внутри [AdbInteractiveShell].
 */
public class AdbTerminalController(
    private val readerExecutor: Executor,
    private val writerExecutor: Executor,
    private val diagnostics: DiagnosticSink = DiagnosticSink { },
    private val elapsedNanos: () -> Long = { System.nanoTime() },
) {
    private val mutableState = MutableStateFlow(AdbTerminalState())
    private val lifecycleLock = Any()

    private var nextRequestId = 0L
    private var activeRequestId: Long? = null
    private var readerRequestId: Long? = null
    private var shellRequestId: Long? = null
    private var closeScheduledRequestId: Long? = null

    @Volatile
    private var shell: AdbInteractiveShell? = null

    /** Состояние сессии. Экран подписывается и ничего не опрашивает. */
    public val state: StateFlow<AdbTerminalState> = mutableState.asStateFlow()

    /** Идёт ли открытие или живая сессия прямо сейчас. */
    public val active: Boolean
        get() = synchronized(lifecycleLock) { activeRequestId != null }

    /**
     * Запрашивает открытие оболочки и сразу возвращается.
     *
     * Даже исходный `OPEN` отправляется на [readerExecutor], а не из callback
     * Compose. До события [AdbShellEvent.Opened] состояние остаётся
     * `ready=false`, поэтому ранний ввод не может молча потеряться.
     */
    public fun start(connection: AdbConnection) {
        val requestId = synchronized(lifecycleLock) {
            if (activeRequestId != null) return
            nextRequestId += 1
            activeRequestId = nextRequestId
            mutableState.value = AdbTerminalState(active = true)
            nextRequestId
        }
        readerExecutor.execute { openAndPump(connection, requestId) }
    }

    /** Передаёт строку вместе с Enter, только когда peer подтвердил `OPEN`. */
    public fun sendInput(text: String) {
        val session = currentReadyShell() ?: return
        writerExecutor.execute { session.sendInput(text + "\n") }
    }

    /** Прерывает текущую команду, не закрывая оболочку. */
    public fun interrupt() {
        val session = currentReadyShell() ?: return
        writerExecutor.execute { session.interrupt() }
    }

    /**
     * Закрывает оболочку; блокирующая USB-запись также уходит с UI thread.
     *
     * Reader ownership не освобождается, пока worker закрытия не отправил
     * `CLSE`, а читающий цикл не вышел. Иначе следующий service call мог бы
     * начать использовать тот же ADB transport параллельно с ещё не
     * завершившимся закрытием Terminal.
     */
    public fun stop() {
        val closeRequest = synchronized(lifecycleLock) {
            val requestId = activeRequestId
            when {
                requestId == null || mutableState.value.closing -> null
                readerRequestId != requestId -> {
                    // Reader task ещё даже не начал выполняться. Его можно отменить
                    // без единой USB-операции; queued task увидит stale request.
                    activeRequestId = null
                    mutableState.value = mutableState.value.copy(
                        active = false,
                        ready = false,
                        closing = false,
                        ended = CLOSED,
                    )
                    null
                }

                else -> prepareClose(requestId)
            }
        }
        closeRequest?.let { (session, requestId) -> scheduleClose(session, requestId) }
    }

    private fun prepareClose(requestId: Long): Pair<AdbInteractiveShell, Long>? {
        val wasReady = mutableState.value.ready
        mutableState.value = mutableState.value.copy(
            active = true,
            ready = false,
            closing = true,
        )

        // До OKAY remoteId неизвестен, а AdbStreamRouter не может сформировать
        // корректный CLSE. При остановке во время OPEN продолжаем pump до
        // OKAY/CLSE и только затем закрываем.
        return shell.takeIf { wasReady && shellRequestId == requestId }?.let { it to requestId }
    }

    private fun openAndPump(connection: AdbConnection, requestId: Long) {
        if (markReaderStarted(requestId)) {
            val session = connection.interactiveShell(diagnostics)
            when {
                !session.open() -> finishSession(session, requestId, OPEN_FAILED)
                bindShell(session, requestId) -> pumpUntilClosed(session, requestId)
                else -> session.close()
            }
        }
    }

    private fun markReaderStarted(requestId: Long): Boolean = synchronized(lifecycleLock) {
        if (activeRequestId == requestId) {
            readerRequestId = requestId
            true
        } else {
            false
        }
    }

    private fun bindShell(session: AdbInteractiveShell, requestId: Long): Boolean =
        synchronized(lifecycleLock) {
            if (activeRequestId == requestId) {
                shell = session
                shellRequestId = requestId
                true
            } else {
                false
            }
        }

    /**
     * Читающий цикл одной сессии.
     *
     * Разбор ящика и показ на экране **развязаны по времени**. Раньше каждая
     * порция вывода тут же копировала весь журнал сессии и толкала его в
     * состояние экрана; при `logcat` это означало десятки копий по 64 КиБ в
     * секунду и столько же перерисовок, а ящик потока в это время наполнялся.
     * Прогон `07` §6.41 показал переполнение именно на `logcat`.
     *
     * Забираем поэтому так быстро, как приходит, а показываем не чаще
     * [PUBLISH_INTERVAL_MS]: терминалу этого хватает, а темп разбора перестаёт
     * зависеть от скорости перерисовки.
     */
    private fun pumpUntilClosed(session: AdbInteractiveShell, requestId: Long) {
        val text = StringBuilder()
        var progress = PumpProgress()
        var shown = ShownState()

        while (session.active && isCurrent(requestId)) {
            progress = consumePumpEvents(session, requestId, text, progress)
            trimToLimit(text)
            if (shown.shouldPublish(progress, elapsedNanos())) {
                publishRunningState(requestId, progress.ready, text)
                shown = ShownState(progress.received, progress.ready, elapsedNanos())
            }
        }

        finishSession(session, requestId, progress.ended ?: CLOSED, text.toString())
    }

    /**
     * Что уже показано на экране.
     *
     * Держится отдельно от [PumpProgress], потому что отвечает на другой
     * вопрос: не «что пришло», а «что из этого пользователь уже видит».
     */
    private data class ShownState(
        val received: Long = -1,
        val ready: Boolean = false,
        val atNanos: Long = 0,
    ) {
        /**
         * Показывать ли сейчас.
         *
         * Готовность оболочки показывается сразу: её ждёт человек, и придержать
         * её на интервал значило бы соврать, что приглашения ещё нет.
         */
        fun shouldPublish(progress: PumpProgress, nowNanos: Long): Boolean {
            if (progress.ready != ready) return true
            if (progress.received == received) return false
            return nowNanos - atNanos >= PUBLISH_INTERVAL_NANOS
        }
    }

    private fun consumePumpEvents(
        session: AdbInteractiveShell,
        requestId: Long,
        text: StringBuilder,
        previous: PumpProgress,
    ): PumpProgress {
        var ready = previous.ready
        var ended = previous.ended
        var received = previous.received
        for (event in session.pump()) {
            received += 1
            if (event == AdbShellEvent.Opened) {
                ready = true
                if (isClosing(requestId)) scheduleClose(session, requestId)
            } else {
                ended = apply(event, text) ?: ended
            }
        }
        return PumpProgress(ready, ended, received)
    }

    private fun publishRunningState(requestId: Long, ready: Boolean, text: StringBuilder) {
        synchronized(lifecycleLock) {
            if (activeRequestId == requestId) {
                val closing = mutableState.value.closing
                mutableState.value = AdbTerminalState(
                    active = true,
                    ready = ready && !closing,
                    closing = closing,
                    output = text.toString(),
                )
            }
        }
    }

    /** Прикладывает событие вывода и возвращает причину завершения. */
    private fun apply(event: AdbShellEvent, text: StringBuilder): String? = when (event) {
        AdbShellEvent.Opened -> null

        is AdbShellEvent.Output -> {
            text.append(event.text)
            null
        }

        // stdout и stderr идут в один поток текста: в терминале они и должны
        // быть перемешаны в порядке появления, как на настоящем экране.
        is AdbShellEvent.ErrorOutput -> {
            text.append(event.text)
            null
        }

        is AdbShellEvent.Exited -> event.code?.let { code -> "exit $code" } ?: "exit"
        is AdbShellEvent.Broken -> event.reason
    }

    private fun scheduleClose(session: AdbInteractiveShell, requestId: Long) {
        val schedule = synchronized(lifecycleLock) {
            if (activeRequestId != requestId || closeScheduledRequestId == requestId) {
                false
            } else {
                closeScheduledRequestId = requestId
                true
            }
        }
        if (schedule) writerExecutor.execute { session.close() }
    }

    private fun currentReadyShell(): AdbInteractiveShell? = synchronized(lifecycleLock) {
        val requestId = activeRequestId ?: return null
        if (!mutableState.value.ready || shellRequestId != requestId) return null
        shell
    }

    private fun isCurrent(requestId: Long): Boolean =
        synchronized(lifecycleLock) { activeRequestId == requestId }

    private fun isClosing(requestId: Long): Boolean = synchronized(lifecycleLock) {
        activeRequestId == requestId && mutableState.value.closing
    }

    private fun finishSession(
        session: AdbInteractiveShell,
        requestId: Long,
        ended: String,
        output: String = "",
    ) {
        synchronized(lifecycleLock) {
            if (shell === session && shellRequestId == requestId) {
                shell = null
                shellRequestId = null
            }
            if (activeRequestId != requestId) return
            activeRequestId = null
            if (readerRequestId == requestId) readerRequestId = null
            if (closeScheduledRequestId == requestId) closeScheduledRequestId = null
            mutableState.value = AdbTerminalState(
                active = false,
                ready = false,
                output = output,
                ended = ended,
            )
        }
    }

    /** Держит вывод в пределах памяти; `logcat` может быть бесконечным. */
    private fun trimToLimit(text: StringBuilder) {
        if (text.length > MAX_TERMINAL_CHARS) {
            text.delete(0, text.length - MAX_TERMINAL_CHARS)
        }
    }

    private data class PumpProgress(
        val ready: Boolean = false,
        val ended: String? = null,
        /** Счётчик принятого: по нему видно, изменилось ли что-нибудь. */
        val received: Long = 0,
    )

    private companion object {
        const val OPEN_FAILED = "open failed"
        const val CLOSED = "closed"

        /** Сколько символов вывода держится на экране. */
        const val MAX_TERMINAL_CHARS = 64 * 1024

        /**
         * Как часто вывод попадает на экран.
         *
         * Двадцать раз в секунду человеку читается как непрерывный поток, а
         * разбор ящика от перерисовки больше не зависит.
         */
        const val PUBLISH_INTERVAL_NANOS = 50L * 1_000_000L
    }
}
