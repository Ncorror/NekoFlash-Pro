package io.github.ncorror.nekoflash.protocol.adb

/**
 * Команды ADB, встречающиеся в заголовке.
 *
 * Значения — четыре ASCII-символа, прочитанные как little-endian. Здесь только
 * набор из Legacy `AdbProtocol.kt`: разбор заголовка не должен зависеть от
 * того, какие сервисы уже реализованы.
 */
public object AdbCommand {
    public const val CNXN: Long = 0x4E58_4E43L
    public const val AUTH: Long = 0x4854_5541L
    public const val OPEN: Long = 0x4E45_504FL
    public const val OKAY: Long = 0x5941_4B4FL
    public const val WRTE: Long = 0x4554_5257L
    public const val CLSE: Long = 0x4553_4C43L
}

/**
 * Заголовок пакета ADB: 24 байта, шесть little-endian значений.
 *
 * `command` хранится как `Long`, потому что на проводе это беззнаковое
 * 32-битное значение, а `magic` — его побитовое отрицание. Держать команду в
 * `Int` означало бы сравнивать отрицательные числа с константами вида
 * `0x4E584E43` и однажды на этом ошибиться.
 */
public data class AdbPacketHeader(
    val command: Long,
    val arg0: Int,
    val arg1: Int,
    val payloadLength: Int,
    val checksum: Int,
    val magic: Int,
) {
    public companion object {
        /** Размер заголовка на проводе. */
        public const val SIZE_BYTES: Int = 24

        /** Смещения полей заголовка на проводе, в порядке объявления. */
        private const val OFFSET_COMMAND = 0
        private const val OFFSET_ARG0 = 4
        private const val OFFSET_ARG1 = 8
        private const val OFFSET_PAYLOAD_LENGTH = 12
        private const val OFFSET_CHECKSUM = 16
        private const val OFFSET_MAGIC = 20

        /**
         * Разбирает заголовок из первых [SIZE_BYTES] байт [source].
         *
         * Проверяется ровно то, что проверяют оба архива: `magic` как
         * отрицание команды и диапазон объявленной длины. Верхняя граница —
         * **наш** объявленный `maxdata`, а не константа: peer обязан уложиться
         * в то, что получатель объявил в `CNXN`. В Legacy здесь стояла
         * фиксированная `MAX_PAYLOAD = 1 МиБ`, и на хостах до API 28 это
         * означало приём кадра, который платформа не в состоянии принять
         * целиком.
         */
        public fun decode(source: ByteArray, maxPayloadBytes: Int): AdbHeaderDecoding {
            require(source.size >= SIZE_BYTES) {
                "ADB header needs $SIZE_BYTES bytes, got ${source.size}"
            }
            require(maxPayloadBytes >= 0) {
                "ADB maxPayloadBytes must not be negative: $maxPayloadBytes"
            }

            val command = readIntLe(source, OFFSET_COMMAND).toLong() and 0xFFFF_FFFFL
            val magic = readIntLe(source, OFFSET_MAGIC)
            val payloadLength = readIntLe(source, OFFSET_PAYLOAD_LENGTH)
            return when {
                magic != command.inv().toInt() -> AdbHeaderDecoding.Rejected(
                    AdbHeaderRejection.MAGIC_MISMATCH,
                    "command=0x${command.toString(16)} magic=0x${magic.toUInt().toString(16)}",
                )

                payloadLength !in 0..maxPayloadBytes -> AdbHeaderDecoding.Rejected(
                    AdbHeaderRejection.PAYLOAD_LENGTH_OUT_OF_RANGE,
                    "length=$payloadLength advertisedMaxPayload=$maxPayloadBytes",
                )

                else -> AdbHeaderDecoding.Decoded(
                    AdbPacketHeader(
                        command = command,
                        arg0 = readIntLe(source, OFFSET_ARG0),
                        arg1 = readIntLe(source, OFFSET_ARG1),
                        payloadLength = payloadLength,
                        checksum = readIntLe(source, OFFSET_CHECKSUM),
                        magic = magic,
                    ),
                )
            }
        }

        /** Записывает заголовок пакета в [target] начиная с нулевого смещения. */
        public fun encode(
            target: ByteArray,
            command: Long,
            arg0: Int,
            arg1: Int,
            payload: ByteArray,
            checksum: Int,
        ) {
            require(target.size >= SIZE_BYTES) {
                "ADB header needs $SIZE_BYTES bytes, got ${target.size}"
            }
            writeIntLe(target, OFFSET_COMMAND, command.toInt())
            writeIntLe(target, OFFSET_ARG0, arg0)
            writeIntLe(target, OFFSET_ARG1, arg1)
            writeIntLe(target, OFFSET_PAYLOAD_LENGTH, payload.size)
            writeIntLe(target, OFFSET_CHECKSUM, checksum)
            writeIntLe(target, OFFSET_MAGIC, command.inv().toInt())
        }

        private fun readIntLe(source: ByteArray, offset: Int): Int =
            (source[offset].toInt() and 0xFF) or
                ((source[offset + 1].toInt() and 0xFF) shl 8) or
                ((source[offset + 2].toInt() and 0xFF) shl 16) or
                ((source[offset + 3].toInt() and 0xFF) shl 24)

        private fun writeIntLe(target: ByteArray, offset: Int, value: Int) {
            target[offset] = value.toByte()
            target[offset + 1] = (value ushr 8).toByte()
            target[offset + 2] = (value ushr 16).toByte()
            target[offset + 3] = (value ushr 24).toByte()
        }
    }
}

/** Почему заголовок отвергнут. */
public enum class AdbHeaderRejection {
    /** `magic` не является отрицанием команды: поток рассинхронизирован. */
    MAGIC_MISMATCH,

    /** Объявленная длина отрицательна или больше объявленного нами `maxdata`. */
    PAYLOAD_LENGTH_OUT_OF_RANGE,
}

/** Результат разбора заголовка. */
public sealed interface AdbHeaderDecoding {
    /** Заголовок разобран и прошёл обе проверки. */
    public data class Decoded(val header: AdbPacketHeader) : AdbHeaderDecoding

    /**
     * Заголовок непригоден.
     *
     * Это не «плохой пакет, читаем дальше»: после такого заголовка положение в
     * потоке неизвестно, и продолжать чтение нельзя.
     */
    public data class Rejected(
        val reason: AdbHeaderRejection,
        val detail: String,
    ) : AdbHeaderDecoding
}
