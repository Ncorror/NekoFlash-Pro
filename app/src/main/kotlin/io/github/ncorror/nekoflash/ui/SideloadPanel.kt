package io.github.ncorror.nekoflash.ui

import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.ncorror.nekoflash.R
import io.github.ncorror.nekoflash.adb.AdbFileState
import io.github.ncorror.nekoflash.adb.AdbSideloadState
import io.github.ncorror.nekoflash.protocol.adb.AdbRecoveryVerdict
import io.github.ncorror.nekoflash.protocol.adb.AdbSideloadOutcome

/** Передача пакета и доказательства установки из Recovery. */
data class SideloadPanel(
    val state: AdbSideloadState,
    /** Отдать пакет, который выберет пользователь. */
    val onChoose: () -> Unit,
    val onCancel: () -> Unit,
    /** Последнее состояние чтения журнала Recovery. */
    val evidence: AdbFileState = AdbFileState.None,
    /** Evidence читается только из обычного Recovery, не из Sideload peer. */
    val evidenceEnabled: Boolean = false,
    val onCaptureBaseline: () -> Unit = {},
    val onReadVerdict: () -> Unit = {},
)

/**
 * Sideload на экране.
 *
 * Главное здесь — не прогресс, а то, что **исход передачи не выдаётся за исход
 * установки**: `DONEDONE` кончает передачу, и сказать, установилось ли, может
 * только Recovery (`03` §6, инвариант 1). Поэтому рядом с передачей снова
 * доступны две части того же сценария: база до установки и вердикт после неё.
 *
 * Отмена гаснет по границе мутации, а не по прогрессу: граница проходится,
 * когда первый блок уходит на провод, а прогресс приходит позже — на
 * подтверждение. Считать её по прогрессу значило бы предлагать отмену там, где
 * отменять уже нечего.
 */
@Composable
internal fun SideloadSection(panel: SideloadPanel) {
    val state = panel.state
    val running = state as? AdbSideloadState.Running
    // Копирование занимает экран так же, как передача, но устройство при этом
    // не тронуто: кнопки гасит и то, и другое, а формулировки у них разные.
    val busy = running != null || state is AdbSideloadState.Staging

    // До первой передачи говорить нечего: «не начинали» читалось как ошибка.
    if (state != AdbSideloadState.None) StatusLine(sideloadText(state))
    Button(onClick = panel.onChoose, enabled = !busy) {
        Text(stringResource(R.string.sideload_choose))
    }
    if (running != null) {
        SideloadCancel(running, panel.onCancel)
    }
    NoteText(stringResource(R.string.sideload_note))
    RecoveryEvidenceSection(panel)
}

/**
 * Корреляция Recovery — часть того же сценария, а не старого файлового экрана.
 *
 * Кнопки доступны только когда баннер прямо говорит `recovery::`. В режиме
 * `sideload::` peer почти ничего больше не умеет, поэтому пытаться читать там
 * журнал — не «больше возможностей», а заведомо неверный транспортный момент.
 */
@Composable
private fun RecoveryEvidenceSection(panel: SideloadPanel) {
    val busy = panel.evidence is AdbFileState.Busy
    val status = recoveryEvidenceText(panel.evidence)
    if (status.isNotEmpty()) StatusLine(status)
    NoteText(
        stringResource(
            if (panel.evidenceEnabled) R.string.recovery_log_note else R.string.recovery_log_needs_adb,
        ),
    )
    ActionRow {
        OutlinedButton(
            onClick = panel.onCaptureBaseline,
            enabled = panel.evidenceEnabled && !busy,
        ) {
            Text(stringResource(R.string.recovery_baseline))
        }
        Button(
            onClick = panel.onReadVerdict,
            enabled = panel.evidenceEnabled && !busy,
        ) {
            Text(stringResource(R.string.recovery_verdict_read))
        }
    }
}

/** Вердикт показывается отдельно от сырого журнала; сам журнал наружу не выходит. */
@Composable
private fun recoveryEvidenceText(state: AdbFileState): String = when (state) {
    AdbFileState.None -> ""
    is AdbFileState.Busy -> stringResource(R.string.recovery_evidence_busy)
    is AdbFileState.Failed -> stringResource(R.string.recovery_evidence_failed, state.reason)
    is AdbFileState.Verdict -> {
        val head = stringResource(
            when (state.verdict) {
                AdbRecoveryVerdict.SUCCESS -> R.string.recovery_verdict_success
                AdbRecoveryVerdict.FAILED -> R.string.recovery_verdict_failed
                AdbRecoveryVerdict.UNKNOWN -> R.string.recovery_verdict_unknown
            },
            state.detail,
        )
        state.evidence?.let { evidence ->
            head + "\n" + stringResource(R.string.recovery_verdict_evidence, evidence)
        } ?: head
    }
    // Остальные состояния принадлежат старым однофайловым Sync-действиям и
    // не должны внезапно объявляться доказательством установки.
    else -> ""
}

/**
 * Отмена — и объяснение, когда её больше нет.
 *
 * Кнопка не исчезает молча: пропавший элемент читается как сбой интерфейса, а
 * здесь произошло событие, о котором оператор обязан узнать.
 */
@Composable
private fun SideloadCancel(state: AdbSideloadState.Running, onCancel: () -> Unit) {
    if (state.cancellable) {
        Button(onClick = onCancel) {
            Text(stringResource(R.string.sideload_cancel))
        }
    } else {
        Text(
            text = stringResource(R.string.sideload_cancel_gone),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
internal fun sideloadText(state: AdbSideloadState): String = when (state) {
    AdbSideloadState.None -> stringResource(R.string.sideload_idle)

    is AdbSideloadState.Running -> stringResource(
        R.string.sideload_running,
        state.progress.percent,
        state.progress.uniqueBlocks,
        state.progress.totalBlocks,
        state.progress.servedBytes,
    )

    is AdbSideloadState.Finished -> finishedText(state)

    is AdbSideloadState.Staging ->
        stringResource(R.string.sideload_staging, state.bytes, state.name) + "\n" +
            stringResource(R.string.sideload_staging_note)

    is AdbSideloadState.Refused -> stringResource(R.string.sideload_refused, state.detail)
}

/**
 * Исход передачи словами — и отдельной строкой то, чего он **не** говорит.
 *
 * Напоминание о проверке ставится по состоянию контроллера, а не по красивому
 * проценту: список исходов, которым нужна проверка, живёт в протокольном
 * контракте и второй его список здесь не заводится.
 */
@Composable
private fun finishedText(state: AdbSideloadState.Finished): String {
    val head = outcomeText(state.outcome)
    return if (state.verificationPending) head + "\n" + stringResource(R.string.sideload_verify) else head
}

@Composable
private fun outcomeText(outcome: AdbSideloadOutcome): String = when (outcome) {
    AdbSideloadOutcome.TransferComplete -> stringResource(R.string.sideload_transfer_complete)

    is AdbSideloadOutcome.ClosedBeforeDoneDone ->
        stringResource(R.string.sideload_closed_early, outcome.percent)

    is AdbSideloadOutcome.InterruptedAfterPayload ->
        stringResource(R.string.sideload_interrupted, outcome.percent)

    AdbSideloadOutcome.Cancelled -> stringResource(R.string.sideload_cancelled)

    is AdbSideloadOutcome.NotInSideloadMode ->
        stringResource(R.string.sideload_not_sideload, outcome.mode)

    is AdbSideloadOutcome.Failed ->
        stringResource(R.string.sideload_failed, outcome.detail, outcome.kind.name)
}
