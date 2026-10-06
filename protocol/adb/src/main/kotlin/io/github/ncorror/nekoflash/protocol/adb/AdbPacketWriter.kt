package io.github.ncorror.nekoflash.protocol.adb

import io.github.ncorror.nekoflash.usb.api.UsbTransferFailure
import io.github.ncorror.nekoflash.usb.api.UsbTransferResult
import io.github.ncorror.nekoflash.usb.api.UsbTransportHandle

/** Исход отправки кадра. */
public sealed interface AdbWriteOutcome {
    /** Кадр отправлен целиком. */
    public data object Sent : AdbWriteOutcome

    /** Интерфейс больше не удерживается. */
    public data object Closed : AdbWriteOutcome

    /**
     * Отправка не завершилась.
     *
     * [sentBytes] — сколько байт кадра ушло до обрыва. Это не диагностика для
     * красоты: по нему видно, успел ли peer увидеть заголовок, а значит —
     * мутировал ли запрос что-нибудь. Повторять такую отправку нельзя, пока
     * ответ на этот вопрос неизвестен
     * (`docs/03_PROTOCOL_AND_SAFETY_INVARIANTS_RU.md` §3).
     */
    public data class Interrupted(val sentBytes: Int, val detail: String) : AdbWriteOutcome
}

/**
 * Отправка кадров ADB в единственный поток записи.
 *
 * Экземпляр **сериализует** отправку сам: [write] берёт замок на всё время
 * кадра. Раньше это было обязанностью владельца транспорта, как `adbWriteLock`
 * в A2, и до постоянного читающего цикла работало — писали либо reader
 * executor, либо writer executor оболочки, а оболочка держала свой замок.
 * С приходом цикла (`docs/adr/0004_CONCURRENT_ADB_DISPATCHER_RU.md`, шаг 5)
 * появился третий пишущий: цикл подтверждает принятые блоки. Требовать
 * сериализации от владельца стало нельзя — цикл принадлежит соединению, а не
 * ему, и владелец не может обернуть замком то, чего не запускал.
 *
 * Замок нужен не только ради порядка кадров: [headerBuffer] один на экземпляр,
 * и два отправителя затёрли бы друг другу заголовок, отправив устройству
 * мусор с правильной контрольной суммой.
 *
 * Кадр уходит как заголовок и следом payload. Обе части дописываются до конца
 * кусками не больше [USB_BULK_CHUNK_BYTES]: в сторону хост → устройство это
 * законно и сделано одинаково в Legacy (`bulkWriteFully`) и в A2. Обратная
 * сторона так не работает — там дробление объявленного payload разрушает
 * рамку, и правило записано в [AdbInboundFraming].
 */
public class AdbPacketWriter(
    private val handle: UsbTransportHandle,
    private val localVersion: Int = AdbChecksum.VERSION_WITH_CHECKSUM,
) {
    private val headerBuffer = ByteArray(AdbPacketHeader.SIZE_BYTES)

    /** Кадр уходит целиком или не уходит: чужой отправитель в середину не влезет. */
    private val writeLock = Any()

    @Volatile
    private var stopped = false

    private var onFailure: (AdbWriteOutcome) -> Unit = { }

    /** The dispatch loop owns the failure of every writer sharing this transport. */
    internal fun observeFailure(listener: (AdbWriteOutcome) -> Unit) {
        onFailure = listener
    }

    /** Stops queued frames and the remaining chunks without waiting for a blocked USB call. */
    internal fun stop() {
        stopped = true
    }

    /**
     * Версия протокола peer'а: определяет, считается ли контрольная сумма.
     *
     * До `CNXN` равна нашей, как и у читателя.
     */
    @Volatile
    public var peerVersion: Int = localVersion
        private set

    /** Запоминает версию, объявленную peer'ом в `CNXN`. */
    public fun negotiate(version: Int) {
        peerVersion = version
    }

    /**
     * Отправляет один кадр.
     *
     * Ограничение `maxdata`, объявленное peer'ом, сюда не спускается: кадр
     * формирует слой, который знает, какой сервис его отправляет. Задача этого
     * класса — довести уже сформированный кадр до провода без искажения.
     */
    public fun write(
        command: Long,
        arg0: Int,
        arg1: Int,
        payload: ByteArray = EMPTY_PAYLOAD,
        timeoutMillis: Int = DEFAULT_SEND_TIMEOUT_MS,
    ): AdbWriteOutcome {
        require(timeoutMillis >= 0) { "ADB send timeout must not be negative: $timeoutMillis" }
        val outcome = synchronized(writeLock) {
            if (stopped) {
                null
            } else {
                finishWrite(writeFrame(command, arg0, arg1, payload, timeoutMillis))
            }
        }
        // The owner may close shells or the connection. It must never run under the frame lock.
        if (outcome != null && outcome != AdbWriteOutcome.Sent) onFailure(outcome)
        return outcome ?: AdbWriteOutcome.Closed
    }

    private fun finishWrite(outcome: AdbWriteOutcome): AdbWriteOutcome {
        if (outcome != AdbWriteOutcome.Sent) stopped = true
        return outcome
    }

    private fun writeFrame(
        command: Long,
        arg0: Int,
        arg1: Int,
        payload: ByteArray,
        timeoutMillis: Int,
    ): AdbWriteOutcome {
        val checksum = if (AdbChecksum.isRequired(localVersion, peerVersion)) {
            AdbChecksum.compute(payload)
        } else {
            0
        }
        AdbPacketHeader.encode(headerBuffer, command, arg0, arg1, payload, checksum)

        val header = sendFully(headerBuffer, headerBuffer.size, 0, timeoutMillis)
        return when {
            header !is AdbWriteOutcome.Sent -> header
            payload.isEmpty() -> AdbWriteOutcome.Sent
            else -> sendFully(payload, payload.size, headerBuffer.size, timeoutMillis)
        }
    }

    /**
     * @param alreadySent сколько байт кадра ушло раньше: нужно, чтобы
     * прерванная отправка payload сообщала о кадре целиком, а не о своём куске.
     */
    // A platform exception cannot leave the shared transport available to another frame.
    @Suppress("TooGenericExceptionCaught")
    private fun sendFully(
        source: ByteArray,
        length: Int,
        alreadySent: Int,
        timeoutMillis: Int,
    ): AdbWriteOutcome {
        var sent = 0
        var outcome: AdbWriteOutcome? = null
        while (sent < length && outcome == null && !stopped) {
            val chunk = minOf(USB_BULK_CHUNK_BYTES, length - sent)
            val result = try {
                handle.send(
                    source = source,
                    offset = sent,
                    length = chunk,
                    timeoutMillis = timeoutMillis,
                )
            } catch (error: RuntimeException) {
                return AdbWriteOutcome.Interrupted(
                    alreadySent + sent,
                    "send threw ${error.javaClass.simpleName}",
                )
            }
            when (result) {
                is UsbTransferResult.Completed -> {
                    if (result.bytes !in 1..chunk) {
                        outcome = AdbWriteOutcome.Interrupted(
                            alreadySent + sent,
                            "transfer returned ${result.bytes} bytes with $chunk requested",
                        )
                    } else {
                        sent += result.bytes
                    }
                }

                is UsbTransferResult.Failed -> outcome = when (result.reason) {
                    UsbTransferFailure.NOT_HELD -> AdbWriteOutcome.Closed
                    UsbTransferFailure.NOT_COMPLETED -> AdbWriteOutcome.Interrupted(
                        alreadySent + sent,
                        "transfer failed after $sent/$length bytes of this part",
                    )
                }
            }
        }
        return outcome ?: if (stopped) AdbWriteOutcome.Closed else AdbWriteOutcome.Sent
    }

    public companion object {
        /** Значение из A2 (`USB_WRITE_TIMEOUT_MS`). */
        public const val DEFAULT_SEND_TIMEOUT_MS: Int = 5_000

        /**
         * Размер одной передачи при записи.
         *
         * Из Legacy и A2 (`USB_BULK_CHUNK_BYTES`): держит отдельные транзакции
         * контроллера в границах, которые платформа принимает на любом
         * поддерживаемом уровне API.
         */
        public const val USB_BULK_CHUNK_BYTES: Int = 16 * 1024

        private val EMPTY_PAYLOAD = ByteArray(0)
    }
}
