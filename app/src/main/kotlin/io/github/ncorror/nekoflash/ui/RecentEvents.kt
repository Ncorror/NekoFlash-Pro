package io.github.ncorror.nekoflash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ncorror.nekoflash.R
import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticEvent
import kotlinx.coroutines.delay

/** Откуда брать недавние события. Функция, а не журнал: экран его не держит. */
fun interface RecentEvents {
    fun snapshot(): List<DiagnosticEvent>
}

/**
 * Что записывается прямо сейчас.
 *
 * Панель нужна не вместо выгрузки, а до неё: на прогоне видно, **пишется ли
 * вообще** то, ради чего прогон затеян. Не раз оказывалось, что событие в отчёт
 * не попадало — и выяснялось это уже после устройства, когда переделывать
 * дорого (`07` §6.36, где `sync_stat` не писал путь; §6.72, где дубликаты
 * считались без имён).
 *
 * **Опрашивается, а не подписывается**, и это осознанно. Журнал не отдаёт
 * потока, а заводить его пришлось бы с ограничением частоты: `logcat` даёт
 * тысячи событий в секунду, и публикация на каждое утопила бы экран ровно тем,
 * что он показывает. Опрос идёт раз в полсекунды и **только пока панель
 * открыта**: закрыл раздел — расход прекратился.
 */
@Composable
internal fun DiagnosticsPane(source: RecentEvents, modifier: Modifier = Modifier) {
    val events by produceState(initialValue = emptyList<DiagnosticEvent>(), source) {
        while (true) {
            value = source.snapshot().takeLast(SHOWN)
            delay(REFRESH_MILLIS)
        }
    }

    SectionHeading(text = stringResource(R.string.diagnostics_recent_title))
    if (events.isEmpty()) {
        Text(
            text = stringResource(R.string.diagnostics_recent_empty),
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // От свежего к старому: смотрят почти всегда последнее.
        events.asReversed().forEach { event ->
            Text(text = line(event), style = MaterialTheme.typography.bodySmall)
        }
    }
    Text(
        text = stringResource(R.string.diagnostics_recent_note, SHOWN),
        style = MaterialTheme.typography.bodySmall,
    )
}

/**
 * Одна строка события.
 *
 * Поля печатаются как есть и **не** сокращаются: через них проходит то, что
 * потом читают в отчёте, и расхождение между экраном и отчётом было бы худшим
 * из возможных — по экрану решают, что прогон удался.
 */
private fun line(event: DiagnosticEvent): String {
    val fields = event.fields.entries.joinToString(separator = " ") { (key, value) -> "$key=$value" }
    return if (fields.isEmpty()) {
        "${event.category}: ${event.message}"
    } else {
        "${event.category}: ${event.message} $fields"
    }
}

/** Сколько строк держать на экране. Больше — это уже отчёт, а его выгружают. */
private const val SHOWN = 12

private const val REFRESH_MILLIS = 500L
