package io.github.ncorror.nekoflash.protocol.adb

/**
 * Внутренний протокол сервиса `shell,v2`.
 *
 * Поверх потока ADB идут собственные рамки: один байт идентификатора и длина
 * четырьмя байтами little-endian. Ради них всё и затевается — обычный `shell:`
 * отдаёт stdout и stderr одной смесью и не сообщает код возврата, а `shell,v2`
 * разделяет их и заканчивает пакетом с кодом.
 *
 * Значения перенесены из Legacy `AdbProtocol.kt`, где они проверены на железе.
 */
public object AdbShellProtocol {
    /** Ввод в команду. */
    public const val ID_STDIN: Int = 0

    /** Обычный вывод. */
    public const val ID_STDOUT: Int = 1

    /** Вывод ошибок. */
    public const val ID_STDERR: Int = 2

    /** Завершение: первый байт payload — код возврата. */
    public const val ID_EXIT: Int = 3

    /** Закрытие ввода. */
    public const val ID_CLOSE_STDIN: Int = 4

    /** Идентификатор и длина. */
    public const val HEADER_SIZE_BYTES: Int = 5

    /**
     * Рамка «ввод закрыт».
     *
     * Отправляется сразу после открытия потока для одноразовой команды: так
     * делает Legacy, и без этого часть команд ждёт конца ввода вместо того,
     * чтобы завершиться.
     */
    public fun closeStdinFrame(): ByteArray = encode(ID_CLOSE_STDIN, ByteArray(0))

    /** Собирает рамку. */
    public fun encode(id: Int, payload: ByteArray): ByteArray {
        val frame = ByteArray(HEADER_SIZE_BYTES + payload.size)
        frame[0] = id.toByte()
        frame[1] = payload.size.toByte()
        frame[2] = (payload.size ushr 8).toByte()
        frame[3] = (payload.size ushr 16).toByte()
        frame[4] = (payload.size ushr 24).toByte()
        payload.copyInto(frame, HEADER_SIZE_BYTES)
        return frame
    }

    /**
     * Разбирает поток рамок целиком.
     *
     * Незнакомые идентификаторы пропускаются вместе с их payload, а не
     * прекращают разбор: протокол расширяемый, и встреченное новое поле не
     * повод потерять уже полученный вывод. Так же поступает Legacy.
     *
     * Обрыв на середине рамки прекращает разбор: остаток недостоверен. Это
     * отражается в [AdbShellOutput.truncated], а не выбрасывается молча.
     */
    public fun decode(bytes: ByteArray, maxFrameBytes: Int = MAX_FRAME_BYTES): AdbShellOutput {
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        var exitCode: Int? = null
        var offset = 0
        var invalidFrame = false

        while (offset + HEADER_SIZE_BYTES <= bytes.size) {
            val id = bytes[offset].toInt() and 0xFF
            val length = readIntLe(bytes, offset + 1)
            val payloadStart = offset + HEADER_SIZE_BYTES
            if (length < 0 || length > maxFrameBytes || payloadStart + length > bytes.size) {
                invalidFrame = true
                break
            }
            when (id) {
                ID_STDOUT -> stdout.append(text(bytes, payloadStart, length))
                ID_STDERR -> stderr.append(text(bytes, payloadStart, length))
                ID_EXIT -> if (length > 0) exitCode = bytes[payloadStart].toInt() and 0xFF
                else -> Unit
            }
            offset = payloadStart + length
        }

        return AdbShellOutput(
            stdout = stdout.toString(),
            stderr = stderr.toString(),
            exitCode = exitCode,
            truncated = invalidFrame || offset != bytes.size,
        )
    }

    /**
     * Нулевые байты выбрасываются.
     *
     * Устройство добавляет их в некоторых режимах, и в тексте на экране они
     * выглядят как повреждение. Так же чистит Legacy.
     */
    private fun text(source: ByteArray, offset: Int, length: Int): String =
        String(source, offset, length, Charsets.UTF_8).replace("\u0000", "")

    private fun readIntLe(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xFF) or
            ((source[offset + 1].toInt() and 0xFF) shl 8) or
            ((source[offset + 2].toInt() and 0xFF) shl 16) or
            ((source[offset + 3].toInt() and 0xFF) shl 24)

    /** Верхняя граница длины одной рамки: та же, что у ADB-пакета. */
    private const val MAX_FRAME_BYTES = 1_048_576
}

/**
 * Итог одной команды.
 *
 * [exitCode] отсутствует, когда команда шла через обычный `shell:` — там кода
 * возврата нет вовсе, и подставлять ноль означало бы сообщить об успехе, о
 * котором ничего не известно.
 */
public data class AdbShellOutput(
    val stdout: String,
    val stderr: String,
    val exitCode: Int?,
    val truncated: Boolean = false,
)
