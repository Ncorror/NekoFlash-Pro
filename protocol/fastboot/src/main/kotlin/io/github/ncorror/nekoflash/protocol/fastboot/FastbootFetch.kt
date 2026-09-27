package io.github.ncorror.nekoflash.protocol.fastboot

import java.io.OutputStream

/** Чем кончилось чтение раздела с устройства. */
public sealed interface FastbootFetchOutcome {
    /** Раздел прочитан целиком, и устройство подтвердило каждый кусок. */
    public data class Completed(
        val partition: String,
        val bytesReceived: Long,
        val chunks: Int,
    ) : FastbootFetchOutcome

    /**
     * Устройство отказалось — **до** того, как отдало хоть байт.
     *
     * Обычный случай, и на запертом загрузчике ожидаемый: `fetch:` в AOSP
     * живёт в `fastbootd` и обычно требует разблокированного или отладочного
     * состояния. Legacy предупреждает об этом перед отправкой и **не**
     * запрещает — решение принимает устройство.
     */
    public data class Refused(
        val partition: String,
        val detail: String,
    ) : FastbootFetchOutcome

    /**
     * Прочитано не всё, и прочитанное **неполно**.
     *
     * Отдать его как раздел нельзя: недостающий кусок снаружи не отличить от
     * нулей внутри, а `03` §3 запрещает выдавать незнание за наблюдение. Это и
     * есть `partial output semantics`: частичный результат обязан называться
     * частичным на всём пути, а не только в момент обрыва.
     */
    public data class Partial(
        val partition: String,
        val bytesReceived: Long,
        val expectedBytes: Long?,
        val detail: String,
    ) : FastbootFetchOutcome

    /** Обмен не начался: полоса занята, закрыта или потеряла рамку. */
    public data class NotStarted(
        val partition: String,
        val detail: String,
    ) : FastbootFetchOutcome
}

/**
 * Чтение раздела — фаза DATA IN.
 *
 * **Форма команды несимметрична отправке, и это взято из Legacy, а не выведено.**
 * `download:` объявляет объём восемью шестнадцатеричными цифрами; `fetch:`
 * называет смещение и размер **десятичными**:
 * `"fetch:$partition:$offset:$chunkSize"` (`FastbootProtocol`, строка 1071).
 * Предположить симметрию здесь значило бы попросить не тот кусок.
 *
 * **Куски считаются по двум переменным устройства.** `partition-size:<имя>`
 * даёт сколько всего, `max-fetch-size` — сколько за раз. Нет любой из них —
 * Legacy падает на один нечанкованный `fetch:<имя>` и позволяет устройству
 * самому назвать объём в кадре `DATA`. Мы делаем так же: домысливать размер
 * раздела, когда устройство его не назвало, нечем.
 *
 * **Кусок нулевой длины — ошибка, названная явно.** Без этой проверки цикл
 * кусков крутился бы вечно, а прогресс стоял бы на месте, выглядя живым.
 *
 * **`max-fetch-size` не превращается в нашу проверку.** Он говорит, какими
 * кусками просить, и только. Отказ по объёму принадлежит устройству — ровно
 * как с `max-download-size` в `FastbootDownload`, и по той же причине (`03`
 * §2): диагностическое поле устройства не должно становиться хостовой
 * авторизацией.
 */
public class FastbootFetch(
    private val lane: FastbootLane,
    private val variables: FastbootGetVar,
) {
    /**
     * Читает [partition] в [sink].
     *
     * Приёмник не закрывается здесь: закрывает тот, кто открыл. Прочитанное
     * нигде не собирается целиком — раздел может быть на несколько гигабайт.
     */
    public fun fetch(partition: String, sink: OutputStream): FastbootFetchOutcome {
        val name = partition.trim()
        val total = sizeOf("$PARTITION_SIZE$name")
        val limit = sizeOf(MAX_FETCH_SIZE)?.takeIf { it > 0L }
        return if (total != null && total > 0L && limit != null) {
            chunked(name, sink, total, limit)
        } else {
            single(name, sink)
        }
    }

    /** Один обмен: объём называет само устройство в кадре `DATA`. */
    private fun single(partition: String, sink: OutputStream): FastbootFetchOutcome =
        when (val chunk = readChunk(command(partition), sink)) {
            is ChunkOutcome.Read -> FastbootFetchOutcome.Completed(partition, chunk.bytes, 1)
            is ChunkOutcome.Refused -> FastbootFetchOutcome.Refused(partition, chunk.detail)
            is ChunkOutcome.Broken ->
                FastbootFetchOutcome.Partial(partition, chunk.bytes, null, chunk.detail)

            is ChunkOutcome.NotStarted -> FastbootFetchOutcome.NotStarted(partition, chunk.detail)
        }

    private fun chunked(
        partition: String,
        sink: OutputStream,
        total: Long,
        limit: Long,
    ): FastbootFetchOutcome {
        var offset = 0L
        var chunks = 0
        var stop: FastbootFetchOutcome? = null

        while (stop == null && offset < total) {
            val wanted = minOf(limit, total - offset)
            when (val chunk = readChunk(command(partition, offset, wanted), sink)) {
                is ChunkOutcome.Read -> {
                    // Кусок нулевой длины остановил бы движение, оставив цикл
                    // живым на вид. Legacy называет это ошибкой прямо.
                    stop = if (chunk.bytes <= 0L) {
                        FastbootFetchOutcome.Partial(partition, offset, total, "устройство отдало пустой кусок")
                    } else {
                        null
                    }
                    offset += chunk.bytes
                    chunks += 1
                }

                is ChunkOutcome.Refused -> stop = if (offset == 0L) {
                    FastbootFetchOutcome.Refused(partition, chunk.detail)
                } else {
                    // Отказ на середине — не «ничего не было»: часть раздела
                    // уже прочитана, и она неполна.
                    FastbootFetchOutcome.Partial(partition, offset, total, chunk.detail)
                }

                is ChunkOutcome.Broken ->
                    stop = FastbootFetchOutcome.Partial(partition, offset + chunk.bytes, total, chunk.detail)

                is ChunkOutcome.NotStarted -> stop = FastbootFetchOutcome.NotStarted(partition, chunk.detail)
            }
        }

        return stop ?: FastbootFetchOutcome.Completed(partition, offset, chunks)
    }

    private fun readChunk(command: String, sink: OutputStream): ChunkOutcome =
        when (val opened = lane.run(command, inactivityMillis = OPENING_FRAME_MS)) {
            is FastbootExchange.DataPhase -> transfer(opened, sink)

            // Терминальный ответ вместо `DATA`: устройство отказалось отдавать.
            // Ни одного байта не пришло.
            is FastbootExchange.Completed -> ChunkOutcome.Refused(
                "${opened.reply.name}: ${opened.payload.ifBlank { "без объяснения" }}",
            )

            is FastbootExchange.TimedOut ->
                ChunkOutcome.Broken(0L, "ответа на $command не было ${opened.waitedMillis} мс")

            is FastbootExchange.NotReady -> ChunkOutcome.NotStarted("полоса занята: ${opened.state}")
            is FastbootExchange.NotSent -> ChunkOutcome.NotStarted(opened.reason)
        }

    private fun transfer(opened: FastbootExchange.DataPhase, sink: OutputStream): ChunkOutcome {
        val declared = opened.declaredSize
        return if (declared == null) {
            lane.stall()
            ChunkOutcome.Broken(0L, "устройство не назвало объём: ${opened.payload}")
        } else {
            outcomeOf(lane.receiveData(sink, declared))
        }
    }

    private fun outcomeOf(outcome: FastbootReceiveOutcome): ChunkOutcome = when (outcome) {
        is FastbootReceiveOutcome.Completed -> if (outcome.reply == FastbootReply.FAIL) {
            // Байты пришли, а устройство сказало «нет». Прочитанному верить
            // нельзя: оно неполно или неверно, и это его слово, а не наше.
            ChunkOutcome.Broken(outcome.bytesReceived, "после данных: FAIL ${outcome.payload}")
        } else {
            ChunkOutcome.Read(outcome.bytesReceived)
        }

        is FastbootReceiveOutcome.Interrupted -> ChunkOutcome.Broken(outcome.bytesReceived, outcome.detail)
        is FastbootReceiveOutcome.NotReady -> ChunkOutcome.NotStarted("фаза данных не открыта: ${outcome.state}")
    }

    private fun sizeOf(variable: String): Long? =
        (variables.read(variable) as? FastbootVariable.Present)?.let { FastbootSize.of(it.value) }

    /** Один кусок: без смещения — весь раздел, как его понимает устройство. */
    internal fun command(partition: String): String = "$PREFIX$partition"

    /**
     * Кусок со смещением и размером.
     *
     * Оба числа **десятичные** — так пишет Legacy. Восемь шестнадцатеричных
     * цифр это другое поле другого направления (`download:`), и спутать их
     * значит попросить не тот кусок.
     */
    internal fun command(partition: String, offset: Long, size: Long): String =
        "$PREFIX$partition:$offset:$size"

    /** Исход одного куска. Наружу не выходит: снаружи важен исход раздела целиком. */
    private sealed interface ChunkOutcome {
        data class Read(val bytes: Long) : ChunkOutcome
        data class Refused(val detail: String) : ChunkOutcome
        data class Broken(val bytes: Long, val detail: String) : ChunkOutcome
        data class NotStarted(val detail: String) : ChunkOutcome
    }

    private companion object {
        /**
         * Сколько ждать кадра `DATA` или отказа на сам `fetch:`.
         *
         * Десять секунд — из Legacy, где `fetchChunk` зовёт
         * `readUntilDataOrFinal(10000)`, а не общий бюджет. Наши семь секунд
         * здесь были расхождением с архивом, введённым молча: `fetch:` просит
         * устройство открыть раздел, и это дольше, чем ответить `getvar`.
         * Найдено разбором `07` §6.91, где отказ пришёл ровно по нашему
         * укороченному бюджету.
         */
        const val OPENING_FRAME_MS = 10_000L

        const val PREFIX = "fetch:"
        const val PARTITION_SIZE = "partition-size:"
        const val MAX_FETCH_SIZE = "max-fetch-size"
    }
}

/**
 * Размер, как его называет устройство.
 *
 * Значения приходят и шестнадцатеричными с `0x`, и десятичными — Legacy
 * `parseFastbootSize` и A2 `FastbootGetVarAllParser.parseSize` сходятся в этом
 * дословно. Отрицательное значение размером не считается.
 */
public object FastbootSize {
    /** Разбирает [raw], либо `null`, если это не размер. */
    public fun of(raw: String?): Long? {
        val value = raw?.trim()?.lowercase() ?: return null
        val parsed = if (value.startsWith(HEX_PREFIX)) {
            value.removePrefix(HEX_PREFIX).toLongOrNull(HEX_RADIX)
        } else {
            value.toLongOrNull()
        }
        return parsed?.takeIf { it >= 0L }
    }

    private const val HEX_PREFIX = "0x"
    private const val HEX_RADIX = 16
}
