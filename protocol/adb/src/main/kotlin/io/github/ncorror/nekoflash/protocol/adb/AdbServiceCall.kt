package io.github.ncorror.nekoflash.protocol.adb

import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticEvent
import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticSink
import java.time.Clock
import java.time.Instant

/** Почему вызов сервиса не дал результата. */
public enum class AdbServiceFailure {
    /** Устройство закрыло поток, не подтвердив открытие: сервиса нет или он отказал. */
    REJECTED,

    /** Ответ не уложился в отведённое время. */
    TIMED_OUT,

    /** Вывод превысил объявленный предел. */
    OUTPUT_TOO_LARGE,

    /** Интерфейс больше не удерживается. */
    TRANSPORT_CLOSED,

    /** Кадр потерян: соединение дальше недостоверно. */
    FRAMING_LOST,

    /** Отправить пакет не удалось. */
    SEND_FAILED,

    /**
     * Ящик потока переполнился: вывод шёл быстрее, чем его забирали.
     *
     * Отдельно от [OUTPUT_TOO_LARGE], хотя оба про предел: там превышен потолок
     * вывода, который назвал вызывающий, а здесь — глубина ящика, о которой он
     * не просил. Свести их в одну причину значило бы сказать оператору
     * «вы просили меньше», когда он не просил ничего подобного.
     */
    MAILBOX_OVERFLOWED,
}

/** Исход вызова сервиса. */
public sealed interface AdbServiceOutcome {
    /** Сервис отработал и закрыл поток. */
    public data class Completed(val output: ByteArray) : AdbServiceOutcome {
        /** Вывод как текст без завершающих переводов строки. */
        public fun text(): String = output.toString(Charsets.UTF_8).trimEnd('\n', '\r')

        override fun toString(): String = "Completed(${output.size} bytes)"
    }

    /** Результата нет. */
    public data class Failed(
        val reason: AdbServiceFailure,
        val detail: String,
    ) : AdbServiceOutcome
}

/**
 * Один вызов сервиса ADB от начала до закрытия потока.
 *
 * Блокирующий и однопоточный: открывает поток, собирает вывод и возвращает его
 * целиком.
 *
 * Устроено так же, как ограниченный read-only probe в A2
 * (`AdbUsbTransport.runReadOnlyProbe`): общий дедлайн на весь вызов, отдельный
 * потолок на одну операцию приёма, предел на объём вывода и закрытие потока при
 * любом из них. Ограничения обязательны: сервис может не закрыть поток никогда,
 * а `cat` большого файла — переполнить память.
 *
 * Вывод берётся из **своего ящика** ([AdbStreamMailbox]), а не из событий
 * маршрутизатора: шаг 2 плана `docs/adr/0004_CONCURRENT_ADB_DISPATCHER_RU.md`.
 * Чужие пакеты, подтверждения и раскладку по потокам знает
 * [AdbStreamDispatcher]; здесь остаётся время, предел и сбор вывода.
 *
 * **Транспорт этот класс не читает.** Ящик наполняет [AdbDispatchLoop], один
 * на соединение, и вызов просто ждёт на своём ящике (шаг 5 того же плана).
 * Отсюда и берётся одновременность: пока ждёт этот вызов, рядом может ждать
 * оболочка или файловая операция — каждый на своём ящике, а читатель
 * по-прежнему один.
 */
public class AdbServiceCall(
    private val writer: AdbPacketWriter,
    private val dispatcher: AdbStreamDispatcher,
    private val diagnostics: DiagnosticSink = DiagnosticSink { },
    private val clock: () -> Instant = { Clock.systemUTC().instant() },
    private val elapsedNanos: () -> Long = { System.nanoTime() },
) {
    /**
     * Вызывает сервис и ждёт его вывод.
     *
     * @param service имя сервиса ADB, например `shell:getprop ro.product.device`.
     * @param maxOutputBytes сколько вывода готов принять вызывающий.
     * @param timeoutMillis общий предел на весь вызов.
     */
    public fun run(
        service: String,
        maxOutputBytes: Int = DEFAULT_MAX_OUTPUT_BYTES,
        timeoutMillis: Int = DEFAULT_TIMEOUT_MS,
        payloadOnOpen: ByteArray? = null,
    ): AdbServiceOutcome {
        require(maxOutputBytes > 0) { "ADB service output cap must be positive: $maxOutputBytes" }
        require(timeoutMillis > 0) { "ADB service timeout must be positive: $timeoutMillis" }

        val (mailbox, open) = dispatcher.open(service)
        emit("service_open", mapOf("service" to service, "stream" to mailbox.localId.toString()))
        val initialFailure = send(open)
        return if (initialFailure != null) {
            abandon(mailbox.localId, initialFailure)
        } else {
            Call(service, mailbox, maxOutputBytes, timeoutMillis, payloadOnOpen).await()
        }
    }

    /**
     * Закрывает поток и возвращает уже готовый отказ.
     *
     * Закрытие делается всегда: брошенный поток остаётся открытым на стороне
     * устройства и держит там сервис.
     */
    private fun abandon(localId: Int, failure: AdbServiceOutcome.Failed): AdbServiceOutcome {
        dispatcher.close(localId)?.let { packet ->
            writer.write(packet.command, packet.arg0, packet.arg1, packet.payload)
        }
        return failure
    }

    private fun send(packet: AdbOutboundPacket): AdbServiceOutcome.Failed? =
        when (val outcome = writer.write(packet.command, packet.arg0, packet.arg1, packet.payload)) {
            AdbWriteOutcome.Sent -> null
            AdbWriteOutcome.Closed -> failure(AdbServiceFailure.TRANSPORT_CLOSED, "send")
            is AdbWriteOutcome.Interrupted -> failure(
                AdbServiceFailure.SEND_FAILED,
                "${outcome.detail} (sent=${outcome.sentBytes})",
            )
        }

    private fun failure(reason: AdbServiceFailure, detail: String): AdbServiceOutcome.Failed {
        emit("service_failed", mapOf("reason" to reason.name, "detail" to detail))
        return AdbServiceOutcome.Failed(reason, detail)
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

    /**
     * Состояние одного вызова: дедлайн, накопитель вывода и свой ящик.
     *
     * Вынесено в отдельный объект, чтобы эти четыре величины не таскались
     * параметрами через каждый шаг разбора: раньше именно так и было, и список
     * параметров рос с каждым новым условием.
     */
    private inner class Call(
        private val service: String,
        private val mailbox: AdbStreamMailbox,
        maxOutputBytes: Int,
        timeoutMillis: Int,
        private val payloadOnOpen: ByteArray?,
    ) {
        private val deadlineNanos = elapsedNanos() + timeoutMillis * NANOS_PER_MILLI
        private val output = ByteArrayBuilder(maxOutputBytes)

        fun await(): AdbServiceOutcome {
            var outcome: AdbServiceOutcome? = null
            while (outcome == null) {
                outcome = step()
            }
            return outcome
        }

        /**
         * Один шаг: дождаться содержимого ящика и разобрать его.
         *
         * Ожидание нарезано на куски не длиннее [PACKET_TIMEOUT_MS], чтобы
         * дедлайн проверялся, даже когда сервис молчит.
         */
        private fun step(): AdbServiceOutcome? {
            val remaining = remainingMillis()
            return if (remaining <= 0) {
                abandon(mailbox.localId, failure(AdbServiceFailure.TIMED_OUT, "service=$service"))
            } else {
                await(remaining)
            }
        }

        /**
         * Ждёт первое событие, потом забирает всё, что уже лежит рядом.
         *
         * `null` означает «за отведённый кусок ничего не пришло»: решает
         * дедлайн, а не одна неудачная попытка.
         */
        private fun await(remainingMillis: Int): AdbServiceOutcome? {
            var outcome = mailbox.poll(remainingMillis.coerceAtMost(PACKET_TIMEOUT_MS).toLong())
                ?.let { item -> consume(item) }
            while (outcome == null) {
                val item = mailbox.poll(0) ?: break
                outcome = consume(item)
            }
            return outcome
        }

        private fun consume(item: AdbMailboxItem): AdbServiceOutcome? = when (item) {
            is AdbMailboxItem.Opened -> opened(item)
            is AdbMailboxItem.Data -> collect(item)
            is AdbMailboxItem.Ended -> finish(item)

            // Подтверждений наших записей этот потребитель не просил:
            // они приходят только в ящик, открытый с ними.
            is AdbMailboxItem.Acknowledged -> null
        }

        private fun opened(item: AdbMailboxItem.Opened): AdbServiceOutcome? {
            emit(
                "service_opened",
                mapOf("service" to service, "remote" to item.remoteId.toString()),
            )
            val write = payloadOnOpen?.let { dispatcher.write(mailbox.localId, it) }
            return write?.let { packet -> send(packet)?.let { abandon(mailbox.localId, it) } }
        }

        private fun collect(item: AdbMailboxItem.Data): AdbServiceOutcome? =
            if (output.append(item.payload)) {
                null
            } else {
                abandon(
                    mailbox.localId,
                    failure(
                        AdbServiceFailure.OUTPUT_TOO_LARGE,
                        "service=$service cap=${output.capacity}",
                    ),
                )
            }

        private fun finish(item: AdbMailboxItem.Ended): AdbServiceOutcome = when (item.reason) {
            AdbMailboxEnd.COMPLETED -> {
                emit(
                    "service_completed",
                    mapOf("service" to service, "bytes" to output.size.toString()),
                )
                AdbServiceOutcome.Completed(output.toByteArray())
            }

            AdbMailboxEnd.REJECTED ->
                failure(AdbServiceFailure.REJECTED, "service=$service closed before OKAY")

            AdbMailboxEnd.LOCAL ->
                failure(AdbServiceFailure.TRANSPORT_CLOSED, "service=$service abandoned")

            // Пока пакеты подаёт этот же поток, ветка недостижима: один пакет
            // даёт одно событие, и ящик разбирается сразу после каждого. Она
            // не мёртвая, а преждевременная — оживёт на шаге 5, когда ящик
            // начнёт наполнять цикл соединения. Ошибиться в ней тогда было бы
            // хуже, чем написать её сейчас.
            AdbMailboxEnd.OVERFLOWED ->
                failure(AdbServiceFailure.MAILBOX_OVERFLOWED, "service=$service ${item.detail}")

            AdbMailboxEnd.FRAMING_LOST ->
                failure(AdbServiceFailure.FRAMING_LOST, item.detail)

            AdbMailboxEnd.TRANSPORT_CLOSED ->
                failure(AdbServiceFailure.TRANSPORT_CLOSED, item.detail)
        }

        private fun remainingMillis(): Int =
            ((deadlineNanos - elapsedNanos()) / NANOS_PER_MILLI)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
    }

    /** Накопитель вывода с жёстким потолком. */
    private class ByteArrayBuilder(val capacity: Int) {
        private val chunks = mutableListOf<ByteArray>()
        var size: Int = 0
            private set

        /** `false` означает, что потолок превышен и добавлять больше нечего. */
        fun append(payload: ByteArray): Boolean {
            if (size + payload.size > capacity) return false
            chunks += payload
            size += payload.size
            return true
        }

        fun toByteArray(): ByteArray {
            val result = ByteArray(size)
            var offset = 0
            for (chunk in chunks) {
                chunk.copyInto(result, offset)
                offset += chunk.size
            }
            return result
        }
    }

    public companion object {
        /** Значения из A2 (`READ_ONLY_PROBE_*`). */
        public const val DEFAULT_TIMEOUT_MS: Int = 30_000
        public const val DEFAULT_MAX_OUTPUT_BYTES: Int = 64 * 1024

        /**
         * Потолок одной операции приёма.
         *
         * Ожидание режется на куски, чтобы дедлайн проверялся, даже когда
         * устройство молчит.
         */
        private const val PACKET_TIMEOUT_MS = 10_000

        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
