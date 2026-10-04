package com.ncorror.nekoflash.pro.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AdbForwardingPlanTest {
    private val compiler = TerminalPlanCompiler()

    private fun step(line: String): TerminalPlanStep =
        assertIs<TerminalPlanResult.Ready>(compiler.compile(line), line).plan.steps.single()

    @Test
    fun oldForwardWorkspaceCapabilitiesHaveTypedPlans() {
        assertEquals(AdbForwardAddStep(1234, "localabstract:agent"), step("adb forward tcp:1234 localabstract:agent"))
        assertEquals(AdbForwardAddStep(0, "tcp:5555"), step("adb forward tcp:0 tcp:5555"))
        assertEquals(AdbForwardRemoveStep(1234), step("adb forward --remove tcp:1234"))
        assertEquals(AdbForwardListStep, step("adb forward --list"))
    }

    @Test
    fun oldReverseWorkspaceCapabilitiesHaveTypedPlans() {
        assertEquals(AdbReverseAddStep("tcp:7777", "tcp:8888"), step("adb reverse tcp:7777 tcp:8888"))
        assertEquals(AdbReverseListStep, step("adb reverse --list"))
        assertEquals(AdbReverseRemoveAllStep, step("adb reverse --remove-all"))
    }

    @Test
    fun recognizedButUnwiredVariantsStopBeforeAnyDeviceCall() {
        for (command in listOf(
            "adb forward --no-rebind tcp:1234 tcp:5555",
            "adb forward --remove-all",
            "adb forward localabstract:host tcp:5555",
            "adb reverse --no-rebind tcp:7777 tcp:8888",
            "adb reverse --remove tcp:7777",
        )) {
            assertIs<TerminalPlanResult.NotImplemented>(compiler.compile(command), command)
        }
    }
}
