package io.github.ncorror.nekoflash.ui

import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.ncorror.nekoflash.R
import io.github.ncorror.nekoflash.adb.AdbSideloadState
import io.github.ncorror.nekoflash.protocol.adb.AdbSideloadOutcome

/** Передача пакета в Recovery: состояние и три действия над ним. */
data class SideloadPanel(
    val state: AdbSideloadState = AdbSideloadState.None,
    /** Отдать пакет заданного размера в байтах. */
    val onSend: (Long) -> Unit = {},
    /** Отдать пакет, который выберет пользователь. */
    val onChoose: () -> Unit = {},
    val onCancel: () -> Unit = {},
)

/**
 * Размеры пакетов, которые приложение умеет породить само.
 *
 * Малый проходит одним блоком, большой — двумястами пятьюдесятью шестью: на нём
 * видно и повторные запросы блоков, и успевает оператор выдернуть кабель
 * посреди передачи, если гейт этого требует.
 */
private const val SMALL_PACKAGE_BYTES = 64L * 1024L
private const val LARGE_PACKAGE_BYTES = 16L * 1024L * 1024L

/**
 * Sideload на экране.
 *
 * Главное здесь — не прогресс, а то, что **исход передачи не выдаётся за исход
 * установки**: `DONEDONE` кончает передачу, и сказать, установилось ли, может
 * только Recovery (`03` §6, инвариант 1). Поэтому под всяким исходом, которому
 * нужна проверка, стоит напоминание прочитать вердикт, а не слово «готово».
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

    LabelledValue(label = stringResource(R.string.sideload_label), value = sideloadText(state))

    Button(onClick = { panel.onSend(SMALL_PACKAGE_BYTES) }, enabled = !busy) {
        Text(stringResource(R.string.sideload_send_small))
    }
    Button(onClick = { panel.onSend(LARGE_PACKAGE_BYTES) }, enabled = !busy) {
        Text(stringResource(R.string.sideload_send_large))
    }
    Button(onClick = panel.onChoose, enabled = !busy) {
        Text(stringResource(R.string.sideload_choose))
    }
    if (running != null) {
        SideloadCancel(running, panel.onCancel)
    }
    Text(
        text = stringResource(R.string.sideload_note),
        style = MaterialTheme.typography.bodySmall,
    )
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
private fun sideloadText(state: AdbSideloadState): String = when (state) {
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
 * Напоминание о проверке ставится по [AdbSideloadContract.requiresVerification],
 * а не по виду исхода: список того, где проверка нужна, живёт в контракте, и
 * второй его список здесь разошёлся бы с первым.
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
