package io.github.ncorror.nekoflash.protocol.adb

/** Пакет, который надо отправить устройству. */
public data class AdbSideloadPacket(
    val command: Long,
    val arg0: Int,
    val arg1: Int,
    val payload: ByteArray,
) {
    /** Сравнение по содержимому: `ByteArray` в `data class` его не даёт. */
    override fun equals(other: Any?): Boolean = this === other ||
        (
            other is AdbSideloadPacket && command == other.command && arg0 == other.arg0 &&
                arg1 == other.arg1 && payload.contentEquals(other.payload)
            )

    override fun hashCode(): Int =
        (((command.hashCode() * PRIME + arg0) * PRIME) + arg1) * PRIME + payload.contentHashCode()

    private companion object {
        const val PRIME = 31
    }
}

/** Какой кусок нагрузки запросило Recovery. */
public data class AdbSideloadBlockRequest(
    val blockNumber: Int,
    val offset: Long,
    val length: Int,
)

/** Сколько отдано и сколько покрыто. */
public data class AdbSideloadProgress(
    /** Кумулятивный трафик, **включая повторы**. Диагностика, не прогресс. */
    val servedBytes: Long,
    /** Уникальное покрытие — единственное, что годится для прогресса. */
    val uniqueBytes: Long,
    val totalBytes: Long,
    val uniqueBlocks: Int,
    val totalBlocks: Int,
) {
    /**
     * Доля уникального покрытия.
     *
     * До `DONEDONE` не поднимается выше 99: сто процентов означало бы, что
     * передача кончилась, а кончает её слово Recovery, а не наш счётчик.
     */
    public val percent: Int
        get() = AdbSideloadContract.coveragePercent(uniqueBytes, totalBytes).coerceAtMost(ALMOST)

    private companion object {
        const val ALMOST = 99
    }
}

/** Что произошло на шаге машины состояний. */
public enum class AdbSideloadTransition {
    OPENED,
    BLOCK_REQUESTED,
    BLOCK_SENT,
    BLOCK_ACKNOWLEDGED,
    REQUESTS_COMPLETE,
    TRANSFER_COMPLETE,
    CLOSED_BEFORE_DONE_DONE,
    INTERRUPTED_AFTER_PAYLOAD,
    CANCELLATION_REJECTED,
    STALE_PACKET,
    FAILED,
    CANCELLED,
}

/** Один шаг: что случилось, что отправить и что об этом известно. */
public data class AdbSideloadStep(
    val transition: AdbSideloadTransition,
    val outbound: List<AdbSideloadPacket> = emptyList(),
    val blockRequest: AdbSideloadBlockRequest? = null,
    val progress: AdbSideloadProgress? = null,
    val outcome: AdbSideloadOutcome? = null,
    val detail: String,
)

/**
 * Машина состояний ADB Sideload — чистая, без ввода-вывода.
 *
 * USB и файл остаются снаружи: блок отдаётся наружу как
 * [AdbSideloadBlockRequest], чтобы вызывающий успел **подтвердить** `WRTE`
 * Recovery до случайного чтения нагрузки. Порядок именно такой у A2, и он
 * повторяет проводной порядок Legacy.
 *
 * Что здесь взято из A2 как доказанное на железе, а не выведено:
 *
 * 1. **Sideload управляется запросами.** Recovery присылает номер блока
 *    восемью ASCII-символами; один и тот же блок оно вправе запросить не раз.
 * 2. **Блок `-1` означает конец запросов**, а не ошибку: хост отвечает пустым
 *    `WRTE` и ждёт `DONEDONE`.
 * 3. **`DONEDONE` кончает передачу, а не установку.**
 * 4. **Граница мутации — первая попытка отправить блок нагрузки**, и отмечается
 *    она **до** того, как байты попадут на провод.
 * 5. **Отмена законна только до этой границы.** После неё отменять нечего.
 * 6. Повторный блок увеличивает отданный трафик и **не** увеличивает
 *    уникальное покрытие.
 */
public class AdbSideloadSession(
    private val localId: Int,
    private val totalBytes: Long,
) {
    private enum class Phase { CREATED, OPEN_SENT, WAITING_REQUEST, WAITING_BLOCK, WAITING_ACK, TERMINAL }

    private var phase = Phase.CREATED
    private var remoteId = 0
    private var served = 0L
    private var unique = 0L
    private val confirmed = HashSet<Int>()
    private var pendingRequest: AdbSideloadBlockRequest? = null
    private var pendingBytes = 0
    private var pendingBlock: Int? = null
    private var pendingEndMarker = false
    private var payloadStarted = false

    init {
        require(localId > 0) { "идентификатор потока должен быть положительным" }
        require(totalBytes > 0L) { "объём нагрузки должен быть положительным" }
    }

    /** Имя сервиса: объём и размер блока объявляются в нём самом. */
    public val service: String get() = AdbSideloadContract.service(totalBytes)

    /** Перешли ли границу мутации. После неё отмены не существует. */
    public val mutated: Boolean get() = payloadStarted

    /** Открывает поток. */
    public fun open(): AdbSideloadPacket {
        check(phase == Phase.CREATED) { "поток уже открыт" }
        phase = Phase.OPEN_SENT
        return AdbSideloadPacket(AdbCommand.OPEN, localId, 0, "$service\u0000".toByteArray(Charsets.US_ASCII))
    }

    /** Разбирает входящий пакет. */
    public fun consume(packet: AdbPacket): AdbSideloadStep = when {
        phase == Phase.TERMINAL -> staleStep("пакет после терминального состояния")
        packet.arg1 != localId -> staleStep("чужой поток: local=${packet.arg1}, ждали $localId")
        remoteId > 0 && packet.arg0 != remoteId ->
            failure(AdbSideloadFailure.PROTOCOL, "удалённый поток сменился: ${packet.arg0}, ждали $remoteId")

        phase == Phase.OPEN_SENT -> onOpen(packet)
        phase == Phase.WAITING_REQUEST -> onRequest(packet)
        phase == Phase.WAITING_BLOCK -> failure(AdbSideloadFailure.PROTOCOL, "пакет пришёл во время чтения блока")
        phase == Phase.WAITING_ACK -> onAck(packet)
        else -> failure(AdbSideloadFailure.PROTOCOL, "пакет до открытия потока")
    }

    /**
     * Отдаёт прочитанный блок.
     *
     * Границу мутации вызывающий обязан отметить [markPayloadStarted] **до**
     * того, как эти байты уйдут на провод.
     */
    public fun provideBlock(request: AdbSideloadBlockRequest, payload: ByteArray): AdbSideloadStep = when {
        phase != Phase.WAITING_BLOCK || pendingRequest != request ->
            failure(AdbSideloadFailure.PROTOCOL, "блок не соответствует запрошенному")

        payload.size != request.length -> failure(
            AdbSideloadFailure.FILE,
            "источник отдал ${payload.size} байт для блока ${request.blockNumber}, ждали ${request.length}",
        )

        else -> {
            pendingRequest = null
            pendingBytes = payload.size
            pendingBlock = request.blockNumber
            pendingEndMarker = false
            phase = Phase.WAITING_ACK
            AdbSideloadStep(
                transition = AdbSideloadTransition.BLOCK_SENT,
                outbound = listOf(writePacket(localId, remoteId, payload)),
                detail = "блок ${request.blockNumber}, смещение ${request.offset}, байт ${payload.size}",
            )
        }
    }

    /**
     * Отмечает необратимую границу — **до** первой отправки нагрузки на провод.
     *
     * Возвращает `true`, если это была первая. Вызывать обязательно: без
     * отметки подтверждение нагрузки будет прочитано как нарушение порядка, и
     * это лучше, чем тихо потерять границу.
     */
    public fun markPayloadStarted(): Boolean {
        check(phase == Phase.WAITING_ACK) { "граница мутации требует ожидания подтверждения блока" }
        check(pendingBytes > 0 && pendingBlock != null) { "граница мутации требует настоящего блока" }
        val first = !payloadStarted
        payloadStarted = true
        return first
    }

    /** Нагрузка не прочиталась на хосте. */
    public fun blockReadFailed(request: AdbSideloadBlockRequest, detail: String): AdbSideloadStep = when {
        phase != Phase.WAITING_BLOCK || pendingRequest != request ->
            failure(AdbSideloadFailure.PROTOCOL, "сбой чтения не соответствует запрошенному блоку")

        else -> {
            pendingRequest = null
            failure(AdbSideloadFailure.FILE, detail)
        }
    }

    /** Транспорт закрылся. */
    public fun transportClosed(detail: String): AdbSideloadStep =
        closeBeforeDoneDone(AdbSideloadFailure.TRANSPORT, detail, emptyList())

    /**
     * Отмена оператором.
     *
     * Законна только до границы мутации. После неё отменять нечего: устройство
     * уже могло начать меняться, и назвать это отменой значило бы обещать
     * несделанное.
     */
    public fun cancel(): AdbSideloadStep = when {
        payloadStarted -> AdbSideloadStep(
            transition = AdbSideloadTransition.CANCELLATION_REJECTED,
            detail = "отмена невозможна после границы мутации",
        )

        phase == Phase.TERMINAL -> AdbSideloadStep(
            transition = AdbSideloadTransition.CANCELLED,
            outcome = AdbSideloadOutcome.Cancelled,
            detail = "поток уже завершён",
        )

        else -> {
            phase = Phase.TERMINAL
            AdbSideloadStep(
                transition = AdbSideloadTransition.CANCELLED,
                outbound = listOfNotNull(closePacketOrNull(localId, remoteId)),
                outcome = AdbSideloadOutcome.Cancelled,
                detail = "отменено до отправки нагрузки",
            )
        }
    }

    private fun onOpen(packet: AdbPacket): AdbSideloadStep = when {
        packet.command == AdbCommand.OKAY && packet.arg0 > 0 -> {
            remoteId = packet.arg0
            phase = Phase.WAITING_REQUEST
            AdbSideloadStep(AdbSideloadTransition.OPENED, detail = "local=$localId remote=$remoteId $service")
        }

        packet.command == AdbCommand.OKAY ->
            failure(AdbSideloadFailure.PROTOCOL, "Recovery подтвердило поток негодным идентификатором ${packet.arg0}")

        packet.command == AdbCommand.CLSE -> {
            remoteId = packet.arg0.coerceAtLeast(0)
            closeBeforeDoneDone(
                AdbSideloadFailure.PROTOCOL,
                "Recovery закрыло поток, не подтвердив открытие",
                listOfNotNull(closePacketOrNull(localId, remoteId)),
            )
        }

        else -> failure(
            AdbSideloadFailure.PROTOCOL,
            "Recovery не подтвердило sideload-host: 0x${packet.command.toString(HEX)}",
        )
    }

    private fun onRequest(packet: AdbPacket): AdbSideloadStep = when (packet.command) {
        AdbCommand.CLSE -> closeBeforeDoneDone(
            AdbSideloadFailure.PROTOCOL,
            "Recovery закрыло поток до DONEDONE",
            listOfNotNull(closePacketOrNull(localId, remoteId)),
        )

        AdbCommand.WRTE -> onWrite(packet.payload)

        else -> failure(
            AdbSideloadFailure.PROTOCOL,
            "неожиданная команда в потоке: 0x${packet.command.toString(HEX)}",
        )
    }

    private fun onWrite(payload: ByteArray): AdbSideloadStep {
        val ack = okayPacket(localId, remoteId)
        val text = runCatching {
            String(payload, Charsets.US_ASCII).trimEnd('\u0000', ' ', '\r', '\n')
        }.getOrDefault("")

        return if (text == AdbSideloadContract.DONE_DONE) {
            phase = Phase.TERMINAL
            AdbSideloadStep(
                transition = AdbSideloadTransition.TRANSFER_COMPLETE,
                outbound = listOf(ack),
                outcome = AdbSideloadOutcome.TransferComplete,
                detail = "Recovery прислало DONEDONE: кончилась передача, не установка",
            )
        } else {
            onBlockNumber(text, payload.size, ack)
        }
    }

    private fun onBlockNumber(text: String, size: Int, ack: AdbSideloadPacket): AdbSideloadStep {
        val number = text.take(TOKEN).trim('\u0000', ' ').toIntOrNull()
        val offset = number?.toLong()?.times(AdbSideloadContract.BLOCK_SIZE_BYTES.toLong())
        return when {
            number == null -> failure(AdbSideloadFailure.PROTOCOL, "негодный запрос блока ($size байт)", listOf(ack))
            number == AdbSideloadContract.END_OF_REQUESTS -> endOfRequests(ack)
            number < 0 -> failure(AdbSideloadFailure.PROTOCOL, "отрицательный номер блока: $number", listOf(ack))
            offset == null || offset >= totalBytes -> failure(
                AdbSideloadFailure.PROTOCOL,
                "Recovery запросило блок вне нагрузки: $number, смещение $offset, объём $totalBytes",
                listOf(ack),
            )

            else -> request(number, offset, ack)
        }
    }

    private fun request(number: Int, offset: Long, ack: AdbSideloadPacket): AdbSideloadStep {
        val length = minOf(AdbSideloadContract.BLOCK_SIZE_BYTES.toLong(), totalBytes - offset).toInt()
        val asked = AdbSideloadBlockRequest(number, offset, length)
        pendingRequest = asked
        phase = Phase.WAITING_BLOCK
        return AdbSideloadStep(
            transition = AdbSideloadTransition.BLOCK_REQUESTED,
            outbound = listOf(ack),
            blockRequest = asked,
            detail = "блок $number, смещение $offset, байт $length",
        )
    }

    private fun endOfRequests(ack: AdbSideloadPacket): AdbSideloadStep {
        pendingBytes = 0
        pendingBlock = null
        pendingEndMarker = true
        phase = Phase.WAITING_ACK
        return AdbSideloadStep(
            transition = AdbSideloadTransition.REQUESTS_COMPLETE,
            outbound = listOf(ack, writePacket(localId, remoteId, ByteArray(0))),
            detail = "запросы блоков кончились, ждём DONEDONE",
        )
    }

    private fun onAck(packet: AdbPacket): AdbSideloadStep = when {
        packet.command == AdbCommand.CLSE -> closeBeforeDoneDone(
            AdbSideloadFailure.PROTOCOL,
            "Recovery закрыло поток, не подтвердив блок",
            listOfNotNull(closePacketOrNull(localId, remoteId)),
        )

        packet.command != AdbCommand.OKAY -> failure(
            AdbSideloadFailure.PROTOCOL,
            "вместо подтверждения пришло 0x${packet.command.toString(HEX)}",
        )

        // Подтверждение нагрузки без отмеченной границы означает, что вызывающий
        // отправил байты, не отметив необратимость. Потерять границу тихо
        // нельзя: после неё отмены не существует.
        !pendingEndMarker && pendingBytes > 0 && !payloadStarted ->
            failure(AdbSideloadFailure.PROTOCOL, "подтверждение нагрузки пришло до отметки границы мутации")

        pendingEndMarker -> {
            pendingEndMarker = false
            phase = Phase.WAITING_REQUEST
            AdbSideloadStep(AdbSideloadTransition.REQUESTS_COMPLETE, detail = "конец запросов подтверждён")
        }

        else -> acknowledged()
    }

    private fun acknowledged(): AdbSideloadStep {
        val bytes = pendingBytes.toLong()
        val block = pendingBlock
        served += bytes
        // Повтор увеличивает отданное и не двигает покрытие: Recovery вправе
        // просить один блок не раз, и считать это прогрессом значило бы
        // показывать больше ста процентов на исправной передаче.
        if (block != null && confirmed.add(block)) unique += bytes
        pendingBytes = 0
        pendingBlock = null
        phase = Phase.WAITING_REQUEST
        return AdbSideloadStep(
            transition = AdbSideloadTransition.BLOCK_ACKNOWLEDGED,
            progress = progress(),
            detail = "подтверждён блок $block",
        )
    }

    private fun progress(): AdbSideloadProgress {
        val blockSize = AdbSideloadContract.BLOCK_SIZE_BYTES.toLong()
        return AdbSideloadProgress(
            servedBytes = served,
            uniqueBytes = unique,
            totalBytes = totalBytes,
            uniqueBlocks = confirmed.size,
            totalBlocks = ((totalBytes + blockSize - 1) / blockSize).toInt(),
        )
    }

    private fun closeBeforeDoneDone(
        kind: AdbSideloadFailure,
        detail: String,
        outbound: List<AdbSideloadPacket>,
    ): AdbSideloadStep {
        phase = Phase.TERMINAL
        val outcome = AdbSideloadContract.classifyClose(kind, detail, payloadStarted, served, unique, totalBytes)
        return AdbSideloadStep(
            transition = when (outcome) {
                is AdbSideloadOutcome.ClosedBeforeDoneDone -> AdbSideloadTransition.CLOSED_BEFORE_DONE_DONE
                is AdbSideloadOutcome.InterruptedAfterPayload -> AdbSideloadTransition.INTERRUPTED_AFTER_PAYLOAD
                else -> AdbSideloadTransition.FAILED
            },
            outbound = outbound,
            progress = progress(),
            outcome = outcome,
            detail = detail,
        )
    }

    private fun failure(
        kind: AdbSideloadFailure,
        detail: String,
        prefix: List<AdbSideloadPacket> = emptyList(),
    ): AdbSideloadStep {
        phase = Phase.TERMINAL
        return AdbSideloadStep(
            transition = AdbSideloadTransition.FAILED,
            outbound = prefix + listOfNotNull(closePacketOrNull(localId, remoteId)),
            progress = progress(),
            // Сбой после границы мутации сбоем уже не является: устройство
            // могло начать меняться, и классификация это учитывает.
            outcome = AdbSideloadContract.classifyClose(kind, detail, payloadStarted, served, unique, totalBytes),
            detail = detail,
        )
    }

    private companion object {
        /** Номер блока приходит восемью символами ASCII. */
        const val TOKEN = 8
        const val HEX = 16
    }
}

/** Шаг, на котором ничего не произошло: пакет не наш или пришёл после конца. */
private fun staleStep(detail: String): AdbSideloadStep =
    AdbSideloadStep(AdbSideloadTransition.STALE_PACKET, detail = detail)

private fun okayPacket(local: Int, remote: Int): AdbSideloadPacket =
    AdbSideloadPacket(AdbCommand.OKAY, local, remote, ByteArray(0))

private fun writePacket(local: Int, remote: Int, payload: ByteArray): AdbSideloadPacket =
    AdbSideloadPacket(AdbCommand.WRTE, local, remote, payload)

/** `CLSE` отправляется только когда удалённый поток известен: иначе некому. */
private fun closePacketOrNull(local: Int, remote: Int): AdbSideloadPacket? =
    if (remote > 0) AdbSideloadPacket(AdbCommand.CLSE, local, remote, ByteArray(0)) else null
