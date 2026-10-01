package io.github.ncorror.nekoflash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.ncorror.nekoflash.R
import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticEvent
import kotlinx.coroutines.delay

/**
 * Raw diagnostics view. Nothing is redacted, shortened or field-filtered here.
 * The separate export action is explicitly the cleaned/shareable report.
 */
@Composable
internal fun RawLogsWorkspace(
    source: RecentEvents,
    onExportCleaned: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val events by produceState(initialValue = emptyList<DiagnosticEvent>(), source) {
        while (true) {
            value = source.snapshot()
            delay(REFRESH_MILLIS)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = LOG_HEADER_HORIZONTAL, vertical = LOG_HEADER_VERTICAL),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.logs_raw_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Button(onClick = onExportCleaned) {
                Text(stringResource(R.string.logs_export_cleaned))
            }
        }
        Text(
            text = stringResource(R.string.logs_raw_note),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = LOG_HEADER_HORIZONTAL, vertical = LOG_NOTE_VERTICAL),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = LOG_LIST_HORIZONTAL, vertical = LOG_LIST_VERTICAL),
            verticalArrangement = Arrangement.spacedBy(LOG_LINE_GAP),
        ) {
            itemsIndexed(events) { _, event ->
                Text(
                    text = rawLine(event),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

private fun rawLine(event: DiagnosticEvent): String = buildString {
    append(event.timestamp)
    append(" ")
    append(event.category)
    append(": ")
    append(event.message)
    event.targetId?.let { append(" target=").append(it.value) }
    event.sessionGeneration?.let { append(" generation=").append(it.value) }
    event.fields.forEach { (key, value) ->
        append(" ").append(key).append("=").append(value)
    }
}

private const val REFRESH_MILLIS = 500L
private val LOG_HEADER_HORIZONTAL = 12.dp
private val LOG_HEADER_VERTICAL = 8.dp
private val LOG_NOTE_VERTICAL = 4.dp
private val LOG_LIST_HORIZONTAL = 10.dp
private val LOG_LIST_VERTICAL = 6.dp
private val LOG_LINE_GAP = 3.dp
