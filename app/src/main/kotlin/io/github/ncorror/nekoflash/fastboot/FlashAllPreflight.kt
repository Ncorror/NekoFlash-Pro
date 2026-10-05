package io.github.ncorror.nekoflash.fastboot

import com.ncorror.nekoflash.pro.engine.CURRENT_SLOT
import com.ncorror.nekoflash.pro.engine.SET_ACTIVE_DEFAULT
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootAndroidInfo
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootGetVar
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootRequirementCheck
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootVariable

/**
 * Что `flashall` выясняет до первой команды: слот (`verify_slot`), сведения
 * об устройстве (`DumpInfo`) и требования `android-info.txt`
 * (`CheckRequirements`, `HandlePartitionExists`). Строки — словами
 * настольного клиента, в [report].
 */
internal class FlashAllPreflight(
    private val current: () -> FastbootGetVar,
    private val report: MutableList<String>,
) {
    private val variables: FastbootGetVar get() = current()

    /** Разделы, которых требует `partition-exists`: их образ обязателен. */
    val required = mutableSetOf<String>()

    fun value(name: String): String? = (variables.read(name) as? FastbootVariable.Present)?.value

    /** `get_slot_count`: не назван или не число — `0`. */
    fun slotCount(): Int = value(SLOT_COUNT)?.trim()?.toIntOrNull() ?: 0

    /** `get_current_slot`: без ведущего `_`; не назван — пусто. */
    fun currentSlot(): String = value(CURRENT_SLOT_VAR)?.removePrefix("_").orEmpty()

    /** `verify_slot(slot, allow_all = true)`: до первой команды. */
    fun verifiedSlot(slot: String): String {
        if (slot == CURRENT_SLOT || slot == ALL) return slot
        return when (val resolved = FastbootSlotTargets.resolve(listOf(SET_ACTIVE_DEFAULT), slot, ::value)) {
            is SlotResolution.Failed -> throw FlashAllStop(resolved.detail)
            is SlotResolution.Resolved -> resolved.commands.single().removePrefix(SET_ACTIVE_DEFAULT)
        }
    }

    fun dumpInfo() {
        report += SEPARATOR
        DUMPED.forEach { (label, name) ->
            report += when (val variable = variables.read(name)) {
                is FastbootVariable.Present -> "$label: ${variable.value}"
                is FastbootVariable.Unsupported -> "getvar:$name FAILED (${variable.detail})"
                is FastbootVariable.Unavailable -> "getvar:$name FAILED (${variable.detail})"
            }
        }
        report += SEPARATOR
    }

    /** `CheckRequirements`: `partition-exists` — отдельно, как `HandlePartitionExists`. */
    fun checkRequirements(lines: Sequence<String>, force: Boolean) {
        val product = value(PRODUCT).orEmpty()
        for (line in lines.filter { it.isNotEmpty() }) {
            val requirement = FastbootAndroidInfo.parseLine(line)
            when {
                requirement == null -> report += "android-info.txt syntax error: $line"
                requirement.name == PARTITION_EXISTS -> partitionExists(requirement.options.first())
                else -> requirementMet(FastbootAndroidInfo.check(requirement, product, variables::read), force)
            }
        }
    }

    private fun partitionExists(partition: String) {
        val hasSlot = value(HAS_SLOT + partition)
        if (hasSlot != YES && hasSlot != NO) throw FlashAllStop("device doesn't have required partition $partition!")
        if (FLASH_ALL_IMAGES.none { it.nickname == partition }) {
            throw FlashAllStop(
                "device requires partition $partition which is not known to this version of fastboot",
            )
        }
        required += partition
    }

    private fun requirementMet(check: FastbootRequirementCheck, force: Boolean) {
        val name = check.requirement.name
        val failure = when (check) {
            is FastbootRequirementCheck.Met -> null.also { report += "Checking '$name' OKAY" }
            is FastbootRequirementCheck.Ignored -> null.also {
                report += "Checking '$name' IGNORE, product is ${check.currentProduct} " +
                    "required only for ${check.requirement.product}"
            }
            is FastbootRequirementCheck.NotMet -> check.message
            is FastbootRequirementCheck.Unreadable -> "Could not getvar for '$name' (${check.detail})"
        } ?: return
        report += "Checking '$name' FAILED"
        report += failure
        if (!force) throw FlashAllStop("requirements not met!")
        report += "requirements not met! but proceeding due to --force"
    }

    private companion object {
        const val ALL = "all"
        const val YES = "yes"
        const val NO = "no"
        const val PRODUCT = "product"
        const val HAS_SLOT = "has-slot:"
        const val SLOT_COUNT = "slot-count"
        const val CURRENT_SLOT_VAR = "current-slot"
        const val PARTITION_EXISTS = "partition-exists"
        const val SEPARATOR = "--------------------------------------------"
        val DUMPED = listOf(
            "Bootloader Version..." to "version-bootloader",
            "Baseband Version....." to "version-baseband",
            "Serial Number........" to "serialno",
        )
    }
}
