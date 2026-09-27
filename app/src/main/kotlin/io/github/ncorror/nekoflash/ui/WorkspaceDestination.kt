package io.github.ncorror.nekoflash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.ncorror.nekoflash.R

/**
 * Разделы рабочего места.
 *
 * Четыре, и это не выдумка: ровно эти четыре названия уже лежали в строках с
 * Phase 1 (`nav_device`, `nav_terminal`, `nav_operations`, `nav_diagnostics`) —
 * они были нарисованы как неподвижный список, потому что переключать было
 * нечего. Теперь есть.
 *
 * Порядок — по тому, как часто в раздел заходят, а не по алфавиту: устройство
 * открыто почти всегда, диагностика нужна в конце прогона.
 */
internal enum class WorkspaceDestination(val label: Int) {
    DEVICE(R.string.nav_device),
    TERMINAL(R.string.nav_terminal),
    OPERATIONS(R.string.nav_operations),
    DIAGNOSTICS(R.string.nav_diagnostics),
}

/**
 * Переключатель разделов для узкого экрана.
 *
 * Вкладки, а не нижняя панель: панель требует значка у каждого пункта, значков
 * в сборке нет, и рисовать их сейчас значило бы решать задачу оформления вместо
 * задачи навигации. Вкладки обходятся словами, а слова здесь точнее любого
 * значка — «Операции» и «Диагностика» пиктограммой не различить.
 *
 * **Прокручиваемые, а не равной ширины**, и это исправление первого же взгляда
 * на экран: четыре слова в четверть ширины телефона не помещаются, и Compose
 * переносил их посреди слова — «Устройст / во». Прокрутка отдаёт каждой вкладке
 * столько, сколько занимает слово; за краем остаётся край, а не обрубок.
 */
@Composable
internal fun DestinationTabs(
    current: WorkspaceDestination,
    onSelect: (WorkspaceDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    PrimaryScrollableTabRow(
        selectedTabIndex = current.ordinal,
        modifier = modifier,
        edgePadding = 0.dp,
    ) {
        WorkspaceDestination.entries.forEach { destination ->
            Tab(
                selected = destination == current,
                onClick = { onSelect(destination) },
                text = { Text(stringResource(destination.label)) },
            )
        }
    }
}

/**
 * Тот же переключатель для широкого экрана.
 *
 * Сбоку, а не сверху: на планшете и в раскладке с клавиатурой вертикальный
 * список не отъедает высоту у самой работы, ради которой экран и открыт.
 */
@Composable
internal fun DestinationRail(
    current: WorkspaceDestination,
    onSelect: (WorkspaceDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.nav_workspace),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            WorkspaceDestination.entries.forEach { destination ->
                RailItem(destination, destination == current) { onSelect(destination) }
            }
        }
    }
}

/**
 * Пункт бокового списка.
 *
 * Выбранный отличается **и** цветом фона, **и** цветом текста: одного цвета
 * фона мало — в тёмной схеме разница между двумя соседними поверхностями на
 * ярком свету почти не видна.
 */
@Composable
private fun RailItem(destination: WorkspaceDestination, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
    ) {
        Text(
            text = stringResource(destination.label),
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}
