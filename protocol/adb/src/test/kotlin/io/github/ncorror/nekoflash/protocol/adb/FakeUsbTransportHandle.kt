package io.github.ncorror.nekoflash.protocol.adb

import io.github.ncorror.nekoflash.usb.api.UsbTransferArguments
import io.github.ncorror.nekoflash.usb.api.UsbTransferFailure
import io.github.ncorror.nekoflash.usb.api.UsbTransferResult
import io.github.ncorror.nekoflash.usb.api.UsbTransportHandle
import io.github.ncorror.nekoflash.usb.api.UsbDeviceDescriptor
import io.github.ncorror.nekoflash.usb.api.UsbEndpointDescriptor
import io.github.ncorror.nekoflash.usb.api.UsbEndpointDirection
import io.github.ncorror.nekoflash.usb.api.UsbInterfaceCandidate
import io.github.ncorror.nekoflash.usb.api.UsbInterfaceKind
import io.github.ncorror.nekoflash.usb.api.UsbMatchConfidence
import io.github.ncorror.nekoflash.usb.api.UsbTransferType
import org.junit.Assert.assertTrue

/**
 * Подставной интерфейс: заранее заданная очередь исходов передачи.
 *
 * Каждый элемент очереди — одна операция приёма или отправки. Это принципиально:
 * тест на inbound framing проверяет ровно то, сколько операций сделал читатель,
 * и очередь позволяет утверждать, что второй попытки дочитать payload не было.
 */
internal class FakeUsbTransportHandle(
    private val inbound: MutableList<Transfer> = mutableListOf(),
    private val outbound: MutableList<Transfer> = mutableListOf(),
    /**
     * Отдавать ли записанные ответы только после первого вопроса.
     *
     * Нужно там, где приём крутит `AdbDispatchLoop` на своём потоке: он
     * начинает читать раньше, чем потребитель успел открыть поток, и без этого
     * записанный `OKAY` достался бы ещё не существующему ящику — маршрутизатор
     * счёл бы его чужим и ответил `CLSE`. Настоящее устройство отвечает после
     * того, как его спросили, и с этим флагом подставное ведёт себя так же.
     */
    private val answerOnlyAfterRequest: Boolean = false,
) : UsbTransportHandle {
    /** Один запланированный исход операции. */
    sealed interface Transfer {
        /** Передача завершилась и перенесла [bytes] байт из [source]. */
        data class Completed(val bytes: Int, val source: ByteArray = ByteArray(0)) : Transfer

        /** Передача не состоялась. */
        data class Failed(val reason: UsbTransferFailure) : Transfer

        /**
         * Не ответ, а условие: следующее отдаётся, когда host отправил
         * [minimumFrames] кадров.
         *
         * Записанный ответ — не устройство: он готов ответить раньше, чем его
         * спросили. Пока приём крутил сам потребитель, порядок задавало число
         * вызовов `pump`; с `AdbDispatchLoop` этого рычага нет — цикл читает
         * сам и вычитывает всё, что лежит. Там, где проверяется именно
         * последовательность («обрыв **после** запроса»), она задаётся здесь
         * явно, а не подразумевается расстановкой вызовов в тесте.
         */
        data class Gate(val minimumFrames: Int) : Transfer
    }

    /** Окна всех выполненных приёмов: длина каждой запрошенной операции. */
    val receiveWindows: MutableList<Int> = mutableListOf()

    /** Таймаут каждой операции приёма: рукопожатие меняет его по ходу. */
    val receiveTimeouts: MutableList<Int> = mutableListOf()

    /** Байты, ушедшие через [send], в порядке отправки. */
    val sentBytes: MutableList<ByteArray> = mutableListOf()

    private var released = false

    /**
     * Очереди трогают два потока: цикл раскладки принимает, потребитель
     * отправляет. Без замка списки разъехались бы.
     */
    private val lock = Any()

    override val candidate: UsbInterfaceCandidate = CANDIDATE

    override val held: Boolean
        get() = !released

    override fun receive(
        destination: ByteArray,
        offset: Int,
        length: Int,
        timeoutMillis: Int,
    ): UsbTransferResult = synchronized(lock) {
        UsbTransferArguments.validate(destination.size, offset, length, timeoutMillis)
        receiveWindows += length
        receiveTimeouts += timeoutMillis
        val transfer = if (answerOnlyAfterRequest && sentBytes.isEmpty()) {
            null
        } else {
            nextReleasedTransfer()
        }
        if (transfer == null) {
            return UsbTransferResult.Failed(UsbTransferFailure.NOT_COMPLETED)
        }
        when (transfer) {
            // Условия отсеяны в nextReleasedTransfer: сюда доходит только ответ.
            is Transfer.Gate -> UsbTransferResult.Failed(UsbTransferFailure.NOT_COMPLETED)
            is Transfer.Failed -> UsbTransferResult.Failed(transfer.reason)
            is Transfer.Completed -> {
                transfer.source.copyInto(
                    destination = destination,
                    destinationOffset = offset,
                    startIndex = 0,
                    endIndex = minOf(transfer.bytes, transfer.source.size),
                )
                UsbTransferResult.Completed(transfer.bytes)
            }
        }
    }

    /**
     * Следующий ответ, если его условие выполнено.
     *
     * Невыполненное условие остаётся в очереди: устройство ещё не дозрело до
     * ответа, а не отказало.
     */
    private fun nextReleasedTransfer(): Transfer? {
        var released: Transfer? = null
        var waiting = false
        while (released == null && !waiting) {
            when (val head = inbound.firstOrNull()) {
                null -> waiting = true
                is Transfer.Gate -> if (sentFrameCount() >= head.minimumFrames) {
                    inbound.removeFirst()
                } else {
                    waiting = true
                }

                else -> released = inbound.removeFirst()
            }
        }
        return released
    }

    /** Сколько целых кадров host успел отправить. */
    private fun sentFrameCount(): Int {
        var frames = 0
        var index = 0
        while (index < sentBytes.size) {
            val decoded = AdbPacketHeader.decode(
                sentBytes[index],
                AdbInboundFraming.MODERN_MAX_PAYLOAD_BYTES,
            )
            if (decoded !is AdbHeaderDecoding.Decoded) return frames
            index += 1
            var collected = 0
            while (collected < decoded.header.payloadLength && index < sentBytes.size) {
                collected += sentBytes[index].size
                index += 1
            }
            if (collected < decoded.header.payloadLength) return frames
            frames += 1
        }
        return frames
    }

    override fun send(
        source: ByteArray,
        offset: Int,
        length: Int,
        timeoutMillis: Int,
    ): UsbTransferResult = synchronized(lock) {
        UsbTransferArguments.validate(source.size, offset, length, timeoutMillis)
        val transfer = outbound.removeFirstOrNull() ?: Transfer.Completed(length)
        when (transfer) {
            is Transfer.Failed -> UsbTransferResult.Failed(transfer.reason)

            // Условие — понятие входящей очереди: отправке ждать нечего.
            is Transfer.Gate -> UsbTransferResult.Completed(length)

            is Transfer.Completed -> {
                val moved = minOf(transfer.bytes, length)
                sentBytes += source.copyOfRange(offset, offset + moved)
                UsbTransferResult.Completed(moved)
            }
        }
    }

    override fun close() {
        released = true
    }

    /** Снимок отправленного: читать список под чужим потоком нельзя. */
    fun sentSnapshot(): List<ByteArray> = synchronized(lock) { sentBytes.toList() }

    private companion object {
        val ENDPOINT_IN = UsbEndpointDescriptor(
            address = 0x81,
            direction = UsbEndpointDirection.IN,
            transferType = UsbTransferType.BULK,
        )

        val ENDPOINT_OUT = UsbEndpointDescriptor(
            address = 0x01,
            direction = UsbEndpointDirection.OUT,
            transferType = UsbTransferType.BULK,
        )

        val CANDIDATE = UsbInterfaceCandidate(
            device = UsbDeviceDescriptor(
                deviceId = 1,
                deviceName = "/dev/bus/usb/001/002",
                vendorId = 0x2717,
                productId = 0xFF48,
            ),
            kind = UsbInterfaceKind.ADB,
            confidence = UsbMatchConfidence.CANONICAL,
            interfaceIndex = 0,
            interfaceId = 0,
            interfaceClass = 0xFF,
            interfaceSubclass = 0x42,
            interfaceProtocol = 0x01,
            endpointIn = ENDPOINT_IN,
            endpointOut = ENDPOINT_OUT,
        )
    }
}

/** Кадр, собранный обратно из того, что ушло в [FakeUsbTransportHandle.send]. */
internal data class SentPacket(
    val command: Long,
    val arg0: Int,
    val arg1: Int,
    val payload: ByteArray,
)

/**
 * Восстанавливает отправленные кадры из последовательности передач.
 *
 * Писатель отправляет заголовок и следом payload, возможно кусками, поэтому
 * собрать кадр обратно можно только по объявленной в заголовке длине — что
 * заодно проверяет, что заголовок и payload действительно согласованы.
 *
 * Незаконченный хвост снимка отбрасывается. Отправлять может **чужой** поток —
 * цикл раскладки подтверждает принятое, — и снимок, взятый между заголовком и
 * payload, показал бы половину кадра. Раньше на таком снимке функция падала по
 * выходу за границу списка, что выглядело бы как поломка писателя.
 */
internal fun FakeUsbTransportHandle.sentFrames(): List<SentPacket> {
    val sent = sentSnapshot()
    val frames = mutableListOf<SentPacket>()
    var index = 0
    var torn = false
    while (index < sent.size && !torn) {
        val headerBytes = sent[index]
        index += 1
        val decoded = AdbPacketHeader.decode(headerBytes, AdbInboundFraming.MODERN_MAX_PAYLOAD_BYTES)
        require(decoded is AdbHeaderDecoding.Decoded) { "writer produced an undecodable header: $decoded" }
        val header = decoded.header
        val payload = ByteArray(header.payloadLength)
        var collected = 0
        while (collected < header.payloadLength && index < sent.size) {
            val part = sent[index]
            index += 1
            part.copyInto(payload, collected)
            collected += part.size
        }
        if (collected < header.payloadLength) {
            torn = true
        } else {
            frames += SentPacket(header.command, header.arg0, header.arg1, payload)
        }
    }
    return frames
}

/**
 * Ждёт, пока отправленных кадров станет не меньше [count], и отдаёт их.
 *
 * Нужно там, где кадр пишет не тот поток, что вернул управление тесту. Долг
 * устройству — `OKAY` за принятый блок и ответное `CLSE` — платит
 * [AdbDispatchLoop], и платит **после** того, как разложил пакет по ящикам, то
 * есть уже разбудив потребителя. Порядок такой намеренно: отправлять под
 * замком диспетчера значило бы держать замок всё время передачи (до пяти
 * секунд по [AdbPacketWriter.DEFAULT_SEND_TIMEOUT_MS]) и блокировать этим
 * открытие чужих потоков — ровно ту одновременность, ради которой затевался
 * `docs/adr/0004_CONCURRENT_ADB_DISPATCHER_RU.md`.
 *
 * Поэтому «мы отправили X» проверяется ожиданием, а не снимком сразу после
 * возврата вызова. Снимок наблюдает чужой поток и держится на планировщике:
 * на одном ядре такая проверка падала в половине прогонов и однажды уронила CI
 * (`07` §6.49).
 */
internal fun FakeUsbTransportHandle.awaitSentFrames(
    count: Int,
    timeoutMillis: Long = AWAIT_FRAMES_MS,
): List<SentPacket> {
    val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLI
    var frames = sentFrames()
    while (frames.size < count && System.nanoTime() < deadline) {
        Thread.sleep(AWAIT_POLL_MS)
        frames = sentFrames()
    }
    assertTrue(
        "ждали кадров: $count, отправлено ${frames.size} — " +
            frames.joinToString { frame -> commandName(frame.command) },
        frames.size >= count,
    )
    return frames
}

/** Команда как четыре буквы: в сообщении об ошибке число ничего не говорит. */
internal fun commandName(command: Long): String =
    (0 until COMMAND_BYTES).joinToString("") { index ->
        (((command shr (index * Byte.SIZE_BITS)) and BYTE_MASK).toInt().toChar()).toString()
    }

private const val AWAIT_FRAMES_MS = 5_000L
private const val AWAIT_POLL_MS = 2L
private const val NANOS_PER_MILLI = 1_000_000L
private const val COMMAND_BYTES = 4
private const val BYTE_MASK = 0xFFL

/** Собирает 24-байтный заголовок для теста. */
internal fun header(
    command: Long,
    arg0: Int = 0,
    arg1: Int = 0,
    payload: ByteArray = ByteArray(0),
    checksum: Int = AdbChecksum.compute(payload),
    magicOverride: Int? = null,
    declaredLength: Int? = null,
): ByteArray {
    val bytes = ByteArray(AdbPacketHeader.SIZE_BYTES)
    AdbPacketHeader.encode(bytes, command, arg0, arg1, payload, checksum)
    declaredLength?.let { writeIntLe(bytes, 12, it) }
    magicOverride?.let { writeIntLe(bytes, 20, it) }
    return bytes
}

private fun writeIntLe(target: ByteArray, offset: Int, value: Int) {
    target[offset] = value.toByte()
    target[offset + 1] = (value ushr 8).toByte()
    target[offset + 2] = (value ushr 16).toByte()
    target[offset + 3] = (value ushr 24).toByte()
}
