package io.github.ncorror.nekoflash.protocol.adb

import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticSink
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant

/** Почему обмен по `sync:` не состоялся. */
public enum class AdbSyncFailure {
    /** Сессия не открыта или уже закрыта. */
    NOT_OPEN,

    /** Устройство отказало: путь недоступен, нет прав, нет места. */
    DEVICE_REFUSED,

    /** Ответ, которого в этом месте протокола быть не может. */
    UNEXPECTED_RESPONSE,

    /** Устройство назвало длину, которой доверять нельзя. */
    INVALID_LENGTH,

    /** Ответа не дождались. */
    TIMED_OUT,

    /** Интерфейс больше не удерживается. */
    TRANSPORT_CLOSED,

    /** Кадр ADB потерян: дальше по этому соединению недостоверно. */
    FRAMING_LOST,

    /** Отправить запрос не удалось. */
    SEND_FAILED,

    /**
     * Ящик потока переполнился: устройство слало быстрее, чем сессия забирала.
     *
     * Отдельная причина, а не `FRAMING_LOST`: кадр не потерян и соединение
     * достоверно — не успел потребитель. Путать эти два случая нельзя, они
     * требуют разных действий.
     */
    MAILBOX_OVERFLOWED,

    /**
     * Путь непредставим на проводе.
     *
     * Единственный случай — байт `NUL` внутри пути: на стороне устройства он
     * оборвал бы строку, и запрос означал бы не то, что просил вызывающий. Это
     * класс A из `03_PROTOCOL_AND_SAFETY_INVARIANTS_RU.md` §2 — техническая
     * непредставимость, а не запрет на путь.
     *
     * Пустой путь сюда **не относится**: он представим, и отвечает на него
     * устройство своим `FAIL`. Упреждать этот ответ проверкой на хосте значило
     * бы подменять class B собственной авторизацией — ровно то, что отменено
     * решением D031.
     */
    UNREPRESENTABLE_PATH,
}

/** Исход обмена по `sync:`. */
public sealed interface AdbSyncOutcome<out T> {
    /** Устройство ответило. */
    public data class Done<T>(val value: T) : AdbSyncOutcome<T>

    /** Ответа нет. */
    public data class Failed(
        val reason: AdbSyncFailure,
        val detail: String,
    ) : AdbSyncOutcome<Nothing>
}

private fun readIntLe(source: ByteArray, offset: Int): Int =
    (source[offset].toInt() and 0xFF) or
        ((source[offset + 1].toInt() and 0xFF) shl 8) or
        ((source[offset + 2].toInt() and 0xFF) shl 16) or
        ((source[offset + 3].toInt() and 0xFF) shl 24)

/**
 * Сессия сервиса `sync:`.
 *
 * Обмен строго по очереди: запрос — ответ, без незапрошенных данных. Поэтому
 * методы блокирующие и цикла качания снаружи не требуется — в отличие от
 * интерактивной оболочки, где поток живёт сам по себе.
 *
 * Пока сессия открыта, других вызовов по этому соединению быть не должно:
 * физический читатель один. Запрет держит владелец соединения.
 *
 * Класс отвечает за операции сервиса и только за них. Как разговаривать по
 * потоку, знает [AdbSyncExchange]; как писать файл — [AdbSyncUpload]. Граница
 * проведена там, где её потребовал detekt: с приходом `SEND` в одном классе
 * оказалось 28 функций при пороге 20, а поднимать порог запрещено
 * (`15_CLEAN_REBUILD_BLUEPRINT_RU.md` §4.1).
 *
 * [send] — единственная мутирующая операция. Она подчиняется правилам
 * `docs/03` §3 и §7; разбор — в KDoc [AdbSyncSendOutcome].
 */
public class AdbSyncSession(
    writer: AdbPacketWriter,
    dispatcher: AdbStreamDispatcher,
    diagnostics: DiagnosticSink = DiagnosticSink { },
    clock: () -> Instant = { Clock.systemUTC().instant() },
    elapsedNanos: () -> Long = { System.nanoTime() },
) {
    private val exchange = AdbSyncExchange(
        writer = writer,
        dispatcher = dispatcher,
        diagnostics = diagnostics,
        clock = clock,
        elapsedNanos = elapsedNanos,
    )

    private val upload = AdbSyncUpload(exchange)

    /** Открыта ли сессия. */
    public val active: Boolean
        get() = exchange.active

    /** Открывает сервис и ждёт подтверждения. */
    public fun open(timeoutMillis: Int = DEFAULT_TIMEOUT_MS): AdbSyncOutcome<Unit> =
        exchange.open(timeoutMillis)

    /** Закрывает сессию. */
    public fun close(): Unit = exchange.close()

    /**
     * Спрашивает сведения о пути.
     *
     * Отсутствующий путь — это **успех** с нулевым режимом, а не отказ:
     * `adbd` отвечает `STAT` с нулями. Отказ приходит только когда устройство
     * не может ответить вовсе.
     */
    public fun stat(path: String, timeoutMillis: Int = DEFAULT_TIMEOUT_MS): AdbSyncOutcome<AdbSyncStat> {
        if (!exchange.active) {
            return exchange.endReason() ?: exchange.failure(AdbSyncFailure.NOT_OPEN, "stat $path")
        }
        val deadline = exchange.deadlineFrom(timeoutMillis)
        return exchange.request(AdbSyncProtocol.ID_STAT, path) ?: readStatResponse(path, deadline)
    }

    /**
     * Читает файл с устройства, отдавая его блоками.
     *
     * Блоки отдаются [sink] по мере поступления и нигде не накапливаются: файл
     * может быть больше памяти, и собирать его целиком, чтобы потом записать,
     * значило бы ограничить размер оперативной памятью.
     */
    public fun receive(
        path: String,
        timeoutMillis: Int = TRANSFER_TIMEOUT_MS,
        sink: (ByteArray) -> Unit,
    ): AdbSyncOutcome<Long> {
        return if (!exchange.active) {
            exchange.endReason() ?: exchange.failure(AdbSyncFailure.NOT_OPEN, "recv $path")
        } else {
            val deadline = exchange.deadlineFrom(timeoutMillis)
            val digest = MessageDigest.getInstance("SHA-256")
            val outcome = exchange.request(AdbSyncProtocol.ID_RECV, path)
                ?: receiveLoop(path, deadline) { chunk ->
                    digest.update(chunk)
                    sink(chunk)
                }
            if (outcome is AdbSyncOutcome.Done) {
                val sha256 = digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
                exchange.emit(
                    "sync_received",
                    mapOf(
                        "path" to path,
                        "bytes" to outcome.value.toString(),
                        "sha256" to sha256,
                    ),
                )
            }
            outcome
        }
    }

    /**
     * Пишет файл на устройство.
     *
     * Единственная мутирующая операция сессии. Содержимое берётся у [source]
     * порциями: он заполняет переданный буфер и возвращает число заполненных
     * байт, а значение `0` или меньше означает конец. Буфер выделяет сессия,
     * поэтому блок не может превысить предел протокола — это инвариант формата,
     * а не ограничение вызывающего.
     *
     * Файл не собирается в памяти ни на одном шаге: он может быть больше
     * доступной памяти (`docs/06` §9).
     *
     * Исход отдельно сообщает, что стало с назначением. После пересечения
     * границы мутации отменить запись безопасно уже нельзя, и результат
     * [AdbSyncDestination.UNKNOWN] здесь — честный ответ, а не сбой. Полный
     * разбор — KDoc [AdbSyncSendOutcome].
     *
     * @param modifiedAtSeconds время изменения файла в секундах эпохи; уходит
     *   в `DONE`, как в Legacy `pushFile`.
     */
    public fun send(
        path: String,
        modifiedAtSeconds: Int,
        mode: Int = AdbSyncProtocol.DEFAULT_FILE_MODE,
        timeoutMillis: Int = TRANSFER_TIMEOUT_MS,
        source: (ByteArray) -> Int,
    ): AdbSyncSendOutcome = upload.send(path, modifiedAtSeconds, mode, timeoutMillis, source)

    private fun readStatResponse(
        path: String,
        deadline: Long,
    ): AdbSyncOutcome<AdbSyncStat> = when (val read = exchange.readHeader(deadline)) {
        is AdbSyncOutcome.Failed -> read
        is AdbSyncOutcome.Done -> when (read.value.id) {
            AdbSyncProtocol.ID_STAT -> when (val body = exchange.readExactly(STAT_BODY_BYTES, deadline)) {
                is AdbSyncOutcome.Failed -> body
                is AdbSyncOutcome.Done -> AdbSyncOutcome.Done(decodeStat(path, read.value, body.value))
            }

            AdbSyncProtocol.ID_FAIL -> exchange.refusal(read.value, deadline, "stat $path")
            else -> exchange.failure(
                AdbSyncFailure.UNEXPECTED_RESPONSE,
                "stat $path got ${read.value.id}",
            )
        }
    }

    private fun receiveLoop(
        path: String,
        deadline: Long,
        sink: (ByteArray) -> Unit,
    ): AdbSyncOutcome<Long> {
        var progress = ReceiveProgress()
        while (progress.outcome == null) {
            progress = when (val read = exchange.readHeader(deadline)) {
                is AdbSyncOutcome.Failed -> progress.copy(outcome = read)
                is AdbSyncOutcome.Done -> receiveHeader(path, deadline, sink, read.value, progress.received)
            }
        }
        return checkNotNull(progress.outcome)
    }

    private fun receiveHeader(
        path: String,
        deadline: Long,
        sink: (ByteArray) -> Unit,
        header: AdbSyncHeader,
        received: Long,
    ): ReceiveProgress = when (header.id) {
        AdbSyncProtocol.ID_DATA -> if (header.value < 0 || header.value > AdbSyncProtocol.DATA_CHUNK_BYTES) {
            ReceiveProgress(
                received,
                exchange.failure(AdbSyncFailure.INVALID_LENGTH, "recv $path chunk=${header.value}"),
            )
        } else {
            when (val chunk = exchange.readExactly(header.value, deadline)) {
                is AdbSyncOutcome.Failed -> ReceiveProgress(received, chunk)
                is AdbSyncOutcome.Done -> {
                    sink(chunk.value)
                    ReceiveProgress(received + chunk.value.size)
                }
            }
        }

        AdbSyncProtocol.ID_DONE -> ReceiveProgress(received, AdbSyncOutcome.Done(received))

        AdbSyncProtocol.ID_FAIL -> ReceiveProgress(
            received,
            exchange.refusal(header, deadline, "recv $path"),
        )

        else -> ReceiveProgress(
            received,
            exchange.failure(AdbSyncFailure.UNEXPECTED_RESPONSE, "recv $path got ${header.id}"),
        )
    }

    /**
     * Разбирает ответ `STAT`.
     *
     * Путь попадает в событие: без него evidence не доказывает, **что именно**
     * спрашивали. Прогон 2026-09-08 (`07` §6.36) упёрся ровно в это — решающая
     * проверка после обрыва записана без пути и сама по себе ничего не
     * доказывала.
     *
     * Число в заголовке здесь — **режим**, а не длина: у `STAT` поле `value`
     * означает именно его, и следом идут только размер и время. Первая версия
     * прочитала его как длину и ждала на четыре байта больше, чем присылает
     * устройство; тест это поймал.
     */
    private fun decodeStat(path: String, header: AdbSyncHeader, body: ByteArray): AdbSyncStat {
        val size = readIntLe(body, 0).toLong() and 0xFFFF_FFFFL
        exchange.emit(
            "sync_stat",
            mapOf("path" to path, "mode" to header.value.toString(), "size" to size.toString()),
        )
        return AdbSyncStat(
            mode = header.value,
            size = size,
            modifiedAtSeconds = readIntLe(body, 4),
        )
    }

    private data class ReceiveProgress(
        val received: Long = 0L,
        val outcome: AdbSyncOutcome<Long>? = null,
    )

    public companion object {
        /** Имя сервиса. */
        public const val SERVICE: String = "sync:"

        public const val DEFAULT_TIMEOUT_MS: Int = 10_000

        /** Передача файла может идти долго, но не бесконечно. */
        public const val TRANSFER_TIMEOUT_MS: Int = 300_000

        /**
         * Размер и время изменения в ответе `STAT`.
         *
         * Режим приходит числом заголовка, поэтому следом за ним остаётся
         * восемь байт, а не двенадцать.
         */
        private const val STAT_BODY_BYTES = 8
    }
}
