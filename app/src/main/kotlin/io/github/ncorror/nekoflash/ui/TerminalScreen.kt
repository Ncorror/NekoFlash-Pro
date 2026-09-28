package io.github.ncorror.nekoflash.ui

import androidx.compose.foundation.background
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.ncorror.nekoflash.R
import io.github.ncorror.nekoflash.adb.AdbTerminalState
import io.github.ncorror.nekoflash.adb.TerminalKey
import io.github.ncorror.nekoflash.terminal.VtRow
import io.github.ncorror.nekoflash.terminal.VtSnapshot
import io.github.ncorror.nekoflash.terminal.VtStyle

/**
 * Экран терминала: строки эмулятора с цветами и курсором.
 *
 * Своей высоты и своей прокрутки: вывод живёт внутри окна, а не растягивает
 * экран. Окно само едет за последней строкой, пока его не пролистали вверх.
 * Сколько столбцов и строк в него помещается, меряется моноширинным шрифтом и
 * уходит [onResize] — устройство узнаёт размер окна, как от настольного клиента.
 */
@Composable
internal fun TerminalScreen(
    screen: VtSnapshot,
    placeholder: String,
    showCursor: Boolean,
    onResize: (Int, Int) -> Unit,
) {
    val colors = TerminalColors(
        foreground = MaterialTheme.colorScheme.onSurfaceVariant,
        background = MaterialTheme.colorScheme.surfaceVariant,
    )
    val style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, color = colors.foreground)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    val following = remember { mutableStateOf(true) }
    LaunchedEffect(scroll.isScrollInProgress) {
        if (!scroll.isScrollInProgress) following.value = scroll.value >= scroll.maxValue - FOLLOW_SLACK_PX
    }
    LaunchedEffect(scroll.maxValue) {
        if (following.value) scroll.scrollTo(scroll.maxValue)
    }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(TERMINAL_HEIGHT)
            .background(colors.background, RoundedCornerShape(8.dp)),
    ) {
        val cell = remember(style) { measurer.measure("M", style).size }
        val inner = with(density) { (maxWidth - PADDING * 2).toPx() to (maxHeight - PADDING * 2).toPx() }
        val columns = (inner.first / cell.width.coerceAtLeast(1)).toInt()
        val rows = (inner.second / cell.height.coerceAtLeast(1)).toInt()
        LaunchedEffect(columns, rows) { onResize(columns, rows) }
        SelectionContainer(
            modifier = Modifier
                .verticalScroll(scroll)
                .horizontalScroll(rememberScrollState())
                .padding(PADDING),
        ) {
            Text(
                text = if (screen.rows.isEmpty()) {
                    AnnotatedString(placeholder)
                } else {
                    annotated(screen, showCursor, colors)
                },
                style = style,
                softWrap = false,
            )
        }
    }
}

/** Вкладки: номер и то, с чего вкладка открыта; «+» — новая, «×» — закрыть. */
@Composable
internal fun TerminalTabsRow(panel: TerminalPanel, actions: TerminalActions) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        panel.tabs.forEachIndexed { index, tab ->
            FilterChip(
                selected = index == panel.selected,
                onClick = { actions.onSelectTab(index) },
                label = { Text(tabTitle(index, tab)) },
                trailingIcon = if (panel.tabs.size > 1) {
                    { CloseTab(index, actions.onCloseTab) }
                } else {
                    null
                },
            )
        }
        OutlinedButton(onClick = actions.onAddTab) { Text(stringResource(R.string.terminal_new_tab)) }
    }
}

/** «×» вкладки: цель не меньше 48 dp и название для TalkBack — «Закрыть вкладку N». */
@Composable
private fun CloseTab(index: Int, onClose: (Int) -> Unit) {
    val label = stringResource(R.string.terminal_close_tab_label, index + 1)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clickable(role = Role.Button, onClickLabel = label) { onClose(index) }
            .semantics { contentDescription = label },
    ) {
        Text(stringResource(R.string.terminal_close_tab))
    }
}

private fun tabTitle(index: Int, tab: AdbTerminalState): String {
    val name = tab.title ?: "sh"
    val mark = when {
        tab.active -> ""
        tab.ended != null -> " ·"
        else -> ""
    }
    return "${index + 1} $name$mark"
}

/** Клавиши, которых нет в строке ввода, и ввод без Enter — для `top`, `less`, `vi`. */
@Composable
internal fun TerminalKeys(enabled: Boolean, onKey: (TerminalKey) -> Unit, onType: () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        OutlinedButton(onClick = onType, enabled = enabled) { Text(stringResource(R.string.terminal_type_no_enter)) }
        for ((key, label) in KEY_LABELS) {
            val spoken = stringResource(keySpoken(key))
            OutlinedButton(
                onClick = { onKey(key) },
                enabled = enabled,
                modifier = Modifier.semantics { contentDescription = spoken },
            ) { Text(label) }
        }
    }
}

private class TerminalColors(val foreground: Color, val background: Color)

private fun annotated(screen: VtSnapshot, showCursor: Boolean, colors: TerminalColors): AnnotatedString =
    buildAnnotatedString {
        screen.rows.forEachIndexed { index, row ->
            if (index > 0) append('\n')
            val cursorHere = showCursor && screen.cursorVisible && index == screen.cursorRow
            val rowStart = length
            appendRow(if (cursorHere) row.padTo(screen.cursorColumn + 1) else row, colors)
            if (cursorHere) {
                val at = rowStart + screen.cursorColumn
                addStyle(SpanStyle(color = colors.background, background = colors.foreground), at, at + 1)
            }
        }
    }

private fun VtRow.padTo(width: Int): VtRow = if (text.length >= width) this else copy(text = text.padEnd(width))

private fun AnnotatedString.Builder.appendRow(row: VtRow, colors: TerminalColors) {
    val start = length
    append(row.text)
    for (run in row.runs) addStyle(spanOf(run.style, colors), start + run.start, start + run.end)
}

private fun spanOf(style: VtStyle, colors: TerminalColors): SpanStyle {
    val foreground = colorOf(style.foreground) ?: colors.foreground
    val background = colorOf(style.background)
    val (text, fill) = if (style.inverse) (background ?: colors.background) to foreground else foreground to background
    return SpanStyle(
        color = if (style.dim) text.copy(alpha = DIM_ALPHA) else text,
        background = fill ?: Color.Unspecified,
        fontWeight = if (style.bold) FontWeight.Bold else null,
        fontStyle = if (style.italic) FontStyle.Italic else null,
        textDecoration = if (style.underline) TextDecoration.Underline else null,
    )
}

/** Палитра xterm: 16 цветов, куб 6×6×6 и 24 оттенка серого; 24-битный — как есть. */
private fun colorOf(value: Int): Color? = when {
    value == VtStyle.DEFAULT -> null
    value and VtStyle.RGB_FLAG != 0 -> Color(OPAQUE or (value and RGB_MASK))
    value < BASE16.size -> Color(OPAQUE or BASE16[value])
    value < GRAY_START -> {
        val cube = value - BASE16.size
        val r = CUBE[cube / CUBE_AREA]
        val g = CUBE[cube / CUBE_SIDE % CUBE_SIDE]
        val b = CUBE[cube % CUBE_SIDE]
        Color(OPAQUE or (r shl RED_SHIFT) or (g shl GREEN_SHIFT) or b)
    }
    else -> {
        val gray = GRAY_BASE + (value - GRAY_START) * GRAY_STEP
        Color(OPAQUE or (gray shl RED_SHIFT) or (gray shl GREEN_SHIFT) or gray)
    }
}

/** Как клавишу назвать вслух: «↑» TalkBack читает по-разному, а то и никак. */
private fun keySpoken(key: TerminalKey): Int = when (key) {
    TerminalKey.ESCAPE -> R.string.terminal_key_escape
    TerminalKey.TAB -> R.string.terminal_key_tab
    TerminalKey.UP -> R.string.terminal_key_up
    TerminalKey.DOWN -> R.string.terminal_key_down
    TerminalKey.LEFT -> R.string.terminal_key_left
    TerminalKey.RIGHT -> R.string.terminal_key_right
    TerminalKey.CTRL_D -> R.string.terminal_key_ctrl_d
}

private val KEY_LABELS = listOf(
    TerminalKey.ESCAPE to "Esc",
    TerminalKey.TAB to "Tab",
    TerminalKey.UP to "↑",
    TerminalKey.DOWN to "↓",
    TerminalKey.LEFT to "←",
    TerminalKey.RIGHT to "→",
    TerminalKey.CTRL_D to "Ctrl+D",
)

/** Высота окна вывода: вмещает экран `logcat` и оставляет место полю ввода. */
private val TERMINAL_HEIGHT = 320.dp
private val PADDING = 12.dp

/** Насколько ниже конца можно отстоять, чтобы окно всё ещё считалось «у последней строки». */
private const val FOLLOW_SLACK_PX = 48
private const val DIM_ALPHA = 0.6f
private const val OPAQUE = -0x1000000
private const val RGB_MASK = 0xFFFFFF
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val CUBE_SIDE = 6
private const val CUBE_AREA = 36
private const val GRAY_START = 232
private const val GRAY_BASE = 8
private const val GRAY_STEP = 10
/** Уровни куба и 16 цветов xterm — значения из его палитры. */
@Suppress("MagicNumber")
private val CUBE = intArrayOf(0, 95, 135, 175, 215, 255)

@Suppress("MagicNumber")
private val BASE16 = intArrayOf(
    0x000000, 0xCD0000, 0x00CD00, 0xCDCD00, 0x0000EE, 0xCD00CD, 0x00CDCD, 0xE5E5E5,
    0x7F7F7F, 0xFF0000, 0x00FF00, 0xFFFF00, 0x5C5CFF, 0xFF00FF, 0x00FFFF, 0xFFFFFF,
)
