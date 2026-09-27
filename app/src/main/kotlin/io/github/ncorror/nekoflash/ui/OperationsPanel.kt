package io.github.ncorror.nekoflash.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.ncorror.nekoflash.R
import io.github.ncorror.nekoflash.core.operation.MutationBoundary
import io.github.ncorror.nekoflash.core.operation.OperationOutcome
import io.github.ncorror.nekoflash.core.operation.OperationRecord

/** История операций: что шло, чем кончилось, что осталось неизвестным. */
data class OperationsPanel(
    val live: List<OperationRecord> = emptyList(),
    val history: List<OperationRecord> = emptyList(),
)

/**
 * Operations Center.
 *
 * Смысл экрана не в списке, а в одном исходе: **`UNKNOWN` показывается как
 * полноценный результат**, а не как поломка и не как неудача. «Неизвестно» — это
 * то, что бывает после границы мутации, и подменить его словом «не удалось»
 * значило бы пообещать, что устройство не изменилось (`03` §3, `06` §3).
 *
 * Живые операции стоят выше истории, потому что про них спрашивают сейчас.
 */
@Composable
internal fun OperationsSection(panel: OperationsPanel) {
    SectionHeading(
        text = stringResource(R.string.operations_title),
    )
    if (panel.live.isEmpty() && panel.history.isEmpty()) {
        // Раздел, не нарисовавший ничего, читается как поломка приложения.
        // Пустая история — это ответ, и он говорится словами: здесь ещё
        // ничего не происходило, а не «экран не работает».
        Text(
            text = stringResource(R.string.operations_empty),
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }
    panel.live.forEach { record -> OperationRow(record, live = true) }
    panel.history.forEach { record -> OperationRow(record, live = false) }
    Text(
        text = stringResource(R.string.operations_note),
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun OperationRow(record: OperationRecord, live: Boolean) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = record.intent.summary, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = if (live) liveLine(record) else finishedLine(record),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * Строка идущей операции.
 *
 * Процент показывается только когда известен объём: `null` там означает, что
 * неизвестно **сколько всего**, а не что ничего не сделано. Скорость
 * показывается только когда она измерена — по первым миллисекундам её не
 * бывает.
 */
@Composable
private fun liveLine(record: OperationRecord): String {
    val progress = record.progress
    val percent = progress?.percent
    val rate = progress?.bytesPerSecond
    val head = when {
        percent == null -> stringResource(
            R.string.operations_live_bytes,
            record.state.name,
            progress?.servedBytes ?: 0L,
        )

        else -> stringResource(R.string.operations_live_percent, record.state.name, percent)
    }
    return if (rate == null) head else head + " · " + stringResource(R.string.operations_rate, rate)
}

/**
 * Строка законченной операции.
 *
 * `UNKNOWN` получает не только своё слово, но и **вторую строку про границу
 * мутации**: без неё «неизвестно» читается как отговорка, а с ней — как факт
 * о том, что байты пошли на устройство.
 */
@Composable
private fun finishedLine(record: OperationRecord): String {
    val outcome = stringResource(
        when (record.outcome) {
            OperationOutcome.SUCCEEDED -> R.string.operations_outcome_succeeded
            OperationOutcome.FAILED -> R.string.operations_outcome_failed
            OperationOutcome.CANCELLED -> R.string.operations_outcome_cancelled
            OperationOutcome.UNKNOWN -> R.string.operations_outcome_unknown
            null -> R.string.operations_outcome_unfinished
        },
    )
    val boundary = record.mutationBoundary
    return if (boundary is MutationBoundary.Crossed) {
        outcome + "\n" + stringResource(R.string.operations_boundary, boundary.detail)
    } else {
        outcome + "\n" + stringResource(R.string.operations_untouched)
    }
}
