package io.github.ncorror.nekoflash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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
    private val stagedInput: MutableState<String>,
    private val hostTranscript: MutableList<String>,
    private val actions: TerminalActions,
    private val hostPrompt: String,
    private val controlActive: MutableState<Boolean>,
    private val altActive: MutableState<Boolean>,
    private val onHostCommand: (String) -> Unit,
) {
    fun onText(text: String) {
        if (!ptyInput) {
            controlActive.value = false
            altActive.value = false
            stagedInput.value += text
            return
        }
        dispatchPtyText(text)
    }

    fun onEnter() {
        controlActive.value = false
        altActive.value = false
        if (ptyInput) actions.onType("\r") else submitHost()
    }

    fun onBackspace() {
        controlActive.value = false
        altActive.value = false
        if (ptyInput) {
            actions.onType(DELETE_CHARACTER)
        } else {
            stagedInput.value = stagedInput.value.dropLastCodePoint()
        }
    }

    fun onSpecialKey(key: TerminalKey) {
        controlActive.value = false
        altActive.value = false
        if (ptyInput) actions.onKey(key)
    }

    fun toggleControl() {
        if (ptyInput) {
            controlActive.value = !controlActive.value
            if (controlActive.value) altActive.value = false
        }
    }

    fun toggleAlt() {
        if (ptyInput) {
            altActive.value = !altActive.value
            if (altActive.value) controlActive.value = false
        }
    }

    private fun dispatchPtyText(text: String) {
        if (text.isEmpty()) return
        val firstEnd = text.offsetByCodePoints(0, 1)
        val first = text.substring(0, firstEnd)
        val rest = text.substring(firstEnd)
        when {
            controlActive.value -> controlOf(first)?.let(actions.onType) ?: actions.onType(first)
            altActive.value -> actions.onType(ESCAPE_CHARACTER + first)
            else -> actions.onType(text)
        }
        controlActive.value = false
        altActive.value = false
        if (rest.isNotEmpty() && (firstEnd < text.length)) actions.onType(rest)
    }

    private fun submitHost() {
        val command = stagedInput.value
        stagedInput.value = ""
        if (command.isBlank()) return
        hostTranscript += "$hostPrompt $command"
        onHostCommand(command)
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
    ptyEnabled: Boolean,
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
        ExtraKey("ESC", ptyEnabled) { onKey(TerminalKey.ESCAPE) }
        ExtraKey("CTRL", ptyEnabled, controlActive, onControl)
        ExtraKey("ALT", ptyEnabled, altActive, onAlt)
        ExtraKey("TAB", ptyEnabled) { onKey(TerminalKey.TAB) }
        ExtraKey("←", ptyEnabled) { onKey(TerminalKey.LEFT) }
        ExtraKey("↓", ptyEnabled) { onKey(TerminalKey.DOWN) }
        ExtraKey("↑", ptyEnabled) { onKey(TerminalKey.UP) }
        ExtraKey("→", ptyEnabled) { onKey(TerminalKey.RIGHT) }
        ExtraKey("HOME", ptyEnabled) { onKey(TerminalKey.HOME) }
        ExtraKey("END", ptyEnabled) { onKey(TerminalKey.END) }
        ExtraKey("PGUP", ptyEnabled) { onKey(TerminalKey.PAGE_UP) }
        ExtraKey("PGDN", ptyEnabled) { onKey(TerminalKey.PAGE_DOWN) }
        ExtraKey("⌨", true, onClick = onFocus)
    }
}

@Composable
private fun ExtraKey(
    label: String,
    enabled: Boolean,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        color = when {
            active -> TerminalPalette.live
            enabled -> TerminalPalette.foreground
            else -> TerminalPalette.muted
        },
        fontFamily = FontFamily.Monospace,
        fontSize = EXTRA_TEXT_SIZE,
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = EXTRA_KEY_HORIZONTAL, vertical = EXTRA_KEY_VERTICAL),
    )
}

@Composable
internal fun TerminalInputDisplay(
    value: String,
    prompt: String,
    live: Boolean,
    focused: Boolean,
    onTap: () -> Unit,
    onPaste: () -> Unit,
) {
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
            text = value + if (focused) INPUT_CURSOR else "",
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

private fun String.dropLastCodePoint(): String {
    if (isEmpty()) return this
    val end = offsetByCodePoints(length, -1)
    return substring(0, end)
}

private val INPUT_CAPTURE_SIZE = 1.dp
private val INPUT_HORIZONTAL_PADDING = 6.dp
private val INPUT_VERTICAL_PADDING = 6.dp
private val INPUT_GAP = 6.dp
private val INPUT_TEXT_SIZE = 11.sp
private val EXTRA_ROW_PADDING = 4.dp
private val EXTRA_VERTICAL_PADDING = 2.dp
private val EXTRA_GAP = 2.dp
private val EXTRA_KEY_HORIZONTAL = 7.dp
private val EXTRA_KEY_VERTICAL = 5.dp
private val EXTRA_TEXT_SIZE = 10.sp
private const val INPUT_CURSOR = "▌"
private const val DELETE_CHARACTER = "\u007F"
private const val ESCAPE_CHARACTER = "\u001B"
private const val CONTROL_MASK = 0x1F
private const val DELETE_CODE = 0x7F
