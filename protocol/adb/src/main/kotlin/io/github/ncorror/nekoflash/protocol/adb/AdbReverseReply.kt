package io.github.ncorror.nekoflash.protocol.adb

/** Что устройство ответило на запрос обратного проброса. */
public sealed interface AdbReverseReply {
    /**
     * Запрос принят.
     *
     * [body] — то, что устройство сказало сверх подтверждения. Для
     * `reverse:forward` там оказался назначенный порт; для
     * `reverse:killforward-all` тела не было вовсе.
     */
    public data class Accepted(val body: String) : AdbReverseReply

    /** Устройство отказало и объяснило, чем именно. */
    public data class Refused(val detail: String) : AdbReverseReply

    /** Ответ есть, но разобрать его не получилось. Текст сохранён целиком. */
    public data class Unreadable(val raw: String) : AdbReverseReply
}

/**
 * Разбор ответа устройства на запрос обратного проброса.
 *
 * **Написан по наблюдению, а не по памяти.** Формат не описан ни в Legacy, ни в
 * A2 — оба дерева проверены до начала работы, — поэтому он выяснялся на
 * устройстве, и три ответа записаны дословно в `07` §6.61:
 *
 * | Запрос | Ответ |
 * |---|---|
 * | `reverse:list-forward` (пусто) | `0000` |
 * | `reverse:forward:tcp:7777;tcp:8888` | `OKAY00047777` |
 * | `reverse:killforward-all` | `OKAY` |
 * | `reverse:list-forward` (после него) | `0019UsbFfs tcp:7777 tcp:8888` + перевод строки |
 *
 * **Разбирать надо сырой вывод, а не текст.** Последняя строка это и показала:
 * объявлено `0019` — двадцать пять символов, — а видимых всего двадцать
 * четыре. Двадцать пятый есть завершающий перевод строки, и всего пришло
 * ровно 29 байт: четыре на длину и двадцать пять на тело. `text()` у
 * [AdbServiceOutcome.Completed] срезает концевые переводы строк, и на таком
 * входе длина перестала бы сходиться — верный ответ устройства пришёл бы как
 * [AdbReverseReply.Unreadable] (`07` §6.62).
 *
 * **Разбор намеренно терпимый, и это главное решение здесь.** У одного ответа
 * есть префикс `OKAY`, у другого нет, у третьего нет и длины. Трёх точек мало,
 * чтобы вывести правило, и требовать от всех ответов одной формы значило бы
 * записать догадку в код и отвергать верные ответы, которых мы просто не
 * видели. Поэтому `OKAY` и длина ищутся там, где они есть, и не требуются там,
 * где их не наблюдали.
 *
 * Чего мы не видели — отказа. Как выглядит `FAIL`, неизвестно, и придумывать
 * его форму нельзя: [AdbReverseReply.Refused] распознаётся по известному
 * префиксу, а всё непонятое отдаётся как [AdbReverseReply.Unreadable] с
 * сохранённым текстом. Потерять ответ устройства хуже, чем не понять его.
 */
public object AdbReverseProtocol {
    /** Разбирает ответ, каким он пришёл из потока. */
    public fun parse(raw: String): AdbReverseReply = when {
        raw.startsWith(FAIL) -> AdbReverseReply.Refused(bodyOf(raw.removePrefix(FAIL)) ?: raw)
        raw.startsWith(OKAY) -> accepted(raw.removePrefix(OKAY))

        // Без подтверждения, но с длиной — так ответил `list-forward`. Пустое
        // тело здесь означает пустой список, а не отказ.
        else -> accepted(raw)
    }

    private fun accepted(rest: String): AdbReverseReply =
        if (rest.isEmpty()) {
            AdbReverseReply.Accepted("")
        } else {
            bodyOf(rest)?.let { body -> AdbReverseReply.Accepted(body) }
                ?: AdbReverseReply.Unreadable(rest)
        }

    /**
     * Тело ответа: четыре шестнадцатеричные цифры длины и столько же символов.
     *
     * `null` означает, что длина не читается или не сходится с остатком, —
     * и тогда ответ отдаётся целиком, а не подгоняется под ожидание.
     */
    private fun bodyOf(rest: String): String? {
        if (rest.length < LENGTH_DIGITS) return null
        val declared = rest.take(LENGTH_DIGITS).toIntOrNull(HEX) ?: return null
        val body = rest.drop(LENGTH_DIGITS)
        return body.takeIf { it.length == declared }
    }

    /** Длина в ответе записана четырьмя шестнадцатеричными цифрами: `0004` для `7777`. */
    private const val LENGTH_DIGITS = 4
    private const val HEX = 16

    private const val OKAY = "OKAY"

    /**
     * Префикс отказа.
     *
     * На устройстве не наблюдался: все три запроса §6.61 были приняты. Оставлен
     * потому, что пропустить отказ и объявить его успехом — худшая из ошибок
     * здесь; если форма окажется иной, непонятый ответ придёт как
     * [AdbReverseReply.Unreadable] и будет виден целиком, а не потеряется.
     */
    private const val FAIL = "FAIL"
}

/**
 * Имена сервисов обратного проброса.
 *
 * Собраны в одном месте, чтобы не рассыпаться строками по коду, но списком
 * возможностей не являются: оператор по-прежнему может набрать что угодно в
 * поле произвольного сервиса (`01` §3).
 */
public object AdbReverseService {
    // Объявлено первым не для порядка: `const val` виден только ниже своего
    // объявления, и ссылки на него из соседних констант иначе не собираются.
    private const val PREFIX = "reverse"

    /**
     * Просит устройство слушать [onDevice] и приводить соединения к [onHost].
     *
     * Порядок именно такой — сначала сторона устройства, потом наша, — и это
     * наблюдение, а не соглашение: `reverse:forward:tcp:7777;tcp:8888` заставил
     * слушать **7777**, и его же устройство назвало в ответе (`07` §6.61).
     */
    public fun forward(onDevice: String, onHost: String): String =
        "$PREFIX:forward:${onDevice.trim()};${onHost.trim()}"

    /** Спрашивает, что устройство слушает сейчас. */
    public const val LIST: String = "$PREFIX:list-forward"

    /** Снимает все обратные пробросы разом. */
    public const val KILL_ALL: String = "$PREFIX:killforward-all"
}
