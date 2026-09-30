package io.github.ncorror.nekoflash.protocol.adb

import java.security.MessageDigest

/**
 * Запись файла на устройство по `sync:`.
 *
 * Отдельный класс, потому что это единственная мутирующая операция сервиса, и
 * правила у неё свои: граница мутации, отчёт о состоянии назначения независимо
 * от исхода операции и запрет автоматического повтора. Обмен по потоку она не
 * ведёт — за это отвечает [AdbSyncExchange].
 *
 * Разбор правил и обоснование границы — KDoc [AdbSyncSendOutcome].
 */
internal class AdbSyncUpload(private val exchange: AdbSyncExchange) {
    /**
     * Пишет файл на устройство.
     *
     * Содержимое берётся у [source] порциями: он заполняет переданный буфер и
     * возвращает число заполненных байт, `0` или меньше означает конец. Буфер
     * выделяется здесь, поэтому блок не может превысить предел протокола — это
     * инвариант формата, а не ограничение вызывающего.
     */
    fun send(
        path: String,
        modifiedAtSeconds: Int,
        mode: Int,
        timeoutMillis: Int,
        sendMode: AdbSyncSendMode,
        source: (ByteArray) -> Int,
    ): AdbSyncSendOutcome {
        val outcome = attempt(path, modifiedAtSeconds, mode, timeoutMillis, sendMode, source)
        return if (sendMode.dryRun) rehearsed(outcome) else outcome
    }

    /**
     * Проба не трогает назначение: `adbd` при `kSyncFlagDryRun` не удаляет и не
     * открывает файл (`send_impl`, `handle_send_file`), поэтому любой исход
     * оставляет его [AdbSyncDestination.UNTOUCHED].
     */
    private fun rehearsed(outcome: AdbSyncSendOutcome): AdbSyncSendOutcome = when (outcome) {
        is AdbSyncSendOutcome.Committed -> AdbSyncSendOutcome.Rehearsed(outcome.bytesSent, outcome.sha256)
        is AdbSyncSendOutcome.Failed -> outcome.copy(destination = AdbSyncDestination.UNTOUCHED)
        is AdbSyncSendOutcome.Rehearsed -> outcome
    }

    private fun attempt(
        path: String,
        modifiedAtSeconds: Int,
        mode: Int,
        timeoutMillis: Int,
        sendMode: AdbSyncSendMode,
        source: (ByteArray) -> Int,
    ): AdbSyncSendOutcome {
        if (!exchange.active) {
            // Назначение не тронуто: запрос `SEND` не уходил. Но **почему**
            // сессии нет, сказать надо — причина известна.
            val ended = exchange.endReason()
            return failed(
                ended?.reason ?: AdbSyncFailure.NOT_OPEN,
                ended?.let { "send $path: ${it.detail}" } ?: "send $path",
                AdbSyncDestination.UNTOUCHED,
                0L,
            )
        }
        if (path.contains(NUL)) {
            return failed(
                AdbSyncFailure.UNREPRESENTABLE_PATH,
                "send: NUL inside the path",
                AdbSyncDestination.UNTOUCHED,
                0L,
            )
        }
        val deadline = exchange.deadlineFrom(timeoutMillis)
        exchange.emit(
            "sync_send_started",
            mapOf(
                "path" to path,
                "mode" to mode.toString(),
                "dryRun" to sendMode.dryRun.toString(),
                "v2" to sendMode.secondVersion.toString(),
                "compression" to (sendMode.compression?.name ?: "none"),
            ),
        )
        return openDestination(path, mode, sendMode)
            ?: transferBody(path, modifiedAtSeconds, deadline, sendMode.compression?.encoder(), source)
    }

    /**
     * Отправляет `SEND` и тем самым пересекает границу мутации.
     *
     * Возвращает отказ, если запрос не ушёл, и `null`, если ушёл. Разделение
     * `UNTOUCHED`/`UNKNOWN` опирается на число фактически отданных в USB байт:
     * пока их ноль, устройство запроса не видело.
     */
    private fun openDestination(path: String, mode: Int, sendMode: AdbSyncSendMode): AdbSyncSendOutcome.Failed? {
        val frame = if (sendMode.secondVersion) {
            AdbSyncProtocol.sendV2(path, mode, sendMode.flags)
        } else {
            AdbSyncProtocol.message(AdbSyncProtocol.ID_SEND, AdbSyncProtocol.sendSpec(path, mode))
        }
        return when (val outcome = exchange.writeFrame(frame)) {
            null -> failed(AdbSyncFailure.NOT_OPEN, "send $path", AdbSyncDestination.UNTOUCHED, 0L)
            AdbWriteOutcome.Sent -> null
            AdbWriteOutcome.Closed -> failed(
                AdbSyncFailure.TRANSPORT_CLOSED,
                "send $path before the request left the host",
                AdbSyncDestination.UNTOUCHED,
                0L,
            )

            is AdbWriteOutcome.Interrupted -> failed(
                AdbSyncFailure.SEND_FAILED,
                "send $path: ${outcome.detail} (sent=${outcome.sentBytes})",
                if (outcome.sentBytes == 0) AdbSyncDestination.UNTOUCHED else AdbSyncDestination.UNKNOWN,
                0L,
            )
        }
    }

    /**
     * Передаёт содержимое и ждёт вердикт.
     *
     * Вызывается только после того, как `SEND` ушёл, поэтому любой отказ здесь
     * оставляет назначение в [AdbSyncDestination.UNKNOWN].
     *
     * Со сжатием блок файла сначала проходит [encoder], а в `DATA` уходит его
     * вывод кусками не больше 64 КиБ, как у `SendLargeFile`. `sent` тогда —
     * байты файла, отданные кодировщику без сбоя записи: сколько из них уже
     * дошло до устройства, по сжатому потоку не сказать.
     */
    private fun transferBody(
        path: String,
        modifiedAtSeconds: Int,
        deadline: Long,
        encoder: SyncEncoder?,
        source: (ByteArray) -> Int,
    ): AdbSyncSendOutcome {
        val buffer = ByteArray(AdbSyncProtocol.DATA_CHUNK_BYTES)
        val digest = MessageDigest.getInstance("SHA-256")
        var sent = 0L
        var failure: AdbSyncSendOutcome.Failed? = null
        val frames = OutputChunks(AdbSyncProtocol.DATA_CHUNK_BYTES) { chunk ->
            if (failure == null) {
                failure = writeAfterBoundary(AdbSyncProtocol.dataFrame(chunk, chunk.size), "send $path data", sent)
            }
        }
        var finished = false
        while (failure == null && !finished) {
            val read = source(buffer)
            if (read <= 0) {
                finished = true
                encoder?.end { frames.write(it, 0, it.size) }
                frames.flush()
            } else {
                digest.update(buffer, 0, read)
                if (encoder != null) {
                    encoder.feed(buffer, read) { frames.write(it, 0, it.size) }
                } else {
                    frames.write(buffer, 0, read)
                    frames.flush()
                }
                // Блок засчитывается, только если его кадр ушёл целиком.
                // Прибавлять его до проверки значило бы сообщать об отправке
                // того, что не отправлено: прогон 2026-09-08 (`07` §6.36) на
                // обрыве кабеля отчитался ровно на один блок больше, чем ушло.
                if (failure == null) sent += read
            }
        }
        return failure ?: finishTransfer(path, modifiedAtSeconds, deadline, sent, digest)
    }

    /** Досылает `DONE` и читает вердикт. */
    private fun finishTransfer(
        path: String,
        modifiedAtSeconds: Int,
        deadline: Long,
        sent: Long,
        digest: MessageDigest,
    ): AdbSyncSendOutcome {
        val done = AdbSyncProtocol.header(AdbSyncProtocol.ID_DONE, modifiedAtSeconds)
        val failure = writeAfterBoundary(done, "send $path done", sent)
        return failure ?: readVerdict(path, deadline, sent, digest)
    }

    /**
     * Читает `OKAY` или `FAIL`.
     *
     * `OKAY` — единственное доказательство, что файл записан. Всё остальное,
     * включая честный `FAIL` устройства, оставляет назначение неизвестным:
     * отказ говорит, что операция не удалась, но не что она ничего не тронула.
     */
    private fun readVerdict(
        path: String,
        deadline: Long,
        sent: Long,
        digest: MessageDigest,
    ): AdbSyncSendOutcome = when (val read = exchange.readHeader(deadline)) {
        is AdbSyncOutcome.Failed -> failed(read.reason, read.detail, AdbSyncDestination.UNKNOWN, sent)
        is AdbSyncOutcome.Done -> when (read.value.id) {
            AdbSyncProtocol.ID_OKAY -> committed(path, sent, digest)
            AdbSyncProtocol.ID_FAIL -> exchange.refusal(read.value, deadline, "send $path").let { refused ->
                failed(refused.reason, refused.detail, AdbSyncDestination.UNKNOWN, sent)
            }

            else -> failed(
                AdbSyncFailure.UNEXPECTED_RESPONSE,
                "send $path got ${read.value.id}",
                AdbSyncDestination.UNKNOWN,
                sent,
            )
        }
    }

    private fun committed(path: String, sent: Long, digest: MessageDigest): AdbSyncSendOutcome.Committed {
        val sha256 = digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
        exchange.emit("sync_sent", mapOf("path" to path, "bytes" to sent.toString(), "sha256" to sha256))
        return AdbSyncSendOutcome.Committed(sent, sha256)
    }

    private fun writeAfterBoundary(
        frame: ByteArray,
        stage: String,
        sent: Long,
    ): AdbSyncSendOutcome.Failed? = when (val outcome = exchange.writeFrame(frame)) {
        null -> failed(AdbSyncFailure.NOT_OPEN, stage, AdbSyncDestination.UNKNOWN, sent)
        AdbWriteOutcome.Sent -> null
        AdbWriteOutcome.Closed ->
            failed(AdbSyncFailure.TRANSPORT_CLOSED, stage, AdbSyncDestination.UNKNOWN, sent)

        is AdbWriteOutcome.Interrupted -> failed(
            AdbSyncFailure.SEND_FAILED,
            "$stage: ${outcome.detail} (sent=${outcome.sentBytes})",
            AdbSyncDestination.UNKNOWN,
            sent,
        )
    }

    private fun failed(
        reason: AdbSyncFailure,
        detail: String,
        destination: AdbSyncDestination,
        bytesSent: Long,
    ): AdbSyncSendOutcome.Failed {
        exchange.emit(
            "sync_send_failed",
            mapOf(
                "reason" to reason.name,
                "detail" to detail,
                "destination" to destination.name,
                "bytes" to bytesSent.toString(),
            ),
        )
        return AdbSyncSendOutcome.Failed(reason, detail, destination, bytesSent)
    }

    private companion object {
        /** Единственный байт, из-за которого путь невозможно передать. */
        const val NUL = '\u0000'
    }
}
