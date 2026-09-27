package io.github.ncorror.nekoflash.protocol.adb

import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticEvent
import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticSink
import java.time.Clock
import java.time.Instant

/** Почему рукопожатие не состоялось. */
public enum class AdbHandshakeFailure {
    /** Peer не ответил за отведённое время. */
    NO_RESPONSE,

    /** Транспорт закрыт: интерфейс больше не удерживается. */
    TRANSPORT_CLOSED,

    /** Кадр потерян: продолжать по этому соединению нельзя. */
    FRAMING_LOST,

    /** Отправить кадр не удалось. */
    SEND_FAILED,

    /** Peer ответил командой, которой в рукопожатии быть не может. */
    UNEXPECTED_COMMAND,

    /** `AUTH` неизвестного типа. */
    UNSUPPORTED_AUTH_TYPE,

    /** Ключ хоста недоступен: ни подписать токен, ни отправить публичный ключ. */
    HOST_KEY_UNAVAILABLE,

    /**
     * Устройство так и не подтвердило авторизацию.
     *
     * Не ошибка соединения: скорее всего, диалог на экране остался нетронутым
     * или отклонён. В Recovery подтверждать его обычно некому.
     */
    AUTHORIZATION_NOT_CONFIRMED,

    /** Рукопожатие на этом транспорте уже проводилось. */
    ALREADY_ATTEMPTED,
}

/** Исход рукопожатия. */
public sealed interface AdbHandshakeOutcome {
    /** Соединение установлено, peer представился. */
    public data class Connected(
        val banner: AdbConnectionBanner,
        val peerVersion: Int,
        val peerMaxPayload: Int,
    ) : AdbHandshakeOutcome

    /** Соединения нет. */
    public data class Failed(
        val reason: AdbHandshakeFailure,
        val detail: String,
    ) : AdbHandshakeOutcome
}

/**
 * Рукопожатие `CNXN` и авторизация `AUTH` поверх одного захваченного интерфейса.
 *
 * **Одно рукопожатие на один транспорт.** Повторный [connect] отклоняется, и
 * это не осторожность, а перенесённое ограничение: в Legacy прямо записано, что
 * автоматическое закрытие с повторным открытием и вторым `CNXN` на ряде
 * Android USB host вызывало цикл detach/attach и разрушало нормальную
 * последовательность `AUTH`. Новое соединение — это новый захват интерфейса и
 * новая `SessionGeneration`, а не второй `CNXN` в том же.
 *
 * Порядок и таймауты взяты из Legacy `handleAuthPacket` и A2 `handleAuth`, где
 * они совпадают: сначала попытка подписать токен сохранённым ключом, при
 * неудаче — отправка публичного ключа; затем до [AUTH_RESPONSE_LIMIT] ответов,
 * с ожиданием 10 секунд до отправки публичного ключа и 60 после, потому что во
 * втором случае ждать приходится человека у экрана.
 */
public class AdbHandshake(
    private val reader: AdbPacketReader,
    private val writer: AdbPacketWriter,
    private val keyStore: AdbKeyStore,
    private val localMaxPayload: Int,
    private val diagnostics: DiagnosticSink = DiagnosticSink { },
    private val onPublicKeySent: () -> Unit = { },
    private val clock: () -> Instant = { Clock.systemUTC().instant() },
    private val elapsedNanos: () -> Long = { System.nanoTime() },
) {
    private var attempted = false

    private sealed interface AuthStart {
        data class Ready(val publicKeySent: Boolean) : AuthStart
        data class Failed(val outcome: AdbHandshakeOutcome) : AuthStart
    }

    private sealed interface AuthRead {
        data class Packet(val packet: AdbPacket, val elapsedMillis: Long) : AuthRead
        data class Failed(val outcome: AdbHandshakeOutcome) : AuthRead
    }

    private sealed interface AuthAction {
        data object Continue : AuthAction
        data object PublicKeySent : AuthAction
        data class Finished(val outcome: AdbHandshakeOutcome) : AuthAction
    }

    /**
     * Проводит рукопожатие.
     *
     * После успеха согласованная версия протокола сообщается читателю и
     * писателю: с этого момента правило контрольной суммы меняется, и оба
     * обязаны узнать об этом одновременно.
     */
    public fun connect(): AdbHandshakeOutcome {
        if (attempted) {
            return failed(
                AdbHandshakeFailure.ALREADY_ATTEMPTED,
                "one transport carries one CNXN; open a new session instead",
            )
        }
        attempted = true

        val sent = writer.write(
            command = AdbCommand.CNXN,
            arg0 = LOCAL_VERSION,
            arg1 = localMaxPayload,
            payload = HOST_BANNER,
        )
        return if (sent !is AdbWriteOutcome.Sent) {
            sendFailure(sent, "CNXN")
        } else {
            emit(
                "cnxn_sent",
                mapOf("version" to LOCAL_VERSION.toHex(), "maxPayload" to localMaxPayload.toString()),
            )
            readInitialResponse()
        }
    }

    private fun readInitialResponse(): AdbHandshakeOutcome {
        var staleCloseCount = 0
        var result: AdbHandshakeOutcome? = null
        while (result == null && staleCloseCount <= STALE_CLOSE_LIMIT) {
            result = when (val outcome = reader.read(RESPONSE_TIMEOUT_MS)) {
                is AdbReadOutcome.Received -> when (outcome.packet.command) {
                    AdbCommand.CNXN -> connected(outcome.packet)
                    AdbCommand.AUTH -> authorize(outcome.packet)
                    AdbCommand.CLSE -> {
                        staleCloseCount += 1
                        emit(
                            "handshake_stale_close",
                            mapOf(
                                "remote" to outcome.packet.arg0.toString(),
                                "local" to outcome.packet.arg1.toString(),
                            ),
                        )
                        null
                    }

                    else -> failed(
                        AdbHandshakeFailure.UNEXPECTED_COMMAND,
                        "command=0x${outcome.packet.command.toString(16)}",
                    )
                }

                else -> readFailure(outcome, "CNXN response")
            }
        }
        return result ?: failed(
            AdbHandshakeFailure.UNEXPECTED_COMMAND,
            "more than $STALE_CLOSE_LIMIT stale CLSE packets before CNXN/AUTH",
        )
    }

    private fun authorize(first: AdbPacket): AdbHandshakeOutcome {
        if (first.arg0 != AUTH_TOKEN) {
            return failed(AdbHandshakeFailure.UNSUPPORTED_AUTH_TYPE, "type=${first.arg0}")
        }
        emit("auth_required")
        emitHostKeyProvenance()
        return when (val start = beginAuthorization(first.payload)) {
            is AuthStart.Failed -> start.outcome
            is AuthStart.Ready -> awaitAuthorization(start.publicKeySent)
        }
    }

    private fun beginAuthorization(token: ByteArray): AuthStart {
        val signature = runCatching { keyStore.signToken(token) }
        return if (signature.isSuccess) {
            val sent = writer.write(AdbCommand.AUTH, AUTH_SIGNATURE, 0, signature.getOrThrow())
            if (sent is AdbWriteOutcome.Sent) {
                emit("auth_signature_sent")
                AuthStart.Ready(publicKeySent = false)
            } else {
                AuthStart.Failed(sendFailure(sent, "AUTH SIGNATURE"))
            }
        } else {
            emit("auth_signature_failed", mapOf("cause" to signature.causeName()))
            val publicKeyFailure = sendPublicKey()
            if (publicKeyFailure == null) {
                AuthStart.Ready(publicKeySent = true)
            } else {
                AuthStart.Failed(publicKeyFailure)
            }
        }
    }

    private fun awaitAuthorization(initialPublicKeySent: Boolean): AdbHandshakeOutcome {
        var publicKeySent = initialPublicKeySent
        var attempt = 1
        var outcome: AdbHandshakeOutcome? = null
        while (attempt <= AUTH_RESPONSE_LIMIT && outcome == null) {
            when (val read = readAuthResponse(publicKeySent)) {
                is AuthRead.Failed -> outcome = read.outcome
                is AuthRead.Packet -> {
                    emitAuthResponse(attempt, read)
                    when (val action = handleAuthResponse(read.packet, publicKeySent)) {
                        AuthAction.Continue -> Unit
                        AuthAction.PublicKeySent -> publicKeySent = true
                        is AuthAction.Finished -> outcome = action.outcome
                    }
                }
            }
            attempt += 1
        }
        return outcome ?: failed(
            AdbHandshakeFailure.AUTHORIZATION_NOT_CONFIRMED,
            "device kept asking after $AUTH_RESPONSE_LIMIT responses",
        )
    }

    private fun readAuthResponse(publicKeySent: Boolean): AuthRead {
        val timeout = if (publicKeySent) AUTH_CONFIRMATION_TIMEOUT_MS else AUTH_SIGNATURE_TIMEOUT_MS
        val startedAt = elapsedNanos()
        val read = reader.read(timeout)
        val elapsedMillis = (elapsedNanos() - startedAt) / NANOS_PER_MILLI
        return when (read) {
            is AdbReadOutcome.Received -> AuthRead.Packet(read.packet, elapsedMillis)
            AdbReadOutcome.Idle -> AuthRead.Failed(
                failed(
                    if (publicKeySent) {
                        AdbHandshakeFailure.AUTHORIZATION_NOT_CONFIRMED
                    } else {
                        AdbHandshakeFailure.NO_RESPONSE
                    },
                    "waited ${elapsedMillis}ms of ${timeout}ms",
                ),
            )

            else -> AuthRead.Failed(readFailure(read, "AUTH response"))
        }
    }

    private fun emitAuthResponse(attempt: Int, read: AuthRead.Packet) {
        // Каждый ответ записывается: молчание устройства и повторный AUTH token
        // должны различаться в hardware evidence.
        emit(
            "auth_response",
            mapOf(
                "attempt" to attempt.toString(),
                "command" to "0x${read.packet.command.toString(16)}",
                "type" to read.packet.arg0.toString(),
                "payload" to read.packet.payload.size.toString(),
                "afterMs" to read.elapsedMillis.toString(),
            ),
        )
    }

    private fun handleAuthResponse(packet: AdbPacket, publicKeySent: Boolean): AuthAction =
        when (packet.command) {
            AdbCommand.CNXN -> AuthAction.Finished(connected(packet))
            AdbCommand.AUTH -> handleAuthToken(packet, publicKeySent)
            else -> AuthAction.Finished(
                failed(
                    AdbHandshakeFailure.UNEXPECTED_COMMAND,
                    "command=0x${packet.command.toString(16)}",
                ),
            )
        }

    private fun handleAuthToken(packet: AdbPacket, publicKeySent: Boolean): AuthAction {
        return when {
            packet.arg0 != AUTH_TOKEN -> AuthAction.Finished(
                failed(AdbHandshakeFailure.UNSUPPORTED_AUTH_TYPE, "type=${packet.arg0}"),
            )

            publicKeySent -> AuthAction.Continue
            else -> sendPublicKey()?.let(AuthAction::Finished) ?: AuthAction.PublicKeySent
        }
    }

    /**
     * Отправляет публичный ключ.
     *
     * Возвращает `null`, когда ключ ушёл, и готовый исход, когда отправить его
     * не удалось: после этого рукопожатию продолжаться незачем.
     */
    private fun sendPublicKey(): AdbHandshakeOutcome? {
        val payload = runCatching { keyStore.authPayload() }
        return payload.fold(
            onSuccess = { bytes ->
                val sent = writer.write(AdbCommand.AUTH, AUTH_RSAPUBLICKEY, 0, bytes)
                if (sent is AdbWriteOutcome.Sent) {
                    emit(
                        "auth_public_key_sent",
                        mapOf(
                            "path" to keyStore.publicKeyPath(),
                            "payload" to bytes.size.toString(),
                        ),
                    )
                    onPublicKeySent()
                    null
                } else {
                    sendFailure(sent, "AUTH RSAPUBLICKEY")
                }
            },
            onFailure = { error ->
                failed(
                    AdbHandshakeFailure.HOST_KEY_UNAVAILABLE,
                    error.message ?: error.javaClass.simpleName,
                )
            },
        )
    }

    /**
     * Сообщает журналу, откуда взялся ключ хоста и какой он.
     *
     * Без этой записи диалог авторизации в неожиданный момент невозможно
     * разобрать: непонятно, устройство забыло хост или хост потерял ключ.
     * Прогон 2026-09-03 упёрся ровно в этот вопрос (`07` §6.15).
     */
    private fun emitHostKeyProvenance() {
        val described = runCatching { keyStore.provenance() }
        emit(
            "host_key",
            described.fold(
                onSuccess = { provenance ->
                    mapOf(
                        "origin" to provenance.origin.name,
                        "fingerprint" to provenance.fingerprint,
                    )
                },
                onFailure = { error ->
                    mapOf("unavailable" to (error.message ?: error.javaClass.simpleName))
                },
            ),
        )
    }

    private fun connected(packet: AdbPacket): AdbHandshakeOutcome {
        val banner = AdbConnectionBanner.parse(packet.payload)
        reader.negotiate(packet.arg0)
        writer.negotiate(packet.arg0)
        emit(
            "connected",
            mapOf(
                "peerMode" to banner.peerMode.name,
                "peerVersion" to packet.arg0.toHex(),
                "peerMaxPayload" to packet.arg1.toString(),
                // Список, а не количество: по числу невозможно сказать,
                // объявил ли peer shell_v2, а от этого зависит, какой веткой
                // пойдёт команда. Разбор прогона в Recovery 2026-09-05
                // упёрся ровно в это.
                "features" to banner.features.sorted().joinToString(separator = ","),
            ),
        )
        return AdbHandshakeOutcome.Connected(banner, packet.arg0, packet.arg1)
    }

    private fun readFailure(outcome: AdbReadOutcome, stage: String): AdbHandshakeOutcome = when (outcome) {
        AdbReadOutcome.Idle -> failed(AdbHandshakeFailure.NO_RESPONSE, stage)
        AdbReadOutcome.Closed -> failed(AdbHandshakeFailure.TRANSPORT_CLOSED, stage)
        is AdbReadOutcome.Failed -> failed(
            AdbHandshakeFailure.FRAMING_LOST,
            "$stage: ${outcome.reason.name} ${outcome.detail}",
        )

        is AdbReadOutcome.Received -> failed(AdbHandshakeFailure.UNEXPECTED_COMMAND, stage)
    }

    private fun sendFailure(outcome: AdbWriteOutcome, stage: String): AdbHandshakeOutcome = when (outcome) {
        AdbWriteOutcome.Closed -> failed(AdbHandshakeFailure.TRANSPORT_CLOSED, stage)
        is AdbWriteOutcome.Interrupted -> failed(
            AdbHandshakeFailure.SEND_FAILED,
            "$stage: ${outcome.detail} (sent=${outcome.sentBytes})",
        )

        AdbWriteOutcome.Sent -> failed(AdbHandshakeFailure.SEND_FAILED, stage)
    }

    private fun failed(reason: AdbHandshakeFailure, detail: String): AdbHandshakeOutcome.Failed {
        emit("handshake_failed", mapOf("reason" to reason.name, "detail" to detail))
        return AdbHandshakeOutcome.Failed(reason, detail)
    }

    private fun emit(message: String, fields: Map<String, String> = emptyMap()) {
        diagnostics.emit(
            DiagnosticEvent(
                timestamp = clock(),
                category = DIAGNOSTIC_CATEGORY,
                message = message,
                fields = fields,
            ),
        )
    }

    private fun Result<*>.causeName(): String {
        val error = exceptionOrNull() ?: return "unknown"
        return error.message ?: error.javaClass.simpleName
    }

    private fun Int.toHex(): String = "0x${toUInt().toString(16)}"

    public companion object {
        public const val DIAGNOSTIC_CATEGORY: String = "adb"

        /** Версия протокола, на которой представляется хост. */
        public const val LOCAL_VERSION: Int = AdbChecksum.VERSION_WITH_CHECKSUM

        /** `AUTH` с токеном для подписи. */
        public const val AUTH_TOKEN: Int = 1

        /** `AUTH` с подписью токена. */
        public const val AUTH_SIGNATURE: Int = 2

        /** `AUTH` с публичным ключом хоста. */
        public const val AUTH_RSAPUBLICKEY: Int = 3

        /** Сколько ответов подряд разбирается, прежде чем ожидание признаётся напрасным. */
        public const val AUTH_RESPONSE_LIMIT: Int = 12

        /** Поздние закрытия старых logical streams перед ответом на новый CNXN. */
        private const val STALE_CLOSE_LIMIT: Int = 8

        private const val NANOS_PER_MILLI = 1_000_000L

        /** Значения из A2 и Legacy. */
        public const val RESPONSE_TIMEOUT_MS: Int = 10_000
        public const val AUTH_SIGNATURE_TIMEOUT_MS: Int = 10_000
        public const val AUTH_CONFIRMATION_TIMEOUT_MS: Int = 60_000

        /**
         * Баннер хоста.
         *
         * Ровно тот, что в обоих архивах. Возможностей хост о себе не
         * объявляет, и расширять строку ради `shell,v2` не нужно: в Legacy
         * баннер такой же, а решение о `shell,v2` принимается по возможностям
         * **устройства** из его `CNXN`. На железе это работало.
         */
        private val HOST_BANNER = "host::NekoFlash\u0000".toByteArray(Charsets.UTF_8)
    }
}
