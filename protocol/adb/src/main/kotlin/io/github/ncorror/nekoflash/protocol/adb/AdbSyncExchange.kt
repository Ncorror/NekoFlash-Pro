package io.github.ncorror.nekoflash.protocol.adb

import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticEvent
import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticSink
import java.time.Clock
import java.time.Instant

/**
 * Обмен кадрами `sync:` поверх одного логического потока ADB.
 *
 * Знает про поток и рамку: как открыть сервис, как дождаться ровно нужного
 * числа байт, как прочитать заголовок и сообщение об отказе. Не знает ни одной
 * операции `sync:` — что именно спрашивают у устройства, решает слой выше.
 *
 * Разделение появилось не ради красоты: с приходом `SEND` в `AdbSyncSession`
 * стало 28 функций при пороге в 20, и detekt это остановил. Поднимать порог
 * запрещено (`15_CLEAN_REBUILD_BLUEPRINT_RU.md` §4.1) — граница проходит
 * здесь, между «как разговаривать» и «о чём».
 */
internal class AdbSyncExchange(
    private val writer: AdbPacketWriter,
    private val dispatcher: AdbStreamDispatcher,
    private val diagnostics: DiagnosticSink,
    private val clock: () -> Instant = { Clock.systemUTC().instant() },
    private val elapsedNanos: () -> Long = { System.nanoTime() },
) {
    private val incoming = AdbStreamBuffer()

    /**
     * Ящик открытого потока.
     *
     * `null` до открытия и после закрытия: ждать нечего, и спрашивать не у
     * кого. Ящик наполняет `AdbDispatchLoop`, один на соединение
     * (`docs/adr/0004_CONCURRENT_ADB_DISPATCHER_RU.md`), а сессия только ждёт
     * на нём — транспорт она не читает.
     */
    private var mailbox: AdbStreamMailbox? = null

    private var localId: Int = 0
    private var opened = false

    /** Открыт ли поток. */
    val active: Boolean
        get() = opened

    /**
     * Почему потока больше нет. `null` — он и не открывался.
     *
     * С постоянным циклом раскладки конец обнаруживается **между** операциями
     * сессии: цикл читает сам, а не по просьбе вызывающего. Ответить на
     * следующую операцию безличным `NOT_OPEN`, зная, что устройство закрыло
     * поток или что интерфейс отпущен, значило бы утаить известную причину.
     */
    fun endReason(): AdbSyncOutcome.Failed? = drain(0)

    /** Открывает сервис и ждёт подтверждения. */
    fun open(timeoutMillis: Int): AdbSyncOutcome<Unit> {
        val (box, packet) = dispatcher.open(AdbSyncSession.SERVICE)
        mailbox = box
        val id = box.localId
        localId = id
        emit("sync_open", mapOf("stream" to id.toString()))
        var failure = sendPacket(packet)
        val deadline = deadlineFrom(timeoutMillis)
        while (!opened && failure == null) {
            failure = pump(deadline)
        }
        return failure ?: AdbSyncOutcome.Done(Unit).also {
            emit("sync_opened", mapOf("stream" to id.toString()))
        }
    }

    /** Закрывает поток. */
    fun close() {
        if (localId == 0) return
        opened = false
        dispatcher.close(localId)?.let { packet ->
            writer.write(packet.command, packet.arg0, packet.arg1, packet.payload)
        }
        emit("sync_closed", mapOf("stream" to localId.toString()))
        mailbox = null
        localId = 0
    }

    /**
     * Отправляет запрос с путём.
     *
     * Если потока уже нет, отвечает **почему** его нет, а не безличным
     * `NOT_OPEN`: с постоянным циклом раскладки конец потока обнаруживается
     * между операциями сессии, и сказать оператору «сессия не открыта» вместо
     * «устройство закрыло поток» значило бы утаить причину, которая известна.
     */
    fun request(id: String, path: String): AdbSyncOutcome.Failed? {
        val packet = dispatcher.write(localId, AdbSyncProtocol.request(id, path))
            ?: return drain(0) ?: failure(AdbSyncFailure.NOT_OPEN, "$id $path")
        return sendPacket(packet)
    }

    /**
     * Отправляет готовую рамку `sync:` и отдаёт сырой исход записи.
     *
     * Сырой — потому что мутирующему пути важно не только «не получилось», но и
     * сколько байт успело уйти: от этого зависит, могло ли устройство увидеть
     * запрос. `null` означает, что поток закрыт и отправлять некуда.
     */
    fun writeFrame(payload: ByteArray): AdbWriteOutcome? {
        val packet = dispatcher.write(localId, payload) ?: return null
        return writer.write(packet.command, packet.arg0, packet.arg1, packet.payload)
    }

    /** Читает заголовок очередного сообщения. */
    fun readHeader(deadline: Long): AdbSyncOutcome<AdbSyncHeader> =
        when (val read = readExactly(AdbSyncProtocol.HEADER_SIZE_BYTES, deadline)) {
            is AdbSyncOutcome.Done -> AdbSyncOutcome.Done(AdbSyncProtocol.decodeHeader(read.value))
            is AdbSyncOutcome.Failed -> read
        }

    /** Добирает из потока ровно столько байт, сколько названо. */
    fun readExactly(count: Int, deadline: Long): AdbSyncOutcome<ByteArray> {
        while (true) {
            incoming.take(count)?.let { bytes -> return AdbSyncOutcome.Done(bytes) }
            pump(deadline)?.let { failure -> return failure }
        }
    }

    /**
     * Читает сообщение об отказе.
     *
     * Устройство называет длину сообщения само, поэтому она проверяется:
     * иначе один испорченный ответ заставил бы ждать мегабайт текста.
     */
    fun refusal(header: AdbSyncHeader, deadline: Long, stage: String): AdbSyncOutcome.Failed {
        if (header.value < 0 || header.value > AdbSyncProtocol.MAX_STRING_BYTES) {
            return failure(AdbSyncFailure.INVALID_LENGTH, "$stage message=${header.value}")
        }
        val message = when (val read = readExactly(header.value, deadline)) {
            is AdbSyncOutcome.Done -> read.value.toString(Charsets.UTF_8)
            is AdbSyncOutcome.Failed -> return read
        }
        return failure(AdbSyncFailure.DEVICE_REFUSED, "$stage: $message")
    }

    fun deadlineFrom(timeoutMillis: Int): Long {
        require(timeoutMillis > 0) { "Sync timeout must be positive: $timeoutMillis" }
        return elapsedNanos() + timeoutMillis * NANOS_PER_MILLI
    }

    fun failure(reason: AdbSyncFailure, detail: String): AdbSyncOutcome.Failed {
        emit("sync_failed", mapOf("reason" to reason.name, "detail" to detail))
        return AdbSyncOutcome.Failed(reason, detail)
    }

    fun emit(message: String, fields: Map<String, String>) {
        diagnostics.emit(
            DiagnosticEvent(
                timestamp = clock(),
                category = AdbHandshake.DIAGNOSTIC_CATEGORY,
                message = message,
                fields = fields,
            ),
        )
    }

    private fun sendPacket(packet: AdbOutboundPacket): AdbSyncOutcome.Failed? =
        when (val outcome = writer.write(packet.command, packet.arg0, packet.arg1, packet.payload)) {
            AdbWriteOutcome.Sent -> null
            AdbWriteOutcome.Closed -> failure(AdbSyncFailure.TRANSPORT_CLOSED, "send")
            is AdbWriteOutcome.Interrupted -> failure(
                AdbSyncFailure.SEND_FAILED,
                "${outcome.detail} (sent=${outcome.sentBytes})",
            )
        }

    /**
     * Один шаг ожидания: дождаться содержимого ящика и разобрать его.
     *
     * Возвращает отказ, когда продолжать нельзя, и `null`, когда можно.
     * Ожидание нарезано на куски не длиннее [READ_SLICE_MS], чтобы дедлайн
     * проверялся, даже когда устройство молчит.
     */
    private fun pump(deadline: Long): AdbSyncOutcome.Failed? {
        val remaining = remainingMillis(deadline)
        return if (remaining <= 0) {
            failure(AdbSyncFailure.TIMED_OUT, "sync")
        } else {
            drain(remaining.coerceAtMost(READ_SLICE_MS).toLong())
        }
    }

    /**
     * Ждёт первое событие, потом забирает всё, что уже лежит рядом.
     *
     * `null` — можно продолжать: либо за отведённый кусок ничего не пришло,
     * либо принятое разобрано и поток жив.
     */
    private fun drain(waitMillis: Long): AdbSyncOutcome.Failed? {
        val box = mailbox ?: return null
        var failed = box.poll(waitMillis)?.let { item -> consume(item) }
        while (failed == null) {
            val item = box.poll(0) ?: break
            failed = consume(item)
        }
        return failed
    }

    private fun consume(item: AdbMailboxItem): AdbSyncOutcome.Failed? = when (item) {
        is AdbMailboxItem.Opened -> {
            opened = true
            null
        }

        is AdbMailboxItem.Data -> {
            incoming.append(item.payload)
            null
        }

        is AdbMailboxItem.Ended -> ended(item)

        // Подтверждений наших записей этот потребитель не просил:
        // они приходят только в ящик, открытый с ними.
        is AdbMailboxItem.Acknowledged -> null
    }

    /**
     * Конец потока.
     *
     * Ящик отдаёт конец сколько угодно раз, поэтому дальнейшие операции сессии
     * будут отказывать той же причиной, а не зависнут в ожидании байт, которых
     * уже не будет.
     */
    private fun ended(item: AdbMailboxItem.Ended): AdbSyncOutcome.Failed {
        opened = false
        return when (item.reason) {
            // Устройство закрыло поток — подтвердив открытие или не подтвердив.
            // Для сессии это одно и то же: разговаривать больше не с кем.
            AdbMailboxEnd.COMPLETED, AdbMailboxEnd.REJECTED, AdbMailboxEnd.LOCAL ->
                failure(AdbSyncFailure.TRANSPORT_CLOSED, "device closed sync")

            AdbMailboxEnd.FRAMING_LOST -> failure(AdbSyncFailure.FRAMING_LOST, item.detail)

            AdbMailboxEnd.TRANSPORT_CLOSED ->
                failure(AdbSyncFailure.TRANSPORT_CLOSED, item.detail)

            // Как и в AdbServiceCall, ветка ждёт шага 5: пока ящик разбирается
            // после каждого пакета, переполниться ему нечем.
            AdbMailboxEnd.OVERFLOWED ->
                failure(AdbSyncFailure.MAILBOX_OVERFLOWED, item.detail)
        }
    }

    private fun remainingMillis(deadlineNanos: Long): Int =
        ((deadlineNanos - elapsedNanos()) / NANOS_PER_MILLI)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()

    private companion object {
        const val READ_SLICE_MS = 2_000
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
