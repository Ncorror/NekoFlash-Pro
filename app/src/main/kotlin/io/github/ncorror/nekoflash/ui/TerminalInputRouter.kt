package io.github.ncorror.nekoflash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.ncorror.nekoflash.adb.TerminalKey

/** Routes one IME surface either to NekoFlash host CLI or to a live remote PTY. */
internal class TerminalInputRouter(
    private val ptyInput: Boolean,
    private val hostEditor: HostLineEditor,
    private val actions: TerminalActions,
    private val controlActive: MutableState<Boolean>,
    private val altActive: MutableState<Boolean>,
) {
    fun onText(text: String) {
        if (ptyInput) dispatchPtyText(text) else dispatchHostText(text)
    }

    fun onEnter() {
        clearModifiers()
        if (ptyInput) actions.onType("\r") else hostEditor.submit()
    }

    fun onBackspace() {
        if (ptyInput) {
            clearModifiers()
            actions.onType(DELETE_CHARACTER)
        } else {
            hostEditor.backspace(controlActive.value || altActive.value)
            clearModifiers()
        }
    }

    fun onSpecialKey(key: TerminalKey) {
        if (ptyInput) {
            val modified = modifiedNavigationSequence(key, controlActive.value, altActive.value)
            clearModifiers()
            if (modified == null) actions.onKey(key) else actions.onType(modified)
        } else {
            hostEditor.key(key, controlActive.value || altActive.value)
            clearModifiers()
        }
    }

    fun toggleControl() {
        controlActive.value = !controlActive.value
        if (controlActive.value) altActive.value = false
    }

    fun toggleAlt() {
        altActive.value = !altActive.value
        if (altActive.value) controlActive.value = false
    }

    private fun dispatchPtyText(text: String) {
        if (text.isEmpty()) return
        if (!controlActive.value && !altActive.value) {
            actions.onType(text)
            return
        }
        val firstEnd = text.offsetByCodePoints(0, 1)
        val first = text.substring(0, firstEnd)
        val rest = text.substring(firstEnd)
        when {
            controlActive.value -> actions.onType(controlOf(first) ?: first)
            altActive.value -> actions.onType(ESCAPE_CHARACTER + first)
        }
        clearModifiers()
        if (rest.isNotEmpty()) actions.onType(rest)
    }

    private fun dispatchHostText(text: String) {
        if (text.isEmpty()) return
        if (!controlActive.value && !altActive.value) {
            hostEditor.insert(text)
            return
        }
        val firstEnd = text.offsetByCodePoints(0, 1)
        val first = text.substring(0, firstEnd)
        val rest = text.substring(firstEnd)
        if (controlActive.value) hostEditor.control(first) else hostEditor.alt(first)
        clearModifiers()
        if (rest.isNotEmpty()) hostEditor.insert(rest)
    }

    private fun clearModifiers() {
        controlActive.value = false
        altActive.value = false
    }

    private fun controlOf(text: String): String? {
        if (text.length != 1) return null
        val char = text[0]
        val code = when (char) {
            in 'a'..'z' -> char.uppercaseChar().code and CONTROL_MASK
            in '@'..'_' -> char.code and CONTROL_MASK
            '?' -> DELETE_CODE
            else -> return null
        }
        return code.toChar().toString()
    }
}

/**
 * Encodes the xterm modifier form used by navigation keys in a live PTY.
 *
 * Plain keys still go through [TerminalActions.onKey], so DECCKM/application
 * cursor mode remains owned by the terminal controller. A modified cursor key
 * uses CSI directly, as xterm does: Alt is parameter 3 and Control is 5.
 */
private fun modifiedNavigationSequence(
    key: TerminalKey,
    control: Boolean,
    alt: Boolean,
): String? {
    val modifier = when {
        control -> CONTROL_CSI_MODIFIER
        alt -> ALT_CSI_MODIFIER
        else -> return null
    }
    return when (key) {
        TerminalKey.UP -> "${CSI_PREFIX}${CSI_CURSOR_PARAMETER};${modifier}A"
        TerminalKey.DOWN -> "${CSI_PREFIX}${CSI_CURSOR_PARAMETER};${modifier}B"
        TerminalKey.RIGHT -> "${CSI_PREFIX}${CSI_CURSOR_PARAMETER};${modifier}C"
        TerminalKey.LEFT -> "${CSI_PREFIX}${CSI_CURSOR_PARAMETER};${modifier}D"
        TerminalKey.HOME -> "${CSI_PREFIX}${CSI_CURSOR_PARAMETER};${modifier}H"
        TerminalKey.END -> "${CSI_PREFIX}${CSI_CURSOR_PARAMETER};${modifier}F"
        TerminalKey.PAGE_UP -> "${CSI_PREFIX}${CSI_PAGE_UP_PARAMETER};${modifier}~"
        TerminalKey.PAGE_DOWN -> "${CSI_PREFIX}${CSI_PAGE_DOWN_PARAMETER};${modifier}~"
        TerminalKey.ESCAPE, TerminalKey.TAB -> null
    }
}

@Composable
internal fun TerminalInputCapture(
    focusRequest: Int,
    pasteRequest: Int,
    onFocusChanged: (Boolean) -> Unit,
    onText: (String) -> Unit,
    onEnter: () -> Unit,
    onBackspace: () -> Unit,
    onInterrupt: () -> Unit,
    onSpecialKey: (TerminalKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    var view by remember { mutableStateOf<TerminalInputView?>(null) }
    AndroidView(
        factory = { context ->
            TerminalInputView(context).also { input ->
                view = input
                input.setOnFocusChangeListener { _, focused -> onFocusChanged(focused) }
            }
        },
        update = { input ->
            input.onText = onText
            input.onEnter = onEnter
            input.onBackspace = onBackspace
            input.onInterrupt = onInterrupt
            input.onTerminalKey = onSpecialKey
        },
        modifier = modifier.size(INPUT_CAPTURE_SIZE),
    )
    LaunchedEffect(focusRequest, view) {
        if (focusRequest > 0) {
            withFrameNanos { }
            view?.showKeyboard()
        }
    }
    LaunchedEffect(pasteRequest, view) {
        if (pasteRequest > 0) {
            withFrameNanos { }
            view?.pasteClipboard()
        }
    }
}

@Composable
internal fun TerminalExtraKeys(
    controlActive: Boolean,
    altActive: Boolean,
    onControl: () -> Unit,
    onAlt: () -> Unit,
    onKey: (TerminalKey) -> Unit,
    onFocus: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TerminalPalette.chrome)
            .drawBehind {
                val stroke = EXTRA_DIVIDER_WIDTH.toPx()
                drawLine(
                    color = TerminalPalette.muted.copy(alpha = EXTRA_DIVIDER_ALPHA),
                    start = Offset(0f, stroke / 2f),
                    end = Offset(size.width, stroke / 2f),
                    strokeWidth = stroke,
                )
            }
            .padding(horizontal = EXTRA_ROW_PADDING, vertical = EXTRA_VERTICAL_PADDING),
        horizontalArrangement = Arrangement.spacedBy(EXTRA_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExtraKey(
            label = "⌨",
            onClick = onFocus,
            modifier = Modifier.size(EXTRA_KEYBOARD_SIZE),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(EXTRA_GAP),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(EXTRA_GAP),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ExtraKey("ESC", onClick = { onKey(TerminalKey.ESCAPE) }, modifier = Modifier.weight(1f))
                ExtraKey("TAB", onClick = { onKey(TerminalKey.TAB) }, modifier = Modifier.weight(1f))
                ExtraKey("HOME", onClick = { onKey(TerminalKey.HOME) }, modifier = Modifier.weight(1f))
                ExtraKey("↑", onClick = { onKey(TerminalKey.UP) }, modifier = Modifier.weight(1f))
                ExtraKey("END", onClick = { onKey(TerminalKey.END) }, modifier = Modifier.weight(1f))
                ExtraKey("PGUP", onClick = { onKey(TerminalKey.PAGE_UP) }, modifier = Modifier.weight(1f))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(EXTRA_GAP),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ExtraKey("CTRL", active = controlActive, onClick = onControl, modifier = Modifier.weight(1f))
                ExtraKey("ALT", active = altActive, onClick = onAlt, modifier = Modifier.weight(1f))
                ExtraKey("←", onClick = { onKey(TerminalKey.LEFT) }, modifier = Modifier.weight(1f))
                ExtraKey("↓", onClick = { onKey(TerminalKey.DOWN) }, modifier = Modifier.weight(1f))
                ExtraKey("→", onClick = { onKey(TerminalKey.RIGHT) }, modifier = Modifier.weight(1f))
                ExtraKey("PGDN", onClick = { onKey(TerminalKey.PAGE_DOWN) }, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ExtraKey(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
) {
    Box(
        modifier = modifier
            .sizeIn(minHeight = EXTRA_KEY_MIN_HEIGHT)
            .background(
                color = if (active) {
                    TerminalPalette.live.copy(alpha = ACTIVE_KEY_ALPHA)
                } else {
                    TerminalPalette.muted.copy(alpha = EXTRA_KEY_SURFACE_ALPHA)
                },
                shape = RoundedCornerShape(EXTRA_KEY_RADIUS),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = EXTRA_KEY_HORIZONTAL, vertical = EXTRA_KEY_VERTICAL),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (active) TerminalPalette.live else TerminalPalette.foreground,
            fontFamily = FontFamily.Monospace,
            fontSize = EXTRA_TEXT_SIZE,
        )
    }
}

@Composable
internal fun TerminalInputDisplay(
    value: String,
    cursor: Int,
    prompt: String,
    live: Boolean,
    focused: Boolean,
    onTap: () -> Unit,
    onPaste: () -> Unit,
) {
    val at = cursor.coerceIn(0, value.length)
    val rendered = if (focused) value.substring(0, at) + INPUT_CURSOR + value.substring(at) else value
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TerminalPalette.chrome)
            .terminalTap(onTap, onPaste)
            .padding(horizontal = INPUT_HORIZONTAL_PADDING, vertical = INPUT_VERTICAL_PADDING),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(INPUT_GAP),
    ) {
        Text(
            text = prompt,
            color = if (live) TerminalPalette.live else TerminalPalette.muted,
            fontFamily = FontFamily.Monospace,
            fontSize = INPUT_TEXT_SIZE,
        )
        Text(
            text = rendered,
            color = TerminalPalette.foreground,
            fontFamily = FontFamily.Monospace,
            fontSize = INPUT_TEXT_SIZE,
        )
    }
}

internal fun Modifier.terminalTap(
    onTap: () -> Unit,
    onLongPress: () -> Unit = {},
): Modifier = pointerInput(onTap, onLongPress) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val up = waitForUpOrCancellation(pass = PointerEventPass.Initial)
        if (up != null && (up.position - down.position).getDistance() < viewConfiguration.touchSlop) {
            val held = up.uptimeMillis - down.uptimeMillis
            if (held >= viewConfiguration.longPressTimeoutMillis) onLongPress() else onTap()
        }
    }
}

private val INPUT_CAPTURE_SIZE = 1.dp
private val INPUT_HORIZONTAL_PADDING = 6.dp
private val INPUT_VERTICAL_PADDING = 6.dp
private val INPUT_GAP = 6.dp
private val INPUT_TEXT_SIZE = 11.sp
private val EXTRA_ROW_PADDING = 6.dp
private val EXTRA_VERTICAL_PADDING = 4.dp
private val EXTRA_GAP = 4.dp
private val EXTRA_KEY_MIN_HEIGHT = 40.dp
private val EXTRA_KEYBOARD_SIZE = 40.dp
private val EXTRA_KEY_HORIZONTAL = 4.dp
private val EXTRA_KEY_VERTICAL = 6.dp
private val EXTRA_KEY_RADIUS = 6.dp
private val EXTRA_DIVIDER_WIDTH = 1.dp
private val EXTRA_TEXT_SIZE = 12.sp
private const val ACTIVE_KEY_ALPHA = 0.18f
private const val EXTRA_DIVIDER_ALPHA = 0.45f
private const val EXTRA_KEY_SURFACE_ALPHA = 0.10f
private const val INPUT_CURSOR = "▌"
private const val DELETE_CHARACTER = "\u007F"
private const val ESCAPE_CHARACTER = "\u001B"
private const val CSI_PREFIX = "\u001B["
private const val CSI_CURSOR_PARAMETER = 1
private const val CSI_PAGE_UP_PARAMETER = 5
private const val CSI_PAGE_DOWN_PARAMETER = 6
private const val ALT_CSI_MODIFIER = 3
private const val CONTROL_CSI_MODIFIER = 5
private const val CONTROL_MASK = 0x1F
private const val DELETE_CODE = 0x7F
