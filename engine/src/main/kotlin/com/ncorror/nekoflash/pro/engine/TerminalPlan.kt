package com.ncorror.nekoflash.pro.engine

/** Only reviewed, read-only variables are part of the first executable plan. */
enum class PlannedFastbootVariable(val wireName: String) {
    VERSION("version"), PRODUCT("product"),
}

sealed interface TerminalPlanStep {
    data class FastbootGetvar(val variable: PlannedFastbootVariable) : TerminalPlanStep
    data class FastbootFlashRaw(
        val partition: String,
        val imageSize: Long,
        val selectionId: Long,
    ) : TerminalPlanStep
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
            is TerminalPlanStep.FastbootFlashRaw ->
                "Fastboot: flash ${step.partition} (raw image, ${step.imageSize} bytes)"
            TerminalPlanStep.AdbProduct -> "ADB: read ro.product.device"
        }
    }
}

sealed interface TerminalPlanResult {
    data class Ready(val plan: TerminalPlan) : TerminalPlanResult
    data class RequiresImage(val partition: String, val expectedName: String?) : TerminalPlanResult
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

    /** A SAF selection id is bound to the reviewed plan to prevent a stale picker result. */
    fun attachImage(request: TerminalPlanResult.RequiresImage,
                    selectedName: String, size: Long, selectionId: Long): TerminalPlanResult {
        if (request.expectedName != null && request.expectedName != selectedName) {
            return TerminalPlanResult.Unavailable("Selected image does not match command")
        }
        if (size !in 1..0xffff_ffffL || selectionId <= 0L) {
            return TerminalPlanResult.Unavailable("Selected image requires sparse or has no known size")
        }
        return TerminalPlanResult.Ready(TerminalPlan(listOf(
            TerminalPlanStep.FastbootFlashRaw(request.partition, size, selectionId))))
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
                is FastbootCommand.Flash -> if (command.partition.matches(PARTITION) &&
                    command.image?.isNotEmpty() != false) {
                    TerminalPlanResult.RequiresImage(command.partition, command.image)
                } else TerminalPlanResult.Invalid("Invalid partition or image name")
                else -> TerminalPlanResult.Unavailable("Fastboot command has no execution plan")
            }
        }
    }

    private companion object {
        val PARTITION = Regex("[A-Za-z0-9._-]{1,64}")
    }
}
