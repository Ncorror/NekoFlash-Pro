package io.github.ncorror.nekoflash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.ncorror.nekoflash.terminal.VtRow
import io.github.ncorror.nekoflash.terminal.VtSnapshot
import io.github.ncorror.nekoflash.terminal.VtStyle
import kotlinx.coroutines.delay

/**
 * VT renderer for the live ADB PTY.
 *
 * The cursor, not the last snapshot row, is the live tail. Current-grid rows
 * below the cursor may be blank, so following the last row would scroll toward
 * empty space after IME/lifecycle resize. Manual user scrolling disables cursor
 * following until the cursor becomes visible again.
 */
@Composable
internal fun TerminalScreen(
    screen: VtSnapshot,
    placeholder: String,
    showCursor: Boolean,
    onResize: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
    onTap: () -> Unit = {},
    onPaste: () -> Unit = {},
) {
    val colors = TerminalColors(TerminalPalette.foreground, TerminalPalette.background)
    val style = TextStyle(
        fontFamily = rememberTerminalFontFamily(),
        fontSize = TEXT_SIZE,
        lineHeight = LINE_HEIGHT,
        color = colors.foreground,
    )
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .terminalTap(onTap, onPaste),
    ) {
        TerminalViewport(
            screen = screen,
            placeholder = placeholder,
            showCursor = showCursor,
            onResize = onResize,
            style = style,
            colors = colors,
            width = maxWidth,
            height = maxHeight,
        )
    }
}

@Composable
private fun TerminalViewport(
    screen: VtSnapshot,
    placeholder: String,
    showCursor: Boolean,
    onResize: (Int, Int) -> Unit,
    style: TextStyle,
    colors: TerminalColors,
    width: Dp,
    height: Dp,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val cell = remember(style) { measurer.measure("M", style).size }
    val inner = with(density) {
        (width - PADDING * 2).toPx().coerceAtLeast(1f) to
            (height - PADDING * 2).toPx().coerceAtLeast(1f)
    }
    val columns = (inner.first / cell.width.coerceAtLeast(1)).toInt().coerceAtLeast(MIN_GRID)
    val rows = (inner.second / cell.height.coerceAtLeast(1)).toInt().coerceAtLeast(MIN_GRID)
    LaunchedEffect(columns, rows) {
        delay(RESIZE_SETTLE_MS)
        onResize(columns, rows)
    }

    if (screen.rows.isEmpty()) {
        Text(
            text = placeholder,
            style = style.copy(color = TerminalPalette.muted),
            modifier = Modifier.padding(PADDING),
        )
    } else {
        TerminalRows(screen, showCursor, style, colors)
    }
}

@Composable
private fun TerminalRows(
    screen: VtSnapshot,
    showCursor: Boolean,
    style: TextStyle,
    colors: TerminalColors,
) {
    val listState = rememberLazyListState()
    val followCursor = remember { mutableStateOf(true) }
    val userWasScrolling = remember { mutableStateOf(false) }
    val resumeGeneration = remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                followCursor.value = true
                resumeGeneration.value += 1
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(listState, screen.cursorRow) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling) {
                userWasScrolling.value = true
            } else if (userWasScrolling.value) {
                userWasScrolling.value = false
                val visible = listState.layoutInfo.visibleItemsInfo
                followCursor.value = visible.isEmpty() || visible.any { it.index == screen.cursorRow }
            }
        }
    }
    LaunchedEffect(resumeGeneration.value) {
        if (screen.rows.isNotEmpty()) {
            delay(RESUME_SCROLL_SETTLE_MS)
            val cursor = screen.cursorRow.coerceIn(0, screen.rows.lastIndex)
            listState.scrollToItem(cursor)
        }
    }
    LaunchedEffect(
        screen.rows.size,
        screen.cursorRow,
        screen.rows.getOrNull(screen.cursorRow)?.text,
    ) {
        if (followCursor.value && screen.rows.isNotEmpty()) {
            val cursor = screen.cursorRow.coerceIn(0, screen.rows.lastIndex)
            listState.scrollToItem(cursor)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(PADDING),
    ) {
        itemsIndexed(screen.rows) { index, row ->
            SelectionContainer {
                Text(
                    text = annotatedRow(row, index, screen, showCursor, colors),
                    style = style,
                    softWrap = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Dark terminal palette independent from the app light/dark surface theme. */
@Suppress("MagicNumber")
internal object TerminalPalette {
    val background = Color(0xFF080A0D)
    val chrome = Color(0xFF0D1117)
    val foreground = Color(0xFFE6EDF3)
    val muted = Color(0xFF7D8590)
    val live = Color(0xFF22C55E)
}

private class TerminalColors(val foreground: Color, val background: Color)

private fun annotatedRow(
    row: VtRow,
    rowIndex: Int,
    screen: VtSnapshot,
    showCursor: Boolean,
    colors: TerminalColors,
): AnnotatedString = buildAnnotatedString {
    val cursorHere = showCursor && screen.cursorVisible && rowIndex == screen.cursorRow
    val rendered = if (cursorHere) row.padTo(screen.cursorColumn + 1) else row
    appendRow(rendered, colors)
    if (cursorHere && screen.cursorColumn < length) {
        addStyle(
            SpanStyle(color = colors.background, background = colors.foreground),
            screen.cursorColumn,
            screen.cursorColumn + 1,
        )
    }
}

private fun VtRow.padTo(width: Int): VtRow = if (text.length >= width) this else copy(text = text.padEnd(width))

private fun AnnotatedString.Builder.appendRow(row: VtRow, colors: TerminalColors) {
    val start = length
    append(row.text)
    row.runs.forEach { run ->
        addStyle(spanOf(run.style, colors), start + run.start, start + run.end)
    }
}

private fun spanOf(style: VtStyle, colors: TerminalColors): SpanStyle {
    val foreground = colorOf(style.foreground) ?: colors.foreground
    val background = colorOf(style.background)
    val (text, fill) = if (style.inverse) {
        (background ?: colors.background) to foreground
    } else {
        foreground to background
    }
    return SpanStyle(
        color = if (style.dim) text.copy(alpha = DIM_ALPHA) else text,
        background = fill ?: Color.Unspecified,
        fontWeight = if (style.bold) FontWeight.Bold else null,
        fontStyle = if (style.italic) FontStyle.Italic else null,
        textDecoration = if (style.underline) TextDecoration.Underline else null,
    )
}

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

private val PADDING = 4.dp
private val TEXT_SIZE = 11.sp
private val LINE_HEIGHT = 13.sp
private const val MIN_GRID = 4
private const val RESIZE_SETTLE_MS = 160L
private const val RESUME_SCROLL_SETTLE_MS = 48L
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
@Suppress("MagicNumber")
private val CUBE = intArrayOf(0, 95, 135, 175, 215, 255)
@Suppress("MagicNumber")
private val BASE16 = intArrayOf(
    0x000000, 0xCD3131, 0x0DBC79, 0xE5E510, 0x2472C8, 0xBC3FBC, 0x11A8CD, 0xE5E5E5,
    0x666666, 0xF14C4C, 0x23D18B, 0xF5F543, 0x3B8EEA, 0xD670D6, 0x29B8DB, 0xFFFFFF,
)
