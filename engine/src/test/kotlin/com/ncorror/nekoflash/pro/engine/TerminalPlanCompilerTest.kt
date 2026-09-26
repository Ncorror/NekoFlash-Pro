package com.ncorror.nekoflash.pro.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TerminalPlanCompilerTest {
    private val compiler = TerminalPlanCompiler()

    @Test
    fun previewUsesTheCompiledStepsForTheTwoImplementedQueries() {
        for (variable in PlannedFastbootVariable.entries) {
            val ready = assertIs<TerminalPlanResult.Ready>(compiler.compile("fastboot getvar ${variable.wireName}"))
            assertEquals(listOf(TerminalPlanStep.FastbootGetvar(variable)), ready.plan.steps)
            assertEquals(listOf("Fastboot: read getvar:${variable.wireName}"), ready.plan.preview())
        }
    }

    @Test
    fun explicitRawAndUnknownVariablesDoNotInheritSafeGetvarPlan() {
        assertIs<TerminalPlanResult.Unavailable>(compiler.compile("fastboot raw getvar:product"))
        assertIs<TerminalPlanResult.Unavailable>(compiler.compile("fastboot getvar all"))
        assertIs<TerminalPlanResult.Unavailable>(compiler.compile("fastboot getvar serialno"))
        assertIs<TerminalPlanResult.Unavailable>(compiler.compile("fastboot oem unlock"))
    }

    @Test
    fun adbProductUsesOneFixedReadOnlyService() {
        val ready = assertIs<TerminalPlanResult.Ready>(
            compiler.compile(TerminalParser.Tool.ADB, "shell getprop ro.product.device")
        )
        assertEquals(listOf(TerminalPlanStep.AdbProduct), ready.plan.steps)
        assertEquals(listOf("ADB: read ro.product.device"), ready.plan.preview())
        for (line in listOf(
            "shell getprop ro.product.device; reboot",
            "shell getprop ro.product.serialno",
            "shell -t getprop ro.product.device",
            "raw shell:getprop ro.product.device",
        )) {
            assertIs<TerminalPlanResult.Unavailable>(compiler.compile(TerminalParser.Tool.ADB, line), line)
        }
    }

    @Test
    fun hostCommandsAndAnyUnimplementedOptionsAreUnavailable() {
        for (command in listOf(
            "fastboot --force getvar product",
            "fastboot -w getvar version",
            "fastboot --set-active=a flash boot boot.img",
            "fastboot -S 512M flash system system.img",
            "fastboot format userdata",
            "fastboot wipe-super super_empty.img",
            "fastboot flashall",
            "adb shell id",
            "adb sideload update.zip",
        )) {
            assertIs<TerminalPlanResult.Unavailable>(compiler.compile(command), command)
        }
        assertIs<TerminalPlanResult.Invalid>(compiler.compile("fastboot -S --force flash boot boot.img"))
    }

    @Test
    fun rawFlashNeedsOneExactPickerSelectionBeforeItCanHaveAPreview() {
        val pending = assertIs<TerminalPlanResult.RequiresImage>(compiler.compile("fastboot flash boot_a boot.img"))
        assertEquals("boot_a", pending.partition)
        assertIs<TerminalPlanResult.Unavailable>(compiler.attachImage(pending, "other.img", 3, 7))
        assertIs<TerminalPlanResult.Unavailable>(compiler.attachImage(pending, "boot.img", 0x1_0000_0000, 7))
        val ready = assertIs<TerminalPlanResult.Ready>(compiler.attachImage(pending, "boot.img", 3, 7))
        assertEquals(listOf(TerminalPlanStep.FastbootFlashRaw("boot_a", 3, 7)), ready.plan.steps)
        assertEquals(listOf("Fastboot: flash boot_a (raw image, 3 bytes)"), ready.plan.preview())
        assertIs<TerminalPlanResult.Unavailable>(compiler.compile("fastboot raw flash:boot_a"))
        assertIs<TerminalPlanResult.Invalid>(compiler.compile("fastboot flash boot:ramdisk new.img"))
    }

    @Test
    fun downloadLimitQueryHasAReadOnlyPlan() {
        val ready = assertIs<TerminalPlanResult.Ready>(compiler.compile("fastboot getvar max-download-size"))
        assertEquals(listOf(TerminalPlanStep.FastbootGetvar(PlannedFastbootVariable.DOWNLOAD_SIZE)),
            ready.plan.steps)
        assertEquals(listOf("Fastboot: read getvar:max-download-size"), ready.plan.preview())
    }
}
