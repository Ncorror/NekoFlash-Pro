package io.github.ncorror.nekoflash.protocol.adb

import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticEvent
import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticSink
import java.time.Clock
import java.time.Instant

/** Что произошло за один шаг интерактивной сессии. */
public sealed interface AdbShellEvent {
    /** Устройство подтвердило OPEN, ввод теперь можно отправлять. */
    public data object Opened : AdbShellEvent

    /** Peer подтвердил предыдущую запись; можно отправить следующую из очереди. */
    public data object WriteReady : AdbShellEvent

    /** Обычный вывод. */
    public data class Output(val text: String) : AdbShellEvent

    /** Вывод ошибок. Отдельно, потому что `shell,v2` их разделяет. */
    public data class ErrorOutput(val text: String) : AdbShellEvent

    /** Оболочка завершилась. */
    public data class Exited(val code: Int?) : AdbShellEvent

    /** Сессия оборвалась. */
    public data class Broken(val reason: String) : AdbShellEvent
}

/** Какой терминал просят у `adb shell`: обычный, `-T` или `-x`. */
public enum class AdbShellMode { PTY, RAW, LEGACY }

/**
 * Живая оболочка на устройстве.
 *
 * Отличается от одноразовой команды тем, что поток **живёт**: данные приходят
 * когда захотят, ввод уходит когда его напечатали, и конца у этого нет, пока
 * оболочка не завершится. Поэтому здесь нет метода «выполнить и дождаться» —
 * есть [pump], один шаг, который владелец вызывает в своём цикле.
 *
 * Шаг сделан обычной функцией намеренно: поток исполнения принадлежит
 * приложению, а тесты обходятся без него и остаются определёнными. Так же
 * разделены пакетный автомат и транспорт во всём остальном модуле.
 *
 * Пока сессия открыта, по этому соединению спокойно идут и одноразовые
 * команды, и файловые операции: у каждой свой логический поток и свой ящик
 * (`docs/adr/0004_CONCURRENT_ADB_DISPATCHER_RU.md`). Раньше здесь стоял запрет —
 * он держался на том, что читатель один и потребитель поэтому мог быть только
 * один; читатель по-прежнему один, но крутит его цикл соединения.
 *
 * Внутри класса потоков **два**: читающий цикл зовёт [pump], а ввод приходит
 * оттуда, где его печатают. Приём и передача идут по разным эндпоинтам и могут
 * идти одновременно, но состояние маршрутизатора и порядок записи у них общие,
 * поэтому всё, что их касается, защищено замком. Само ожидание пакета остаётся
 * снаружи замка: держать его все 250 мс значило бы задерживать каждое нажатие
 * клавиши на это время.
 *
 * Устройство с `shell,v2` получает `shell,v2,pty:` — настоящий терминал с
 * разделением потоков. Без него остаётся `shell:`, где нет ни разделения, ни
 * кода возврата; так же выбирает Legacy. [raw] — `adb shell -T`:
 * `shell,v2,raw:`, те же рамки без терминала.
 */
public class AdbInteractiveShell(
    private val writer: AdbPacketWriter,
    private val dispatcher: AdbStreamDispatcher,
    private val useShellV2: Boolean,
    private val diagnostics: DiagnosticSink = DiagnosticSink { },
    private val raw: Boolean = false,
    /** `TERM=` в строке сервиса `shell,v2`, как у настольного клиента; `null` — не передавать. */
    private val term: String? = null,
    /**
     * Команда после `:` — как у `adb shell -t КОМАНДА`: устройство выполняет её
     * через `sh -c` в том же терминале. Пустая — оболочка входа, как `adb shell`.
     */
    private val command: String = "",
    private val clock: () -> Instant = { Clock.systemUTC().instant() },
) {
    private val frames = AdbShellFrameBuffer()
    private val stdoutText = IncrementalUtf8Decoder()
    private val stderrText = IncrementalUtf8Decoder()
    private val legacyText = IncrementalUtf8Decoder()

    /** Защищает диспетчер, писателя и состояние сессии. */
    private val lock = Any()
    private val pendingWrites = ArrayDeque<ByteArray>()
    private var pendingWriteBytes = 0
    private var awaitingWriteAck = false

    /**
     * Ящик открытого потока.
     *
     * Ящик наполняет `AdbDispatchLoop`, один на соединение
     * (`docs/adr/0004_CONCURRENT_ADB_DISPATCHER_RU.md`). Сессия транспорт не
     * читает: [pump] ждёт на ящике.
     */
    private var mailbox: AdbStreamMailbox? = null

    private var localId: Int = 0

    @Volatile
    private var opened = false

    @Volatile
    private var finished = false

    /** Открыта ли сессия и имеет ли смысл её качать. */
    public val active: Boolean
        get() = localId != 0 && !finished

    /**
     * Открывает оболочку.
     *
     * Возвращает `false`, если запрос не удалось даже отправить. Подтверждение
     * открытия придёт позже, отдельным шагом: устройство отвечает `OKAY`, когда
     * сочтёт нужным.
     */
    public fun open(): Boolean = synchronized(lock) {
        if (localId != 0 || finished) return false
        val service = when {
            !useShellV2 -> SERVICE_LEGACY
            term == null -> if (raw) SERVICE_RAW else SERVICE_PTY
            // `ShellServiceString`: shell,v2,TERM=…,pty: — в этом порядке.
            else -> "shell,v2,TERM=$term,${if (raw) "raw" else "pty"}:"
        } + command
        val (box, packet) = dispatcher.open(service, acknowledgements = true)
        mailbox = box
        val id = box.localId
        localId = id
        emit("shell_open", mapOf("service" to service, "stream" to id.toString()))
        if (!send(packet)) {
            // Remove the local router entry even when OPEN itself could not be
            // written; otherwise a later stream can retain a dead id forever.
            dispatcher.close(id)?.let(::send)
            finished = true
            mailbox = null
            localId = 0
            return false
        }
        return true
    }

    /**
     * Передаёт ввод в оболочку.
     *
     * С `shell,v2` ввод заворачивается в рамку `stdin`, без него уходит как
     * есть. Перевод строки не добавляется: его печатает тот, кто вводит, и
     * дописывать за него означало бы выполнять команду, которую не просили.
     */
    public fun sendInput(text: String): Boolean = sendInput(text.toByteArray(Charsets.UTF_8))

    /** Тот же ввод байтами: для управляющих символов вроде `Ctrl+C`. */
    public fun sendInput(bytes: ByteArray): Boolean = synchronized(lock) {
        val payload = if (useShellV2) AdbShellProtocol.encode(AdbShellProtocol.ID_STDIN, bytes) else bytes
        val queued = if (!opened || finished) {
            false
        } else {
            enqueue(payload) && if (awaitingWriteAck) true else flushPendingWrites()
        }
        emit(
            "shell_input",
            mapOf("stream" to localId.toString(), "bytes" to bytes.size.toString(), "queued" to queued.toString()),
        )
        return queued
    }

    /**
     * Сообщает новый размер окна — `send_window_size_change` настольного
     * клиента: рамка 5 с текстом `СТРОКИxСТОЛБЦЫ,0x0` и нулём на конце.
     * Без `shell,v2` размер передать нечем: старые устройства его не знают.
     */
    public fun resize(rows: Int, columns: Int): Boolean = synchronized(lock) {
        if (!useShellV2 || !opened || finished) return false
        if (rows !in 1..MAX_WINDOW_DIMENSION || columns !in 1..MAX_WINDOW_DIMENSION) return false
        val text = "${rows}x$columns,0x0\u0000".toByteArray(Charsets.US_ASCII)
        val frame = AdbShellProtocol.encode(AdbShellProtocol.ID_WINDOW_SIZE_CHANGE, text)
        // A resize burst is common while the IME or rotation settles. Keep only
        // the newest unsent window frame; sending every intermediate size can
        // reorder with input and makes full-screen programs redraw repeatedly.
        if (pendingWrites.lastOrNull()?.firstOrNull()?.toInt()?.and(0xFF) ==
            AdbShellProtocol.ID_WINDOW_SIZE_CHANGE
        ) {
            val old = pendingWrites.removeLast()
            pendingWriteBytes -= old.size
        }
        if (!enqueue(frame)) return false
        return if (awaitingWriteAck) true else flushPendingWrites()
    }

    /**
     * Прерывает текущую команду, не закрывая оболочку.
     *
     * Событие пишется с номером потока намеренно: гейт `07` §6.39 требует
     * показать, что прерывание задело **свой** поток и не тронуло соседний, а
     * по записи «оператор нажал Ctrl+C» этого не видно. Прогон §6.41 на этом и
     * остановился — не на поведении, а на ненаблюдаемости.
     */
    public fun interrupt(): Boolean = synchronized(lock) {
        val sent = sendInput(byteArrayOf(CTRL_C))
        emit("shell_interrupt", mapOf("stream" to localId.toString(), "sent" to sent.toString()))
        return sent
    }

    /**
     * Сообщает оболочке о конце ввода.
     *
     * В `shell,v2` это отдельная рамка; в обычном `shell:` конца ввода нет, и
     * притворяться, что он отправлен, нельзя.
     */
    public fun closeInput(): Boolean = synchronized(lock) {
        if (!useShellV2 || !opened || finished) return false
        if (!enqueue(AdbShellProtocol.closeStdinFrame())) return false
        return if (awaitingWriteAck) true else flushPendingWrites()
    }

    /**
     * Отправка из очереди вызывается writer executor'ом после OKAY. На одном
     * ADB-потоке одновременно остаётся не больше одной неподтверждённой WRTE.
     */
    public fun flushPendingWrites(): Boolean = synchronized(lock) {
        if (!opened || finished) return false
        if (awaitingWriteAck) return true
        val payload = pendingWrites.removeFirstOrNull() ?: return true
        pendingWriteBytes -= payload.size
        val packet = dispatcher.write(localId, payload)
        val sent = packet != null && send(packet)
        if (sent) {
            awaitingWriteAck = true
        } else {
            // A failed WRTE can never receive an acknowledgement. Mark the
            // session finished so the reader leaves its pump instead of
            // spinning forever with a dead queued write.
            finished = true
            pendingWrites.clear()
            pendingWriteBytes = 0
        }
        emit(
            "shell_write",
            mapOf("stream" to localId.toString(), "bytes" to payload.size.toString(), "sent" to sent.toString()),
        )
        return sent
    }

    /**
     * Один шаг: дождаться того, что пришло, и разобрать.
     *
     * [timeoutMillis] короткий намеренно: цикл владельца должен получать
     * управление, даже когда оболочка молчит, иначе остановить сессию можно
     * будет только закрытием транспорта. Значение из Legacy.
     */
    public fun pump(timeoutMillis: Int = PUMP_TIMEOUT_MS): List<AdbShellEvent> {
        if (!active) return emptyList()

        // Ожидание снаружи замка: пока сессия ждёт вывод, ввод должен уходить
        // без задержки. Замок берётся только на разбор принятого.
        val first = mailbox?.poll(timeoutMillis.toLong())
        return synchronized(lock) {
            if (finished || first == null) emptyList() else drainMailbox(first)
        }
    }

    /**
     * Закрывает поток. Устройство узнает, что оболочка больше не нужна.
     *
     * [reason] попадает в журнал. Без неё запись `shell_closed` не отвечала на
     * вопрос, ради которого её и читают: закрылась оболочка сама, по просьбе
     * оператора или вместе с оборвавшимся транспортом. Прогон §6.39 на этом и
     * споткнулся — гейт требует, чтобы при обрыве оба потока закрылись **одной**
     * причиной, а сверить было не с чем.
     */
    public fun close(reason: String = CLOSED_BY_HOST): Unit = synchronized(lock) {
        if (localId == 0 || finished) return
        finished = true
        pendingWrites.clear()
        pendingWriteBytes = 0
        awaitingWriteAck = false
        dispatcher.close(localId)?.let(::send)
        mailbox = null
        emit("shell_closed", mapOf("stream" to localId.toString(), "reason" to reason))
    }

    /**
     * Разбирает дождавшееся событие и всё, что уже лежит рядом с ним.
     *
     * Вызывается уже под замком.
     */
    private fun drainMailbox(first: AdbMailboxItem): List<AdbShellEvent> {
        val box = mailbox ?: return emptyList()
        val events = mutableListOf<AdbShellEvent>()
        var terminal = applyItem(first, events)
        while (terminal == null) {
            val item = box.poll(0) ?: break
            terminal = applyItem(item, events)
        }
        return terminal ?: (events + drainFrames())
    }

    /**
     * Разбор одного события ящика. `null` — сессия продолжается.
     *
     * Подтверждение и данные разбираются здесь же, а не отдельными функциями:
     * порог detekt на число функций в классе поднимать запрещено
     * (`15_CLEAN_REBUILD_BLUEPRINT_RU.md` §4.1), а эти два случая коротки и
     * читаются на месте.
     */
    private fun applyItem(
        item: AdbMailboxItem,
        events: MutableList<AdbShellEvent>,
    ): List<AdbShellEvent>? = when (item) {
        is AdbMailboxItem.Opened -> {
            opened = true
            emit("shell_opened", mapOf("remote" to item.remoteId.toString()))
            events += AdbShellEvent.Opened
            null
        }

        is AdbMailboxItem.Data -> {
            if (useShellV2) {
                if (frames.append(item.payload)) null
                else finish(AdbShellEvent.Broken("shell frame buffer overflow"))
            } else {
                // Legacy shell has no frames: every payload byte belongs to stdout.
                legacyText.decode(item.payload)
                    .takeIf(String::isNotEmpty)
                    ?.let { text -> events += AdbShellEvent.Output(text) }
                null
            }
        }

        is AdbMailboxItem.Ended -> applyEnd(item, events)

        is AdbMailboxItem.Acknowledged -> {
            awaitingWriteAck = false
            if (pendingWrites.isNotEmpty()) events += AdbShellEvent.WriteReady
            null
        }
    }

    /**
     * Конец потока.
     *
     * Устройство закрыло поток — сначала отдаётся всё принятое, потом конец:
     * обрыв не должен съедать уже полученный вывод. Обрыв транспорта и потеря
     * кадра, наоборот, ничего не дособирают — как и раньше: после них
     * недобранный кусок недостоверен.
     */
    private fun applyEnd(
        item: AdbMailboxItem.Ended,
        events: MutableList<AdbShellEvent>,
    ): List<AdbShellEvent> = when (item.reason) {
        AdbMailboxEnd.COMPLETED, AdbMailboxEnd.REJECTED, AdbMailboxEnd.LOCAL -> {
            events += drainFrames()
            events += flushTextStreams()
            events + finish(AdbShellEvent.Exited(null))
        }

        AdbMailboxEnd.FRAMING_LOST, AdbMailboxEnd.TRANSPORT_CLOSED ->
            events + finish(AdbShellEvent.Broken(item.detail))

        // Из всех потребителей оболочке переполниться вероятнее всего: `logcat`
        // льёт вывод, ни у кого не спрашивая. Ветка оживёт на шаге 5.
        AdbMailboxEnd.OVERFLOWED ->
            events + finish(AdbShellEvent.Broken("shell mailbox: ${item.detail}"))
    }

    private fun drainFrames(): List<AdbShellEvent> {
        if (!useShellV2) return emptyList()
        val events = mutableListOf<AdbShellEvent>()
        var done = false
        while (!done) {
            when (val poll = frames.poll()) {
                AdbShellFramePoll.Incomplete -> done = true

                is AdbShellFramePoll.Corrupt -> {
                    events += finish(AdbShellEvent.Broken("shell frame: ${poll.detail}"))
                    done = true
                }

                is AdbShellFramePoll.Ready -> done = appendFrame(poll.frame, events)
            }
        }
        return events
    }

    private fun appendFrame(
        frame: AdbShellFrame,
        events: MutableList<AdbShellEvent>,
    ): Boolean = when (frame.id) {
        AdbShellProtocol.ID_STDOUT -> {
            shellV2Text(stdoutText.decode(frame.payload))
                .takeIf(String::isNotEmpty)
                ?.let { text -> events += AdbShellEvent.Output(text) }
            false
        }

        AdbShellProtocol.ID_STDERR -> {
            shellV2Text(stderrText.decode(frame.payload))
                .takeIf(String::isNotEmpty)
                ?.let { text -> events += AdbShellEvent.ErrorOutput(text) }
            false
        }

        AdbShellProtocol.ID_EXIT -> {
            val code = frame.payload.firstOrNull()?.toInt()?.and(0xFF)
            events += flushTextStreams()
            events += finish(AdbShellEvent.Exited(code))
            true
        }

        // Незнакомое поле пропускается вместе с payload: протокол расширяемый,
        // и терять из-за него уже полученный вывод незачем.
        else -> false
    }

    /** Вызывается уже под замком: [close] берёт его повторно, что разрешено. */
    private fun finish(event: AdbShellEvent): List<AdbShellEvent> {
        if (finished) return emptyList()
        close(
            when (event) {
                is AdbShellEvent.Exited -> "device closed the stream"
                is AdbShellEvent.Broken -> event.reason
                else -> CLOSED_BY_HOST
            },
        )
        return listOf(event)
    }

    private fun send(packet: AdbOutboundPacket): Boolean =
        writer.write(packet.command, packet.arg0, packet.arg1, packet.payload) == AdbWriteOutcome.Sent

    /** Adds one outbound frame without allowing an unbounded paste backlog. */
    private fun enqueue(payload: ByteArray): Boolean {
        if (payload.size > MAX_PENDING_WRITE_BYTES ||
            pendingWriteBytes > MAX_PENDING_WRITE_BYTES - payload.size
        ) {
            emit(
                "shell_write_rejected",
                mapOf("stream" to localId.toString(), "bytes" to payload.size.toString()),
            )
            return false
        }
        pendingWrites.addLast(payload)
        pendingWriteBytes += payload.size
        return true
    }

    private fun flushTextStreams(): List<AdbShellEvent> {
        if (!useShellV2) {
            return legacyText.finish()
                .takeIf(String::isNotEmpty)
                ?.let { listOf(AdbShellEvent.Output(it)) }
                ?: emptyList()
        }

        val events = mutableListOf<AdbShellEvent>()
        shellV2Text(stdoutText.finish())
            .takeIf(String::isNotEmpty)
            ?.let { events += AdbShellEvent.Output(it) }
        shellV2Text(stderrText.finish())
            .takeIf(String::isNotEmpty)
            ?.let { events += AdbShellEvent.ErrorOutput(it) }
        return events
    }

    private fun emit(message: String, fields: Map<String, String>) {
        diagnostics.emit(
            DiagnosticEvent(
                timestamp = clock(),
                category = AdbHandshake.DIAGNOSTIC_CATEGORY,
                message = message,
                fields = fields,
            ),
        )
    }

    public companion object {
        /** Настоящий терминал: разделение потоков и код возврата. */
        public const val SERVICE_PTY: String = "shell,v2,pty:"

        /** `shell,v2` без терминала: `adb shell -T`. */
        public const val SERVICE_RAW: String = "shell,v2,raw:"

        /** Без `shell,v2`: ни разделения, ни кода возврата. */
        public const val SERVICE_LEGACY: String = "shell:"

        /** Значение из Legacy: цикл возвращает управление и когда peer молчит. */
        public const val PUMP_TIMEOUT_MS: Int = 250

        /** Причина по умолчанию: закрыли мы сами. */
        public const val CLOSED_BY_HOST: String = "closed by host"

        /** `ETX` — то же, что нажатие `Ctrl+C` в терминале. */
        public const val CTRL_C: Byte = 3

        /** Bounds both malformed UI measurements and a hostile caller. */
        private const val MAX_WINDOW_DIMENSION = 1_000

        /** One MiB is enough for a paste while keeping the session bounded. */
        private const val MAX_PENDING_WRITE_BYTES = 1_048_576
    }
}

/**
 * `shell,v2` historically strips NUL bytes before showing text. Keep that
 * hardware-proven behaviour while decoding UTF-8 incrementally. Legacy
 * `shell:` deliberately remains byte-for-text compatible with its old path.
 */
private fun shellV2Text(text: String): String = text.replace("\u0000", "")
