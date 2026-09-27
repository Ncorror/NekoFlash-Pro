package io.github.ncorror.nekoflash.protocol.fastboot

/**
 * Что команда меняет в устройстве, если устройство её выполнит.
 *
 * **Это классификация для отчёта, а не фильтр.** Ни одно значение здесь ничего
 * не запрещает и ничего не требует: решение о допустимости принимает загрузчик
 * (`01` §3, `03` §5.1). Классификация нужна ровно затем, чтобы про **исход**
 * можно было сказать правду. Оборванный `flash:` оставляет раздел в неизвестном
 * состоянии, оборванный `getvar` — нет, и сложить их в один исход значило бы
 * потерять ту самую разницу, ради которой написан `03` §3.
 *
 * Legacy устроен так же и говорит это прямо в заголовке `FastbootProtocol`:
 * «Mutation-команды отправляются напрямую устройству; решение о разрешённости
 * flash/erase/oem/flashing принимает bootloader/fastbootd, а не UI-политика».
 * Широкая хостовая политика авторизации пришла не оттуда, а из A2
 * (`FastbootFlashPolicy`), и её мы не переносим — `07` §6.74.
 */
public enum class FastbootMutationClass {
    /** Ничего не меняет: опрос, чтение переменной. */
    NONE,

    /** Меняет содержимое раздела. Оборванный обмен оставляет раздел неизвестным. */
    PARTITION,

    /** Меняет, с какого слота устройство загрузится. `OKAY` доказательством не является. */
    SLOT,

    /** Грузит из буфера загрузки, ничего не записывая. */
    BOOT,

    /** Уводит устройство из текущей роли: ответа может не быть вовсе, и это норма. */
    REBOOT,

    /**
     * Меняет состояние замка загрузчика — и вместе с ним, как правило, стирает
     * устройство целиком.
     *
     * Отдельный класс не ради строгости: оборванная смена замка оставляет
     * неизвестным **и** замок, **и** пользовательские данные, то есть это
     * худший `Unknown` из всех, что бывают у Fastboot. Сложить его с записью в
     * раздел значило бы сказать оператору меньше, чем мы знаем.
     *
     * Сюда попадает только стандартное семейство `flashing` из AOSP, и только
     * его меняющие члены. Ни Legacy, ни A2 этих команд не знают вовсе — Legacy
     * умеет `oem unlock` от Xiaomi, и всё, — так что это наше решение, а не
     * перенос, и помечено как наше.
     */
    LOCK,

    /**
     * Меняет **разметку** super, а не содержимое раздела.
     *
     * Отдельный класс потому, что правда про исход тут другая: оборванный
     * `resize-logical-partition:` оставляет неизвестной таблицу разделов, а не
     * байты внутри одного из них. Сказать «раздел мог остаться записанным
     * наполовину» было бы не строже, а просто неверно.
     *
     * Четыре имени взяты из Legacy `isLogicalPartitionManagementCommand`.
     */
    SUPER,
}

/** Чем кончилась команда, способная изменить устройство. */
public sealed interface FastbootMutationOutcome {
    /** Команда, о которой идёт речь, как она ушла на устройство. */
    public val command: String

    /**
     * Устройство ответило `OKAY`.
     *
     * [confirmation] заполнено там, где `OKAY` сам по себе доказательством не
     * является и наблюдаемое состояние перечитано отдельно, — сейчас это только
     * слоты.
     */
    public data class Applied(
        override val command: String,
        val mutation: FastbootMutationClass,
        val payload: String,
        val info: List<String>,
        val confirmation: String? = null,
    ) : FastbootMutationOutcome

    /**
     * Устройство ответило `FAIL`.
     *
     * Это **слово устройства о том, что команда не выполнена**, и оно ценно
     * именно как слово. Доказательством целости раздела оно не является:
     * известно, что сказало устройство, а не что оно успело сделать до отказа.
     * Тем это отличается от `download:`, где отказ до фазы данных означает, что
     * с хоста не ушло ни одного байта, и «не тронут» можно утверждать.
     */
    public data class Refused(
        override val command: String,
        val mutation: FastbootMutationClass,
        val detail: String,
    ) : FastbootMutationOutcome

    /**
     * `OKAY` пришёл, а наблюдаемое состояние с ним не согласилось.
     *
     * Взято из Legacy: после `set_active:` он перечитывает `getvar:current-slot`
     * и на расхождении пишет «set_active not confirmed: requested=…,
     * current-slot=…» (`FastbootProtocol.verifyTerminalMutation`). Считать такой
     * `OKAY` успехом значило бы объявить слот переключённым по чужому обещанию.
     */
    public data class Unconfirmed(
        override val command: String,
        val mutation: FastbootMutationClass,
        val expected: String,
        val observed: String,
        val detail: String,
    ) : FastbootMutationOutcome

    /**
     * Устройство ушло, не ответив, — и для перезагрузки это норма.
     *
     * Отличать этот исход от [Unknown] обязательно: там мы не знаем, что стало
     * с разделом, здесь мы попросили устройство уйти, и оно ушло. Ни один архив
     * их не различает — Legacy на таком молчании помечает сессию `BROKEN`, —
     * так что это наше решение, а не перенос.
     */
    public data class Departed(
        override val command: String,
        val waitedMillis: Long,
        val info: List<String>,
    ) : FastbootMutationOutcome

    /**
     * Что стало с устройством — неизвестно.
     *
     * `Unknown` из `03` §3, и для [FastbootMutationClass.PARTITION] он означает
     * ровно то, что страшно: раздел мог остаться записанным наполовину.
     * Показывать это как отказ или как «ничего не случилось» нельзя.
     */
    public data class Unknown(
        override val command: String,
        val mutation: FastbootMutationClass,
        val detail: String,
    ) : FastbootMutationOutcome

    /** Обмен не начался: команда не ушла или полоса занята. Устройство её не видело. */
    public data class NotStarted(
        override val command: String,
        val detail: String,
    ) : FastbootMutationOutcome
}

/**
 * Команды, способные изменить устройство.
 *
 * **Отдельного транспорта здесь нет и не должно быть.** Мутирующая команда —
 * обычная команда на той же единственной синхронной полосе (ADR-0006 §1); так
 * же устроен и Legacy, где `flash:` и `set_active:` уходят через общий
 * `sendCommand`. Этот класс добавляет к полосе не путь, а **чтение исхода**:
 * какой класс состояния устройства затронут и что про него можно утверждать.
 *
 * Три вещи взяты из архивов, а не выведены:
 *
 * 1. **`OKAY` на `set_active:` не доказывает переключение.** Legacy перечитывает
 *    `getvar:current-slot` и сравнивает канонично — сняв ведущее подчёркивание и
 *    регистр с обеих сторон.
 * 2. **Фаза данных на команде без нагрузки ломает рамку.** Legacy помечает
 *    сессию `BROKEN` со словами «Terminal Fastboot command entered DATA phase
 *    without a payload handler». Устройство осталось ждать байты, о которых мы
 *    не договаривались.
 * 3. **`flash:` идёт отдельной командой после `download:` и фазы данных**, а не
 *    несёт нагрузку сам (Legacy строки 921–972).
 *
 * Чего в архивах нет: различения «устройство ушло по нашей же просьбе» и
 * «устройство молчит неизвестно почему». Legacy на любом молчании ломает сессию.
 * Для `reboot` это неверно по существу, и [FastbootMutationOutcome.Departed] —
 * наше решение, помеченное как наше.
 */
public class FastbootMutation(
    private val lane: FastbootLane,
    private val variables: FastbootGetVar,
) {
    /**
     * Отправляет [command] и читает исход через границу мутации.
     *
     * Команда уходит как есть. Что бывает у Fastboot, знает устройство, и
     * незнакомая команда — это его `FAIL`, а не наш отказ.
     */
    public fun run(
        command: String,
        inactivityMillis: Long = patienceFor(command),
    ): FastbootMutationOutcome {
        val mutation = classify(command)
        return when (val exchange = lane.run(command, inactivityMillis)) {
            is FastbootExchange.Completed -> terminal(command, mutation, exchange)

            is FastbootExchange.TimedOut -> silence(command, mutation, exchange)

            // Устройство открыло фазу данных там, где нагрузки нет. Оно ждёт
            // байты, которых не будет, и следующая команда попадёт не туда.
            is FastbootExchange.DataPhase -> {
                lane.stall()
                FastbootMutationOutcome.Unknown(
                    command = command,
                    mutation = mutation,
                    detail = "устройство открыло фазу данных на команду без нагрузки",
                )
            }

            is FastbootExchange.NotReady ->
                FastbootMutationOutcome.NotStarted(command, "полоса занята: ${exchange.state}")

            is FastbootExchange.NotSent -> FastbootMutationOutcome.NotStarted(command, exchange.reason)
        }
    }

    private fun terminal(
        command: String,
        mutation: FastbootMutationClass,
        exchange: FastbootExchange.Completed,
    ): FastbootMutationOutcome = when {
        exchange.reply == FastbootReply.FAIL -> FastbootMutationOutcome.Refused(
            command = command,
            mutation = mutation,
            detail = exchange.payload.ifBlank { "без объяснения" },
        )

        mutation == FastbootMutationClass.SLOT -> confirmSlot(command, exchange)

        else -> FastbootMutationOutcome.Applied(
            command = command,
            mutation = mutation,
            payload = exchange.payload,
            info = exchange.info,
        )
    }

    /** Перечитывает `current-slot`: согласие устройства переключением ещё не является. */
    private fun confirmSlot(
        command: String,
        exchange: FastbootExchange.Completed,
    ): FastbootMutationOutcome {
        val requested = command.substringAfter(':', "").trim()
        val observed = variables.read(CURRENT_SLOT)
        val actual = (observed as? FastbootVariable.Present)?.value
        return when {
            FastbootSlots.same(requested, actual) -> FastbootMutationOutcome.Applied(
                command = command,
                mutation = FastbootMutationClass.SLOT,
                payload = exchange.payload,
                info = exchange.info,
                confirmation = "$CURRENT_SLOT=$actual",
            )

            else -> FastbootMutationOutcome.Unconfirmed(
                command = command,
                mutation = FastbootMutationClass.SLOT,
                expected = FastbootSlots.normalize(requested) ?: requested,
                observed = actual ?: UNKNOWN_SLOT,
                detail = "устройство ответило OKAY, а $CURRENT_SLOT говорит другое",
            )
        }
    }

    /**
     * Молчание после команды.
     *
     * Для перезагрузки это ожидаемый уход, для всего остального — незнание.
     * Полоса в обоих случаях уже помечена потерявшей рамку самой полосой: она не
     * знает, о какой команде шла речь, а мы знаем.
     */
    private fun silence(
        command: String,
        mutation: FastbootMutationClass,
        exchange: FastbootExchange.TimedOut,
    ): FastbootMutationOutcome = when (mutation) {
        FastbootMutationClass.REBOOT -> FastbootMutationOutcome.Departed(
            command = command,
            waitedMillis = exchange.waitedMillis,
            info = exchange.info,
        )

        else -> FastbootMutationOutcome.Unknown(
            command = command,
            mutation = mutation,
            detail = "ответа не было ${exchange.waitedMillis} мс",
        )
    }

    public companion object {
        /**
         * К какому классу состояния относится команда.
         *
         * Незнакомая команда — [FastbootMutationClass.NONE], и это не значит «она
         * ничего не меняет»: это значит, что мы не знаем, что она меняет.
         * Отказывать по этой причине нельзя — в поле консоли набирают в том числе
         * `oem`-команды, которых не знает никто, кроме конкретного загрузчика.
         *
         * **`oem` остаётся неклассифицированным намеренно.** Что делает
         * `oem <что-то>`, знает вендор, и на разных устройствах одно и то же
         * слово значит разное. Приписать классу догадку значило бы выдать наше
         * предположение за знание — ровно то, чего не делает и Legacy, где
         * `oem`-команды уходят обычным `sendCommand` без разбора смысла.
         *
         * Перечислены **только** меняющие члены семейства `flashing`.
         * `flashing get_unlock_ability` сюда не входит: это чтение, оно ничего
         * не меняет, и назвать его сменой замка было бы неправдой.
         */
        public fun classify(command: String): FastbootMutationClass {
            val clean = command.trim().lowercase()
            return when {
                PARTITION_PREFIXES.any { clean.startsWith(it) } -> FastbootMutationClass.PARTITION
                SLOT_PREFIXES.any { clean.startsWith(it) } -> FastbootMutationClass.SLOT
                clean in LOCK_COMMANDS -> FastbootMutationClass.LOCK
                FastbootLogicalPartitions.manages(clean) -> FastbootMutationClass.SUPER
                clean == BOOT -> FastbootMutationClass.BOOT
                clean == REBOOT || clean.startsWith("$REBOOT-") -> FastbootMutationClass.REBOOT
                else -> FastbootMutationClass.NONE
            }
        }

        /**
         * Терпение для команд, про которые архив прямо говорит «долго».
         *
         * Legacy даёт `oem get_token` 30 секунд против обычных пяти
         * (`FastbootProtocol`, `elapsedMs >= 30_000L`). Это **терпение, а не
         * разрешение**: ошибка в эту сторону — подождать дольше, и ни при каких
         * условиях не отказать. Бюджет считается по бездействию, поэтому
         * болтливая команда с потоком `INFO` мёртвой не выглядит.
         */
        public const val VENDOR_INACTIVITY_MS: Long = 30_000L

        /**
         * Сколько ждать команду, если вызывающий не сказал иначе.
         *
         * Различаются здесь не права, а ожидания: `oem` и `flashing` у
         * вендоров медленные, и мерить их общей меркой значило бы объявлять
         * мёртвым то, что просто думает.
         */
        public fun patienceFor(command: String): Long {
            val clean = command.trim().lowercase()
            return if (SLOW_PREFIXES.any { clean.startsWith(it) }) {
                VENDOR_INACTIVITY_MS
            } else {
                FastbootLane.DEFAULT_INACTIVITY_MS
            }
        }

        private val PARTITION_PREFIXES = listOf("flash:", "erase:", "format:")
        private val SLOW_PREFIXES = listOf("oem ", "oem:", "flashing ", "flashing:")

        /**
         * Меняющие члены семейства `flashing`, как их пишет AOSP.
         *
         * Список закрытый и короткий, потому что это **стандартные** команды с
         * известным действием. Он ничего не разрешает и не запрещает: набрать
         * можно что угодно, включая то, чего здесь нет, — он только называет
         * класс состояния для отчёта.
         */
        private val LOCK_COMMANDS = setOf(
            "flashing lock",
            "flashing unlock",
            "flashing lock_critical",
            "flashing unlock_critical",
        )
        private val SLOT_PREFIXES = listOf("set_active:", "set-active:")
        private const val BOOT = "boot"
        private const val REBOOT = "reboot"
        private const val CURRENT_SLOT = "current-slot"
        private const val UNKNOWN_SLOT = "неизвестно"
    }
}
