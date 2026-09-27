package io.github.ncorror.nekoflash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.ncorror.nekoflash.pro.engine.TerminalFileNeed
import com.ncorror.nekoflash.pro.engine.TerminalParser
import com.ncorror.nekoflash.pro.engine.TerminalPlan
import com.ncorror.nekoflash.pro.engine.TerminalPlanCompiler
import com.ncorror.nekoflash.pro.engine.TerminalPlanResult
import com.ncorror.nekoflash.pro.engine.TerminalPlanStep
import com.ncorror.nekoflash.pro.engine.fileNeed
import com.ncorror.nekoflash.pro.engine.writesPartition
import io.github.ncorror.nekoflash.R
import io.github.ncorror.nekoflash.adb.AdbCommandState
import io.github.ncorror.nekoflash.adb.AdbFileState
import io.github.ncorror.nekoflash.adb.AdbInstallState
import io.github.ncorror.nekoflash.adb.AdbRawServiceState
import io.github.ncorror.nekoflash.adb.AdbRebootState
import io.github.ncorror.nekoflash.adb.AdbSideloadState
import io.github.ncorror.nekoflash.command.DesktopCommandArea
import io.github.ncorror.nekoflash.command.DesktopCommandStart
import io.github.ncorror.nekoflash.fastboot.FastbootConsoleState
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootLockStatus

/**
 * Команды настольных `adb` и `fastboot` — третий способ сказать то же самое.
 *
 * Строка разбирается в план, план показывается целиком, и исполняется ровно он
 * (`04` §3, правило 6). Исполняют его те же движки, что и кнопки с сырой
 * консолью (D045); результат приходит туда же, где его показывают они, и здесь
 * повторяется для той части, которой коснулась команда.
 */
data class DesktopCommandPanel(
    /** Какой инструмент подразумевать, если строка начинается не с `adb`/`fastboot`. */
    val defaultTool: TerminalParser.Tool = TerminalParser.Tool.ADB,
    /** Замок Fastboot этой generation, если связь есть: задаёт форму подтверждения. */
    val lock: FastbootLockStatus? = null,
    /** Исполнить план; выбор файла, если он нужен, берёт на себя проводка. */
    val onRun: (TerminalPlan) -> Unit = {},
    /**
     * Строку поменяли: исход прошлой команды больше не относится к ней.
     *
     * Прогон этапа 3: под «не реализовано» для `adb getprop` висел ответ
     * предыдущей `adb shell getprop`, и было неясно, чей он.
     */
    val onEdit: () -> Unit = {},
    /** Где искать исход последней команды. */
    val area: DesktopCommandArea? = null,
    /** Выбор файла не подошёл плану: ничего не отправлено. */
    val fileProblem: DesktopCommandStart? = null,
    val outcomes: DesktopCommandOutcomes = DesktopCommandOutcomes(),
)

/** Состояния движков, в которых оказывается исход команды. */
data class DesktopCommandOutcomes(
    val fastboot: FastbootConsoleState = FastbootConsoleState.Idle,
    val shell: AdbCommandState = AdbCommandState.None,
    val service: AdbRawServiceState = AdbRawServiceState.None,
    val reboot: AdbRebootState = AdbRebootState.None,
    val files: AdbFileState = AdbFileState.None,
    val sideload: AdbSideloadState = AdbSideloadState.None,
    val install: AdbInstallState = AdbInstallState.None,
)

@Composable
internal fun DesktopCommandSection(panel: DesktopCommandPanel) {
    var line by rememberSaveable { mutableStateOf("") }
    val compiler = remember { TerminalPlanCompiler() }
    val result = remember(line, panel.defaultTool) {
        line.takeIf { it.isNotBlank() }?.let { compiler.compile(panel.defaultTool, it) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeading(text = stringResource(R.string.command_title))
        OutlinedTextField(
            value = line,
            onValueChange = { edited ->
                if (edited != line) panel.onEdit()
                line = edited
            },
            label = { Text(stringResource(R.string.command_label)) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        when (result) {
            null -> Unit
            is TerminalPlanResult.Invalid -> CommandLine(stringResource(R.string.command_invalid, result.reason))
            is TerminalPlanResult.NotImplemented ->
                CommandLine(stringResource(R.string.command_not_implemented, result.what))
            is TerminalPlanResult.Ready -> PlanPreview(result.plan, panel)
        }
        when (val problem = panel.fileProblem) {
            is DesktopCommandStart.FileCount ->
                CommandLine(stringResource(R.string.command_file_count, problem.expected, problem.picked))
            null, is DesktopCommandStart.Started -> Unit
            DesktopCommandStart.FileMissing -> CommandLine(stringResource(R.string.command_file_missing))
        }
        CommandOutcome(panel.area, panel.outcomes)
        Text(text = stringResource(R.string.command_note), style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * План целиком до нажатия — и подтверждение, если он пишет в раздел.
 *
 * Подтверждение то же, что у кнопок группы «Запись» (ADR-0002): слово `yes`
 * спрашивается только при подтверждённом замке, `UNKNOWN` замком не считается,
 * а команда уходит устройству без изменений в любом случае.
 */
@Composable
private fun PlanPreview(plan: TerminalPlan, panel: DesktopCommandPanel) {
    var typed by remember(plan.steps) { mutableStateOf("") }
    val writes = plan.steps.any { it.writesPartition }
    val needsWord = writes && panel.lock?.typedConfirmation == true

    Text(text = stringResource(R.string.command_preview_title), style = MaterialTheme.typography.titleSmall)
    plan.steps.forEachIndexed { index, step ->
        CommandLine(stringResource(R.string.command_step, index + 1, stepText(step)))
    }
    if (writes) {
        Text(
            text = stringResource(R.string.command_writes_partition),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        panel.lock?.let { lock ->
            Text(
                text = stringResource(R.string.fastboot_destructive_lock_state, lock.detail),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    if (needsWord) {
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            label = { Text(stringResource(R.string.fastboot_destructive_type_yes, CONFIRM_WORD)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    val picks = plan.fileStep?.fileNeed ?: TerminalFileNeed.NONE
    Button(
        onClick = { panel.onRun(plan) },
        enabled = !needsWord || typed.trim().equals(CONFIRM_WORD, ignoreCase = true),
        colors = if (writes) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            )
        } else {
            ButtonDefaults.buttonColors()
        },
    ) {
        Text(
            stringResource(
                when (picks) {
                    TerminalFileNeed.NONE -> R.string.command_run
                    TerminalFileNeed.OPEN -> R.string.command_run_open
                    TerminalFileNeed.OPEN_MANY -> R.string.command_run_open_many
                    TerminalFileNeed.CREATE -> R.string.command_run_create
                },
            ),
        )
    }
}

/** Шаг и, если задан `--slot`, пометка, что имя уточнит устройство. */
@Composable
private fun stepText(step: TerminalPlanStep): String {
    val slot = (step as? TerminalPlanStep.FastbootWire)?.slot ?: (step as? TerminalPlanStep.FastbootFlashImage)?.slot
    val flags = (step as? TerminalPlanStep.FastbootFlashImage)?.vbmetaFlags ?: 0
    val base = baseStepText(step)
    val text = if (flags == 0) {
        base
    } else {
        stringResource(R.string.command_step_vbmeta_flags, base, vbmetaFlagNames(flags))
    }
    return if (slot == null) text else stringResource(R.string.command_step_slot, text, slot)
}

/** Флаги так, как их пишет настольный `fastboot`. */
private fun vbmetaFlagNames(flags: Int): String = listOfNotNull(
    "--disable-verity".takeIf { flags and 1 != 0 },
    "--disable-verification".takeIf { flags and 2 != 0 },
).joinToString(" ")

@Composable
private fun baseStepText(step: TerminalPlanStep): String = when (step) {
    is TerminalPlanStep.FastbootWire -> stringResource(R.string.command_step_fastboot_wire, step.command)
    is TerminalPlanStep.FastbootGetvar -> stringResource(R.string.command_step_fastboot_getvar, step.variable)
    TerminalPlanStep.FastbootGetvarAll -> stringResource(R.string.command_step_fastboot_getvar_all)
    is TerminalPlanStep.FastbootFlashImage ->
        stringResource(R.string.command_step_fastboot_flash, step.partition, step.imageName ?: "—")
    is TerminalPlanStep.FastbootBootImage -> stringResource(R.string.command_step_fastboot_boot, step.imageName)
    is TerminalPlanStep.FastbootFetch ->
        stringResource(R.string.command_step_fastboot_fetch, step.partition, step.destinationName)
    is TerminalPlanStep.AdbShellCommand -> stringResource(R.string.command_step_adb_shell, step.command)
    is TerminalPlanStep.AdbInteractiveShell -> step.firstLine
        ?.let { stringResource(R.string.command_step_adb_interactive_line, it) }
        ?: stringResource(R.string.command_step_adb_interactive)
    is TerminalPlanStep.AdbService -> stringResource(R.string.command_step_adb_service, step.service)
    is TerminalPlanStep.AdbReboot -> stringResource(R.string.command_step_adb_reboot, step.target)
    is TerminalPlanStep.AdbPull -> stringResource(R.string.command_step_adb_pull, step.remotePath, step.localName)
    is TerminalPlanStep.AdbPush -> stringResource(R.string.command_step_adb_push, step.localName, step.remotePath)
    is TerminalPlanStep.AdbSideload -> stringResource(R.string.command_step_adb_sideload, step.archiveName)
    is TerminalPlanStep.AdbInstall -> stringResource(
        if (step.several) R.string.command_step_adb_install_multiple else R.string.command_step_adb_install,
        step.names.joinToString(", "),
        step.options.joinToString(" ").ifEmpty { "—" },
    )
}

/** Исход — в той части, которой коснулась команда, тем же текстом, что и у кнопок. */
@Composable
private fun CommandOutcome(area: DesktopCommandArea?, outcomes: DesktopCommandOutcomes) {
    when (area) {
        null, DesktopCommandArea.ADB_TERMINAL -> Unit
        DesktopCommandArea.FASTBOOT -> FastbootOutcomePane(outcomes.fastboot)
        DesktopCommandArea.ADB_SHELL -> ShellOutcome(outcomes.shell)
        DesktopCommandArea.ADB_SERVICE -> CommandLine(rawServiceText(outcomes.service))
        DesktopCommandArea.ADB_REBOOT -> CommandLine(rebootText(outcomes.reboot))
        DesktopCommandArea.ADB_FILES -> CommandLine(fileStateText(outcomes.files))
        DesktopCommandArea.ADB_SIDELOAD -> CommandLine(sideloadText(outcomes.sideload))
        DesktopCommandArea.ADB_INSTALL -> CommandLine(installText(outcomes.install))
    }
}

/** Исход установки: ответ `package` как есть, байты — отдельно. */
@Composable
private fun installText(state: AdbInstallState): String = when (state) {
    AdbInstallState.None -> ""
    is AdbInstallState.Running ->
        stringResource(R.string.install_running, state.stage, state.sentBytes, state.totalBytes)
    is AdbInstallState.Finished -> stringResource(
        if (state.success) R.string.install_success else R.string.install_refused,
        state.files,
        state.bytes,
        state.answer,
    )
    is AdbInstallState.Failed -> stringResource(R.string.install_failed, state.detail)
}

@Composable
private fun CommandLine(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium)
}

/** Слово подтверждения из ADR-0002 — то же, что у группы «Запись». */
private const val CONFIRM_WORD = "yes"
