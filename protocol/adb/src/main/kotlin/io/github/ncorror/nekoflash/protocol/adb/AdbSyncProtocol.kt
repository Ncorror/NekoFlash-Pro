package io.github.ncorror.nekoflash.protocol.adb

/**
 * Рамка сервиса `sync:`.
 *
 * Поверх обычного потока ADB идёт третий формат, не похожий ни на пакет ADB, ни
 * на рамку `shell,v2`: четыре байта ASCII-идентификатора и четырёхбайтное
 * little-endian число. Что означает число, зависит от идентификатора — это
 * длина payload у `SEND` и `DATA`, время изменения у `DONE`, код у `OKAY`.
 * Обобщать его в «длину» нельзя, и поэтому оно называется здесь `value`.
 *
 * Формат перенесён из Legacy `AdbProtocol.kt`: `writeSyncRequest`,
 * `writeSyncIdAndInt`, `readSyncHeader`.
 */
public object AdbSyncProtocol {
    /** Запрос сведений о пути. */
    public const val ID_STAT: String = "STAT"

    /** Запрос содержимого каталога. */
    public const val ID_LIST: String = "LIST"

    /** Начало передачи файла на устройство. */
    public const val ID_SEND: String = "SEND"

    /** Запрос файла с устройства. */
    public const val ID_RECV: String = "RECV"

    /** Блок данных. */
    public const val ID_DATA: String = "DATA"

    /** Конец передачи; `value` — время изменения файла. */
    public const val ID_DONE: String = "DONE"

    /** Успех. */
    public const val ID_OKAY: String = "OKAY"

    /** Отказ; `value` — длина сообщения, идущего следом. */
    public const val ID_FAIL: String = "FAIL"

    /** Элемент каталога в ответе на `LIST`. */
    public const val ID_DENT: String = "DENT"

    /** Завершение сессии `sync:`. */
    public const val ID_QUIT: String = "QUIT"

    /** Идентификатор и число. */
    public const val HEADER_SIZE_BYTES: Int = 8

    /**
     * Размер блока при передаче файла.
     *
     * Значение из Legacy (`SYNC_DATA_CHUNK`). Это предел самого протокола
     * `sync:`, а не удобная величина: `adbd` отвергает блок больше 64 КиБ.
     */
    public const val DATA_CHUNK_BYTES: Int = 64 * 1024

    /**
     * Предельная длина строки в ответе.
     *
     * Из Legacy (`SYNC_MAX_STRING`). Нужен, потому что длину сообщения
     * называет устройство, и доверять ей без границы нельзя.
     */
    public const val MAX_STRING_BYTES: Int = 1024 * 1024

    /** Маска типа в поле режима. */
    public const val MODE_TYPE_MASK: Int = 0xF000

    /** Тип «каталог». */
    public const val MODE_DIRECTORY: Int = 0x4000

    /** Тип «обычный файл». */
    public const val MODE_REGULAR_FILE: Int = 0x8000

    /**
     * Режим создаваемого файла по умолчанию — `0644`.
     *
     * Значение из Legacy (`pushFile(..., mode: Int = 0x1A4)`). Это умолчание
     * вызывающего, а не ограничение: любой режим передаётся как есть, и решает
     * его судьбу устройство.
     */
    public const val DEFAULT_FILE_MODE: Int = 0x1A4

    /** Собирает заголовок без полезной части. */
    public fun header(id: String, value: Int): ByteArray {
        require(id.length == ID_LENGTH) { "Sync id must be $ID_LENGTH characters: $id" }
        val frame = ByteArray(HEADER_SIZE_BYTES)
        id.toByteArray(Charsets.US_ASCII).copyInto(frame)
        writeIntLe(frame, ID_LENGTH, value)
        return frame
    }

    /** Собирает сообщение с полезной частью; `value` — её длина. */
    public fun message(id: String, payload: ByteArray): ByteArray {
        val frame = ByteArray(HEADER_SIZE_BYTES + payload.size)
        header(id, payload.size).copyInto(frame)
        payload.copyInto(frame, HEADER_SIZE_BYTES)
        return frame
    }

    /** Собирает запрос с путём. Путь идёт в UTF-8: имена файлов бывают любые. */
    public fun request(id: String, path: String): ByteArray =
        message(id, path.toByteArray(Charsets.UTF_8))

    /**
     * Собирает спецификацию назначения для `SEND`: путь и режим через запятую.
     *
     * Формат перенесён из Legacy `pushFile`: `"$path,$mode"`, режим — десятичное
     * число. `adbd` отделяет режим по **последней** запятой, поэтому запятая
     * внутри самого пути допустима и не экранируется.
     */
    public fun sendSpec(path: String, mode: Int): ByteArray =
        "$path,$mode".toByteArray(Charsets.UTF_8)

    /**
     * Собирает блок `DATA` из первых [length] байт буфера.
     *
     * Буфер переиспользуется вызывающим, поэтому берётся часть, а не весь
     * массив: копировать хвост прошлого блока значило бы отправить мусор.
     *
     * @throws IllegalArgumentException если длина выходит за предел протокола.
     */
    public fun dataFrame(buffer: ByteArray, length: Int): ByteArray {
        require(length in 0..DATA_CHUNK_BYTES) {
            "Sync data chunk must fit $DATA_CHUNK_BYTES bytes, got $length"
        }
        require(length <= buffer.size) {
            "Sync data chunk of $length bytes exceeds the buffer of ${buffer.size}"
        }
        val frame = ByteArray(HEADER_SIZE_BYTES + length)
        header(ID_DATA, length).copyInto(frame)
        buffer.copyInto(frame, HEADER_SIZE_BYTES, 0, length)
        return frame
    }

    /** Разбирает заголовок из первых [HEADER_SIZE_BYTES] байт. */
    public fun decodeHeader(source: ByteArray): AdbSyncHeader {
        require(source.size >= HEADER_SIZE_BYTES) {
            "Sync header needs $HEADER_SIZE_BYTES bytes, got ${source.size}"
        }
        return AdbSyncHeader(
            id = String(source, 0, ID_LENGTH, Charsets.US_ASCII),
            value = readIntLe(source, ID_LENGTH),
        )
    }

    private const val ID_LENGTH = 4

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

/** Заголовок сообщения `sync:`. */
public data class AdbSyncHeader(val id: String, val value: Int)

/**
 * Сведения о пути на устройстве.
 *
 * Нулевой режим означает, что пути нет: `adbd` отвечает `STAT` с нулями вместо
 * отказа, и отличать «нет файла» от «отказано» приходится по этому полю.
 */
public data class AdbSyncStat(
    val mode: Int,
    val size: Long,
    val modifiedAtSeconds: Int,
) {
    /** Существует ли путь. */
    public val exists: Boolean
        get() = mode != 0

    public val directory: Boolean
        get() = exists && mode and AdbSyncProtocol.MODE_TYPE_MASK == AdbSyncProtocol.MODE_DIRECTORY

    public val regularFile: Boolean
        get() = exists && mode and AdbSyncProtocol.MODE_TYPE_MASK == AdbSyncProtocol.MODE_REGULAR_FILE
}
