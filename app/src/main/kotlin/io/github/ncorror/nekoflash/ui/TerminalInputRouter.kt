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
    private val stagedInput: MutableState<String>,
    private val stagedCursor: MutableState<Int>,
    private val historyIndex: MutableState<Int>,
    private val hostHistory: MutableList<String>,
    private val hostTranscript: MutableList<String>,
    private val actions: TerminalActions,
    private val hostPrompt: String,
    private val controlActive: MutableState<Boolean>,
    private val altActive: MutableState<Boolean>,
    private val onHostCommand: (String) -> Unit,
) {
    fun onText(text: String) {
        if (ptyInput) dispatchPtyText(text) else dispatchHostText(text)
    }

    fun onEnter() {
        clearModifiers()
        if (ptyInput) actions.onType("\r") else submitHost()
    }

    fun onBackspace() {
        if (ptyInput) {
            clearModifiers()
            actions.onType(DELETE_CHARACTER)
            return
        }
        if (controlActive.value || altActive.value) deletePreviousWord() else deletePreviousCodePoint()
        clearModifiers()
    }

    fun onSpecialKey(key: TerminalKey) {
        if (ptyInput) {
            clearModifiers()
            actions.onKey(key)
            return
        }
        when (key) {
            TerminalKey.ESCAPE -> clearModifiers()
            TerminalKey.TAB -> insertHostText("\t")
            TerminalKey.LEFT -> moveHostCursorLeft(controlActive.value || altActive.value)
            TerminalKey.RIGHT -> moveHostCursorRight(controlActive.value || altActive.value)
            TerminalKey.HOME -> stagedCursor.value = 0
            TerminalKey.END -> stagedCursor.value = stagedInput.value.length
            TerminalKey.UP -> moveHistory(-1)
            TerminalKey.DOWN -> moveHistory(1)
            TerminalKey.PAGE_UP -> moveHistory(-HISTORY_PAGE)
            TerminalKey.PAGE_DOWN -> moveHistory(HISTORY_PAGE)
        }
        clearModifiers()
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
            insertHostText(text)
            return
        }
        val firstEnd = text.offsetByCodePoints(0, 1)
        val first = text.substring(0, firstEnd)
        val rest = text.substring(firstEnd)
        if (controlActive.value) handleHostControl(first) else handleHostAlt(first)
        clearModifiers()
        if (rest.isNotEmpty()) insertHostText(rest)
    }

    private fun handleHostControl(text: String) {
        when (text.lowercase()) {
            "a" -> stagedCursor.value = 0
            "c" -> cancelHostLine()
            "e" -> stagedCursor.value = stagedInput.value.length
            "k" -> stagedInput.value = stagedInput.value.substring(0, cursor())
            "l" -> hostTranscript.clear()
            "u" -> {
                stagedInput.value = stagedInput.value.substring(cursor())
                stagedCursor.value = 0
            }
            "w" -> deletePreviousWord()
            else -> insertHostText(text)
        }
    }

    private fun handleHostAlt(text: String) {
        when (text.lowercase()) {
            "b" -> stagedCursor.value = previousWordStart()
            "f" -> stagedCursor.value = nextWordEnd()
            "d" -> deleteNextWord()
            else -> insertHostText(text)
        }
    }

    private fun submitHost() {
        val command = stagedInput.value.trim()
        stagedInput.value = ""
        stagedCursor.value = 0
        historyIndex.value = hostHistory.size
        if (command.isBlank()) return
        addHistory(command)
        if (command.equals("clear", ignoreCase = true) || command.equals("cls", ignoreCase = true)) {
            hostTranscript.clear()
            return
        }
        hostTranscript += "$hostPrompt $command"
        onHostCommand(command)
    }

    private fun addHistory(command: String) {
        if (hostHistory.lastOrNull() != command) hostHistory += command
        historyIndex.value = hostHistory.size
    }

    private fun moveHistory(delta: Int) {
        if (hostHistory.isEmpty()) return
        val next = (historyIndex.value + delta).coerceIn(0, hostHistory.size)
        historyIndex.value = next
        stagedInput.value = if (next == hostHistory.size) "" else hostHistory[next]
        stagedCursor.value = stagedInput.value.length
    }

    private fun cancelHostLine() {
        stagedInput.value = ""
        stagedCursor.value = 0
        historyIndex.value = hostHistory.size
        hostTranscript += "^C"
    }

    private fun insertHostText(text: String) {
        val at = cursor()
        stagedInput.value = stagedInput.value.substring(0, at) + text + stagedInput.value.substring(at)
        stagedCursor.value = at + text.length
        historyIndex.value = hostHistory.size
    }

    private fun deletePreviousCodePoint() {
        val at = cursor()
        if (at == 0) return
        val start = stagedInput.value.offsetByCodePoints(at, -1)
        stagedInput.value = stagedInput.value.removeRange(start, at)
        stagedCursor.value = start
    }

    private fun deletePreviousWord() {
        val at = cursor()
        val start = previousWordStart()
        if (start < at) {
            stagedInput.value = stagedInput.value.removeRange(start, at)
            stagedCursor.value = start
        }
    }

    private fun deleteNextWord() {
        val at = cursor()
        val end = nextWordEnd()
        if (end > at) stagedInput.value = stagedInput.value.removeRange(at, end)
    }

    private fun moveHostCursorLeft(byWord: Boolean) {
        val at = cursor()
        stagedCursor.value = when {
            at == 0 -> 0
            byWord -> previousWordStart()
            else -> stagedInput.value.offsetByCodePoints(at, -1)
        }
    }

    private fun moveHostCursorRight(byWord: Boolean) {
        val at = cursor()
        stagedCursor.value = when {
            at >= stagedInput.value.length -> stagedInput.value.length
            byWord -> nextWordEnd()
            else -> stagedInput.value.offsetByCodePoints(at, 1)
        }
    }

    private fun previousWordStart(): Int {
        var index = cursor()
        while (index > 0 && stagedInput.value[index - 1].isWhitespace()) index--
        while (index > 0 && !stagedInput.value[index - 1].isWhitespace()) index--
        return index
    }

    private fun nextWordEnd(): Int {
        var index = cursor()
        while (index < stagedInput.value.length && stagedInput.value[index].isWhitespace()) index++
        while (index < stagedInput.value.length && !stagedInput.value[index].isWhitespace()) index++
        return index
    }

    private fun cursor(): Int = stagedCursor.value.coerceIn(0, stagedInput.value.length)

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
private const val HISTORY_PAGE = 5
private const val INPUT_CURSOR = "▌"
private const val DELETE_CHARACTER = "\u007F"
private const val ESCAPE_CHARACTER = "\u001B"
private const val CONTROL_MASK = 0x1F
private const val DELETE_CODE = 0x7F
