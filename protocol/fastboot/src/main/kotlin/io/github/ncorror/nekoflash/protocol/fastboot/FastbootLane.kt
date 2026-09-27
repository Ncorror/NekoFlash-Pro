package io.github.ncorror.nekoflash.protocol.fastboot

import io.github.ncorror.nekoflash.usb.api.UsbTransferResult
import io.github.ncorror.nekoflash.usb.api.UsbTransportHandle

/** Состояние единственной полосы обмена, принадлежащей одному захваченному интерфейсу. */
public enum class FastbootLaneState {
    /** Команду можно отправлять. */
    IDLE,

    /** Команда ушла, терминального ответа ещё нет. */
    AWAITING_FINAL,

    /** Устройство объявило фазу данных и ждёт байты. */
    AWAITING_DATA,

    /**
     * Рамка потеряна.
     *
     * Состояние липкое и снимается только новым захватом интерфейса. Слать
     * вторую команду по той же полосе нельзя: устройство ждёт не её, и обмен
     * разойдётся тем сильнее, чем дольше мы будем делать вид, что всё в
     * порядке. Так же устроено в обоих архивах — Legacy `SessionState.BROKEN`
     * (`FastbootProtocol.kt`), A2 `FastbootTransactionState.STALLED`.
     */
    STALLED,

    /** Полоса закрыта вместе с интерфейсом. */
    CLOSED,
}

/** Чем кончился один обмен командой. */
public sealed interface FastbootExchange {
    /**
     * Устройство ответило терминально.
     *
     * [reply] — `OKAY` или `FAIL`. **Отказ сюда попадает как обычный исход**, а
     * не как ошибка: `FAIL` — это ответ устройства, и подменять его своей
     * ошибкой значило бы скрыть слово peer'а (`03` §2, Device authority).
     */
    public data class Completed(
        val reply: FastbootReply,
        val payload: String,
        val info: List<String>,
    ) : FastbootExchange

    /** Устройство объявило фазу данных. Полоса ждёт байты и командой не занята. */
    public data class DataPhase(
        val declaredSize: Long?,
        val payload: String,
        val info: List<String>,
    ) : FastbootExchange

    /**
     * Ответа не дождались.
     *
     * Полоса при этом переходит в [FastbootLaneState.STALLED]: команда ушла, и
     * что с ней стало — неизвестно. Это `Unknown`, а не отказ.
     */
    public data class TimedOut(
        val waitedMillis: Long,
        val info: List<String>,
    ) : FastbootExchange

    /** По этой полосе сейчас нельзя: она занята, закрыта или потеряла рамку. */
    public data class NotReady(val state: FastbootLaneState) : FastbootExchange

    /** Команду не удалось отправить. Устройство её не видело. */
    public data class NotSent(val reason: String) : FastbootExchange
}

/** Чем кончилась передача байтов в открытой фазе данных. */
public sealed interface FastbootDataOutcome {
    /**
     * Байты переданы целиком, и устройство ответило.
     *
     * [reply] — `OKAY` или `FAIL`: отказ после полной передачи это ответ
     * устройства, а не наша ошибка.
     */
    public data class Completed(
        val reply: FastbootReply,
        val payload: String,
        val info: List<String>,
        val bytesSent: Long,
    ) : FastbootDataOutcome

    /**
     * Передача не дошла до конца, и **состояние приёмника неизвестно**.
     *
     * Это `Unknown`, а не отказ (`03` §3). Устройство получило часть байтов и
     * осталось ждать остальные; что лежит в его буфере загрузки — мы не знаем,
     * и прошивать из него нельзя. Повторять ту же передачу по той же полосе
     * тоже нельзя: рамка потеряна.
     */
    public data class Interrupted(
        val bytesSent: Long,
        val expectedBytes: Long,
        val detail: String,
    ) : FastbootDataOutcome

    /** Фаза данных не открыта: передавать некуда. */
    public data class NotReady(val state: FastbootLaneState) : FastbootDataOutcome
}

/**
 * Единственная синхронная полоса обмена Fastboot.
 *
 * **Почему полоса одна.** Fastboot не мультиплексирует: у него нет ни
 * идентификаторов потоков, ни порядковых номеров — есть команда и ответ на неё.
 * Вторая команда, отправленная до терминального ответа на первую, необратимо
 * перепутает, чей ответ читается следующим. Поэтому одновременность здесь
 * невозможна не по нашему решению, а по устройству протокола, и это ровно тот
 * случай, когда ограничение принадлежит классу hard invariant (`03` §2).
 *
 * Тем и отличается от ADB, где `AdbStreamDispatcher` раздаёт потоки по
 * идентификаторам и одновременность законна (ADR-0004).
 *
 * **Ожидание считается по бездействию, а не по общему времени.** Каждый
 * пришедший кадр продлевает бюджет: `erase` может слать `INFO` минутами и быть
 * при этом совершенно живым. Считать такой обмен мёртвым по общему таймеру
 * значило бы рвать исправную операцию тем вернее, чем больше она делает. Взято
 * у A2 (`FastbootInactivityBudget`), где это записано прямо.
 *
 * **Неуспешный приём сам по себе ничего не значит.** Транспорт не различает
 * таймаут и ошибку — об этом прямо сказано в `UsbTransferFailure.NOT_COMPLETED`.
 * Различает слой выше, и различает по своему состоянию: ожидание, в котором не
 * пришло ни кадра, — обычный таймаут, и его надо продолжать в пределах бюджета.
 * Legacy делает так же: `readPacket(2000) == null` не кончает цикл.
 *
 * **Время ожидания измеряется часами, а не складывается из запрошенных
 * ломтей.** Пустое чтение на этом хосте возвращается **мгновенно**, и сумма
 * запрошенных таймаутов к потраченному времени отношения не имеет: бюджет в
 * 7000 мс «истекал» за 15 мс реального времени, а в журнал уходило «ответа не
 * было 7000 мс» — утверждение о наблюдении, которого не было (`03` §3, `07`
 * §6.91). Legacy считает то же самое по `System.currentTimeMillis()`
 * (`readGetVarResponse`, строки 2097–2111) и прямо описывает эту ловушку в
 * комментарии «V5.8.10 onyx handshake fix»: «пустые чтения возвращаются
 * мгновенно, поэтому счётчик из трёх набегал за ~200 мс». Хост прогона — тот
 * самый `onyx`.
 */
public class FastbootLane(
    private val transport: UsbTransportHandle,
    private val readBufferBytes: Int = DEFAULT_READ_BUFFER,
    /**
     * Часы ожидания — монотонные и подменяемые.
     *
     * Подменяются не ради удобства тестов, а потому, что измеряемая величина
     * обязана быть проверяемой: «сколько ждали» без возможности задать время в
     * тесте снова стало бы числом, которое никто не сверял.
     */
    private val elapsedMillis: () -> Long = System::currentTimeMillis,
    /**
     * Пауза между пустыми чтениями.
     *
     * Без неё мгновенно возвращающееся чтение крутит цикл вхолостую весь
     * бюджет. Legacy держит её по той же причине
     * (`GETVAR_READ_RETRY_DELAY_MS = 100`).
     */
    private val pauseMillis: (Long) -> Unit = { Thread.sleep(it) },
) {
    private var currentState: FastbootLaneState = FastbootLaneState.IDLE

    /** Состояние полосы прямо сейчас. */
    public val state: FastbootLaneState get() = currentState

    /**
     * Отправляет [command] и читает ответ до терминального кадра или до `DATA`.
     *
     * [inactivityMillis] — сколько ждать **без единого кадра**; каждый кадр
     * отсчёт продлевает.
     */
    public fun run(
        command: String,
        inactivityMillis: Long = DEFAULT_INACTIVITY_MS,
        writeTimeoutMillis: Int = DEFAULT_WRITE_TIMEOUT_MS,
    ): FastbootExchange {
        require(inactivityMillis > 0L) { "бюджет бездействия должен быть положительным" }
        return if (currentState != FastbootLaneState.IDLE) {
            FastbootExchange.NotReady(currentState)
        } else {
            send(command, writeTimeoutMillis, inactivityMillis)
        }
    }

    /** Закрывает полосу. Интерфейсом владеет вызывающий, здесь только состояние. */
    public fun close() {
        currentState = FastbootLaneState.CLOSED
    }

    /**
     * Объявляет рамку потерянной.
     *
     * Нужен тому, кто вошёл в фазу данных и не довёл её: устройство осталось
     * ждать байты, и следующая команда попадёт не туда.
     */
    public fun stall() {
        currentState = FastbootLaneState.STALLED
    }

    /**
     * Передаёт [expectedBytes] байт из [source] в открытую фазу данных.
     *
     * **Правила счёта байтов взяты из архива, а не выведены.** A2
     * `FastbootDataTransfer.transferSyncBulk` и Legacy
     * `FastbootProtocol.transferDownloadPayload` сходятся в трёх вещах:
     *
     * 1. **Короткая запись здесь законна и дописывается.** Хост → устройство
     *    дробится и повторяется, пока не отправлено всё; это прямо разрешено
     *    контрактом [UsbTransportHandle.send] и не путается с приёмом, где
     *    дробление объявленного payload разрушало рамку.
     * 2. **Запись «больше запрошенного» или «ноль и меньше» — неоднозначна.**
     *    A2 называет её `Ambiguous` и не повторяет. Мы тоже: повторить те же
     *    байты после неоднозначной записи запрещено (`03` §3), потому что
     *    доказать, что предыдущая попытка не дошла, нечем.
     * 3. **Ранний конец источника — провал, а не успех.** Передать меньше
     *     объявленного и получить `OKAY` невозможно: устройство ждёт ровно
     *     столько, сколько назвало.
     *
     * Во всех неполных случаях полоса переходит в [FastbootLaneState.STALLED]:
     * устройство осталось ждать байты, и следующая команда попадёт не туда.
     */
    public fun sendData(
        source: java.io.InputStream,
        expectedBytes: Long,
        inactivityMillis: Long = DEFAULT_INACTIVITY_MS,
        writeTimeoutMillis: Int = DATA_WRITE_TIMEOUT_MS,
    ): FastbootDataOutcome {
        require(expectedBytes >= 0L) { "объявленный размер не может быть отрицательным" }
        return if (currentState != FastbootLaneState.AWAITING_DATA) {
            FastbootDataOutcome.NotReady(currentState)
        } else {
            pump(source, expectedBytes, inactivityMillis, writeTimeoutMillis)
        }
    }

    private fun pump(
        source: java.io.InputStream,
        expectedBytes: Long,
        inactivityMillis: Long,
        writeTimeoutMillis: Int,
    ): FastbootDataOutcome {
        val block = ByteArray(DATA_BLOCK_BYTES)
        var sent = 0L
        var failure: String? = null

        while (failure == null && sent < expectedBytes) {
            val wanted = minOf(block.size.toLong(), expectedBytes - sent).toInt()
            val read = runCatching { source.read(block, 0, wanted) }.getOrElse { error ->
                failure = "чтение источника не удалось: ${error.javaClass.simpleName}"
                0
            }
            failure = failure
                ?: if (read <= 0) "источник кончился на $sent из $expectedBytes" else null
            if (failure == null) {
                val written = writeBlock(block, read, sent, writeTimeoutMillis)
                failure = written.second
                sent += written.first
            }
        }

        return finish(sent, expectedBytes, failure, inactivityMillis)
    }

    /** Сколько байт блока ушло и что помешало. Короткая запись дописывается здесь. */
    private fun writeBlock(
        block: ByteArray,
        length: Int,
        alreadySent: Long,
        writeTimeoutMillis: Int,
    ): Pair<Long, String?> {
        var offset = 0
        var problem: String? = null
        while (problem == null && offset < length) {
            val requested = length - offset
            val result = transport.send(block, offset, requested, writeTimeoutMillis)
            problem = when {
                result is UsbTransferResult.Failed ->
                    "запись не состоялась на ${alreadySent + offset}: ${result.reason}"

                result is UsbTransferResult.Completed && (result.bytes <= 0 || result.bytes > requested) ->
                    "неоднозначная запись на ${alreadySent + offset}: " +
                        "отправлено ${result.bytes} из $requested"

                else -> null
            }
            if (problem == null) offset += (result as UsbTransferResult.Completed).bytes
        }
        return offset.toLong() to problem
    }

    private fun finish(
        sent: Long,
        expectedBytes: Long,
        failure: String?,
        inactivityMillis: Long,
    ): FastbootDataOutcome = when {
        failure != null -> {
            currentState = FastbootLaneState.STALLED
            FastbootDataOutcome.Interrupted(sent, expectedBytes, failure)
        }

        sent != expectedBytes -> {
            currentState = FastbootLaneState.STALLED
            FastbootDataOutcome.Interrupted(sent, expectedBytes, "передано $sent из $expectedBytes")
        }

        else -> {
            currentState = FastbootLaneState.AWAITING_FINAL
            terminal(sent, expectedBytes, inactivityMillis)
        }
    }

    private fun terminal(sent: Long, expectedBytes: Long, inactivityMillis: Long): FastbootDataOutcome =
        when (val exchange = readUntilTerminal(inactivityMillis)) {
            is FastbootExchange.Completed ->
                FastbootDataOutcome.Completed(exchange.reply, exchange.payload, exchange.info, sent)

            is FastbootExchange.TimedOut -> FastbootDataOutcome.Interrupted(
                bytesSent = sent,
                expectedBytes = expectedBytes,
                detail = "байты переданы, ответа не было ${exchange.waitedMillis} мс",
            )

            // Вторая фаза данных подряд не предусмотрена протоколом, и что
            // устройство при этом делает — неизвестно. Рамку считаем потерянной.
            else -> {
                currentState = FastbootLaneState.STALLED
                FastbootDataOutcome.Interrupted(sent, expectedBytes, "непредусмотренный ответ после данных")
            }
        }

    /**
     * Принимает [expectedBytes] байт от устройства в [sink] — фаза DATA IN.
     *
     * **Направление фазы данных знает команда, а не кадр.** `DATA` выглядит
     * одинаково у `download:` и у `fetch:`, поэтому приём и отправка — разные
     * вызовы, а не одна догадливая функция.
     *
     * Правила взяты из Legacy `readRawDataTo`, а не выведены из симметрии с
     * отправкой:
     *
     * 1. **Блок чтения ограничен 16 КиБ, и это осознанный компромисс
     *    совместимости.** Комментарий стоит прямо над строкой: «Fetch остаётся
     *    синхронным IN-путём и использует отдельный консервативный 16 KiB
     *    compatibility-read. Это не связано с асинхронным UsbRequest DATA OUT
     *    transport прошивки». Совпадение с [DATA_BLOCK_BYTES] случайное: тот из
     *    `SYNC_BULK` у A2 и описывает отправку.
     * 2. **Приём нулевой длины или отказ — провал сразу, без повтора.**
     * 3. **Короткий приём законен** и дочитывается. Этим приём похож на
     *    отправку, и только этим.
     *
     * Принятое не накапливается: байты уходят в [sink] по мере чтения.
     * Приёмник не закрывается здесь — закрывает тот, кто открыл.
     */
    public fun receiveData(
        sink: java.io.OutputStream,
        expectedBytes: Long,
        inactivityMillis: Long = DATA_IN_INACTIVITY_MS,
        readTimeoutMillis: Int = DATA_READ_TIMEOUT_MS,
    ): FastbootReceiveOutcome {
        require(expectedBytes >= 0L) { "объявленный размер не может быть отрицательным" }
        return if (currentState != FastbootLaneState.AWAITING_DATA) {
            FastbootReceiveOutcome.NotReady(currentState)
        } else {
            drain(sink, expectedBytes, inactivityMillis, readTimeoutMillis)
        }
    }

    /**
     * Цикл приёма.
     *
     * **Неуспешное чтение — это «байт ещё не было», а не «байтов не будет».**
     * Правило записано на самом классе и до `07` §6.93 соблюдалось только при
     * чтении кадров: приём данных сдавался на первом же `Failed`. На прогоне
     * §6.93 устройство объявило `DATA` с осмысленным объёмом (96 МиБ, ровно
     * `partition-size`), а первое чтение отказало **через миллисекунды** при
     * запрошенных десяти секундах — то самое мгновенно возвращающееся пустое
     * чтение, о котором Legacy оставил комментарий «V5.8.10 onyx handshake
     * fix». Транспорт не различает таймаут и ошибку (`NOT_COMPLETED`), поэтому
     * единственное честное прочтение одного такого ответа — «байт пока нет»;
     * решает бюджет бездействия, а не отдельное чтение.
     *
     * **Приём нулевой длины остаётся провалом сразу.** `Completed(0)` — это
     * состоявшаяся передача, то есть слово устройства; `Failed` — отсутствие
     * слова. Сводить их к одному значило бы потерять различие, ради которого
     * [receiveProblem] и разделён.
     */
    private fun drain(
        sink: java.io.OutputStream,
        expectedBytes: Long,
        inactivityMillis: Long,
        readTimeoutMillis: Int,
    ): FastbootReceiveOutcome {
        val block = ByteArray(DATA_IN_BLOCK_BYTES)
        var received = 0L
        var failure: String? = null
        var quietSince = elapsedMillis()

        while (failure == null && received < expectedBytes) {
            val wanted = minOf(block.size.toLong(), expectedBytes - received).toInt()
            val result = transport.receive(block, 0, wanted, readTimeoutMillis)
            if (result is UsbTransferResult.Failed) {
                failure = waitedOut(quietSince, inactivityMillis, received, expectedBytes)
                continue
            }
            failure = receiveProblem(result, wanted, received, expectedBytes)
            if (failure == null) {
                val count = (result as UsbTransferResult.Completed).bytes
                failure = runCatching { sink.write(block, 0, count) }
                    .exceptionOrNull()
                    ?.let { "запись принятого не удалась на $received: ${it.javaClass.simpleName}" }
                received += count
                quietSince = elapsedMillis()
            }
        }

        return settle(received, expectedBytes, failure, inactivityMillis)
    }

    /** Кончилось ли терпение на байты, либо `null` — ждём дальше, выдержав паузу. */
    private fun waitedOut(
        quietSince: Long,
        inactivityMillis: Long,
        received: Long,
        expectedBytes: Long,
    ): String? {
        val idle = (elapsedMillis() - quietSince).coerceAtLeast(0L)
        val remaining = inactivityMillis - idle
        return if (remaining <= 0L) {
            "байт не было $idle мс на $received из $expectedBytes"
        } else {
            pauseMillis(minOf(EMPTY_READ_PAUSE_MS, remaining))
            null
        }
    }

    /**
     * Что помешало этому чтению, либо `null`.
     *
     * Приём нулевой длины отделён от отказа намеренно: устройство, замолчавшее
     * посреди раздела, и отказ на уровне транспорта — разные наблюдения, и по
     * журналу их надо различать.
     *
     * **Объявленный объём называется в каждой из причин.** Без него «не
     * состоялся на 0» не отличить от «устройство назвало бессмысленный объём»,
     * а по выгрузке `07` §6.89 пришлось именно это и гадать. Смещение без того,
     * от чего оно отсчитано, — половина наблюдения.
     */
    private fun receiveProblem(
        result: UsbTransferResult,
        wanted: Int,
        received: Long,
        expectedBytes: Long,
    ): String? = when {
        result is UsbTransferResult.Failed ->
            "приём не состоялся на $received из $expectedBytes: ${result.reason}"

        result is UsbTransferResult.Completed && result.bytes <= 0 ->
            "устройство перестало слать на $received из $expectedBytes"

        result is UsbTransferResult.Completed && result.bytes > wanted ->
            "неоднозначный приём на $received из $expectedBytes: принято ${result.bytes} из $wanted"

        else -> null
    }

    private fun settle(
        received: Long,
        expectedBytes: Long,
        failure: String?,
        inactivityMillis: Long,
    ): FastbootReceiveOutcome = when {
        failure != null -> {
            currentState = FastbootLaneState.STALLED
            FastbootReceiveOutcome.Interrupted(received, expectedBytes, failure)
        }

        else -> {
            currentState = FastbootLaneState.AWAITING_FINAL
            receivedTerminal(received, inactivityMillis)
        }
    }

    private fun receivedTerminal(received: Long, inactivityMillis: Long): FastbootReceiveOutcome =
        when (val exchange = readUntilTerminal(inactivityMillis)) {
            is FastbootExchange.Completed ->
                FastbootReceiveOutcome.Completed(exchange.reply, exchange.payload, exchange.info, received)

            is FastbootExchange.TimedOut -> FastbootReceiveOutcome.Interrupted(
                bytesReceived = received,
                expectedBytes = received,
                detail = "байты приняты, ответа не было ${exchange.waitedMillis} мс",
            )

            else -> {
                currentState = FastbootLaneState.STALLED
                FastbootReceiveOutcome.Interrupted(received, received, "непредусмотренный ответ после данных")
            }
        }

    /**
     * Дочитывает терминальный кадр после **принятой** фазы данных.
     *
     * Нужен приёму (`FastbootReceiver`): байты идут от устройства, и полоса их
     * не видит, но довести обмен до конца обязана всё равно — иначе следующая
     * команда уйдёт в открытый обмен.
     *
     * Отправка этого не требует: там полоса сама считает байты и переходит в
     * ожидание ответа. Здесь переход делается по слову вызывающего, и другого
     * способа нет — направление фазы данных знает команда, а не кадр.
     */
    public fun finishDataPhase(inactivityMillis: Long = DEFAULT_INACTIVITY_MS): FastbootExchange {
        require(inactivityMillis > 0L) { "бюджет бездействия должен быть положительным" }
        return if (currentState != FastbootLaneState.AWAITING_DATA) {
            FastbootExchange.NotReady(currentState)
        } else {
            currentState = FastbootLaneState.AWAITING_FINAL
            readUntilTerminal(inactivityMillis)
        }
    }

    private fun send(command: String, writeTimeoutMillis: Int, inactivityMillis: Long): FastbootExchange {
        val bytes = command.toByteArray(Charsets.US_ASCII)
        return when {
            bytes.isEmpty() -> FastbootExchange.NotSent("пустая команда")

            // Провод у Fastboot — ASCII, и `toByteArray` подменяет всё
            // остальное вопросительным знаком **молча**. Отправить подменённое
            // и назвать это отправкой набранного значило бы солгать о том, что
            // ушло на устройство. Поэтому несоответствие называется, а не
            // скрывается: набрать оператор может что угодно (`01` §3), но
            // отправить мы обязаны ровно набранное — или ничего.
            command.any { it.code > MAX_ASCII } ->
                FastbootExchange.NotSent("команда не передаётся в ASCII, а провод Fastboot другого не несёт")

            // Ограничение провода, а не наше: команда Fastboot передаётся одним
            // кадром, и длиннее шестидесяти четырёх байт он не бывает.
            bytes.size > MAX_COMMAND_BYTES ->
                FastbootExchange.NotSent("команда в ${bytes.size} байт не помещается в кадр $MAX_COMMAND_BYTES")

            else -> write(bytes, writeTimeoutMillis, inactivityMillis)
        }
    }

    private fun write(bytes: ByteArray, writeTimeoutMillis: Int, inactivityMillis: Long): FastbootExchange {
        val written = transport.send(bytes, 0, bytes.size, writeTimeoutMillis)
        return when {
            written is UsbTransferResult.Failed -> FastbootExchange.NotSent("запись не состоялась: ${written.reason}")

            // Короткая запись — не «почти отправили». Устройство получило
            // обрезанную команду, и что оно с ней сделало, мы не знаем.
            written is UsbTransferResult.Completed && written.bytes < bytes.size -> {
                currentState = FastbootLaneState.STALLED
                FastbootExchange.NotSent("отправлено ${written.bytes} из ${bytes.size} байт")
            }

            else -> {
                currentState = FastbootLaneState.AWAITING_FINAL
                readUntilTerminal(inactivityMillis)
            }
        }
    }

    private fun readUntilTerminal(inactivityMillis: Long): FastbootExchange {
        val buffer = ByteArray(readBufferBytes)
        val info = mutableListOf<String>()
        var quietSince = elapsedMillis()
        var outcome: FastbootExchange? = null

        while (outcome == null) {
            val idleMillis = (elapsedMillis() - quietSince).coerceAtLeast(0L)
            val remaining = inactivityMillis - idleMillis
            if (remaining <= 0L) {
                currentState = FastbootLaneState.STALLED
                // Сообщается измеренное, а не бюджет: разница между ними и была
                // дефектом (`07` §6.91).
                outcome = FastbootExchange.TimedOut(idleMillis, info.toList())
            } else {
                val slice = minOf(READ_SLICE_MS.toLong(), remaining).toInt().coerceAtLeast(1)
                val packet = packetOf(transport.receive(buffer, 0, buffer.size, slice), buffer)
                if (packet == null) {
                    pauseMillis(minOf(EMPTY_READ_PAUSE_MS, remaining))
                } else {
                    quietSince = elapsedMillis()
                    outcome = classify(packet, info)
                }
            }
        }
        return outcome
    }

    /**
     * Кадр из результата приёма, либо `null`, если кадра не было.
     *
     * Пустой успешный приём — тоже «кадра не было»: устройство молчит, а не
     * прислало пустоту.
     */
    private fun packetOf(received: UsbTransferResult, buffer: ByteArray): FastbootPacket? =
        (received as? UsbTransferResult.Completed)
            ?.takeIf { it.bytes > 0 }
            ?.let { FastbootPacketCodec.parse(buffer, it.bytes) }

    private fun classify(packet: FastbootPacket, info: MutableList<String>): FastbootExchange? = when {
        packet.terminal -> {
            currentState = FastbootLaneState.IDLE
            FastbootExchange.Completed(packet.reply, packet.payload, info.toList())
        }

        packet.reply == FastbootReply.DATA -> {
            currentState = FastbootLaneState.AWAITING_DATA
            FastbootExchange.DataPhase(packet.declaredSize(), packet.payload, info.toList())
        }

        // INFO, TEXT и непонятый кадр обмен не кончают. Непонятое копится
        // наравне с остальным: Legacy на нём пишет предупреждение и читает
        // дальше, и терять сказанное устройством мы не станем.
        else -> {
            info += packet.payload.ifBlank { packet.raw }
            null
        }
    }

    public companion object {
        /** Команда Fastboot передаётся одним кадром, и он не длиннее этого. */
        public const val MAX_COMMAND_BYTES: Int = 64

        /** Наибольший код символа, который несёт провод Fastboot. */
        internal const val MAX_ASCII: Int = 0x7F

        /**
         * Буфер приёма.
         *
         * С запасом: ответ `getvar:all` приходит многими кадрами `INFO`, и
         * каждый отдельный кадр невелик, но обрезать его нечем — короткий приём
         * объявленного ответа мы бы не отличили от полного.
         */
        public const val DEFAULT_READ_BUFFER: Int = 512

        /** Столько ждём **без единого кадра**, прежде чем считать обмен мёртвым. */
        public const val DEFAULT_INACTIVITY_MS: Long = 7_000

        /**
         * Размер блока передачи данных.
         *
         * 16 КиБ — то же, что у A2 в `SYNC_BULK` и у Legacy в
         * `bulkWriteFully`. Больший блок принадлежит нативному пути
         * (`usb:native`, 256 КиБ с конвейером), которого здесь нет.
         */
        public const val DATA_BLOCK_BYTES: Int = 16 * 1024

        /**
         * Блок **приёма** — 16 КиБ, из Legacy и по его же причине.
         *
         * Совпадение с [DATA_BLOCK_BYTES] случайное: тот взят из `SYNC_BULK` у
         * A2 и описывает отправку. Свести их в одну константу значило бы
         * связать два независимых решения и потерять обоснование каждого.
         */
        public const val DATA_IN_BLOCK_BYTES: Int = 16 * 1024

        /** Таймаут одного чтения. У Legacy столько же на `bulkTransfer` в `readRawDataTo`. */
        public const val DATA_READ_TIMEOUT_MS: Int = 10_000

        /**
         * Терпение на терминальный кадр после принятых байт.
         *
         * Две минуты, как у Legacy (`maxTotalTimeMs = 120_000`): устройство
         * успело отдать раздел и вправе думать перед ответом.
         */
        public const val DATA_IN_INACTIVITY_MS: Long = 120_000L

        /** Таймаут одной записи блока. Взят у A2: `SYNC_BULK_TIMEOUT_MS`. */
        internal const val DATA_WRITE_TIMEOUT_MS: Int = 10_000

        internal const val DEFAULT_WRITE_TIMEOUT_MS: Int = 7_000

        /**
         * Шаг ожидания.
         *
         * Дробим не ради точности, а чтобы отмена и подсчёт бездействия имели
         * место случиться: один приём на весь бюджет отдал бы управление только
         * в конце.
         */
        internal const val READ_SLICE_MS: Int = 900

        /**
         * Пауза после пустого чтения.
         *
         * Взята у Legacy (`GETVAR_READ_RETRY_DELAY_MS`) вместе с причиной:
         * пустое чтение возвращается мгновенно, и без паузы цикл крутится
         * вхолостую, изображая ожидание.
         */
        internal const val EMPTY_READ_PAUSE_MS: Long = 100L
    }
}
