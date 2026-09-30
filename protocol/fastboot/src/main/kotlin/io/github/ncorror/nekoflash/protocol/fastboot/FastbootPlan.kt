package io.github.ncorror.nekoflash.protocol.fastboot

/** Чем кончился план. */
public sealed interface FastbootPlanOutcome {
    /** Шаги, которые устройство приняло, по порядку. Пуст, если не принят ни один. */
    public val applied: List<FastbootMutationOutcome.Applied>

    /** Все шаги приняты устройством. */
    public data class Completed(
        override val applied: List<FastbootMutationOutcome.Applied>,
    ) : FastbootPlanOutcome

    /**
     * План остановлен на шаге, который не был принят.
     *
     * **Устройство осталось между «до» и «после»**, и это главное, что здесь
     * надо сказать: часть шагов выполнена, остальные — нет. Показать такой исход
     * как неудачу целиком значило бы скрыть сделанное, а как успех — скрыть
     * несделанное.
     */
    public data class Stopped(
        override val applied: List<FastbootMutationOutcome.Applied>,
        val index: Int,
        val command: String,
        val outcome: FastbootMutationOutcome,
        val total: Int,
    ) : FastbootPlanOutcome {
        /** Сколько шагов не выполнено, включая оборвавшийся. */
        public val remaining: Int get() = total - index
    }
}

/**
 * Последовательность команд как одно действие оператора.
 *
 * **План — это предложение, а не полномочие.** Он не добавляет ни одной
 * возможности: каждый шаг уходит тем же `FastbootMutation`, что и набранная
 * руками команда, и решение по каждому принимает устройство. Смысл плана в
 * другом — в том, чтобы оператор увидел **весь** список до нажатия
 * ([commands]), а не узнавал его по ходу. `01` §3 требует содержательного
 * preflight, и вот он.
 *
 * **План останавливается на первом же шаге, который не принят.** Продолжать
 * после `Unknown` значило бы складывать неизвестность с неизвестностью: если не
 * известно, что стало с разделом, то неизвестно и с чем работает следующий шаг.
 * После `Refused` продолжение тоже неверно — отказ обычно означает, что
 * предпосылки шага не выполнены.
 *
 * **Отката нет и не будет.** Отменить `erase:` нечем, и делать вид, что план
 * транзакционен, было бы худшей из возможных неправд: оператор положился бы на
 * несуществующее свойство именно там, где цена ошибки — устройство.
 */
public class FastbootPlan(
    /** Команды по порядку, как они уйдут на устройство. */
    public val commands: List<String>,
) {
    /** Класс состояния для каждого шага — для предпросмотра, не для запрета. */
    public val classes: List<FastbootMutationClass> get() = commands.map { FastbootMutation.classify(it) }

    /** Меняет ли план что-нибудь. Ложь здесь была бы опаснее всего. */
    public val destructive: Boolean
        get() = classes.any { it != FastbootMutationClass.NONE && it != FastbootMutationClass.REBOOT }

    /**
     * Выполняет шаги по порядку, останавливаясь на первом непринятом.
     *
     * Возвращает **и** сделанное, **и** место остановки: между «до» и «после»
     * устройство оказывается ровно здесь, и знать об этом должен оператор, а не
     * только журнал.
     */
    public fun run(
        mutation: FastbootMutation,
        inactivityMillis: Long? = null,
    ): FastbootPlanOutcome {
        val applied = mutableListOf<FastbootMutationOutcome.Applied>()
        var stopped: FastbootPlanOutcome.Stopped? = null

        commands.forEachIndexed { index, command ->
            if (stopped == null) {
                val patience = inactivityMillis ?: FastbootMutation.patienceFor(command)
                when (val outcome = mutation.run(command, patience)) {
                    is FastbootMutationOutcome.Applied -> applied += outcome
                    else -> stopped = FastbootPlanOutcome.Stopped(
                        applied = applied.toList(),
                        index = index,
                        command = command,
                        outcome = outcome,
                        total = commands.size,
                    )
                }
            }
        }

        return stopped ?: FastbootPlanOutcome.Completed(applied.toList())
    }
}

/**
 * Готовые планы.
 *
 * Их немного и они простые намеренно: план, который прячет от оператора половину
 * своих шагов, полезнее не становится — он становится опаснее, потому что
 * ответственность за них остаётся на операторе, а знание уходит в код.
 */
public object FastbootPlans {
    /**
     * Записать буфер в раздел и перезагрузиться.
     *
     * **Буфер должен быть уже наполнен.** `flash:` пишет то, что лежит в буфере
     * загрузки; кладёт туда содержимое `download:`, и это отдельный обмен с
     * фазой данных, который в план плоских команд не входит. Так устроен сам
     * протокол, и Legacy делает ровно эти два шага порознь (строки 921–972).
     *
     * Отсюда единственное, что план обязан сказать оператору прямо: он запишет
     * **то, что там уже есть**, а не то, что оператор выбрал минуту назад в
     * другом месте.
     */
    public fun flashBuffer(partition: String, slot: String? = null, reboot: Boolean = true): FastbootPlan {
        val target = FastbootSlots.apply(partition, slot)
        val steps = mutableListOf(FastbootCommands.flash(target))
        if (reboot) steps += FastbootCommands.reboot()
        return FastbootPlan(steps)
    }

    /** Стереть раздел и перезагрузиться. */
    public fun eraseAndReboot(partition: String, slot: String? = null): FastbootPlan =
        FastbootPlan(listOf(FastbootCommands.erase(FastbootSlots.apply(partition, slot)), FastbootCommands.reboot()))

    /**
     * Переключить слот и перезагрузиться.
     *
     * `set_active:` сам по себе переключением не является — устройство может
     * ответить `OKAY` и не сделать, — поэтому шаг проверяется перечитыванием
     * `current-slot` внутри `FastbootMutation`, и план на этом остановится, если
     * подтверждения не будет.
     */
    public fun switchSlot(slot: String): FastbootPlan =
        FastbootPlan(listOf(FastbootCommands.setActive(slot), FastbootCommands.reboot()))
}
