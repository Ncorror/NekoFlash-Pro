package io.github.ncorror.nekoflash.protocol.fastboot

/**
 * Чем кончился приём объявленного объёма — фаза DATA IN.
 *
 * Отдельный тип, а не переиспользованный [FastbootDataOutcome], и не ради
 * симметрии: у отправки поле называется `bytesSent`, и написать в нём число
 * **принятых** байт значило бы соврать именем. Словарь отказов тоже другой — у
 * приёма не бывает «источник кончился рано», зато бывает «устройство перестало
 * слать».
 */
public sealed interface FastbootReceiveOutcome {
    /** Объявленный объём принят целиком, и устройство ответило терминально. */
    public data class Completed(
        val reply: FastbootReply,
        val payload: String,
        val info: List<String>,
        val bytesReceived: Long,
    ) : FastbootReceiveOutcome
    /**
     * Приём не дошёл до конца, и **прочитанное неполно**.
     *
     * Выдать его за результат нельзя: отсутствие куска раздела ничем не
     * отличается снаружи от нулей в нём, а `03` §3 запрещает выдавать незнание
     * за наблюдение. Рамка при этом потеряна: устройство осталось слать байты,
     * которых мы больше не читаем.
     */
    public data class Interrupted(
        val bytesReceived: Long,
        val expectedBytes: Long,
        val detail: String,
    ) : FastbootReceiveOutcome
    /** Фаза данных не открыта: принимать нечего. */
    public data class NotReady(val state: FastbootLaneState) : FastbootReceiveOutcome
}
