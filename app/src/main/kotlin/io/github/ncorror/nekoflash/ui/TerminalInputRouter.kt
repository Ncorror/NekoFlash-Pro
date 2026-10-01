package io.github.ncorror.nekoflash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
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
            clearModifiers()
            actions.onKey(key)
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
    val scroll = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TerminalPalette.chrome)
            .horizontalScroll(scroll)
            .padding(horizontal = EXTRA_ROW_PADDING, vertical = EXTRA_VERTICAL_PADDING),
        horizontalArrangement = Arrangement.spacedBy(EXTRA_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExtraKey("⌨", onClick = onFocus)
        ExtraKey("ESC") { onKey(TerminalKey.ESCAPE) }
        ExtraKey("CTRL", active = controlActive, onClick = onControl)
        ExtraKey("ALT", active = altActive, onClick = onAlt)
        ExtraKey("TAB") { onKey(TerminalKey.TAB) }
        ExtraKey("←") { onKey(TerminalKey.LEFT) }
        ExtraKey("↓") { onKey(TerminalKey.DOWN) }
        ExtraKey("↑") { onKey(TerminalKey.UP) }
        ExtraKey("→") { onKey(TerminalKey.RIGHT) }
        ExtraKey("HOME") { onKey(TerminalKey.HOME) }
        ExtraKey("END") { onKey(TerminalKey.END) }
        ExtraKey("PGUP") { onKey(TerminalKey.PAGE_UP) }
        ExtraKey("PGDN") { onKey(TerminalKey.PAGE_DOWN) }
    }
}

@Composable
private fun ExtraKey(
    label: String,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .sizeIn(minWidth = EXTRA_KEY_MIN_WIDTH, minHeight = EXTRA_KEY_MIN_HEIGHT)
            .background(
                color = if (active) TerminalPalette.live.copy(alpha = ACTIVE_KEY_ALPHA) else TerminalPalette.chrome,
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
        val still = up != null && (up.position - down.position).getDistance() < viewConfiguration.touchSlop
        if (still) {
            val held = up!!.uptimeMillis - down.uptimeMillis
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
private val EXTRA_KEY_MIN_WIDTH = 44.dp
private val EXTRA_KEY_MIN_HEIGHT = 40.dp
private val EXTRA_KEY_HORIZONTAL = 8.dp
private val EXTRA_KEY_VERTICAL = 6.dp
private val EXTRA_KEY_RADIUS = 6.dp
private val EXTRA_TEXT_SIZE = 13.sp
private const val ACTIVE_KEY_ALPHA = 0.18f
private const val INPUT_CURSOR = "▌"
private const val DELETE_CHARACTER = "\u007F"
private const val ESCAPE_CHARACTER = "\u001B"
private const val CONTROL_MASK = 0x1F
private const val DELETE_CODE = 0x7F
