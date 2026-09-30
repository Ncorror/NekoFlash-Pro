package io.github.ncorror.nekoflash.protocol.fastboot

/** Чем кончилось чтение одной переменной устройства. */
public sealed interface FastbootVariable {
    /** Устройство назвало значение. */
    public data class Present(val name: String, val value: String) : FastbootVariable

    /**
     * Устройство ответило `FAIL`: такой переменной у него нет.
     *
     * Это **ответ**, а не сбой: разные загрузчики знают разный набор
     * переменных, и отсутствие `is-userspace` само по себе осмысленно
     * (`03` §2, Device authority).
     */
    public data class Unsupported(val name: String, val detail: String) : FastbootVariable

    /**
     * Спросить не получилось: полоса занята, потеряла рамку или промолчала.
     *
     * От [Unsupported] отличается принципиально: там устройство сказало «нет»,
     * здесь мы не знаем, что оно сказало. Смешивать их значило бы выдавать
     * незнание за ответ устройства.
     */
    public data class Unavailable(val name: String, val detail: String) : FastbootVariable
}

/**
 * Чтение переменных устройства.
 *
 * **Значение приходит двумя разными способами, и это наблюдение, а не догадка.**
 * Обычно оно лежит прямо в теле `OKAY`. Но часть загрузчиков отвечает пустым
 * `OKAY`, а значение шлёт перед ним кадрами `INFO`, и Legacy разбирает этот
 * случай отдельно (`FastbootProtocol.readGetVarResponse`), перебирая собранные
 * строки **с конца**: последняя относится к последнему заданному вопросу.
 * Поддержать только первый способ значило бы объявить такие устройства
 * неотвечающими.
 */
public class FastbootGetVar(private val lane: FastbootLane) {
    /**
     * Спрашивает [name] у устройства.
     *
     * Имя передаётся как есть: какие переменные существуют, знает загрузчик, и
     * списком их запирать нельзя (`01` §3). Незнакомое имя — это его `FAIL`, а
     * не наш отказ.
     */
    public fun read(name: String, inactivityMillis: Long = FastbootLane.DEFAULT_INACTIVITY_MS): FastbootVariable =
        when (val exchange = lane.run("$PREFIX$name", inactivityMillis)) {
            is FastbootExchange.Completed -> valueOf(name, exchange)
            is FastbootExchange.TimedOut ->
                FastbootVariable.Unavailable(name, "ответа не было ${exchange.waitedMillis} мс")

            is FastbootExchange.NotReady -> FastbootVariable.Unavailable(name, "полоса занята: ${exchange.state}")
            is FastbootExchange.NotSent -> FastbootVariable.Unavailable(name, exchange.reason)

            // `getvar` фазы данных не открывает. Если она всё же открыта,
            // устройство ждёт байты, а мы о них не договаривались: рамка
            // потеряна, и молчать об этом нельзя.
            is FastbootExchange.DataPhase -> {
                lane.stall()
                FastbootVariable.Unavailable(name, "устройство открыло фазу данных на getvar")
            }
        }

    /**
     * Спрашивает всё, что устройство готово рассказать.
     *
     * Ответ приходит не так, как у одиночной переменной: десятки кадров `INFO`,
     * каждый с одной или несколькими строками, и терминальный кадр в конце.
     * Поэтому разбирается он [FastbootVariables], а не [FastbootVariableValue].
     *
     * Бюджет по умолчанию больше: вывод длинный, и устройство шлёт его не
     * мгновенно. Ожидание при этом считается по бездействию, поэтому длинный
     * ответ мёртвым не выглядит.
     */
    public fun readAll(inactivityMillis: Long = ALL_INACTIVITY_MS): FastbootVariableSnapshot =
        when (val exchange = lane.run(ALL, inactivityMillis)) {
            is FastbootExchange.Completed -> FastbootVariables.parse(
                lines = exchange.info,
                complete = true,
                finalReply = exchange.reply,
                finalPayload = exchange.payload,
            )

            // Оборвалось на середине — прочитанное отдаётся, но помечается
            // неполным. Выдать частичный список за полный значило бы соврать
            // о том, чего у устройства нет.
            is FastbootExchange.TimedOut -> FastbootVariables.parse(
                lines = exchange.info,
                complete = false,
                finalReply = FastbootReply.UNKNOWN,
                finalPayload = "ответа не было ${exchange.waitedMillis} мс",
            )

            is FastbootExchange.DataPhase -> {
                lane.stall()
                unreadable(exchange.info, "устройство открыло фазу данных на $ALL")
            }

            is FastbootExchange.NotReady -> unreadable(emptyList(), "полоса занята: ${exchange.state}")
            is FastbootExchange.NotSent -> unreadable(emptyList(), exchange.reason)
        }

    private fun unreadable(lines: List<String>, detail: String): FastbootVariableSnapshot =
        FastbootVariables.parse(
            lines = lines,
            complete = false,
            finalReply = FastbootReply.UNKNOWN,
            finalPayload = detail,
        )

    private fun valueOf(name: String, exchange: FastbootExchange.Completed): FastbootVariable = when {
        exchange.reply == FastbootReply.FAIL ->
            FastbootVariable.Unsupported(name, exchange.payload.ifBlank { "без объяснения" })

        exchange.payload.isNotBlank() -> FastbootVariable.Present(name, FastbootVariableValue.of(name, exchange.payload))

        else -> fromInfo(name, exchange.info)
    }

    /** Значение из строк `INFO` — с конца, как это делает Legacy. */
    private fun fromInfo(name: String, info: List<String>): FastbootVariable =
        info.asReversed()
            .asSequence()
            .map { FastbootVariableValue.of(name, it) }
            .firstOrNull { it.isNotBlank() }
            ?.let { FastbootVariable.Present(name, it) }
            ?: FastbootVariable.Present(name, "")

    private companion object {
        const val PREFIX = "getvar:"

        /** Имя сервиса целиком: `all` — не переменная, а особый запрос. */
        const val ALL = "getvar:all"

        /**
         * Бюджет бездействия для `getvar:all`.
         *
         * Больше обычного, потому что вывод длинный. Взято у A2, где для этого
         * запроса заведена своя константа
         * (`FastbootGetVarAllRequest.INACTIVITY_TIMEOUT_MS`).
         */
        const val ALL_INACTIVITY_MS = 30_000L
    }
}

/**
 * Очистка значения переменной от обёрток, которыми его окружают загрузчики.
 *
 * Собрано по A2 `FastbootGetVarValue.normalize`: встречается и префикс `INFO`
 * внутри самой строки, и повторение имени переменной перед значением — причём
 * имя приходит как через дефис, так и через подчёркивание.
 */
public object FastbootVariableValue {
    /** Снимает с [raw] всё, что не является значением [name]. */
    public fun of(name: String, raw: String): String {
        val cleaned = raw.trim().removePrefix("INFO").trim()
        val prefix = listOf(name, name.replace('-', '_'))
            .map { "$it:" }
            .firstOrNull { cleaned.startsWith(it, ignoreCase = true) }
        return prefix?.let { cleaned.substring(it.length).trim() } ?: cleaned
    }
}
