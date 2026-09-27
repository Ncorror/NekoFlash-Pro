package io.github.ncorror.nekoflash.fastboot

import com.ncorror.nekoflash.pro.engine.CURRENT_SLOT
import com.ncorror.nekoflash.pro.engine.SET_ACTIVE_DEFAULT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Слоты — как `verify_slot` и `do_for_partitions` настольного `fastboot`. */
class FastbootSlotTargetsTest {
    private val abDevice = mapOf(
        "slot-count" to "2",
        "has-slot:boot" to "yes",
        "has-slot:persist" to "no",
        "current-slot" to "_a",
    )

    private fun resolve(commands: List<String>, slot: String, device: Map<String, String> = abDevice) =
        FastbootSlotTargets.resolve(commands, slot) { device[it] }

    private fun failure(commands: List<String>, slot: String, device: Map<String, String> = abDevice): String =
        (resolve(commands, slot, device) as SlotResolution.Failed).detail

    @Test
    fun onlySlottedPartitionsGetASuffix() {
        assertEquals(
            SlotResolution.Resolved(listOf("flash:boot_b", "erase:persist", "erase:unknown", "set_active:b")),
            resolve(listOf("flash:boot", "erase:persist", "erase:unknown", "set_active:b"), "b"),
        )
    }

    @Test
    fun withoutSlotOptionASlottedPartitionGetsTheCurrentSlot() {
        assertEquals(
            SlotResolution.Resolved(listOf("flash:boot_a", "erase:persist", "fetch:boot_a")),
            resolve(listOf("flash:boot", "erase:persist", "fetch:boot"), CURRENT_SLOT),
        )
        assertEquals(
            "Failed to identify current slot",
            failure(listOf("flash:boot"), CURRENT_SLOT, abDevice - "current-slot"),
        )
    }

    @Test
    fun otherIsTheNextSlotAfterTheCurrentOne() {
        assertEquals(SlotResolution.Resolved(listOf("erase:boot_b")), resolve(listOf("erase:boot"), "other"))
        val onB = abDevice + ("current-slot" to "b")
        assertEquals(SlotResolution.Resolved(listOf("erase:boot_a")), resolve(listOf("erase:boot"), "other", onB))
    }

    @Test
    fun allCoversEverySlotTheDeviceCounts() {
        assertEquals(
            SlotResolution.Resolved(listOf("flash:boot_a", "flash:boot_b", "erase:persist")),
            resolve(listOf("flash:boot", "erase:persist"), "all"),
        )
        val three = abDevice + ("slot-count" to "3")
        assertEquals(
            SlotResolution.Resolved(listOf("erase:boot_a", "erase:boot_b", "erase:boot_c")),
            resolve(listOf("erase:boot"), "all", three),
        )
        assertTrue(failure(listOf("erase:unknown"), "all").contains("has slot all"))
    }

    @Test
    fun theSlotOptionIsCheckedAgainstSlotCount() {
        val single = mapOf("has-slot:boot" to "no")
        assertEquals("Device does not support slots", failure(listOf("flash:boot"), "a", single))
        assertTrue(failure(listOf("flash:boot"), "c").startsWith("Slot c does not exist"))
        assertTrue(failure(listOf("flash:boot"), "_a").startsWith("Slot _a does not exist"))
        assertEquals("No known slots", failure(listOf("flash:boot"), "other", abDevice - "current-slot"))
    }

    @Test
    fun setActiveIsVerifiedAndAllMeansTheFirstSlot() {
        assertEquals(SlotResolution.Resolved(listOf("set_active:b")), resolve(listOf("set_active:other"), CURRENT_SLOT))
        assertEquals(SlotResolution.Resolved(listOf("set_active:a")), resolve(listOf("set_active:all"), CURRENT_SLOT))
        assertEquals("Device does not support slots", failure(listOf("set_active:a"), CURRENT_SLOT, emptyMap()))
    }

    @Test
    fun setActiveWithoutAValueIsTheSlotOptionOrTheCurrentSlot() {
        assertEquals(
            SlotResolution.Resolved(listOf("flash:boot_a", "set_active:a")),
            resolve(listOf("flash:boot", SET_ACTIVE_DEFAULT), CURRENT_SLOT),
        )
        assertEquals(
            SlotResolution.Resolved(listOf("flash:boot_b", "set_active:b")),
            resolve(listOf("flash:boot", SET_ACTIVE_DEFAULT), "b"),
        )
        assertEquals(SlotResolution.Resolved(listOf("set_active:a")), resolve(listOf(SET_ACTIVE_DEFAULT), "all"))
        // Текущий слот не назван — `set_active` не идёт, как у настольного клиента.
        val silent = mapOf("slot-count" to "2", "has-slot:persist" to "no")
        assertEquals(
            SlotResolution.Resolved(listOf("erase:persist")),
            resolve(listOf("erase:persist", SET_ACTIVE_DEFAULT), CURRENT_SLOT, silent),
        )
        assertTrue(failure(listOf(SET_ACTIVE_DEFAULT), CURRENT_SLOT, silent).contains("set_active is not sent"))
    }

    @Test
    fun otherCommandsGoAsTyped() {
        assertEquals(SlotResolution.Resolved(listOf("oem device-info")), resolve(listOf("oem device-info"), "b"))
    }
}
