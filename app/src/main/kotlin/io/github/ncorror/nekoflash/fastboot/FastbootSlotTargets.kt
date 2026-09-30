package io.github.ncorror.nekoflash.fastboot

import com.ncorror.nekoflash.pro.engine.CURRENT_SLOT
import com.ncorror.nekoflash.pro.engine.SET_ACTIVE_DEFAULT
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootGetVar
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootLane
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootLaneState
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootVariable

/** Чем кончилось уточнение имён разделов под `--slot`. */
internal sealed interface SlotResolution {
    data class Resolved(val commands: List<String>) : SlotResolution

    /** Уточнить нельзя; [detail] называет почему, и ничего не отправлено. */
    data class Failed(val detail: String) : SlotResolution
}

/**
 * Слоты так, как их понимает настольный `fastboot`: `verify_slot` и
 * `do_for_partitions` из `fastboot.cpp`.
 *
 * [resolve] получает `--slot` или [CURRENT_SLOT], если его нет:
 *
 * - `--slot` проверяется по `slot-count`: устройство без слотов, буква вне
 *   счёта и `other` без `current-slot` останавливают план до первой команды;
 * - `flash:`, `erase:`, `format:`, `fetch:` — раздел, у которого `has-slot`
 *   отвечает `yes`, получает суффикс слота, а без `--slot` — текущего слота;
 *   прочий идёт под своим именем. `all` — каждый слот из `slot-count`, и тогда
 *   `has-slot` обязан ответить;
 * - `set_active:СЛОТ` проверяется так же, и `all` в нём — слот `a`;
 *   [SET_ACTIVE_DEFAULT] — слот из `--slot`, а без него текущий; если текущий
 *   устройство не назвало, `set_active` не идёт вовсе, как у настольного клиента.
 *
 * Спрашивает [variable] — `getvar` по той же полосе, до отправки первой команды.
 */
internal object FastbootSlotTargets {
    fun resolve(commands: List<String>, slot: String, variable: (String) -> String?): SlotResolution = try {
        val device = SlotFacts(variable)
        val verified = if (slot == CURRENT_SLOT) CURRENT_SLOT else device.verify(slot, allowAll = true)
        val resolved = commands.flatMap { command -> device.resolve(command, verified) }
        // План из одного `--set-active` без текущего слота пуст: настольный
        // клиент молча ничего не делает, здесь это названо.
        if (resolved.isEmpty() && commands.isNotEmpty()) fail("current-slot is not reported: set_active is not sent")
        SlotResolution.Resolved(resolved)
    } catch (failure: SlotFailure) {
        SlotResolution.Failed(failure.detail)
    }

    private class SlotFacts(private val variable: (String) -> String?) {
        private val slotCount: Int by lazy { variable(SLOT_COUNT)?.trim()?.toIntOrNull() ?: 0 }
        private val current: String? by lazy { variable(CURRENT)?.removePrefix("_")?.ifEmpty { null } }
        private val hasSlot = mutableMapOf<String, String?>()

        fun verify(slot: String, allowAll: Boolean): String = when {
            slot == ALL && allowAll -> ALL
            slot == ALL -> if (slotCount > 0) FIRST else fail("No known slots")
            slotCount == 0 -> fail("Device does not support slots")
            slot == OTHER -> current?.let(::other) ?: fail("No known slots")
            slot.length == 1 && slot[0] - FIRST_LETTER in 0 until slotCount -> slot
            else -> fail("Slot $slot does not exist. supported slots are: ${letters().joinToString(" ")}")
        }

        fun resolve(command: String, slot: String): List<String> {
            val verb = PARTITION_VERBS.firstOrNull { command.startsWith(it) }
            return when {
                verb != null -> partitions(verb, command.removePrefix(verb), slot).map { verb + it }
                command == SET_ACTIVE_DEFAULT -> activeDefault(slot)
                command.startsWith(SET_ACTIVE_DEFAULT) ->
                    listOf(SET_ACTIVE_DEFAULT + verify(command.removePrefix(SET_ACTIVE_DEFAULT), allowAll = false))
                else -> listOf(command)
            }
        }

        private fun partitions(verb: String, partition: String, slot: String): List<String> {
            val answer = hasSlot.getOrPut(partition) { variable(HAS_SLOT + partition) }
            return when {
                slot == ALL && answer == null -> fail("Could not check if partition $partition has slot all")
                slot == ALL && answer == YES -> letters().map { "${partition}_$it" }
                    .ifEmpty { fail("${verb}$partition: has-slot yes, slot-count 0") }
                answer != YES -> listOf(partition)
                slot == CURRENT_SLOT -> listOf("${partition}_${current ?: fail("Failed to identify current slot")}")
                else -> listOf("${partition}_$slot")
            }
        }

        private fun activeDefault(slot: String): List<String> = when {
            slot != CURRENT_SLOT -> listOf(SET_ACTIVE_DEFAULT + verify(slot, allowAll = false))
            else -> listOfNotNull(current?.let { SET_ACTIVE_DEFAULT + verify(it, allowAll = false) })
        }

        private fun other(current: String): String =
            ((current[0] - FIRST_LETTER + 1).mod(slotCount) + FIRST_LETTER.code).toChar().toString()

        private fun letters(): List<String> = (0 until slotCount).map { (FIRST_LETTER + it).toString() }
    }

    private class SlotFailure(val detail: String) : RuntimeException(detail)

    private fun fail(detail: String): Nothing = throw SlotFailure(detail)

    private val PARTITION_VERBS = listOf("flash:", "erase:", "format:", "fetch:")

    private const val HAS_SLOT = "has-slot:"
    private const val SLOT_COUNT = "slot-count"
    private const val CURRENT = "current-slot"
    private const val YES = "yes"
    private const val ALL = "all"
    private const val OTHER = "other"
    private const val FIRST = "a"
    private const val FIRST_LETTER = 'a'
}

/**
 * Команды плана под [slot]; `null` — план кнопок и сырой консоли: команды
 * уходят как есть, без единого `getvar`.
 */
internal fun slotCommands(commands: List<String>, slot: String?, lane: FastbootLane): SlotResolution =
    if (slot == null) {
        SlotResolution.Resolved(commands)
    } else {
        val variables = FastbootGetVar(lane)
        FastbootSlotTargets.resolve(commands, slot) { name ->
            (variables.read(name) as? FastbootVariable.Present)?.value
        }
    }

/** План, остановленный до первой команды: слот не уточнился, ничего не отправлено. */
internal fun notResolved(
    commands: List<String>,
    failed: SlotResolution.Failed,
    lane: FastbootLaneState,
): FastbootConsoleState = FastbootConsoleState.Planned(
    commands = commands,
    applied = 0,
    stoppedAt = 0,
    detail = failed.detail,
    lane = lane,
)
