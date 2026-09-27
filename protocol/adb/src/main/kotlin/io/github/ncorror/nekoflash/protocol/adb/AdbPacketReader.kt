package io.github.ncorror.nekoflash.protocol.adb

import io.github.ncorror.nekoflash.usb.api.UsbTransferFailure
import io.github.ncorror.nekoflash.usb.api.UsbTransferResult
import io.github.ncorror.nekoflash.usb.api.UsbTransportHandle

/** Целый принятый пакет. */
public class AdbPacket(
    public val command: Long,
    public val arg0: Int,
    public val arg1: Int,
    public val payload: ByteArray,
) {
    /** Содержимое payload не печатается: там бывают токены AUTH и чужие данные. */
    override fun toString(): String =
        "AdbPacket(command=0x${command.toString(16)}, arg0=$arg0, arg1=$arg1, payload=${payload.size} bytes)"
}

/** Почему приём кадра не состоялся. */
public enum class AdbReadFailure {
    /**
     * Заголовок оборвался, когда часть его байтов уже принята.
     *
     * Отличается от простого ожидания: до этого места поток был синхронен, а
     * теперь неизвестно, где начинается следующий пакет.
     */
    PARTIAL_HEADER,

    /** `magic` или объявленная длина непригодны. */
    INVALID_HEADER,

    /**
     * Приём объявленного payload завершился короче объявленного.
     *
     * Тот самый доказанный на `vayu` случай. Дочитывать остаток запрещено.
     */
    SHORT_PAYLOAD,

    /** Приём payload не состоялся вовсе: платформа не выполнила передачу. */
    PAYLOAD_TRANSFER_FAILED,

    /** Содержимое payload не сходится с объявленной контрольной суммой. */
    CHECKSUM_MISMATCH,
}

/** Исход одной попытки принять кадр. */
public sealed interface AdbReadOutcome {
    /** Пакет принят целиком и проверен. */
    public data class Received(val packet: AdbPacket) : AdbReadOutcome

    /**
     * За отведённое время не пришло ни одного байта.
     *
     * Обычное состояние простаивающего соединения, а не ошибка: читающий цикл
     * просто повторяет попытку. Платформа не отличает таймаут от ошибки
     * ввода-вывода, поэтому «ни байта не принято» — единственное, что здесь
     * вообще можно утверждать.
     */
    public data object Idle : AdbReadOutcome

    /** Интерфейс больше не удерживается: читать нечего и незачем. */
    public data object Closed : AdbReadOutcome

    /** Кадр потерян. Поколение транспорта после этого недостоверно. */
    public data class Failed(
        val reason: AdbReadFailure,
        val detail: String,
    ) : AdbReadOutcome
}

/**
 * Единственный физический читатель входящего потока ADB.
 *
 * Экземпляр принадлежит одному потоку: буфер заголовка переиспользуется, и
 * параллельные вызовы [read] разрушили бы кадр. Логическое разделение на
 * потоки данных живёт выше — здесь только рамка.
 *
 * Заголовок дочитывается до полных 24 байт, payload — никогда: это не
 * непоследовательность, а разница между двумя передачами USB. Заголовок в
 * обоих архивах набирается циклом, payload после исправления A2 принимается
 * одной операцией.
 *
 * @param maxPayloadBytes значение `maxdata`, объявленное **нами** в `CNXN`.
 * Кадр длиннее объявленного не принимается: приняв его, получатель нарушил бы
 * собственное обещание и снова оказался бы в ситуации, из которой брался
 * инвариант.
 */
public class AdbPacketReader(
    private val handle: UsbTransportHandle,
    private val maxPayloadBytes: Int,
    private val localVersion: Int = AdbChecksum.VERSION_WITH_CHECKSUM,
) {
    private val headerBuffer = ByteArray(AdbPacketHeader.SIZE_BYTES)

    /**
     * Версия протокола peer'а.
     *
     * До `CNXN` равна нашей: пока версия неизвестна, контрольная сумма
     * считается обязательной. Так же поступают оба архива — предполагать более
     * новый протокол авансом означало бы пропускать битые кадры.
     */
    @Volatile
    public var peerVersion: Int = localVersion
        private set

    /** Запоминает версию, объявленную peer'ом в `CNXN`. */
    public fun negotiate(version: Int) {
        peerVersion = version
    }

    /**
     * Принимает один кадр.
     *
     * [timeoutMillis] ограничивает каждую отдельную передачу, а не кадр
     * целиком: читающий цикл работает короткими отрезками, чтобы остановка
     * была наблюдаемой.
     */
    public fun read(timeoutMillis: Int = DEFAULT_RECEIVE_TIMEOUT_MS): AdbReadOutcome =
        when (val outcome = readHeader(timeoutMillis)) {
            is HeaderOutcome.Ready -> readPacket(outcome.header, timeoutMillis)
            is HeaderOutcome.Other -> outcome.result
        }

    private fun readPacket(header: AdbPacketHeader, timeoutMillis: Int): AdbReadOutcome {
        val payload = if (header.payloadLength == 0) {
            PayloadOutcome.Ready(EMPTY_PAYLOAD)
        } else {
            readPayload(header.payloadLength, timeoutMillis)
        }
        return when (payload) {
            is PayloadOutcome.Other -> payload.result
            is PayloadOutcome.Ready -> decodePacket(header, payload.payload)
        }
    }

    private fun decodePacket(header: AdbPacketHeader, payload: ByteArray): AdbReadOutcome =
        if (AdbChecksum.matches(header.checksum, payload, localVersion, peerVersion)) {
            AdbReadOutcome.Received(
                AdbPacket(
                    command = header.command,
                    arg0 = header.arg0,
                    arg1 = header.arg1,
                    payload = payload,
                ),
            )
        } else {
            AdbReadOutcome.Failed(
                AdbReadFailure.CHECKSUM_MISMATCH,
                "command=0x${header.command.toString(16)} bytes=${payload.size} declared=${header.checksum}",
            )
        }

    private fun readHeader(timeoutMillis: Int): HeaderOutcome {
        var received = 0
        var terminal: HeaderOutcome? = null
        while (received < AdbPacketHeader.SIZE_BYTES && terminal == null) {
            when (
                val result = handle.receive(
                    destination = headerBuffer,
                    offset = received,
                    length = AdbPacketHeader.SIZE_BYTES - received,
                    timeoutMillis = timeoutMillis,
                )
            ) {
                is UsbTransferResult.Completed -> {
                    if (result.bytes == 0) {
                        terminal = HeaderOutcome.Other(idleOrPartialHeader(received))
                    } else {
                        received += result.bytes
                    }
                }

                is UsbTransferResult.Failed -> terminal = HeaderOutcome.Other(
                    when (result.reason) {
                        UsbTransferFailure.NOT_HELD -> AdbReadOutcome.Closed
                        UsbTransferFailure.NOT_COMPLETED -> idleOrPartialHeader(received)
                    },
                )
            }
        }

        return terminal ?: decodeHeader()
    }

    private fun decodeHeader(): HeaderOutcome =
        when (val decoding = AdbPacketHeader.decode(headerBuffer, maxPayloadBytes)) {
            is AdbHeaderDecoding.Decoded -> HeaderOutcome.Ready(decoding.header)
            is AdbHeaderDecoding.Rejected -> HeaderOutcome.Other(
                AdbReadOutcome.Failed(
                    AdbReadFailure.INVALID_HEADER,
                    "${decoding.reason.name}: ${decoding.detail}",
                ),
            )
        }

    /**
     * Ожидание без единого принятого байта — это простой; обрыв после части
     * заголовка — потеря рамки.
     */
    private fun idleOrPartialHeader(received: Int): AdbReadOutcome =
        if (received == 0) {
            AdbReadOutcome.Idle
        } else {
            AdbReadOutcome.Failed(
                AdbReadFailure.PARTIAL_HEADER,
                "received=$received/${AdbPacketHeader.SIZE_BYTES}",
            )
        }

    private fun readPayload(length: Int, timeoutMillis: Int): PayloadOutcome {
        val payload = ByteArray(length)
        val result = handle.receive(
            destination = payload,
            offset = 0,
            length = length,
            timeoutMillis = timeoutMillis,
        )
        return when (result) {
            is UsbTransferResult.Completed ->
                if (AdbInboundFraming.payloadReadIsComplete(length, result.bytes)) {
                    PayloadOutcome.Ready(payload)
                } else {
                    PayloadOutcome.Other(
                        AdbReadOutcome.Failed(
                            AdbReadFailure.SHORT_PAYLOAD,
                            "expected=$length actual=${result.bytes}",
                        ),
                    )
                }

            is UsbTransferResult.Failed -> PayloadOutcome.Other(
                when (result.reason) {
                    UsbTransferFailure.NOT_HELD -> AdbReadOutcome.Closed
                    UsbTransferFailure.NOT_COMPLETED -> AdbReadOutcome.Failed(
                        AdbReadFailure.PAYLOAD_TRANSFER_FAILED,
                        "expected=$length",
                    )
                },
            )
        }
    }

    private sealed interface HeaderOutcome {
        data class Ready(val header: AdbPacketHeader) : HeaderOutcome
        data class Other(val result: AdbReadOutcome) : HeaderOutcome
    }

    private sealed interface PayloadOutcome {
        data class Ready(val payload: ByteArray) : PayloadOutcome
        data class Other(val result: AdbReadOutcome) : PayloadOutcome
    }

    public companion object {
        /** Значение из A2 (`USB_READ_TIMEOUT_MS`). */
        public const val DEFAULT_RECEIVE_TIMEOUT_MS: Int = 5_000

        private val EMPTY_PAYLOAD = ByteArray(0)
    }
}
