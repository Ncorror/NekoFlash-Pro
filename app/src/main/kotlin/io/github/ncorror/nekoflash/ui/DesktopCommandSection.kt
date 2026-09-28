package io.github.ncorror.nekoflash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
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
import com.ncorror.nekoflash.pro.engine.CURRENT_SLOT
import com.ncorror.nekoflash.pro.engine.LocalReportKind
import com.ncorror.nekoflash.pro.engine.SET_ACTIVE_DEFAULT
import com.ncorror.nekoflash.pro.engine.ShellMode
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
import io.github.ncorror.nekoflash.adb.AdbInstallState
import io.github.ncorror.nekoflash.adb.AdbRawServiceState
import io.github.ncorror.nekoflash.adb.AdbRebootState
import io.github.ncorror.nekoflash.adb.AdbSideloadState
import io.github.ncorror.nekoflash.adb.AdbTerminalState
import io.github.ncorror.nekoflash.adb.AdbTransferState
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
    /**
     * Исполнить план; выбор файла, если он нужен, берёт на себя проводка.
     * Второй аргумент — выбрать папку, а не файл, там, где годится и то и другое.
     */
    val onRun: (TerminalPlan, Boolean) -> Unit = { _, _ -> },
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
    /** Ответ самого приложения: `devices`, `get-state`, `status`. */
    val answer: String? = null,
    val outcomes: DesktopCommandOutcomes = DesktopCommandOutcomes(),
)

/** Состояния движков, в которых оказывается исход команды. */
data class DesktopCommandOutcomes(
    val fastboot: FastbootConsoleState = FastbootConsoleState.Idle,
    val shell: AdbCommandState = AdbCommandState.None,
    val service: AdbRawServiceState = AdbRawServiceState.None,
    val reboot: AdbRebootState = AdbRebootState.None,
    val sideload: AdbSideloadState = AdbSideloadState.None,
    val install: AdbInstallState = AdbInstallState.None,
    val transfer: AdbTransferState = AdbTransferState.None,
    val terminal: AdbTerminalState = AdbTerminalState(),
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
        // Объявляется устоявшийся итог; ожидание и счётчики байт — нет.
        val announced = if (settled(panel.area, panel.outcomes)) Modifier.announcedOutcome() else Modifier
        Column(modifier = announced, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CommandResult(panel)
        }
        Text(text = stringResource(R.string.command_note), style = MaterialTheme.typography.bodySmall)
    }
}

/** Закончилась ли команда: идущую TalkBack не объявляет на каждое изменение. */
private fun settled(area: DesktopCommandArea?, outcomes: DesktopCommandOutcomes): Boolean = when (area) {
    DesktopCommandArea.FASTBOOT -> outcomes.fastboot !is FastbootConsoleState.Running
    DesktopCommandArea.ADB_SHELL -> outcomes.shell !is AdbCommandState.Running
    DesktopCommandArea.ADB_INSTALL -> outcomes.install !is AdbInstallState.Running
    DesktopCommandArea.ADB_TRANSFER -> outcomes.transfer !is AdbTransferState.Running
    DesktopCommandArea.ADB_SIDELOAD -> outcomes.sideload !is AdbSideloadState.Running
    DesktopCommandArea.ADB_SERVICE, DesktopCommandArea.ADB_REBOOT, DesktopCommandArea.ADB_TERMINAL, null -> true
}

/** Итог последней команды: то, о чём спросили, и ответ устройства или приложения. */
@Composable
private fun CommandResult(panel: DesktopCommandPanel) {
    when (val problem = panel.fileProblem) {
        is DesktopCommandStart.FileCount ->
            CommandLine(stringResource(R.string.command_file_count, problem.expected, problem.picked))
        null, is DesktopCommandStart.Started, is DesktopCommandStart.Answered -> Unit
        DesktopCommandStart.FileMissing -> CommandLine(stringResource(R.string.command_file_missing))
    }
    panel.answer?.let { answer ->
        SelectionContainer {
            Text(
                text = answer.ifBlank { stringResource(R.string.command_answer_empty) },
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
    CommandOutcome(panel.area, panel.outcomes)
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

    SectionHeading(text = stringResource(R.string.command_preview_title))
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
    val enabled = !needsWord || typed.trim().equals(CONFIRM_WORD, ignoreCase = true)
    if (picks == TerminalFileNeed.OPEN_OR_FOLDER) {
        // Файл это или папка, по имени в команде не узнать: выбирает оператор.
        Button(onClick = { panel.onRun(plan, false) }, enabled = enabled) {
            Text(stringResource(R.string.command_run_open))
        }
        Button(onClick = { panel.onRun(plan, true) }, enabled = enabled) {
            Text(stringResource(R.string.command_run_folder))
        }
    } else {
        RunButton(plan, panel, picks, writes, enabled)
    }
}

@Composable
private fun RunButton(
    plan: TerminalPlan,
    panel: DesktopCommandPanel,
    picks: TerminalFileNeed,
    writes: Boolean,
    enabled: Boolean,
) {
    Button(
        onClick = { panel.onRun(plan, picks == TerminalFileNeed.FOLDER) },
        enabled = enabled,
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
                    TerminalFileNeed.OPEN, TerminalFileNeed.OPEN_OR_FOLDER -> R.string.command_run_open
                    TerminalFileNeed.OPEN_MANY -> R.string.command_run_open_many
                    TerminalFileNeed.CREATE -> R.string.command_run_create
                    TerminalFileNeed.FOLDER -> R.string.command_run_save_folder
                },
            ),
        )
    }
}

/** Шаг и, если имя или слот уточнит устройство, пометка об этом. */
@Composable
private fun stepText(step: TerminalPlanStep): String {
    // У `set_active` слот — сама команда; пометка о суффиксе раздела к нему не относится.
    val slot = (step as? TerminalPlanStep.FastbootWire)?.takeUnless { it.command.startsWith(SET_ACTIVE_DEFAULT) }?.slot
        ?: (step as? TerminalPlanStep.FastbootFlashImage)?.slot
        ?: (step as? TerminalPlanStep.FastbootFetch)?.slot
    val flags = (step as? TerminalPlanStep.FastbootFlashImage)?.vbmetaFlags ?: 0
    val sparse = (step as? TerminalPlanStep.FastbootFlashImage)?.sparseLimit
    val base = baseStepText(step)
    val flagged = if (flags == 0) {
        base
    } else {
        stringResource(R.string.command_step_vbmeta_flags, base, vbmetaFlagNames(flags))
    }
    val text = if (sparse == null) flagged else stringResource(R.string.command_step_sparse_limit, flagged, sparse)
    return when (slot) {
        null -> text
        CURRENT_SLOT -> stringResource(R.string.command_step_current_slot, text)
        else -> stringResource(R.string.command_step_slot, text, slot)
    }
}

/** Флаги так, как их пишет настольный `fastboot`. */
private fun vbmetaFlagNames(flags: Int): String = listOfNotNull(
    "--disable-verity".takeIf { flags and 1 != 0 },
    "--disable-verification".takeIf { flags and 2 != 0 },
).joinToString(" ")

@Composable
private fun baseStepText(step: TerminalPlanStep): String = when (step) {
    is TerminalPlanStep.FastbootWire -> if (step.command == SET_ACTIVE_DEFAULT) {
        stringResource(R.string.command_step_set_active_default)
    } else {
        stringResource(R.string.command_step_fastboot_wire, step.command)
    }
    is TerminalPlanStep.FastbootGetvar -> stringResource(R.string.command_step_fastboot_getvar, step.variable)
    TerminalPlanStep.FastbootGetvarAll -> stringResource(R.string.command_step_fastboot_getvar_all)
    is TerminalPlanStep.FastbootFlashImage ->
        stringResource(R.string.command_step_fastboot_flash, step.partition, step.imageName ?: "—")
    is TerminalPlanStep.FastbootBootImage -> stringResource(R.string.command_step_fastboot_boot, step.imageName)
    is TerminalPlanStep.FastbootFetch ->
        stringResource(R.string.command_step_fastboot_fetch, step.partition, step.destinationName)
    is TerminalPlanStep.FastbootStage -> stringResource(R.string.command_step_fastboot_stage, step.fileName)
    is TerminalPlanStep.FastbootGetStaged ->
        stringResource(R.string.command_step_fastboot_get_staged, step.destinationName)
    is TerminalPlanStep.AdbShellCommand -> stringResource(R.string.command_step_adb_shell, step.command)
    is TerminalPlanStep.AdbInteractiveShell -> when (step.mode) {
        ShellMode.RAW -> stringResource(R.string.command_step_adb_shell_raw)
        ShellMode.LEGACY -> stringResource(R.string.command_step_adb_shell_legacy)
        ShellMode.PTY -> step.firstLine
            ?.let { stringResource(R.string.command_step_adb_interactive_line, it) }
            ?: stringResource(R.string.command_step_adb_interactive)
    }
    is TerminalPlanStep.LocalReport -> stringResource(
        R.string.command_step_local_report,
        "${step.tool.prefix} ${localReportName(step.kind)}",
    )
    is TerminalPlanStep.AdbService -> stringResource(R.string.command_step_adb_service, step.service)
    is TerminalPlanStep.AdbReboot -> stringResource(R.string.command_step_adb_reboot, step.target)
    is TerminalPlanStep.AdbPull -> (
        step.localName
            ?.let { stringResource(R.string.command_step_adb_pull_named, step.remotePaths.joinToString(" "), it) }
            ?: stringResource(R.string.command_step_adb_pull, step.remotePaths.joinToString(" "))
        ) + (if (step.preserveAttributes) stringResource(R.string.command_step_adb_keep_attributes) else "") +
        compressionNote(step.compression, PULL_COMPRESSION)
    is TerminalPlanStep.AdbSync -> stringResource(
        if (step.listOnly) R.string.command_step_adb_sync_list else R.string.command_step_adb_sync,
        step.partition,
    ) + dryRunNote(step.dryRun) + compressionNote(step.compression, PUSH_COMPRESSION)
    is TerminalPlanStep.AdbPush -> stringResource(
        if (step.sync) R.string.command_step_adb_push_sync else R.string.command_step_adb_push,
        step.localNames.joinToString(" "),
        step.remotePath,
    ) + dryRunNote(step.dryRun) + compressionNote(step.compression, PUSH_COMPRESSION)
    is TerminalPlanStep.AdbSideload -> stringResource(R.string.command_step_adb_sideload, step.archiveName)
    is TerminalPlanStep.FastbootFlashVendorRamdisk,
    is TerminalPlanStep.FastbootWipeSuper,
    is TerminalPlanStep.FastbootFlashAll,
    is TerminalPlanStep.FastbootFormat,
    is TerminalPlanStep.FastbootWipe,
    -> hostScenarioText(step)
    is TerminalPlanStep.AdbInstall -> stringResource(
        if (step.several) R.string.command_step_adb_install_multiple else R.string.command_step_adb_install,
        step.names.joinToString(", "),
        step.options.joinToString(" ").ifEmpty { "—" },
    )
}

/** Сценарии хоста `fastboot`: что соберёт приложение и что прошьёт. */
@Composable
private fun hostScenarioText(step: TerminalPlanStep): String = when (step) {
    is TerminalPlanStep.FastbootFlashVendorRamdisk -> stringResource(
        R.string.command_step_fastboot_vendor_ramdisk,
        step.ramdisk,
        step.imageName,
        step.slot.ifEmpty { "—" },
    )
    is TerminalPlanStep.FastbootWipeSuper -> stringResource(
        R.string.command_step_fastboot_wipe_super,
        step.imageName,
        step.slot.ifEmpty { "—" },
    )
    is TerminalPlanStep.FastbootFlashAll -> step.archiveName
        ?.let { stringResource(R.string.command_step_fastboot_update, it, flashAllOptions(step)) }
        ?: stringResource(R.string.command_step_fastboot_flashall, flashAllOptions(step))
    is TerminalPlanStep.FastbootFormat -> stringResource(
        R.string.command_step_fastboot_format,
        step.partition,
        step.fileSystem ?: "—",
        step.size ?: "—",
        formatOptions(step.slot, step.sparseLimit, step.fsOptions),
    )
    is TerminalPlanStep.FastbootWipe -> stringResource(
        R.string.command_step_fastboot_wipe,
        step.then.joinToString(", ").ifEmpty { "—" },
        (listOfNotNull("--force".takeIf { step.force }) + formatOptions("", step.sparseLimit, step.fsOptions))
            .filter { it != "—" }.joinToString(" ").ifEmpty { "—" },
    )
    else -> step.toString()
}

/** Исход — в той части, которой коснулась команда, тем же текстом, что и у кнопок. */
@Composable
private fun CommandOutcome(area: DesktopCommandArea?, outcomes: DesktopCommandOutcomes) {
    when (area) {
        null -> Unit
        // Открытая оболочка может быть другого режима, чем просили: вторую
        // рядом не открыть. Какой она на деле — видно здесь.
        DesktopCommandArea.ADB_TERMINAL -> if (outcomes.terminal.active) {
            CommandLine(
                stringResource(R.string.command_terminal_open, terminalModeText(outcomes.terminal.mode)),
            )
        }
        DesktopCommandArea.FASTBOOT -> FastbootOutcomePane(outcomes.fastboot)
        DesktopCommandArea.ADB_SHELL -> ShellOutcome(outcomes.shell)
        DesktopCommandArea.ADB_SERVICE -> CommandLine(rawServiceText(outcomes.service))
        DesktopCommandArea.ADB_REBOOT -> CommandLine(rebootText(outcomes.reboot))
        DesktopCommandArea.ADB_TRANSFER -> TransferOutcome(outcomes.transfer)
        DesktopCommandArea.ADB_SIDELOAD -> CommandLine(sideloadText(outcomes.sideload))
        DesktopCommandArea.ADB_INSTALL -> CommandLine(installText(outcomes.install))
    }
}

private fun localReportName(kind: LocalReportKind): String = when (kind) {
    LocalReportKind.DEVICES -> "devices"
    LocalReportKind.DEVICES_LONG -> "devices -l"
    LocalReportKind.GET_STATE -> "get-state"
    LocalReportKind.STATUS -> "status"
}

/** Итог переноса — числа и строки настольного клиента, как он их пишет. */
@Composable
private fun TransferOutcome(state: AdbTransferState) {
    when (state) {
        AdbTransferState.None -> Unit
        is AdbTransferState.Running -> CommandLine(stringResource(R.string.transfer_running, state.what))
        is AdbTransferState.Failed -> CommandLine(stringResource(R.string.transfer_failed, state.what, state.detail))
        is AdbTransferState.Finished -> {
            val report = state.report
            CommandLine(
                stringResource(
                    if (report.success) R.string.transfer_done else R.string.transfer_done_with_errors,
                    report.files,
                    report.bytes,
                    report.skipped,
                ),
            )
            if (report.messages.isNotEmpty()) {
                SelectionContainer {
                    Text(
                        text = report.messages.joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
        }
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

/** `-n`: проба `SEND_V2`, устройство читает данные и ничего не пишет. */
@Composable
private fun dryRunNote(dryRun: Boolean): String =
    if (dryRun) stringResource(R.string.command_step_adb_dry_run) else ""

/** `-z`/`-Z`, если они меняют умолчание команды: `push` и `sync` сжимают, `pull` — нет. */
@Composable
private fun compressionNote(compression: String, default: String): String =
    if (compression != default) stringResource(R.string.command_step_adb_compression, compression) else ""

private const val PUSH_COMPRESSION = "any"
private const val PULL_COMPRESSION = "none"

/** Опции `flashall`/`update` словами команды: они не переводятся. */
private fun formatOptions(slot: String, sparseLimit: Long?, fsOptions: Set<String>): String = listOfNotNull(
    slot.takeIf { it.isNotEmpty() }?.let { "--slot $it" },
    sparseLimit?.let { "-S $it" },
    fsOptions.takeIf { it.isNotEmpty() }?.let { "--fs-options=${it.joinToString(",")}" },
).joinToString(" ").ifEmpty { "—" }

private fun flashAllOptions(step: TerminalPlanStep.FastbootFlashAll): String = listOfNotNull(
    step.slot.takeIf { it.isNotEmpty() }?.let { "--slot $it" },
    "--force".takeIf { step.force },
    "--skip-secondary".takeIf { step.skipSecondary },
    "--skip-reboot".takeIf { step.skipReboot },
    "--disable-verity".takeIf { step.vbmetaFlags and 1 != 0 },
    "--disable-verification".takeIf { step.vbmetaFlags and 2 != 0 },
    step.sparseLimit?.let { "-S $it" },
    "-w".takeIf { step.wipe },
    step.fsOptions.takeIf { it.isNotEmpty() }?.let { "--fs-options=${it.joinToString(",")}" },
).joinToString(" ").ifEmpty { "—" }
