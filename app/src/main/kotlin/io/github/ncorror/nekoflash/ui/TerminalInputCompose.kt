package io.github.ncorror.nekoflash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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

internal class TerminalInputRouter(
    private val currentReady: Boolean,
    private val fastbootConnected: Boolean,
    private val stagedInput: MutableState<String>,
    private val offlineTranscript: MutableList<String>,
    private val actions: TerminalActions,
    private val connectMessage: String,
    private val offlinePrompt: String,
    private val onFastbootCommand: (String) -> Unit,
) {
    fun onText(text: String) {
        if (currentReady && !fastbootConnected) actions.onType(text) else stagedInput.value += text
    }

    fun onEnter() {
        when {
            currentReady && !fastbootConnected -> actions.onType("\r")
            fastbootConnected -> submitFastboot()
            else -> submitOffline()
        }
    }

    fun onBackspace() {
        if (currentReady && !fastbootConnected) {
            actions.onType(DELETE_CHARACTER)
        } else {
            stagedInput.value = stagedInput.value.dropLastCodePoint()
        }
    }

    fun onSpecialKey(key: TerminalKey) {
        if (currentReady && !fastbootConnected) actions.onKey(key)
    }

    private fun submitFastboot() {
        val command = stagedInput.value
        stagedInput.value = ""
        if (command.isNotBlank()) onFastbootCommand(command)
    }

    private fun submitOffline() {
        val command = stagedInput.value
        stagedInput.value = ""
        if (command.isBlank()) return
        offlineTranscript += "$offlinePrompt $command"
        offlineTranscript += "[NekoFlash] $connectMessage"
    }
}

@Composable
internal fun TerminalInputCapture(
    focusRequest: Int,
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
}

@Composable
internal fun TerminalInputDisplay(
    value: String,
    prompt: String,
    live: Boolean,
    focused: Boolean,
    onTap: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TerminalPalette.chrome)
            .terminalTap(onTap)
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

internal fun Modifier.terminalTap(onTap: () -> Unit): Modifier = pointerInput(onTap) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val up = waitForUpOrCancellation(pass = PointerEventPass.Initial)
        val quick = up != null && up.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis
        val still = up != null && (up.position - down.position).getDistance() < viewConfiguration.touchSlop
        if (quick && still) onTap()
    }
}

private fun String.dropLastCodePoint(): String {
    if (isEmpty()) return this
    val end = offsetByCodePoints(length, -1)
    return substring(0, end)
}

private val INPUT_CAPTURE_SIZE = 1.dp
private val INPUT_HORIZONTAL_PADDING = 10.dp
private val INPUT_VERTICAL_PADDING = 9.dp
private val INPUT_GAP = 8.dp
private val INPUT_TEXT_SIZE = 13.sp
private const val INPUT_CURSOR = "▌"
private const val DELETE_CHARACTER = "\u007F"
