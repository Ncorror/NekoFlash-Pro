package io.github.ncorror.nekoflash.protocol.fastboot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Формы команд и слоты.
 *
 * Правила сверены с обоими архивами до кода (`16` §3): суффикс слота из A2
 * `FastbootFlashTargetPolicy.applySlot`, каноничный вид слота — оттуда же и из
 * Legacy `verifyTerminalMutation`, где подчёркивание и регистр снимаются с обеих
 * сторон сравнения.
 */
class FastbootCommandsTest {
    @Test
    fun mutatingCommandsAreBuiltAsTheDeviceKnowsThem() {
        assertEquals("flash:boot_a", FastbootCommands.flash("boot_a"))
        assertEquals("erase:userdata", FastbootCommands.erase(" userdata "))
        assertEquals("format:cache", FastbootCommands.format("cache"))
        assertEquals("boot", FastbootCommands.boot())
        assertEquals("set_active:b", FastbootCommands.setActive("_B"))
    }

    /** Пустая цель перезагрузки — обычная загрузка, а не `reboot-`. */
    @Test
    fun anEmptyRebootTargetIsAPlainReboot() {
        assertEquals("reboot", FastbootCommands.reboot())
        assertEquals("reboot", FastbootCommands.reboot("   "))
        assertEquals("reboot-bootloader", FastbootCommands.reboot("bootloader"))
        assertEquals("reboot-fastboot", FastbootCommands.reboot("FASTBOOT"))
    }

    /**
     * Неразобранный слот в `set_active:` уходит как набран.
     *
     * Подставлять догадку нельзя, а отказываться от команды — не наше дело:
     * какие слоты у него есть, знает устройство (`01` §3).
     */
    @Test
    fun anUnparsedSlotIsSentAsTyped() {
        assertEquals("set_active:other", FastbootCommands.setActive("other"))
    }

    /**
     * Суффикс слота приписывается **перед** двоеточной частью имени.
     *
     * Наивное склеивание дало бы `boot:foo_a` — имени, которого у устройства
     * нет; A2 разрезает имя по первому `:` именно поэтому.
     */
    @Test
    fun theSlotSuffixGoesBeforeTheColonPartOfTheName() {
        assertEquals("boot_a", FastbootSlots.apply("boot", "a"))
        assertEquals("boot_a:foo", FastbootSlots.apply("boot:foo", "_A"))
        assertEquals("boot", FastbootSlots.apply("boot", null))
        assertEquals("boot", FastbootSlots.apply("boot", "not-a-slot"))
    }

    @Test
    fun aSlotIsOneLowercaseLetterInCanonicalForm() {
        assertEquals("a", FastbootSlots.normalize("_A"))
        assertEquals("b", FastbootSlots.normalize(" b "))
        assertNull(FastbootSlots.normalize("ab"))
        assertNull(FastbootSlots.normalize(""))
        assertNull(FastbootSlots.normalize(null))
    }

    @Test
    fun slotsAreComparedCanonically() {
        assertTrue(FastbootSlots.same("_a", "A"))
        assertTrue(FastbootSlots.same("b", "_b"))
        assertEquals(false, FastbootSlots.same("a", "b"))
        assertEquals("неразобранный слот ничему не равен", false, FastbootSlots.same("ab", "ab"))
    }
}
