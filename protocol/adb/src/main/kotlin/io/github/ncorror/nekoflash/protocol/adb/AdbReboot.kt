package io.github.ncorror.nekoflash.protocol.adb

import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticEvent
import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticSink
import java.time.Clock
import java.time.Instant
import java.util.Locale

/** Почему перезагрузка не подтвердилась. */
public enum class AdbRebootFailure {
    /** Отправить запрос не удалось. */
    SEND_FAILED,

    /** Интерфейс не удерживается — запрос не ушёл. */
    TRANSPORT_CLOSED,

    /**
     * Устройство ответило текстом.
     *
     * Раньше это называлось отказом, и это было **неправдой**. Прогон `07`
     * §6.46: recovery ответила словом `reboot.` и через 336 мс ушла в
     * перезагрузку. Текст устройства — это его слова, а не вердикт; что он
     * значит, знает устройство, и переводить его в «отказал» значит
     * приписывать ему решение, которого он не выносил.
     */
    DEVICE_ANSWERED,

    /** Кадр потерян: соединение недостоверно, и переклеивать это в «ожидаемый разрыв» нельзя. */
    FRAMING_LOST,

    /** Ящик потока переполнился. */
    MAILBOX_OVERFLOWED,

    /** Запрос ушёл, но признаков перехода за отведённое время не появилось. */
    NO_TRANSITION,
}

/**
 * Что стало с устройством.
 *
 * Отдельно от исхода по той же причине, что и `AdbSyncDestination` у записи
 * файла: оператору важно не только «получилось или нет», но и «тронули ли мы
 * устройство». Ответ на второй вопрос решает, можно ли повторять. Имя говорит
 * о том, **чьё** это состояние: состояние самого запроса живёт в UI и зовётся
 * иначе.
 */
public enum class AdbRebootDevice {
    /** Запрос в провод не ушёл: устройство не тронуто, повтор безопасен. */
    UNTOUCHED,

    /** Устройство приняло запрос и начало переход. */
    TRANSITIONING,

    /** Запрос ушёл, а подтверждения перехода нет. Повторять нельзя. */
    UNKNOWN,
}

/** Исход запроса перезагрузки. */
public sealed interface AdbRebootOutcome {
    /** Устройство приняло команду и начало переход. */
    public data class Accepted(val service: String, val evidence: String) : AdbRebootOutcome

    /** Перехода не подтвердилось. */
    public data class Failed(
        val service: String,
        val reason: AdbRebootFailure,
        val detail: String,
        val device: AdbRebootDevice,
    ) : AdbRebootOutcome
}

/**
 * Имя сервиса перезагрузки.
 *
 * Пустая цель и `system` означают обычную перезагрузку, и адресуется она пустым
 * хвостом — так же, как в Legacy (`AdbServiceCompletionPolicy.normalizeRebootService`).
 *
 * **Регистр цели не трогается, и это расхождение с Legacy намеренное.** Legacy
 * приводил цель к нижнему регистру; здесь этого нет. Список целей открыт —
 * `bootloader`, `recovery`, `sideload`, вендорские вроде `edl`, — и какие из
 * них чувствительны к регистру, знает устройство, а не хост. Молча менять то,
 * что набрал оператор, значило бы выполнять не ту команду, которую просили.
 * Распознавание `system` от регистра при этом не зависит: там сравнивается
 * известное слово, а не передаётся произвольное.
 */
internal object AdbRebootService {
    fun of(target: String): String {
        val trimmed = target.trim()
        return if (trimmed.isEmpty() || trimmed.lowercase(Locale.US) == SYSTEM) PREFIX else PREFIX + trimmed
    }

    const val PREFIX: String = "reboot:"
    private const val SYSTEM = "system"
}

/**
 * Какие сервисы ведут себя односторонне.
 *
 * Решает **имя сервиса**, а не нажатая кнопка, и это прямо из Legacy
 * (`AdbServiceCompletionPolicy.expectsOneWayDisconnect`). Оператор может
 * набрать `reboot:bootloader` в поле произвольного сервиса, и обойтись с этим
 * надо так же, как с нажатием «Перезагрузить»: иначе ожидаемый разрыв будет
 * показан как отказ, то есть экран соврёт.
 *
 * Послабление ограничено `reboot:*` намеренно и никогда не распространяется на
 * `shell`, `sync`, `install` и произвольные сервисы: для них разрыв — это
 * именно разрыв.
 */
public object AdbServicePolicy {
    /** Ждать ли от сервиса того, что устройство уйдёт с шины, не ответив. */
    public fun expectsOneWayDisconnect(service: String): Boolean =
        service.trim().startsWith(AdbRebootService.PREFIX, ignoreCase = true)

    /**
     * Цель перезагрузки, спрятанная в имени сервиса.
     *
     * `reboot:bootloader` — это цель `bootloader`; `reboot:` — обычная
     * перезагрузка. Нужна, чтобы набранное в поле сервиса можно было отдать
     * тому же коду, что и набранное в поле цели.
     */
    public fun rebootTargetOf(service: String): String {
        val trimmed = service.trim()
        // Префикс снимается ровно по длине: сравнение выше было без учёта
        // регистра, и `removePrefix` здесь ошибся бы на `REBOOT:`.
        return if (expectsOneWayDisconnect(trimmed)) {
            trimmed.substring(AdbRebootService.PREFIX.length)
        } else {
            trimmed
        }
    }
}

/**
 * Как узнать, что транспорта больше нет.
 *
 * Два источника, и порядок между ними существенный. Цикл раскладки знает
 * только те беды, которые принёс **сам**: обрыв чтения и потерю кадра. Но в
 * production об отпадении устройства первым узнаёт USB-слой — он закрывает
 * сессию, владелец закрывает соединение, и цикл кончается по просьбе, а не по
 * ошибке чтения. Его поле в этом случае пусто.
 *
 * Поэтому спрашивать надо **у транспорта**: интерфейс больше не удерживается —
 * значит устройства нет. Прогон `07` §6.48 показал это дословно: устройство
 * ушло через 306 мс, а признака ухода мы не увидели, потому что смотрели не
 * туда.
 */
internal object AdbTransportEnd {
    fun of(loopEnd: AdbMailboxEnd?, held: Boolean): AdbMailboxEnd? =
        loopEnd ?: if (held) null else AdbMailboxEnd.TRANSPORT_CLOSED
}

/**
 * Перезагрузка устройства через сервис `reboot:`.
 *
 * **Это односторонний сервис, и в этом вся его особенность.** Устройство
 * обычно разрывает USB раньше, чем успевает ответить `OKAY`/`CLSE`. Разрыв
 * здесь — не отказ, а как раз то, чего мы добивались. Поведение открыто в
 * Legacy `AdbServiceCompletionPolicy` перед реализацией, а не восстановлено по
 * памяти: там же записано, что послабление действует **только** для `reboot:*`
 * и никогда для `shell`, `sync`, `install` и произвольных сервисов.
 *
 * **Граница мутации — записанный целиком пакет `OPEN`** (`03` §3). До неё
 * устройство не тронуто и повтор безопасен; после — уже нет, и ответ об этом
 * говорит прямо через [AdbRebootDevice].
 *
 * **Расхождение с Legacy, сделанное сознательно.** Legacy считал ожидаемым
 * завершением и молчание устройства: у него не было способа отличить «peer
 * ушёл с шины» от «peer молчит, а транспорт жив». Здесь такой способ есть —
 * ящик потока приносит конец с причиной, — и поэтому молчание при живом
 * транспорте становится [AdbRebootFailure.NO_TRANSITION] с состоянием
 * `UNKNOWN`, а не успехом. Сообщить «перезагружается», не увидев ни одного
 * признака перехода, значило бы выдать надежду за наблюдение.
 *
 * Испорченный кадр не переклеивается в ожидаемый разрыв ни при каких условиях —
 * это прямое требование Legacy, и оно здесь сохранено.
 *
 * **`UNTOUCHED` возможно только до границы.** Ответить «устройство не тронуто»
 * можно ровно в двух случаях: пакет `OPEN` не ушёл вовсе или не ушло ни одного
 * его байта. Всё, что случилось после, оставляет состояние неизвестным — даже
 * если устройство ответило словами. Правило было записано здесь с самого
 * начала, но нарушено в ветке ответа текстом; `07` §6.46 показал, чем это
 * кончается на живом устройстве.
 */
public class AdbReboot(
    private val writer: AdbPacketWriter,
    private val dispatcher: AdbStreamDispatcher,
    /**
     * Чем кончился транспорт. `null` — жив.
     *
     * Спрашивается уже после конца своего потока, когда ящик ничего нового не
     * расскажет: он хранит первую причину. По умолчанию — «жив всегда», и
     * тогда ответ текстом остаётся `UNKNOWN`, как было до `07` §6.47.
     */
    private val transportEnd: () -> AdbMailboxEnd? = { null },
    /**
     * Сколько ждать ухода с шины после ответа словами.
     *
     * Вынесено в параметр, а не оставлено константой, по той же причине, что и
     * таймаут вызова: тест не должен платить за него настоящими секундами.
     * Значение по умолчанию выбрано по измеренной паузе — см. [ANSWER_GRACE_MS].
     */
    private val answerGraceMillis: Long = ANSWER_GRACE_MS,
    private val diagnostics: DiagnosticSink = DiagnosticSink { },
    private val clock: () -> Instant = { Clock.systemUTC().instant() },
    private val elapsedNanos: () -> Long = { System.nanoTime() },
) {
    /**
     * Просит устройство перезагрузиться.
     *
     * @param target цель: пусто или `system` — обычная перезагрузка, иначе
     * произвольная строка вроде `bootloader` или `recovery`. Список не
     * ограничивается: какие цели существуют, знает устройство (`01` §3).
     */
    public fun reboot(target: String, timeoutMillis: Int = DEFAULT_TIMEOUT_MS): AdbRebootOutcome {
        require(timeoutMillis > 0) { "Reboot timeout must be positive: $timeoutMillis" }

        val service = AdbRebootService.of(target)
        val (mailbox, open) = dispatcher.open(service)
        emit("reboot_requested", mapOf("service" to service, "stream" to mailbox.localId.toString()))

        return when (val sent = writer.write(open.command, open.arg0, open.arg1, open.payload)) {
            AdbWriteOutcome.Sent -> awaitTransition(service, mailbox, timeoutMillis)

            // Интерфейс не удерживается: запрос не ушёл, устройство не тронуто.
            AdbWriteOutcome.Closed ->
                failed(service, AdbRebootFailure.TRANSPORT_CLOSED, "open", AdbRebootDevice.UNTOUCHED)

            is AdbWriteOutcome.Interrupted -> interrupted(service, sent)
        }
    }

    /**
     * Оборванная отправка запроса.
     *
     * Ноль отправленных байт — устройство запроса не видело. Всё остальное
     * консервативно считается `UNKNOWN`, как и у записи файла: неполный кадр
     * устройство исполнить не может, но утверждать это как факт мы не в
     * состоянии, а цена ошибки здесь — сказать «не тронули» про устройство,
     * которое уже уходит в перезагрузку.
     */
    private fun interrupted(service: String, outcome: AdbWriteOutcome.Interrupted): AdbRebootOutcome {
        val state = if (outcome.sentBytes == 0) AdbRebootDevice.UNTOUCHED else AdbRebootDevice.UNKNOWN
        return failed(
            service,
            AdbRebootFailure.SEND_FAILED,
            "${outcome.detail} (sent=${outcome.sentBytes})",
            state,
        )
    }

    /**
     * Ждёт признака перехода.
     *
     * Признак — конец потока: устройство закрыло его само или транспорт
     * кончился. Текст от устройства признаком **не** является: `reboot:` в
     * норме молчит, а отвечает словами тогда, когда отказывает.
     */
    private fun awaitTransition(
        service: String,
        mailbox: AdbStreamMailbox,
        timeoutMillis: Int,
    ): AdbRebootOutcome {
        val deadline = elapsedNanos() + timeoutMillis * NANOS_PER_MILLI
        val said = StringBuilder()
        var outcome: AdbRebootOutcome? = null
        while (outcome == null) {
            val remaining = (deadline - elapsedNanos()) / NANOS_PER_MILLI
            if (remaining <= 0) {
                outcome = failed(
                    service,
                    AdbRebootFailure.NO_TRANSITION,
                    "no end of stream in ${timeoutMillis}ms${saidSuffix(said)}",
                    AdbRebootDevice.UNKNOWN,
                )
            } else {
                outcome = consume(service, mailbox.poll(remaining.coerceAtMost(SLICE_MS)), said)
            }
        }
        return outcome
    }

    /** `null` — ждём дальше. */
    private fun consume(
        service: String,
        item: AdbMailboxItem?,
        said: StringBuilder,
    ): AdbRebootOutcome? = when (item) {
        null -> null

        // Устройство подтвердило поток. Само по себе это ещё не переход.
        is AdbMailboxItem.Opened -> null

        is AdbMailboxItem.Data -> {
            said.append(item.payload.toString(Charsets.UTF_8))
            null
        }

        is AdbMailboxItem.Ended -> ended(service, item, said)

        // Подтверждений наших записей этот потребитель не просил:
        // они приходят только в ящик, открытый с ними.
        is AdbMailboxItem.Acknowledged -> null
    }

    private fun ended(
        service: String,
        item: AdbMailboxItem.Ended,
        said: StringBuilder,
    ): AdbRebootOutcome = when (item.reason) {
        // Устройство закрыло поток или ушло с шины — то и другое означает, что
        // оно приняло команду. Но если оно при этом сказало словами, значит
        // отказало: `reboot:` в норме молчит. Слово устройства весомее
        // молчаливого закрытия — это class B из `03` §2.
        AdbMailboxEnd.COMPLETED, AdbMailboxEnd.REJECTED, AdbMailboxEnd.TRANSPORT_CLOSED ->
            if (said.isEmpty()) {
                accepted(service, item.detail)
            } else {
                answered(service, said.toString().trim())
            }

        AdbMailboxEnd.FRAMING_LOST -> failed(
            service,
            AdbRebootFailure.FRAMING_LOST,
            item.detail,
            AdbRebootDevice.UNKNOWN,
        )

        AdbMailboxEnd.OVERFLOWED -> failed(
            service,
            AdbRebootFailure.MAILBOX_OVERFLOWED,
            item.detail,
            AdbRebootDevice.UNKNOWN,
        )

        // Своей рукой этот поток никто не закрывает: закрывать нечего, ответа
        // не ждут. Если это случилось, честнее сказать «не знаю».
        AdbMailboxEnd.LOCAL -> failed(
            service,
            AdbRebootFailure.NO_TRANSITION,
            item.detail,
            AdbRebootDevice.UNKNOWN,
        )
    }

    /**
     * Устройство ответило словами. Что дальше — решает шина, а не текст.
     *
     * Толковать сказанное мы не беремся: `reboot.` и `not permitted` для нас
     * одинаково непрозрачны. Зато видно наблюдаемое — уйдёт устройство с шины
     * или останется. Ушло: перешло, и слова переносятся в evidence целиком.
     * Осталось: состояние **неизвестно**, потому что граница мутации позади
     * (`03` §3), и доказать нетронутость нечем.
     *
     * Ожидание добавляется к общему таймауту, а не отнимается от него: цена —
     * несколько секунд, выигрыш — верный ответ вместо «не знаю» (`07` §6.47).
     */
    private fun answered(service: String, said: String): AdbRebootOutcome {
        val deadline = elapsedNanos() + answerGraceMillis * NANOS_PER_MILLI
        var end = transportEnd()
        while (end == null && elapsedNanos() < deadline) {
            Thread.sleep(POLL_MS)
            end = transportEnd()
        }
        return when (end) {
            AdbMailboxEnd.TRANSPORT_CLOSED ->
                accepted(service, "device said: $said; then left the bus")

            // Потеря кадра ожидаемым разрывом не становится ни при каких
            // условиях — прямое требование Legacy, сохранённое и здесь.
            AdbMailboxEnd.FRAMING_LOST ->
                failed(service, AdbRebootFailure.FRAMING_LOST, said, AdbRebootDevice.UNKNOWN)

            else ->
                failed(service, AdbRebootFailure.DEVICE_ANSWERED, said, AdbRebootDevice.UNKNOWN)
        }
    }

    private fun accepted(service: String, evidence: String): AdbRebootOutcome {
        emit("reboot_accepted", mapOf("service" to service, "evidence" to evidence))
        return AdbRebootOutcome.Accepted(service, evidence)
    }

    private fun failed(
        service: String,
        reason: AdbRebootFailure,
        detail: String,
        device: AdbRebootDevice,
    ): AdbRebootOutcome.Failed {
        emit(
            "reboot_failed",
            mapOf(
                "service" to service,
                "reason" to reason.name,
                "device" to device.name,
                "detail" to detail,
            ),
        )
        return AdbRebootOutcome.Failed(service, reason, detail, device)
    }

    private fun saidSuffix(said: StringBuilder): String =
        if (said.isEmpty()) "" else "; device said: ${said.toString().trim()}"

    private fun emit(message: String, fields: Map<String, String>) {
        diagnostics.emit(
            DiagnosticEvent(
                timestamp = clock(),
                category = AdbHandshake.DIAGNOSTIC_CATEGORY,
                message = message,
                fields = fields,
            ),
        )
    }

    public companion object {
        /**
         * Сколько ждать признака перехода.
         *
         * Legacy давал на чтение заголовка после `reboot:` три секунды и держал
         * пятнадцатисекундное окно «ожидаемого разрыва». Пять секунд лежат
         * между этими числами: устройству хватает, чтобы уйти с шины, а
         * оператор не ждёт впустую, если переход не начался.
         */
        public const val DEFAULT_TIMEOUT_MS: Int = 5_000

        /** Ожидание режется, чтобы дедлайн проверялся, даже когда устройство молчит. */
        private const val SLICE_MS = 250L

        /**
         * Сколько ждать ухода с шины после ответа словами.
         *
         * В §6.46 recovery ушла через 336 мс. Две секунды дают запас почти в
         * шесть раз и не заставляют оператора ждать заметно дольше. Значение
         * выбрано по **измеренной** паузе, и если прогон §6.47 покажет
         * большую — менять надо по новому числу, а не подбором.
         */
        public const val ANSWER_GRACE_MS: Long = 2_000L

        /** Шаг опроса шины: спрашивать чаще незачем, реже — терять точность. */
        private const val POLL_MS = 25L

        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
