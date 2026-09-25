package com.ncorror.nekoflash.pro.engine

/** Only reviewed, read-only variables are part of the first executable plan. */
enum class PlannedFastbootVariable(val wireName: String) {
    VERSION("version"), PRODUCT("product"),
}

sealed interface TerminalPlanStep {
    data class FastbootGetvar(val variable: PlannedFastbootVariable) : TerminalPlanStep
    data object AdbProduct : TerminalPlanStep
}

/** Preview is derived from these exact steps; executors receive the same plan. */
class TerminalPlan(steps: List<TerminalPlanStep>) {
    val steps: List<TerminalPlanStep> = steps.toList()

    init {
        require(this.steps.isNotEmpty()) { "A terminal plan must have at least one step" }
    }

    fun preview(): List<String> = steps.map { step ->
        when (step) {
            is TerminalPlanStep.FastbootGetvar -> "Fastboot: read getvar:${step.variable.wireName}"
            TerminalPlanStep.AdbProduct -> "ADB: read ro.product.device"
        }
    }
}

sealed interface TerminalPlanResult {
    data class Ready(val plan: TerminalPlan) : TerminalPlanResult
    data class Invalid(val reason: String) : TerminalPlanResult
    data class Unavailable(val reason: String) : TerminalPlanResult
}

/** Parsing a host command is not evidence that a safe executor exists for it. */
class TerminalPlanCompiler(private val parser: TerminalParser = TerminalParser()) {
    fun compile(line: String): TerminalPlanResult = compile(parser.parse(line))

    fun compile(tool: TerminalParser.Tool, line: String): TerminalPlanResult = compile(parser.parse(tool, line))

    fun compile(parsed: TerminalParseResult): TerminalPlanResult = when (parsed) {
        is TerminalParseResult.Parsed -> compile(parsed.invocation)
        is TerminalParseResult.Invalid -> TerminalPlanResult.Invalid(parsed.reason)
        is TerminalParseResult.Unsupported -> TerminalPlanResult.Unavailable("Command has no execution plan")
    }

    fun compile(invocation: TerminalInvocation): TerminalPlanResult = when (invocation) {
        is TerminalInvocation.Adb -> when (val command = invocation.command) {
            is AdbCommand.Shell -> if (command.command == "getprop ro.product.device" &&
                command.tty == null && !command.noExitCode) {
                TerminalPlanResult.Ready(TerminalPlan(listOf(TerminalPlanStep.AdbProduct)))
            } else TerminalPlanResult.Unavailable("ADB command has no execution plan")
            else -> TerminalPlanResult.Unavailable("ADB command has no execution plan")
        }
        is TerminalInvocation.Fastboot -> {
            if (invocation.options != FastbootOptions()) {
                TerminalPlanResult.Unavailable("Fastboot options have no execution plan")
            } else when (val command = invocation.command) {
                is FastbootCommand.Getvar -> {
                    val variable = PlannedFastbootVariable.entries.firstOrNull { it.wireName == command.variable }
                    if (variable == null) TerminalPlanResult.Unavailable("Fastboot variable has no execution plan")
                    else TerminalPlanResult.Ready(TerminalPlan(listOf(TerminalPlanStep.FastbootGetvar(variable))))
                }
                else -> TerminalPlanResult.Unavailable("Fastboot command has no execution plan")
            }
        }
    }
}
