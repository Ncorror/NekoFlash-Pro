package io.github.ncorror.nekoflash.adb

import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticSink
import io.github.ncorror.nekoflash.protocol.adb.AdbConnection
import io.github.ncorror.nekoflash.protocol.adb.AdbInteractiveShell
import io.github.ncorror.nekoflash.protocol.adb.AdbShellEvent
import io.github.ncorror.nekoflash.protocol.adb.AdbShellMode
import io.github.ncorror.nekoflash.terminal.TerminalShellProfile
import io.github.ncorror.nekoflash.terminal.VtScreen
import io.github.ncorror.nekoflash.terminal.VtSnapshot
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
    /** Экран терминала: история и текущие строки с оформлением. */
    val screen: VtSnapshot = VtSnapshot.EMPTY,
    /** Чем закончилась последняя сессия, если она закончилась. */
    val ended: String? = null,
    /** Какой терминал открыт: обычный, `adb shell -T` или `adb shell -x`. */
    val mode: AdbShellMode = AdbShellMode.PTY,
    /** Чем вкладку назвать: первое слово команды, с которой она открыта. */
    val title: String? = null,
) {
    /** Вывод без оформления. */
    val output: String get() = screen.text
}

/** Клавиши, которых нет в строке ввода: для программ во весь экран вроде `top`. */
public enum class TerminalKey { ESCAPE, TAB, UP, DOWN, LEFT, RIGHT, HOME, END, PAGE_UP, PAGE_DOWN }

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
    /**
     * Логотип и приглашение как у NetHunter (D078) — только для обычного
     * терминала: у `-T` и `-x` приглашения нет или оно чужое.
     */
    private val profile: Boolean = false,
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

    /** Размер окна, который назвал экран; до первого замера — 80 × 24, как у терминала по умолчанию. */
    @Volatile
    private var size = WindowSize(VtScreen.DEFAULT_COLUMNS, VtScreen.DEFAULT_ROWS)

    /** Экран живой сессии; кормит его читающий цикл, меняет размер — экран приложения. */
    private val screenLock = Any()
    private var screen: VtScreen? = null

    /**
     * Строка, которую оболочка получит, как только устройство подтвердит `OPEN`.
     *
     * Так `adb logcat` и `adb shell -t КОМАНДА` становятся командой в живой
     * оболочке с Ctrl+C и окном вывода, а не разовым вызовом, который у
     * `logcat` не кончился бы никогда.
     */
    private var pendingLine: String? = null

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
    public fun start(connection: AdbConnection, firstLine: String? = null, mode: AdbShellMode = AdbShellMode.PTY) {
        val requestId = synchronized(lifecycleLock) {
            if (activeRequestId != null) return
            nextRequestId += 1
            activeRequestId = nextRequestId
            pendingLine = firstLine
            mutableState.value = AdbTerminalState(active = true, mode = mode, title = firstLine?.substringBefore(' '))
            nextRequestId
        }
        readerExecutor.execute { openAndPump(connection, requestId, mode) }
    }

    /** Передаёт строку вместе с Enter, только когда peer подтвердил `OPEN`. */
    public fun sendInput(text: String, enter: Boolean = true) {
        val session = currentReadyShell() ?: return
        writerExecutor.execute { session.sendInput(if (enter) text + "\n" else text) }
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

    private fun openAndPump(connection: AdbConnection, requestId: Long, mode: AdbShellMode) {
        if (markReaderStarted(requestId)) {
            val styled = profile && mode == AdbShellMode.PTY
            val session = connection.interactiveShell(
                diagnostics = diagnostics,
                mode = mode,
                term = VtScreen.TERM,
                command = if (styled) TerminalShellProfile.command else "",
            )
            when {
                !session.open() -> finishSession(session, requestId, OPEN_FAILED)
                bindShell(session, requestId) -> pumpUntilClosed(session, requestId, styled)
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
    private fun pumpUntilClosed(session: AdbInteractiveShell, requestId: Long, styled: Boolean) {
        // Ответы терминала (`DSR`, `DA`) уходят в оболочку тем же вводом.
        val terminal = VtScreen(size.columns, size.rows) { answer ->
            writerExecutor.execute { session.sendInput(answer) }
        }
        synchronized(screenLock) {
            if (styled) terminal.feed(TerminalShellProfile.banner(size.columns))
            screen = terminal
        }
        // Логотип виден сразу, пока устройство ещё открывает поток.
        if (styled) publishRunningState(requestId, ready = false, terminal = terminal)
        var progress = PumpProgress()
        var shown = ShownState()

        while (session.active && isCurrent(requestId)) {
            progress = consumePumpEvents(session, requestId, terminal, progress)
            if (shown.shouldPublish(progress, elapsedNanos())) {
                publishRunningState(requestId, progress.ready, terminal)
                shown = ShownState(progress.received, progress.ready, elapsedNanos())
            }
        }

        val last = synchronized(screenLock) {
            screen = null
            terminal.snapshot()
        }
        finishSession(session, requestId, progress.ended ?: CLOSED, last)
    }

    /**
     * Экран приложения назвал новый размер: экран терминала перестраивается, а
     * устройство получает его рамкой `shell,v2`, как при `SIGWINCH` у
     * настольного клиента.
     */
    public fun resize(columns: Int, rows: Int) {
        val next = WindowSize(columns, rows)
        if (columns <= 0 || rows <= 0 || next == size) return
        size = next
        synchronized(screenLock) { screen?.resize(columns, rows) }
        currentReadyShell()?.let { session -> writerExecutor.execute { session.resize(rows, columns) } }
    }

    /** Клавиша без Enter; стрелки — в режиме, который включила программа (`?1h`). */
    public fun sendKey(key: TerminalKey) {
        val session = currentReadyShell() ?: return
        val application = synchronized(screenLock) { screen?.applicationCursorKeys } == true
        val arrow = if (application) "\u001BO" else "\u001B["
        val bytes = when (key) {
            TerminalKey.ESCAPE -> "\u001B"
            TerminalKey.TAB -> "\t"
            TerminalKey.UP -> arrow + "A"
            TerminalKey.DOWN -> arrow + "B"
            TerminalKey.RIGHT -> arrow + "C"
            TerminalKey.LEFT -> arrow + "D"
            // Как у xterm: Home и End следуют режиму курсора, страницы — нет.
            TerminalKey.HOME -> arrow + "H"
            TerminalKey.END -> arrow + "F"
            TerminalKey.PAGE_UP -> "\u001B[5~"
            TerminalKey.PAGE_DOWN -> "\u001B[6~"
        }
        writerExecutor.execute { session.sendInput(bytes) }
    }

    private data class WindowSize(val columns: Int, val rows: Int)

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
        terminal: VtScreen,
        previous: PumpProgress,
    ): PumpProgress {
        var ready = previous.ready
        var ended = previous.ended
        var received = previous.received
        for (event in session.pump()) {
            received += 1
            when (event) {
                AdbShellEvent.Opened -> {
                    ready = true
                    onOpened(session, requestId)
                }
                AdbShellEvent.WriteReady -> writerExecutor.execute { session.flushPendingWrites() }
                else -> ended = applyEvent(event, terminal, screenLock) ?: ended
            }
        }
        return PumpProgress(ready, ended, received)
    }

    /** Открытие подтверждено: закрыть, если просили, иначе отдать первую строку. */
    private fun onOpened(session: AdbInteractiveShell, requestId: Long) {
        if (isClosing(requestId)) {
            scheduleClose(session, requestId)
            return
        }
        // Размер окна — первым, как `stdin_read_thread_loop` настольного клиента.
        val current = size
        writerExecutor.execute { session.resize(current.rows, current.columns) }
        synchronized(lifecycleLock) { pendingLine.also { pendingLine = null } }
            ?.let { line -> writerExecutor.execute { session.sendInput(line + "\n") } }
    }

    private fun publishRunningState(requestId: Long, ready: Boolean, terminal: VtScreen) {
        val snapshot = synchronized(screenLock) { terminal.snapshot() }
        synchronized(lifecycleLock) {
            if (activeRequestId == requestId) {
                val closing = mutableState.value.closing
                mutableState.value = AdbTerminalState(
                    active = true,
                    ready = ready && !closing,
                    closing = closing,
                    screen = snapshot,
                    mode = mutableState.value.mode,
                    title = mutableState.value.title,
                )
            }
        }
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
        last: VtSnapshot = VtSnapshot.EMPTY,
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
                screen = last,
                ended = ended,
                mode = mutableState.value.mode,
                title = mutableState.value.title,
            )
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


        /**
         * Как часто вывод попадает на экран.
         *
         * Двадцать раз в секунду человеку читается как непрерывный поток, а
         * разбор ящика от перерисовки больше не зависит.
         */
        const val PUBLISH_INTERVAL_NANOS = 50L * 1_000_000L
    }
}

/** Прикладывает событие вывода и возвращает причину завершения. */
private fun applyEvent(event: AdbShellEvent, terminal: VtScreen, lock: Any): String? = when (event) {
    AdbShellEvent.Opened, AdbShellEvent.WriteReady -> null

    is AdbShellEvent.Output -> {
        synchronized(lock) { terminal.feed(event.text) }
        null
    }

    // stdout и stderr идут на один экран: в терминале они и должны быть
    // перемешаны в порядке появления, как на настоящем экране.
    is AdbShellEvent.ErrorOutput -> {
        synchronized(lock) { terminal.feed(event.text) }
        null
    }

    is AdbShellEvent.Exited -> event.code?.let { code -> "exit $code" } ?: "exit"
    is AdbShellEvent.Broken -> event.reason
}
