package io.github.ncorror.nekoflash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Одна задача — одна карточка с заголовком.
 *
 * Разделы вкладки раньше шли одной лентой полей и кнопок во всю ширину, и где
 * кончается проброс и начинается перезагрузка, было видно только по тексту.
 * Карточка отделяет задачу воздухом, а заголовок остаётся заголовком для
 * TalkBack.
 */
@Composable
internal fun ToolCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionHeading(text = title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** Состояние задачи под заголовком; пустое не показывается. */
@Composable
internal fun StatusLine(text: String) {
    if (text.isNotBlank()) Text(text = text, style = MaterialTheme.typography.bodyMedium)
}

/** Пояснение — мельче и тише действия, чтобы не спорить с ним за взгляд. */
@Composable
internal fun NoteText(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Кнопки в ряд с переносом, а не столбиком во всю ширину. */
@Composable
internal fun ActionRow(content: @Composable RowScope.() -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = { content() },
    )
}
