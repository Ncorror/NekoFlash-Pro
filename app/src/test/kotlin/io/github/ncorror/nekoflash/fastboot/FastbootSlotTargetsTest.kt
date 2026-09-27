package io.github.ncorror.nekoflash.fastboot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `--slot` уточняет имена так же, как настольный клиент. */
class FastbootSlotTargetsTest {
    private val device = mapOf("has-slot:boot" to "yes", "has-slot:persist" to "no", "current-slot" to "_a")

    private fun resolve(commands: List<String>, slot: String?) =
        FastbootSlotTargets.resolve(commands, slot) { device[it] }

    @Test
    fun onlySlottedPartitionsGetASuffix() {
        assertEquals(
            SlotResolution.Resolved(listOf("flash:boot_b", "erase:persist", "erase:unknown", "set_active:b")),
            resolve(listOf("flash:boot", "erase:persist", "erase:unknown", "set_active:b"), "b"),
        )
    }

    @Test
    fun otherIsTheOppositeOfTheCurrentSlot() {
        assertEquals(SlotResolution.Resolved(listOf("erase:boot_b")), resolve(listOf("erase:boot"), "other"))
    }

    @Test
    fun allCoversBothSlots() {
        assertEquals(SlotResolution.Resolved(listOf("erase:boot_a", "erase:boot_b")), resolve(listOf("erase:boot"), "all"))
    }

    @Test
    fun setActiveOtherIsResolvedWithoutASlotOption() {
        assertEquals(
            SlotResolution.Resolved(listOf("flash:boot", "set_active:b")),
            resolve(listOf("flash:boot", "set_active:other"), null),
        )
    }

    @Test
    fun anUnknownSlotSendsNothing() {
        val silent = FastbootSlotTargets.resolve(listOf("erase:boot"), "other") { null }
        assertTrue((silent as SlotResolution.Failed).detail.contains("current-slot"))
        assertTrue(resolve(listOf("erase:boot"), "ab") is SlotResolution.Failed)
    }
}
