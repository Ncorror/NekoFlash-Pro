package io.github.ncorror.nekoflash.protocol.adb

import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticEvent
import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticSink
import java.io.IOException
import java.time.Clock
import java.time.Instant

/**
 * Откуда проброс берёт байты и куда их отдаёт.
 *
 * Сокета протокольный модуль не знает и знать не должен: слушатель принадлежит
 * приложению вместе с разрешениями и временем жизни поколения
 * (`docs/adr/0005_LOCAL_SOCKET_FORWARDING_RU.md` §3). Здесь остаётся ровно то,
 * что нужно перекладывать байты, — и потому проброс проверяется тестами без
 * единого сокета, как раскладка потоков проверяется без USB.
 *
 * Контракт намеренно повторяет семантику `InputStream`/`OutputStream`, а не
 * прячет её: `-1` означает конец, исключение — обрыв. Придумывать поверх свой
 * словарь значило бы переводить туда и обратно на каждом вызове.
 */
public interface AdbByteChannel {
    /**
     * Читает в [destination] и возвращает число прочитанных байт.
     *
     * `-1` — другая сторона закрылась штатно. Вызов **блокирующий**: возвращать
     * ноль как «пока нечего» нельзя, иначе цикл превратится в опрос.
     */
    public fun read(destination: ByteArray): Int

    /** Отдаёт [length] байт из начала [source] целиком. */
    public fun write(source: ByteArray, length: Int)

    /** Закрывает канал. Вызывается дважды и обязан это переживать. */
    public fun close()
}

/** Чем кончилось одно проброшенное соединение. */
public enum class AdbForwardEnd {
    /** Клиент закрыл соединение. */
    CLIENT_CLOSED,

    /** Устройство закрыло поток. */
    DEVICE_CLOSED,

    /** Устройство не открыло поток: адреса на той стороне нет или он занят. */
    REJECTED,

    /** Отправить не удалось. */
    SEND_FAILED,

    /** Интерфейс больше не удерживается. */
    TRANSPORT_CLOSED,

    /** Кадр потерян: соединение дальше недостоверно. */
    FRAMING_LOST,

    /** Ящик потока переполнился: устройство лило быстрее, чем клиент забирал. */
    MAILBOX_OVERFLOWED,

    /** Канал клиента оборвался. */
    CLIENT_FAILED,
}

/** Итог одного проброшенного соединения. */
public data class AdbForwardOutcome(
    val end: AdbForwardEnd,
    val detail: String,
    val fromClient: Long,
    val fromDevice: Long,
)

/**
 * Одно проброшенное соединение поверх одного логического потока ADB.
 *
 * **Устройству проброс ничего не сообщает.** `tcp:5555` или
 * `localabstract:имя` — обычный сервис ADB: adbd открывает по нему сокет у
 * себя и отдаёт поток. Реестр пробросов живёт целиком на хосте, и потому этот
 * класс не заводит ни одной новой протокольной сущности — только открывает
 * поток и перекладывает байты (`ADR-0005` §1).
 *
 * **Работа делится на три вызова, и порядок между ними обязателен.** Сперва
 * [open] — он отправляет запрос и **дожидается подтверждения**. Ждать
 * приходится не для порядка: до `OKAY` маршрутизатор не знает идентификатора
 * устройства и отказывается формировать `WRTE`, так что байты клиента,
 * отправленные раньше, просто исчезли бы. Потом два направления, [fromDevice]
 * и [fromClient], каждое на своём потоке исполнения.
 *
 * **Направления разделены по потокам, и это не роскошь.** Оба ожидания
 * блокирующие: одно висит на канале клиента, другое на ящике. Свести их в одно
 * можно только коротким таймаутом на чтение канала, платя постоянными
 * пробуждениями и лишней задержкой. Два простых цикла понятнее, и ошибиться в
 * них труднее (`ADR-0005` §4).
 *
 * Кто выделяет потоки — решает владелец: здесь, как и у [AdbDispatchLoop], нет
 * ни своего пула, ни своего `Thread`.
 *
 * **Конец с одной стороны закрывает другую, но не переклеивается в неё.**
 * Закрыл клиент — закрываем поток; закрыло устройство — закрываем канал. Первая
 * названная причина побеждает: встречное направление узнаёт о конце по
 * закрытому каналу, и его `CLIENT_FAILED` не должен затирать настоящую причину
 * (`ADR-0005` §6).
 */
public class AdbForwardStream(
    private val writer: AdbPacketWriter,
    private val dispatcher: AdbStreamDispatcher,
    private val channel: AdbByteChannel,
    private val maxPayload: Int = MAX_CHUNK_BYTES,
    private val diagnostics: DiagnosticSink = DiagnosticSink { },
    private val clock: () -> Instant = { Clock.systemUTC().instant() },
    private val elapsedNanos: () -> Long = { System.nanoTime() },
) {
    @Volatile
    private var outcome: AdbForwardOutcome? = null

    @Volatile
    private var localId: Int = 0

    private var mailbox: AdbStreamMailbox? = null

    /** Счётчики односторонние: каждый пишет свой поток, читает — закрывающий. */
    @Volatile
    private var clientBytes = 0L

    @Volatile
    private var deviceBytes = 0L

    /** Итог соединения; `null` — ещё живо. */
    public val finished: AdbForwardOutcome?
        get() = outcome

    /**
     * Открывает поток к [service] и ждёт подтверждения устройства.
     *
     * `null` означает, что поток открыт и качать можно. Иначе соединение уже
     * закончено, и возвращённый итог — окончательный.
     */
    public fun open(service: String, timeoutMillis: Int = OPEN_TIMEOUT_MS): AdbForwardOutcome? {
        require(timeoutMillis > 0) { "Forward open timeout must be positive: $timeoutMillis" }

        val (opened, request) = dispatcher.open(service)
        mailbox = opened
        localId = opened.localId
        emit("forward_open", mapOf("service" to service, "stream" to opened.localId.toString()))

        val sent = writer.write(request.command, request.arg0, request.arg1, request.payload)
        return if (sent == AdbWriteOutcome.Sent) {
            awaitOpened(opened, service, timeoutMillis)
        } else {
            finish(AdbForwardEnd.SEND_FAILED, "open: $sent")
        }
    }

    /**
     * Берёт поток, который устройство завело **само**.
     *
     * Открывать нечего: при обратном пробросе поток уже открыт с обеих сторон —
     * устройство его завело, диспетчер подтвердил. Поэтому [open] здесь не
     * вызывается вовсе, а качать можно сразу.
     *
     * Отдельный вход, а не флаг в [open], потому что это другое начало жизни:
     * там мы просим и ждём ответа, здесь нас поставили перед фактом.
     */
    public fun adopt(accepted: AdbStreamMailbox) {
        check(mailbox == null) { "Forward stream is already bound to a mailbox" }
        mailbox = accepted
        localId = accepted.localId
        emit("forward_adopted", mapOf("stream" to accepted.localId.toString()))
    }

    /**
     * Качает из устройства в канал клиента, пока соединение живо.
     *
     * Блокирует вызвавший поток и возвращает итог всего соединения — своего
     * конца или названного встречным направлением.
     */
    public fun fromDevice(): AdbForwardOutcome {
        val opened = requireNotNull(mailbox) { "Forward stream is not open" }
        var ended: AdbForwardOutcome? = outcome
        while (ended == null) {
            ended = when (val item = opened.poll(SLICE_MS)) {
                null -> outcome
                is AdbMailboxItem.Opened -> null
                is AdbMailboxItem.Data -> deliver(item.payload)
                is AdbMailboxItem.Ended -> finish(endOf(item.reason), item.detail)

                // Подтверждений наших записей этот потребитель не просил:
                // они приходят только в ящик, открытый с ними.
                is AdbMailboxItem.Acknowledged -> null
            }
        }
        return ended
    }

    /** Качает из канала клиента в устройство, пока соединение живо. */
    public fun fromClient() {
        val buffer = ByteArray(maxPayload.coerceIn(1, MAX_CHUNK_BYTES))
        var pumping = true
        while (pumping && outcome == null) {
            pumping = when (val read = readOrEnd(buffer)) {
                // Канал оборвался, и причина уже названа там, где это увидели.
                null -> false

                // Клиент закрылся штатно: поток устройству больше не нужен.
                0, -1 -> {
                    finish(AdbForwardEnd.CLIENT_CLOSED, "client closed after $clientBytes bytes")
                    false
                }

                else -> {
                    clientBytes += read
                    forwardToDevice(buffer, read)
                }
            }
        }
    }

    /** Закрывает соединение снаружи: оператор снял проброс или ушёл транспорт. */
    public fun cancel(detail: String) {
        finish(AdbForwardEnd.CLIENT_CLOSED, detail)
    }

    private fun awaitOpened(
        opened: AdbStreamMailbox,
        service: String,
        timeoutMillis: Int,
    ): AdbForwardOutcome? {
        val deadline = elapsedNanos() + timeoutMillis * NANOS_PER_MILLI
        var answer: AdbForwardOutcome? = null
        var live = false
        while (!live && answer == null) {
            val remaining = (deadline - elapsedNanos()) / NANOS_PER_MILLI
            answer = if (remaining <= 0) {
                finish(AdbForwardEnd.REJECTED, "no answer to $service in ${timeoutMillis}ms")
            } else {
                when (val item = opened.poll(remaining.coerceAtMost(SLICE_MS))) {
                    is AdbMailboxItem.Opened -> { live = true; null }
                    is AdbMailboxItem.Ended -> finish(endOf(item.reason), item.detail)

                    // Данные раньше подтверждения маршрутизатор не пропускает,
                    // и ждать нам по-прежнему нечего, кроме подтверждения.
                    else -> null
                }
            }
        }
        return answer
    }

    /** `null` — канал оборвался и соединение уже закончено. */
    private fun readOrEnd(buffer: ByteArray): Int? =
        try {
            channel.read(buffer)
        } catch (failure: IOException) {
            // Обрыв канала клиента — не обрыв транспорта: устройство ни при чём.
            finish(AdbForwardEnd.CLIENT_FAILED, failure.message ?: "read failed")
            null
        }

    private fun forwardToDevice(buffer: ByteArray, length: Int): Boolean {
        // Потока уже нет, если его закрыло встречное направление.
        val packet = dispatcher.write(localId, buffer.copyOf(length)) ?: return false
        val sent = writer.write(packet.command, packet.arg0, packet.arg1, packet.payload)
        if (sent != AdbWriteOutcome.Sent) {
            finish(AdbForwardEnd.SEND_FAILED, "write: $sent")
            return false
        }
        return true
    }

    /** `null` — качаем дальше. */
    private fun deliver(payload: ByteArray): AdbForwardOutcome? =
        try {
            channel.write(payload, payload.size)
            deviceBytes += payload.size
            null
        } catch (failure: IOException) {
            finish(AdbForwardEnd.CLIENT_FAILED, failure.message ?: "write failed")
        }

    /**
     * Закрывает обе стороны и запоминает итог.
     *
     * Под замком целиком: два направления приходят сюда одновременно, и без
     * него оба закрыли бы поток и оба записали бы свою причину.
     */
    @Synchronized
    private fun finish(end: AdbForwardEnd, detail: String): AdbForwardOutcome {
        outcome?.let { return it }
        val ended = AdbForwardOutcome(end, detail, clientBytes, deviceBytes)
        outcome = ended
        // Закрывается и то и другое: брошенный поток держит сокет на устройстве,
        // брошенный канал — соединение у клиента.
        dispatcher.close(localId)?.let { packet ->
            writer.write(packet.command, packet.arg0, packet.arg1, packet.payload)
        }
        channel.close()
        emit(
            "forward_closed",
            mapOf(
                "stream" to localId.toString(),
                "end" to end.name,
                "detail" to detail,
                "fromClient" to ended.fromClient.toString(),
                "fromDevice" to ended.fromDevice.toString(),
            ),
        )
        return ended
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
        /**
         * Потолок одного куска, отправляемого устройству.
         *
         * Меньше объявленного peer'ом `maxdata` брать можно, больше — нельзя:
         * кадр сверх объявленного устройство отбрасывает целиком. Значение то
         * же, что и кусок записи в [AdbPacketWriter]: крупнее не ускоряет, а
         * память держит.
         */
        public const val MAX_CHUNK_BYTES: Int = 16 * 1024

        /**
         * Сколько ждать подтверждения потока.
         *
         * Столько же, сколько [AdbReboot.DEFAULT_TIMEOUT_MS]: adbd либо
         * открывает сокет сразу, либо закрывает поток, и долгое молчание здесь
         * означает не занятость, а то, что отвечать некому.
         */
        public const val OPEN_TIMEOUT_MS: Int = 5_000

        /** Ожидание режется, чтобы закрытие встречным направлением было замечено. */
        private const val SLICE_MS = 250L

        private const val NANOS_PER_MILLI = 1_000_000L

        private fun endOf(reason: AdbMailboxEnd): AdbForwardEnd = when (reason) {
            AdbMailboxEnd.COMPLETED -> AdbForwardEnd.DEVICE_CLOSED
            AdbMailboxEnd.REJECTED -> AdbForwardEnd.REJECTED
            AdbMailboxEnd.LOCAL -> AdbForwardEnd.CLIENT_CLOSED
            AdbMailboxEnd.OVERFLOWED -> AdbForwardEnd.MAILBOX_OVERFLOWED
            AdbMailboxEnd.FRAMING_LOST -> AdbForwardEnd.FRAMING_LOST
            AdbMailboxEnd.TRANSPORT_CLOSED -> AdbForwardEnd.TRANSPORT_CLOSED
        }
    }
}
