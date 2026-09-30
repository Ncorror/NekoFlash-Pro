package io.github.ncorror.nekoflash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ncorror.nekoflash.terminal.VtRow
import io.github.ncorror.nekoflash.terminal.VtSnapshot
import io.github.ncorror.nekoflash.terminal.VtStyle

/**
 * Экран терминала: строки эмулятора с цветами и курсором.
 *
 * Вывод занимает свободное пространство раздела и имеет собственную прокрутку.
 * Экран едет за последней строкой, пока его не пролистали вверх.
 * Сколько столбцов и строк в него помещается, меряется моноширинным шрифтом и
 * уходит [onResize] — устройство узнаёт размер окна, как от настольного клиента.
 */
@Composable
internal fun TerminalScreen(
    screen: VtSnapshot,
    placeholder: String,
    showCursor: Boolean,
    onResize: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
    onTap: () -> Unit = {},
) {
    val colors = TerminalColors(foreground = TerminalPalette.foreground, background = TerminalPalette.background)
    val terminalFont = rememberTerminalFontFamily()
    val style = TextStyle(
        fontFamily = terminalFont,
        fontSize = TEXT_SIZE,
        lineHeight = LINE_HEIGHT,
        color = colors.foreground,
    )
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
        modifier = modifier
            .fillMaxWidth()
            .background(colors.background)
            .observeTaps(onTap),
    ) {
        val cell = remember(style) { measurer.measure("M", style).size }
        val inner = with(density) { (maxWidth - PADDING * 2).toPx() to (maxHeight - PADDING * 2).toPx() }
        val columns = (inner.first / cell.width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
        val rows = (inner.second / cell.height.coerceAtLeast(1)).toInt().coerceAtLeast(1)
        LaunchedEffect(columns, rows) { onResize(columns, rows) }
        if (screen.rows.isEmpty()) {
            // Подсказка — обычный текст с переносом, а не строка терминала.
            Text(
                text = placeholder,
                style = style.copy(color = TerminalPalette.muted),
                modifier = Modifier.padding(PADDING),
            )
        } else {
            SelectionContainer(
                modifier = Modifier
                    .verticalScroll(scroll)
                    .horizontalScroll(rememberScrollState())
                    .padding(PADDING),
            ) {
                Text(text = annotated(screen, showCursor, colors), style = style, softWrap = false)
            }
        }
    }
}

/**
 * Касание экрана открывает клавиатуру, а долгое нажатие остаётся выделению
 * текста: касание только наблюдается, событие не съедается.
 */
private fun Modifier.observeTaps(onTap: () -> Unit): Modifier = pointerInput(onTap) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val up = waitForUpOrCancellation(pass = PointerEventPass.Initial)
        val quick = up != null && up.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis
        if (quick && (up!!.position - down.position).getDistance() < viewConfiguration.touchSlop) onTap()
    }
}

/**
 * Цвета терминала — свои, тёмные в любой теме, как у NetHunter и Termux:
 * вывод программ рассчитан на тёмный фон, и на светлом жёлтый и белый текст
 * `ls --color` пропадали бы.
 */
@Suppress("MagicNumber")
internal object TerminalPalette {
    val background = Color(0xFF0C0C10)
    val chrome = Color(0xFF16161D)
    val foreground = Color(0xFFE4E4E7)
    val muted = Color(0xFF8B8B96)

    /** Метка живой вкладки — зелёный, как у приглашения. */
    val live = Color(0xFF23D18B)
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

private val PADDING = 8.dp
private val TEXT_SIZE = 13.sp
private val LINE_HEIGHT = 16.sp

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

/**
 * 16 цветов — читаемые на тёмном фоне: у классической палитры xterm синий
 * `#0000EE` на чёрном почти не виден, а каталоги в `ls` именно синие.
 */
@Suppress("MagicNumber")
private val BASE16 = intArrayOf(
    0x000000, 0xCD3131, 0x0DBC79, 0xE5E510, 0x2472C8, 0xBC3FBC, 0x11A8CD, 0xE5E5E5,
    0x666666, 0xF14C4C, 0x23D18B, 0xF5F543, 0x3B8EEA, 0xD670D6, 0x29B8DB, 0xFFFFFF,
)
