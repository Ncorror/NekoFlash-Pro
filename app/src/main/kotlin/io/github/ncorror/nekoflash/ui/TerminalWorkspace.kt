package io.github.ncorror.nekoflash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ncorror.nekoflash.pro.engine.TerminalParser
import com.ncorror.nekoflash.pro.engine.TerminalPlanCompiler
import com.ncorror.nekoflash.pro.engine.TerminalPlanResult
import io.github.ncorror.nekoflash.R
import io.github.ncorror.nekoflash.adb.AdbLinkState
import io.github.ncorror.nekoflash.adb.TerminalKey
import io.github.ncorror.nekoflash.fastboot.FastbootConsoleState
import io.github.ncorror.nekoflash.fastboot.FastbootLinkState
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootReply

/**
 * Main production workspace.
 *
 * ADB mode is a real interactive PTY. Fastboot mode is a line-oriented terminal
 * over the same Fastboot engine used by the flashing screens. There is no mock
 * console, no hidden 60-line window and no filtering of device output.
 */
@Composable
internal fun TerminalWorkspace(
    adbLink: AdbLinkState,
    fastbootLink: FastbootLinkState,
    terminal: TerminalPanel,
    actions: TerminalActions,
    desktopCommand: DesktopCommandPanel,
    modifier: Modifier = Modifier,
) {
    val current = terminal.current
    val adbGeneration = (adbLink as? AdbLinkState.Connected)?.generation?.value
    val fastbootGeneration = (fastbootLink as? FastbootLinkState.Connected)?.generation?.value
    val fastbootConnected = fastbootLink is FastbootLinkState.Connected
    val fastbootTranscript = remember { mutableStateListOf<String>() }

    AutoStartAdbShell(adbGeneration, current.active, actions.onStart)
    AutoPrintAdbDeviceInfo(adbGeneration, current.ready, actions.onSend)
    AutoPrintFastbootDeviceInfo(fastbootGeneration, desktopCommand, fastbootTranscript)
    ObserveFastbootOutcome(desktopCommand.outcomes.fastboot, fastbootTranscript)

    Column(modifier = modifier.fillMaxSize().background(TerminalPalette.background)) {
        if (fastbootConnected) {
            FastbootTerminalOutput(
                lines = fastbootTranscript,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        } else {
            TerminalScreen(
                screen = current.screen,
                placeholder = terminalPlaceholder(adbLink, current.ready),
                showCursor = current.ready,
                onResize = actions.onResize,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
        TerminalCommandLine(
            enabled = current.ready || fastbootConnected,
            prompt = if (fastbootConnected) FASTBOOT_PROMPT else ADB_PROMPT,
            onSubmit = { raw ->
                if (fastbootConnected) {
                    fastbootTranscript += "$ $raw"
                    compileAndRunFastboot(raw, desktopCommand, fastbootTranscript)
                } else if (current.ready) {
                    submitAdbLine(raw, actions)
                }
            },
            onInterrupt = actions.onInterrupt,
            onSpecialKey = actions.onKey,
        )
    }
}

@Composable
private fun AutoStartAdbShell(generation: Long?, active: Boolean, start: () -> Unit) {
    var startedGeneration by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(generation, active) {
        val currentGeneration = generation ?: return@LaunchedEffect
        if (startedGeneration != currentGeneration && !active) {
            startedGeneration = currentGeneration
            start()
        }
    }
}

@Composable
private fun AutoPrintAdbDeviceInfo(generation: Long?, ready: Boolean, send: (String) -> Unit) {
    var infoGeneration by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(generation, ready) {
        val currentGeneration = generation ?: return@LaunchedEffect
        if (ready && infoGeneration != currentGeneration) {
            infoGeneration = currentGeneration
            send(ADB_DEVICE_INFO_COMMAND)
        }
    }
}

@Composable
private fun AutoPrintFastbootDeviceInfo(
    generation: Long?,
    panel: DesktopCommandPanel,
    transcript: MutableList<String>,
) {
    var infoGeneration by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(generation) {
        val currentGeneration = generation ?: return@LaunchedEffect
        if (infoGeneration != currentGeneration) {
            infoGeneration = currentGeneration
            transcript += "[NekoFlash] Fastboot connected"
            transcript += "$ fastboot getvar all"
            compileAndRunFastboot("fastboot getvar all", panel, transcript)
        }
    }
}

@Composable
private fun ObserveFastbootOutcome(state: FastbootConsoleState, transcript: MutableList<String>) {
    var previous by remember { mutableStateOf<FastbootConsoleState?>(null) }
    LaunchedEffect(state) {
        if (state != previous) {
            previous = state
            fastbootStateLines(state).forEach(transcript::add)
        }
    }
}

@Composable
private fun terminalPlaceholder(adb: AdbLinkState, ready: Boolean): String = when {
    ready -> ""
    adb is AdbLinkState.Connecting -> stringResource(R.string.terminal_waiting_connection)
    adb is AdbLinkState.WaitingForAuthorization -> stringResource(R.string.terminal_waiting_authorization)
    adb is AdbLinkState.Connected -> stringResource(R.string.terminal_opening_shell)
    else -> stringResource(R.string.terminal_connect_device)
}

private fun submitAdbLine(raw: String, actions: TerminalActions) {
    val line = raw.removePrefix("adb shell ")
    when {
        raw == "adb shell" -> Unit // already inside the live shell
        raw.startsWith("adb shell ") -> actions.onSend(line)
        else -> actions.onSend(raw)
    }
}

@Composable
private fun TerminalCommandLine(
    enabled: Boolean,
    prompt: String,
    onSubmit: (String) -> Unit,
    onInterrupt: () -> Unit,
    onSpecialKey: (TerminalKey) -> Unit,
) {
    var value by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val submit = {
        if (enabled && value.isNotBlank()) {
            val command = value
            value = ""
            onSubmit(command)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TerminalPalette.chrome)
            .padding(horizontal = INPUT_HORIZONTAL_PADDING, vertical = INPUT_VERTICAL_PADDING),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(INPUT_GAP),
    ) {
        TerminalPrompt(prompt, enabled)
        TerminalInputField(
            value = value,
            enabled = enabled,
            focus = focus,
            onValueChange = { value = it },
            onSubmit = submit,
            onInterrupt = onInterrupt,
            onSpecialKey = onSpecialKey,
        )
    }

    LaunchedEffect(enabled) {
        if (enabled) focus.requestFocus()
    }
}

@Composable
private fun TerminalPrompt(prompt: String, enabled: Boolean) {
    androidx.compose.material3.Text(
        text = prompt,
        color = if (enabled) TerminalPalette.live else TerminalPalette.muted,
        fontFamily = FontFamily.Monospace,
        fontSize = INPUT_TEXT_SIZE,
    )
}

@Composable
private fun TerminalInputField(
    value: String,
    enabled: Boolean,
    focus: FocusRequester,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onInterrupt: () -> Unit,
    onSpecialKey: (TerminalKey) -> Unit,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        textStyle = TextStyle(
            color = if (enabled) TerminalPalette.foreground else TerminalPalette.muted,
            fontFamily = FontFamily.Monospace,
            fontSize = INPUT_TEXT_SIZE,
        ),
        cursorBrush = SolidColor(TerminalPalette.live),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { onSubmit() }),
        modifier = Modifier
            .weight(1f)
            .focusRequester(focus)
            .onPreviewKeyEvent { event ->
                handleTerminalKey(event, onSubmit, onInterrupt, onSpecialKey)
            },
    )
}

private fun handleTerminalKey(
    event: KeyEvent,
    submit: () -> Unit,
    interrupt: () -> Unit,
    specialKey: (TerminalKey) -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    return when {
        event.isCtrlPressed && event.key == Key.C -> handled(interrupt)
        event.key == Key.DirectionUp -> handled { specialKey(TerminalKey.UP) }
        event.key == Key.DirectionDown -> handled { specialKey(TerminalKey.DOWN) }
        event.key == Key.DirectionLeft -> handled { specialKey(TerminalKey.LEFT) }
        event.key == Key.DirectionRight -> handled { specialKey(TerminalKey.RIGHT) }
        event.key == Key.Escape -> handled { specialKey(TerminalKey.ESCAPE) }
        event.key == Key.Tab -> handled { specialKey(TerminalKey.TAB) }
        event.key == Key.Enter -> handled(submit)
        else -> false
    }
}

private inline fun handled(action: () -> Unit): Boolean {
    action()
    return true
}

@Composable
private fun FastbootTerminalOutput(lines: List<String>, modifier: Modifier = Modifier) {
    val state = rememberLazyListState()
    val horizontal = rememberScrollState()
    var followTail by remember { mutableStateOf(true) }

    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) {
                val lastVisible = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                followTail = lastVisible >= lines.lastIndex - 1
            }
        }
    }
    LaunchedEffect(lines.size, lines.lastOrNull()) {
        if (followTail && lines.isNotEmpty()) state.scrollToItem(lines.lastIndex)
    }

    LazyColumn(
        state = state,
        modifier = modifier.padding(OUTPUT_PADDING),
        verticalArrangement = Arrangement.spacedBy(OUTPUT_GAP),
    ) {
        itemsIndexed(lines) { _, line ->
            androidx.compose.material3.Text(
                text = line,
                color = TerminalPalette.foreground,
                fontFamily = FontFamily.Monospace,
                fontSize = OUTPUT_TEXT_SIZE,
                softWrap = false,
                modifier = Modifier.horizontalScroll(horizontal),
            )
        }
    }
}

private fun compileAndRunFastboot(
    raw: String,
    panel: DesktopCommandPanel,
    transcript: MutableList<String>,
) {
    val compiler = TerminalPlanCompiler()
    val line = raw.trim()
    when (val result = compiler.compile(TerminalParser.Tool.FASTBOOT, line)) {
        is TerminalPlanResult.Invalid -> transcript += "error: ${result.reason}"
        is TerminalPlanResult.NotImplemented -> transcript += "not implemented: ${result.what}"
        is TerminalPlanResult.Ready -> panel.onRun(result.plan, false)
    }
}

private fun fastbootStateLines(state: FastbootConsoleState): List<String> = when (state) {
    FastbootConsoleState.Idle -> emptyList()
    is FastbootConsoleState.Running -> listOf("[running] ${state.command}")
    is FastbootConsoleState.Answered -> buildList {
        state.info.forEach { add("INFO $it") }
        add("${state.reply.name} ${state.payload}".trimEnd())
        add("lane=${state.lane}")
    }
    is FastbootConsoleState.NotAnswered -> listOf(
        "no answer: ${state.command}: ${state.detail}",
        "lane=${state.lane}",
    )
    is FastbootConsoleState.Downloaded -> listOf(
        "download declared=${state.declaredBytes} sent=${state.sentBytes} reply=${state.reply ?: FastbootReply.UNKNOWN}",
        state.detail,
        "route=${state.route ?: "none"} lane=${state.lane}",
    )
    is FastbootConsoleState.Mutated -> listOf(
        state.outcome.toString(),
        "lane=${state.lane}",
    )
    is FastbootConsoleState.Fetched -> listOf(
        "fetch ${state.partition}: bytes=${state.bytes} complete=${state.complete} sha256=${state.sha256}",
        state.detail,
        "lane=${state.lane}",
    )
    is FastbootConsoleState.Planned -> buildList {
        add("plan applied=${state.applied}/${state.commands.size} stoppedAt=${state.stoppedAt ?: "none"}")
        state.commands.forEachIndexed { index, command -> add("[$index] $command") }
        add(state.detail)
        add("lane=${state.lane}")
    }
    is FastbootConsoleState.Variables -> buildList {
        state.snapshot.variables.forEach { (name, value) -> add("$name: $value") }
        state.snapshot.duplicates.forEach { duplicate ->
            add("duplicate ${duplicate.name}: ${duplicate.values.joinToString(" | ")}")
        }
        state.snapshot.ignored.forEach { raw -> add("unparsed: $raw") }
        add("${state.snapshot.finalReply.name} ${state.snapshot.finalPayload}".trimEnd())
        add("complete=${state.snapshot.complete} lane=${state.lane}")
    }
}

private val INPUT_HORIZONTAL_PADDING = 10.dp
private val INPUT_VERTICAL_PADDING = 9.dp
private val INPUT_GAP = 8.dp
private val INPUT_TEXT_SIZE = 13.sp
private val OUTPUT_PADDING = 8.dp
private val OUTPUT_GAP = 2.dp
private val OUTPUT_TEXT_SIZE = 12.sp
private const val ADB_PROMPT = "❯"
private const val FASTBOOT_PROMPT = "fastboot ❯"

private const val ADB_DEVICE_INFO_COMMAND =
    "printf '\\n[NekoFlash] device info\\n'; " +
        "printf 'manufacturer='; getprop ro.product.manufacturer; " +
        "printf 'model='; getprop ro.product.model; " +
        "printf 'device='; getprop ro.product.device; " +
        "printf 'android='; getprop ro.build.version.release; " +
        "printf 'sdk='; getprop ro.build.version.sdk; " +
        "printf 'build='; getprop ro.build.display.id; " +
        "printf 'slot='; getprop ro.boot.slot_suffix; " +
        "printf 'verifiedboot='; getprop ro.boot.verifiedbootstate; " +
        "printf 'flash_locked='; getprop ro.boot.flash.locked; " +
        "uname -a"
