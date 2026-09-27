package io.github.ncorror.nekoflash.fastboot

import com.ncorror.nekoflash.pro.engine.SET_ACTIVE_OTHER
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootGetVar
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootLane
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootLaneState
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootSlots
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootVariable

/** Чем кончилось уточнение имён разделов под `--slot`. */
internal sealed interface SlotResolution {
    data class Resolved(val commands: List<String>) : SlotResolution

    /** Уточнить нельзя; [detail] называет почему, и ничего не отправлено. */
    data class Failed(val detail: String) : SlotResolution
}

/**
 * `fastboot --slot СЛОТ` так, как его понимает настольный клиент.
 *
 * Суффикс получает только раздел, у которого `has-slot:ИМЯ` = `yes`; раздел без
 * слотов идёт под своим именем, как и у настольного клиента, когда устройство
 * отвечает «нет» или не знает переменной. `other` — слот, противоположный
 * `current-slot`; `all` — оба слота. Спрашивает [variable] — `getvar` по той же
 * полосе, до отправки первой команды.
 */
internal object FastbootSlotTargets {
    fun resolve(commands: List<String>, slot: String?, variable: (String) -> String?): SlotResolution {
        val slots = slot?.let { slotsFor(it, variable) }
        val other = if (SET_ACTIVE_OTHER in commands) slotsFor(OTHER, variable)?.single() else null
        return when {
            slot != null && slots == null -> SlotResolution.Failed(noSlot(slot, variable))
            SET_ACTIVE_OTHER in commands && other == null -> SlotResolution.Failed(noSlot(OTHER, variable))
            else -> SlotResolution.Resolved(
                commands.flatMap { command ->
                    if (command == SET_ACTIVE_OTHER) listOf("set_active:$other") else slotted(command, slots, variable)
                },
            )
        }
    }

    private fun slotted(command: String, slots: List<String>?, variable: (String) -> String?): List<String> {
        val verb = PARTITION_VERBS.firstOrNull { command.startsWith(it) }
        val partition = verb?.let { command.removePrefix(it) }
        return if (slots == null || partition == null || variable("$HAS_SLOT$partition") != YES) {
            listOf(command)
        } else {
            slots.map { suffix -> verb + FastbootSlots.apply(partition, suffix) }
        }
    }

    private fun slotsFor(slot: String, variable: (String) -> String?): List<String>? = when (slot.lowercase()) {
        ALL -> listOf("a", "b")
        OTHER -> FastbootSlots.normalize(variable(CURRENT_SLOT))
            ?.let { current -> listOf(if (current == "a") "b" else "a") }
        else -> FastbootSlots.normalize(slot)?.let(::listOf)
    }

    private fun noSlot(slot: String, variable: (String) -> String?): String =
        if (slot.lowercase() == OTHER) {
            "--slot other: current-slot is ${variable(CURRENT_SLOT) ?: "not reported"}"
        } else {
            "--slot $slot: not a slot name"
        }

    private val PARTITION_VERBS = listOf("flash:", "erase:", "format:")

    private const val HAS_SLOT = "has-slot:"
    private const val CURRENT_SLOT = "current-slot"
    private const val YES = "yes"
    private const val ALL = "all"
    private const val OTHER = "other"
}

/** Команды плана под [slot]; без слота — как есть, без единого `getvar`. */
internal fun slotCommands(commands: List<String>, slot: String?, lane: FastbootLane): SlotResolution =
    if (slot == null && SET_ACTIVE_OTHER !in commands) {
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
