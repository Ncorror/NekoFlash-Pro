package io.github.ncorror.nekoflash.payload

/**
 * Содержимое, которое приложение порождает само.
 *
 * Существует потому, что записать на устройство нечего: выбор пользовательского
 * файла требует artifact source из Phase 8. Для аппаратного гейта `07` §6.34
 * этого достаточно — он проверяет протокольный путь, а не пользовательский
 * сценарий push.
 *
 * Содержимое детерминировано, поэтому прогон воспроизводим, и нигде не
 * собирается целиком: генератор заполняет чужой буфер порциями.
 *
 * Период узора — простое число, а не степень двойки, намеренно: узор с периодом
 * 256 совпал бы с границей блока в 64 КиБ, и перепутанные местами блоки дали бы
 * тот же отпечаток. С простым периодом такая ошибка видна.
 */
internal class GeneratedPayload(private val totalBytes: Long) {
    private var produced = 0L

    /** Заполняет буфер очередной порцией. Возвращает `0`, когда содержимое кончилось. */
    fun fill(buffer: ByteArray): Int {
        val remaining = totalBytes - produced
        if (remaining <= 0L) return 0
        val count = minOf(remaining, buffer.size.toLong()).toInt()
        for (index in 0 until count) {
            buffer[index] = ((produced + index) % PATTERN_PERIOD).toByte()
        }
        produced += count
        return count
    }

    /**
     * Тот же узор, но **с произвольного места**.
     *
     * Нужен Sideload: обмен управляется запросами Recovery, и один и тот же
     * блок оно вправе попросить не раз. Источник, умеющий только «дай
     * следующее», на повторный запрос отдал бы следующий кусок, и образ уехал
     * бы перемешанным при исправной с виду передаче.
     *
     * Позиция узора считается от абсолютного смещения — той же формулой, что и
     * в [fill]. Поэтому прочитанное по смещению и прочитанное подряд совпадают
     * байт в байт, и сверка отпечатков между `push` и Sideload остаётся
     * осмысленной.
     */
    fun read(offset: Long, length: Int): ByteArray {
        require(offset >= 0L) { "смещение не может быть отрицательным: $offset" }
        require(length >= 0) { "длина не может быть отрицательной: $length" }
        require(offset + length <= totalBytes) {
            "запрошено за пределами содержимого: $offset + $length > $totalBytes"
        }
        return ByteArray(length) { index -> ((offset + index) % PATTERN_PERIOD).toByte() }
    }

    private companion object {
        const val PATTERN_PERIOD = 251L
    }
}

/**
 * Поток над [GeneratedPayload].
 *
 * Нужен там, где принимающая сторона говорит на `InputStream`, — так устроена
 * фаза данных Fastboot, и так же читает источник A2
 * (`FastbootDataTransfer.transferSyncBulk`). Содержимое по-прежнему нигде не
 * собирается целиком: поток отдаёт ровно то, что попросили.
 */
internal class GeneratedPayloadStream(private val payload: GeneratedPayload) : java.io.InputStream() {
    private var buffer = ByteArray(0)
    private var offset = 0

    override fun read(): Int {
        val single = ByteArray(1)
        return if (read(single, 0, 1) == 1) single[0].toInt() and BYTE_MASK else END_OF_STREAM
    }

    override fun read(destination: ByteArray, destinationOffset: Int, length: Int): Int {
        if (offset >= buffer.size) {
            val next = ByteArray(length.coerceAtLeast(1))
            val produced = payload.fill(next)
            if (produced == 0) return -1
            buffer = next.copyOf(produced)
            offset = 0
        }
        val count = minOf(length, buffer.size - offset)
        buffer.copyInto(destination, destinationOffset, offset, offset + count)
        offset += count
        return count
    }

    private companion object {
        /** `InputStream` отдаёт байт как беззнаковое число, а `Byte` в Kotlin знаковый. */
        const val BYTE_MASK = 0xFF

        /** Конец потока по контракту `InputStream`. */
        const val END_OF_STREAM = -1
    }
}
