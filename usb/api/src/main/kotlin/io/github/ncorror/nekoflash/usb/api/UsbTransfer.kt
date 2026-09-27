package io.github.ncorror.nekoflash.usb.api

/**
 * Исход одной передачи по bulk-эндпоинту.
 *
 * Одна операция — ровно одна передача USB. Результат короче запрошенного тоже
 * успешный: короткая передача **завершилась**, а не приостановилась, и
 * дочитывать её следующим вызовом нельзя. Допустима ли короткая передача,
 * решает протокольный слой: для ADB доказанный на `vayu` inbound framing
 * invariant считает короткий приём объявленного payload несостоявшейся рамкой
 * (`docs/03_PROTOCOL_AND_SAFETY_INVARIANTS_RU.md` §4).
 */
public sealed interface UsbTransferResult {
    /**
     * Передача завершена.
     *
     * [bytes] — сколько байт действительно перенесено, от нуля до запрошенной
     * длины.
     */
    public data class Completed(val bytes: Int) : UsbTransferResult

    /** Передача не состоялась. */
    public data class Failed(val reason: UsbTransferFailure) : UsbTransferResult
}

/** Почему передача не состоялась. */
public enum class UsbTransferFailure {
    /**
     * Интерфейс уже освобождён.
     *
     * Обычный исход гонки с отключением устройства, а не ошибка вызывающего:
     * освобождение могло произойти между проверкой и вызовом.
     */
    NOT_HELD,

    /**
     * Платформа не выполнила передачу.
     *
     * Таймаут, ошибка ввода-вывода и исчезновение устройства приходят от
     * Android одним и тем же значением, и различить их здесь нечем. Придумывать
     * различие означало бы солгать о причине. Слой выше различает их по
     * собственному состоянию: ожидание, в котором не пришло ни байта, — обычный
     * таймаут, а обрыв посреди уже начатого пакета — потеря рамки. Так же это
     * устроено в A2 (`adb/transport/AdbUsbTransport.kt`, `readHeaderDirect`), и
     * прямо записано в Legacy (`FastbootProtocol.readPacket`): «bulkTransfer
     * возвращает <=0 и по обычному timeout».
     */
    NOT_COMPLETED,
}

/**
 * Предусловия одной передачи.
 *
 * Живёт здесь, а не в реализации, по той же причине, что и разбор дескрипторов:
 * платформенный код невозможно проверить без устройства, поэтому в нём не
 * должно оставаться ничего, что можно проверить в другом месте.
 *
 * Нарушение — ошибка программиста, а не протокольный исход, поэтому это
 * исключение, а не `UsbTransferResult.Failed`.
 */
public object UsbTransferArguments {
    /**
     * Проверяет окно передачи и таймаут.
     *
     * Верхняя граница проверяется как `length <= bufferSize - offset`, а не как
     * `offset + length <= bufferSize`: сумма двух больших значений переполняется
     * и превращает проверку в её противоположность.
     *
     * Нулевая длина допустима: это передача нулевой длины, а не бессмысленный
     * вызов. Нулевой [timeoutMillis] означает ожидание без ограничения по
     * времени — так его определяет платформа.
     */
    public fun validate(bufferSize: Int, offset: Int, length: Int, timeoutMillis: Int) {
        require(bufferSize >= 0) { "USB transfer buffer size must not be negative: $bufferSize" }
        require(offset >= 0) { "USB transfer offset must not be negative: $offset" }
        require(length >= 0) { "USB transfer length must not be negative: $length" }
        require(offset <= bufferSize) {
            "USB transfer offset $offset is outside a buffer of $bufferSize bytes"
        }
        require(length <= bufferSize - offset) {
            "USB transfer of $length bytes at offset $offset does not fit a buffer of $bufferSize bytes"
        }
        require(timeoutMillis >= 0) { "USB transfer timeout must not be negative: $timeoutMillis" }
    }
}
