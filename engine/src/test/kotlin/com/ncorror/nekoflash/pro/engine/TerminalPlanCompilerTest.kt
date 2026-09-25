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
}
