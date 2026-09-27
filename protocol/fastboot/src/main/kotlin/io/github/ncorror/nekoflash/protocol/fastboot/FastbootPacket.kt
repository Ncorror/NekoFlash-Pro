package io.github.ncorror.nekoflash.protocol.fastboot

/**
 * Что устройство ответило одним кадром.
 *
 * Четыре первых байта — тип, остальное — полезная часть. Набор типов сверен по
 * обоим архивам и совпал: Legacy `FastbootProtocol.kt` разбирает `OKAY`,
 * `FAIL`, `INFO`, `TEXT` и `DATA` (строки 404–425), A2
 * `fastboot/transport/FastbootTransaction.kt` — те же. Ничего пятого ни одно
 * дерево не знает.
 */
public enum class FastbootReply {
    /** Команда выполнена. Терминальный ответ. */
    OKAY,

    /** Устройство отказало и объяснило, чем. Терминальный ответ. */
    FAIL,

    /** Промежуточное сообщение. Обмен продолжается. */
    INFO,

    /** То же, что [INFO], но отдельным типом — Legacy обрабатывает их вместе. */
    TEXT,

    /** Устройство готово принять или отдать объявленное число байт. */
    DATA,

    /**
     * Тип не опознан.
     *
     * Не ошибка разбора и не повод рвать обмен: Legacy на таком кадре пишет
     * предупреждение и продолжает читать. Мы поступаем так же — потерять
     * сказанное устройством хуже, чем не понять его.
     */
    UNKNOWN,
}

/**
 * Один разобранный кадр.
 *
 * [raw] хранится целиком намеренно: когда тип не опознан или полезная часть
 * пуста, единственное достоверное — то, что пришло на самом деле.
 */
public data class FastbootPacket(
    val reply: FastbootReply,
    val payload: String,
    val raw: String,
) {
    /** Терминальный ли это ответ, то есть кончился ли обмен. */
    public val terminal: Boolean
        get() = reply == FastbootReply.OKAY || reply == FastbootReply.FAIL

    /**
     * Объявленный размер для кадра `DATA`, иначе `null`.
     *
     * Число шестнадцатеричное и приходит как с префиксом `0x`, так и без него —
     * оба вида наблюдались, и A2 снимает префикс явно
     * (`FastbootPacket.dataSizeBytes`). Неразбираемое значение даёт `null`, а не
     * ноль: «не поняли, сколько» и «нисколько» — разные вещи.
     */
    public fun declaredSize(): Long? = payload
        .trim()
        .takeIf { reply == FastbootReply.DATA }
        ?.removePrefix("0x")
        ?.removePrefix("0X")
        ?.takeIf { it.isNotBlank() }
        ?.toLongOrNull(HEX)
        ?.takeIf { it >= 0L }

    private companion object {
        const val HEX = 16
    }
}

/**
 * Разбор кадра Fastboot.
 *
 * Слой знает только провод: что означает ответ и можно ли слать следующую
 * команду — решает [FastbootLane], а что делать с `DATA` — вызывающий.
 */
public object FastbootPacketCodec {
    /**
     * Разбирает [length] байт из [bytes].
     *
     * Текст ASCII: устройство отвечает именно так, и брать UTF-8 значило бы
     * придумать себе многобайтные символы там, где их нет. Нулевые байты
     * снимаются — Legacy и A2 делают это одинаково, потому что устройства
     * дополняют ими ответ до длины пакета.
     */
    public fun parse(bytes: ByteArray, length: Int = bytes.size): FastbootPacket {
        require(length in 0..bytes.size) { "кадр длиной $length не помещается в ${bytes.size} байт" }
        val raw = String(bytes, 0, length, Charsets.US_ASCII).replace("\u0000", "").trim()
        val reply = raw.take(TYPE_LENGTH).takeIf { raw.length >= TYPE_LENGTH }?.let(::replyOf)
        return FastbootPacket(
            reply = reply ?: FastbootReply.UNKNOWN,
            payload = if (reply == null) raw else raw.drop(TYPE_LENGTH).trim(),
            raw = raw,
        )
    }

    private fun replyOf(type: String): FastbootReply = when (type) {
        "OKAY" -> FastbootReply.OKAY
        "FAIL" -> FastbootReply.FAIL
        "INFO" -> FastbootReply.INFO
        "TEXT" -> FastbootReply.TEXT
        "DATA" -> FastbootReply.DATA
        else -> FastbootReply.UNKNOWN
    }

    /** Тип занимает ровно четыре байта — это и есть весь заголовок Fastboot. */
    private const val TYPE_LENGTH = 4
}
