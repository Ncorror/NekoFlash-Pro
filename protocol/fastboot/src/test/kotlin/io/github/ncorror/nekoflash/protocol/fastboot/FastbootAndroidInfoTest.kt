package io.github.ncorror.nekoflash.protocol.fastboot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `android-info.txt` — как `ParseRequirementLine` и `CheckRequirement`. */
class FastbootAndroidInfoTest {
    private val device = mapOf("product" to "vayu", "version-bootloader" to "vayu-1.2-3")

    private fun read(name: String): FastbootVariable =
        device[name]?.let { FastbootVariable.Present(name, it) } ?: FastbootVariable.Unsupported(name, "unknown variable")

    @Test
    fun linesAreParsedLikeTheDesktopClient() {
        assertEquals(
            FastbootRequirement("product", "", false, listOf("vayu", "bhima")),
            FastbootAndroidInfo.parseLine("require board=vayu| bhima"),
        )
        assertEquals(
            FastbootRequirement("version-baseband", "", true, listOf("1.0")),
            FastbootAndroidInfo.parseLine("reject version-baseband=1.0"),
        )
        assertEquals(
            FastbootRequirement("version-bootloader", "bhima", false, listOf("x", "y")),
            FastbootAndroidInfo.parseLine("require-for-product:bhima version-bootloader=x|y"),
        )
        assertEquals(FastbootRequirement("product", "", false, listOf("vayu")), FastbootAndroidInfo.parseLine("board=vayu"))
        assertNull(FastbootAndroidInfo.parseLine("nonsense"))
    }

    @Test
    fun aStarMatchesAPrefixAndRejectInverts() {
        val star = FastbootAndroidInfo.parseLine("require version-bootloader=vayu-1.*")!!
        assertTrue(FastbootAndroidInfo.check(star, "vayu", ::read) is FastbootRequirementCheck.Met)
        val reject = FastbootAndroidInfo.parseLine("reject product=vayu")!!
        val notMet = FastbootAndroidInfo.check(reject, "vayu", ::read) as FastbootRequirementCheck.NotMet
        assertEquals("Device product is 'vayu'. Update rejects 'vayu'.", notMet.message)
    }

    @Test
    fun aRequirementForAnotherProductIsIgnoredAndAnUnknownVariableIsUnreadable() {
        val other = FastbootAndroidInfo.parseLine("require-for-product:bhima product=bhima")!!
        assertTrue(FastbootAndroidInfo.check(other, "vayu", ::read) is FastbootRequirementCheck.Ignored)
        val unknown = FastbootAndroidInfo.parseLine("require version-baseband=1")!!
        assertEquals(
            FastbootRequirementCheck.Unreadable(unknown, "unknown variable"),
            FastbootAndroidInfo.check(unknown, "vayu", ::read),
        )
    }

    @Test
    fun severalOptionsAreJoinedWithOr() {
        val requirement = FastbootAndroidInfo.parseLine("require product=a|b")!!
        val notMet = FastbootAndroidInfo.check(requirement, "vayu", ::read) as FastbootRequirementCheck.NotMet
        assertEquals("Device product is 'vayu'. Update requires 'a' or 'b'.", notMet.message)
    }
}
