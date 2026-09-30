package io.github.ncorror.nekoflash.protocol.adb

import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticEvent
import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticSink
import java.time.Clock
import java.time.Instant

/**
 * Production-путь Sideload: машина состояний на настоящем транспорте.
 *
 * [AdbSideloadSession] — чистая машина без ввода-вывода, и сама по себе она
 * ничего не передаёт. Драйвер даёт ей провод: открывает поток через
 * [AdbStreamDispatcher], кормит её тем, что пришло в ящик, и отправляет то, что
 * она велела отправить.
 *
 * Три стыка неочевидны, и каждый стоил отдельного разбора.
 *
 * 1. **Подтверждения приходят по просьбе.** Покрытие Sideload считается по
 *    подтверждённым блокам, а `OKAY` открытого потока маршрутизатор раньше
 *    никуда не отдавал — он для него чистое управление потоком. Поэтому поток
 *    открывается с `acknowledgements = true`, и это единственный потребитель,
 *    который их просит. Заменить подтверждение отметкой «байты ушли» нельзя:
 *    она говорит про нас, а не про устройство.
 * 2. **Подтверждать входящий `WRTE` второй раз не надо.** Маршрутизатор делает
 *    это немедленно — это backpressure ADB. Сессия строит свой `OKAY`, потому
 *    что не знает, кто её вертит; драйвер знает и такой пакет не отправляет.
 *    Заодно порядок получается тот самый, которого требует A2: подтверждение
 *    уходит **до** чтения нагрузки с диска, а не после.
 * 3. **Граница мутации отмечается до провода.** `provideBlock` возвращает пакет,
 *    но не отправляет его; отметка ставится между этими двумя шагами. Иначе она
 *    потерялась бы ровно в том случае, когда важна, — при обрыве на первом
 *    блоке.
 *
 * Пакет открытия сессия тоже строит сама, но на провод идёт пакет диспетчера:
 * идентификатор потока и ящик принадлежат ему. Байты у них совпадают — то же
 * имя сервиса с завершающим нулём, — так что сессия остаётся в согласии с
 * проводом, а не расходится с ним.
 */
public class AdbSideloadDriver(
    private val writer: AdbPacketWriter,
    private val dispatcher: AdbStreamDispatcher,
    private val diagnostics: DiagnosticSink = DiagnosticSink { },
    private val clock: () -> Instant = { Clock.systemUTC().instant() },
    private val elapsedNanos: () -> Long = { System.nanoTime() },
) {
    /**
     * Отдаёт нагрузку Recovery и возвращает исход **передачи**.
     *
     * Исход установки этим не выясняется и выясниться не может: `DONEDONE`
     * кончает передачу, а не установку (`03` §6, инвариант 1). Вердикт даёт
     * [AdbRecoveryCorrelator] по журналу Recovery, снятому относительно базы.
     */
    public fun send(
        source: AdbSideloadSource,
        totalBytes: Long,
        transportConnected: Boolean,
        peerIsSideload: Boolean,
        listener: AdbSideloadListener = AdbSideloadListener.NOTHING,
        timeoutMillis: Int = TRANSFER_TIMEOUT_MS,
        cancelRequested: () -> Boolean = { false },
    ): AdbSideloadOutcome {
        require(timeoutMillis > 0) { "таймаут Sideload должен быть положительным: $timeoutMillis" }
        val rejection = AdbSideloadContract.validateStart(transportConnected, peerIsSideload, totalBytes)
        return if (rejection == null) {
            transfer(source, totalBytes, timeoutMillis, listener, cancelRequested)
        } else {
            refused(rejection)
        }
    }

    /** Отказ до единого байта на проводе. Причина называется, а не прячется. */
    private fun refused(rejection: AdbSideloadRejection): AdbSideloadOutcome {
        emit("sideload_refused", mapOf("reason" to rejection.name))
        return when (rejection) {
            AdbSideloadRejection.NOT_IN_SIDELOAD_MODE ->
                AdbSideloadOutcome.NotInSideloadMode("peer отвечает не из Sideload")

            AdbSideloadRejection.TRANSPORT_UNAVAILABLE ->
                AdbSideloadOutcome.Failed(AdbSideloadFailure.TRANSPORT, "транспорт недоступен")

            AdbSideloadRejection.EMPTY_PAYLOAD ->
                AdbSideloadOutcome.Failed(AdbSideloadFailure.FILE, "нагрузка пуста: передавать нечего")
        }
    }

    private fun transfer(
        source: AdbSideloadSource,
        totalBytes: Long,
        timeoutMillis: Int,
        listener: AdbSideloadListener,
        cancelRequested: () -> Boolean,
    ): AdbSideloadOutcome {
        val service = AdbSideloadContract.service(totalBytes)
        val (mailbox, open) = dispatcher.open(service, acknowledgements = true)
        val state = Transfer(
            session = AdbSideloadSession(mailbox.localId, totalBytes),
            mailbox = mailbox,
            source = source,
            timeoutMillis = timeoutMillis,
            listener = listener,
            cancelRequested = cancelRequested,
        )
        // Пакет сессии отбрасывается намеренно: на провод идёт пакет диспетчера,
        // потому что идентификатор и ящик принадлежат ему. Вызов всё равно
        // нужен — он переводит сессию в ожидание подтверждения открытия.
        state.session.open()
        emit("sideload_open", mapOf("stream" to "${mailbox.localId}", "bytes" to "$totalBytes"))

        val deadline = elapsedNanos() + timeoutMillis.toLong() * NANOS_PER_MILLI
        var outcome = written(state, writer.write(open.command, open.arg0, open.arg1, open.payload))
        while (outcome == null) {
            outcome = pump(state, deadline)
        }
        release(state)
        emit(
            "sideload_finished",
            mapOf("stream" to "${mailbox.localId}", "outcome" to outcome.javaClass.simpleName),
        )
        return outcome
    }

    /** Один шаг: сперва отмена, если она ещё законна, потом ожидание ящика. */
    private fun pump(state: Transfer, deadline: Long): AdbSideloadOutcome? {
        // Отмена спрашивается **до** границы мутации и только до неё: после
        // границы отменять нечего, и сессия ответила бы отказом.
        val cancelled = if (!state.session.mutated && state.cancelRequested()) {
            apply(state, state.session.cancel())
        } else {
            null
        }
        return cancelled ?: receive(state, deadline)
    }

    private fun receive(state: Transfer, deadline: Long): AdbSideloadOutcome? {
        val remaining = (deadline - elapsedNanos()) / NANOS_PER_MILLI
        val item = if (remaining <= 0) null else state.mailbox.poll(remaining.coerceAtMost(SLICE_MS))
        return when {
            item != null -> apply(state, advance(state, item))
            remaining <= 0 -> interrupted(state, "Recovery молчит дольше ${state.timeoutMillis} мс")
            else -> null
        }
    }

    /** Содержимое ящика превращается в то, что сессия умеет разбирать. */
    private fun advance(state: Transfer, item: AdbMailboxItem): AdbSideloadStep = when (item) {
        is AdbMailboxItem.Opened -> {
            state.remoteId = item.remoteId
            state.session.consume(AdbPacket(AdbCommand.OKAY, item.remoteId, state.localId, EMPTY))
        }

        // Подтверждение нашей записи. Ради него поток и открыт с подтверждениями:
        // именно оно двигает уникальное покрытие.
        AdbMailboxItem.Acknowledged ->
            state.session.consume(AdbPacket(AdbCommand.OKAY, state.remoteId, state.localId, EMPTY))

        is AdbMailboxItem.Data ->
            state.session.consume(AdbPacket(AdbCommand.WRTE, state.remoteId, state.localId, item.payload))

        is AdbMailboxItem.Ended -> state.session.transportClosed("${item.reason}: ${item.detail}")
    }

    /**
     * Исполняет шаг сессии: отправить, показать прогресс, отдать блок.
     *
     * Возвращает исход, когда дальше идти некуда, и `null`, когда можно.
     */
    private fun apply(state: Transfer, step: AdbSideloadStep): AdbSideloadOutcome? {
        val failure = step.outbound.firstNotNullOfOrNull { packet -> dispatchOutbound(state, packet) }
        step.progress?.let(state.listener::onProgress)
        val request = step.blockRequest
        return failure ?: step.outcome ?: request?.let { asked -> serve(state, asked) }
    }

    private fun dispatchOutbound(state: Transfer, packet: AdbSideloadPacket): AdbSideloadOutcome? = when {
        // Входящий `WRTE` маршрутизатор подтвердил сам и немедленно. Второе
        // подтверждение было бы лишним кадром на проводе.
        packet.command == AdbCommand.OKAY -> null

        packet.command == AdbCommand.CLSE -> {
            release(state)
            null
        }

        else -> written(state, writer.write(packet.command, packet.arg0, packet.arg1, packet.payload))
    }

    /**
     * Отдаёт запрошенный блок.
     *
     * Сбой чтения остаётся сбоем **файла** и границы мутации не трогает:
     * устройство этих байт не видело.
     */
    private fun serve(state: Transfer, request: AdbSideloadBlockRequest): AdbSideloadOutcome? =
        runCatching { state.source.read(request.offset, request.length) }.fold(
            onSuccess = { payload -> deliver(state, request, payload) },
            onFailure = { error ->
                val reason = error.message ?: error.javaClass.simpleName
                apply(state, state.session.blockReadFailed(request, "блок ${request.blockNumber} не прочитан: $reason"))
            },
        )

    private fun deliver(
        state: Transfer,
        request: AdbSideloadBlockRequest,
        payload: ByteArray,
    ): AdbSideloadOutcome? {
        val step = state.session.provideBlock(request, payload)
        // Отметка ставится между «пакет готов» и «пакет на проводе». Поставить
        // её после отправки значило бы потерять границу ровно тогда, когда она
        // важнее всего: при обрыве на первом блоке.
        if (step.transition == AdbSideloadTransition.BLOCK_SENT && state.session.markPayloadStarted()) {
            emit("sideload_mutation_boundary", mapOf("block" to "${request.blockNumber}"))
            state.listener.onMutationBoundary()
        }
        return apply(state, step)
    }

    private fun written(state: Transfer, outcome: AdbWriteOutcome): AdbSideloadOutcome? = when (outcome) {
        AdbWriteOutcome.Sent -> null
        AdbWriteOutcome.Closed -> interrupted(state, "транспорт закрыт при отправке")
        is AdbWriteOutcome.Interrupted ->
            interrupted(state, "${outcome.detail} (ушло ${outcome.sentBytes} байт)")
    }

    /**
     * Провод стал непригоден.
     *
     * Классификацию делает сессия: после границы мутации это не сбой, а
     * неизвестное состояние устройства, **каким бы ни выглядел процент**.
     */
    private fun interrupted(state: Transfer, detail: String): AdbSideloadOutcome =
        state.session.transportClosed(detail).outcome
            ?: AdbSideloadOutcome.Failed(AdbSideloadFailure.TRANSPORT, detail)

    /** Закрывает поток. Повторный вызов ничего не делает: диспетчер уже забыл ящик. */
    private fun release(state: Transfer) {
        dispatcher.close(state.localId)?.let { packet ->
            writer.write(packet.command, packet.arg0, packet.arg1, packet.payload)
        }
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

    /** Состояние одной передачи. Живёт не дольше вызова [send]. */
    private class Transfer(
        val session: AdbSideloadSession,
        val mailbox: AdbStreamMailbox,
        val source: AdbSideloadSource,
        val timeoutMillis: Int,
        val listener: AdbSideloadListener,
        val cancelRequested: () -> Boolean,
    ) {
        val localId: Int get() = mailbox.localId

        /** Известен после подтверждения открытия; до него адресовать некому. */
        var remoteId: Int = 0
    }

    public companion object {
        /**
         * Сколько ждать Recovery.
         *
         * Отсчёт общий на передачу, а не на пакет: образ идёт минутами, и
         * пакетный таймаут пришлось бы делать таким же длинным, потеряв смысл.
         */
        public const val TRANSFER_TIMEOUT_MS: Int = 900_000

        private const val SLICE_MS = 200L
        private const val NANOS_PER_MILLI = 1_000_000L
        private val EMPTY = ByteArray(0)
    }
}
