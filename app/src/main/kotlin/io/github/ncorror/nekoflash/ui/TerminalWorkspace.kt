package io.github.ncorror.nekoflash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ncorror.nekoflash.pro.engine.TerminalPlanCompiler
import com.ncorror.nekoflash.pro.engine.TerminalPlanResult
import io.github.ncorror.nekoflash.R
import io.github.ncorror.nekoflash.adb.AdbCommandState
import io.github.ncorror.nekoflash.adb.AdbLinkState
import io.github.ncorror.nekoflash.adb.AdbRawServiceState
import io.github.ncorror.nekoflash.adb.AdbRebootState
import io.github.ncorror.nekoflash.adb.AdbTerminalState
import io.github.ncorror.nekoflash.fastboot.FastbootConsoleState
import io.github.ncorror.nekoflash.fastboot.FastbootLinkState
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootReply

private enum class TerminalPane { HOST, SHELL }
private enum class HostTransport { NONE, ADB, FASTBOOT }

/** Process-owned terminal UI state. Sidebar destinations may leave composition. */
private object TerminalUiSession {
    val hostTranscript = mutableStateListOf<String>()
    val stagedInput = mutableStateOf("")
    val stagedCursor = mutableStateOf(0)
    val hostHistory = mutableStateListOf<String>()
    val historyIndex = mutableStateOf(0)
    val pane = mutableStateOf(TerminalPane.HOST)
    val controlActive = mutableStateOf(false)
    val altActive = mutableStateOf(false)
    var fastbootInfoGeneration: Long? = null
    var previousFastbootState: FastbootConsoleState? = null
    var previousHostAnswer: String? = null
    var previousAdbCommand: AdbCommandState? = null
    var previousAdbService: AdbRawServiceState? = null
    var previousAdbReboot: AdbRebootState? = null
    var previousTerminalEnded: String? = null
}

/** Host CLI and remote PTY are deliberately separate terminal views. */
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
    val transport = when {
        fastbootLink is FastbootLinkState.Connected -> HostTransport.FASTBOOT
        adbLink is AdbLinkState.Connected -> HostTransport.ADB
        else -> HostTransport.NONE
    }
    val hostTranscript = TerminalUiSession.hostTranscript
    val stagedInput = TerminalUiSession.stagedInput
    val selectedPane = TerminalUiSession.pane
    val focusRequest = remember { mutableStateOf(0) }
    val pasteRequest = remember { mutableStateOf(0) }
    val inputFocused = remember { mutableStateOf(false) }
    val ptyInput = selectedPane.value == TerminalPane.SHELL && current.ready
    val noTransport = stringResource(R.string.terminal_no_transport)
    val hostLabel = when (transport) {
        HostTransport.FASTBOOT -> "FASTBOOT"
        HostTransport.ADB -> "ADB"
        HostTransport.NONE -> "HOST"
    }
    val hostEditor = HostLineEditor(
        input = stagedInput,
        cursor = TerminalUiSession.stagedCursor,
        historyIndex = TerminalUiSession.historyIndex,
        history = TerminalUiSession.hostHistory,
        transcript = hostTranscript,
        prompt = HOST_PROMPT,
        onCommand = { command ->
            compileAndRunHostCommand(command, desktopCommand, hostTranscript, transport, noTransport)
        },
    )
    val router = TerminalInputRouter(
        ptyInput = ptyInput,
        hostEditor = hostEditor,
        actions = actions,
        controlActive = TerminalUiSession.controlActive,
        altActive = TerminalUiSession.altActive,
    )

    TerminalAutomation(fastbootLink, current, desktopCommand, hostTranscript)
    TerminalLifecycle(current, ptyInput, selectedPane, stagedInput)
    TerminalWorkspaceLayout(
        terminal = terminal,
        actions = actions,
        router = router,
        hostLabel = hostLabel,
        selectedPane = selectedPane,
        shellAvailable = current.active || current.screen.rows.isNotEmpty() || current.ended != null,
        transportConnected = transport != HostTransport.NONE,
        hostTranscript = hostTranscript,
        stagedInput = stagedInput,
        stagedCursor = TerminalUiSession.stagedCursor,
        focusRequest = focusRequest,
        pasteRequest = pasteRequest,
        inputFocused = inputFocused,
        modifier = modifier,
    )
}

@Composable
private fun TerminalLifecycle(
    current: AdbTerminalState,
    ptyInput: Boolean,
    selectedPane: MutableState<TerminalPane>,
    stagedInput: MutableState<String>,
) {
    LaunchedEffect(current.ready, current.ended) {
        when {
            current.ready -> selectedPane.value = TerminalPane.SHELL
            current.ended != null -> selectedPane.value = TerminalPane.HOST
        }
    }
    LaunchedEffect(ptyInput) {
        if (!ptyInput) {
            TerminalUiSession.controlActive.value = false
            TerminalUiSession.altActive.value = false
        }
    }
    LaunchedEffect(current.ready) {
        if (current.ready) {
            stagedInput.value = ""
            TerminalUiSession.stagedCursor.value = 0
        }
    }
}

@Composable
private fun TerminalAutomation(
    fastbootLink: FastbootLinkState,
    current: AdbTerminalState,
    desktopCommand: DesktopCommandPanel,
    hostTranscript: MutableList<String>,
) {
    val fastbootGeneration = (fastbootLink as? FastbootLinkState.Connected)?.generation?.value
    AutoPrintFastbootDeviceInfo(fastbootGeneration, desktopCommand, hostTranscript)
    ObserveHostAnswer(desktopCommand.answer, hostTranscript)
    ObserveAdbCommand(desktopCommand.outcomes.shell, hostTranscript)
    ObserveAdbService(desktopCommand.outcomes.service, hostTranscript)
    ObserveAdbReboot(desktopCommand.outcomes.reboot, hostTranscript)
    ObserveFastbootOutcome(desktopCommand.outcomes.fastboot, hostTranscript)
    ObserveTerminalEnd(current.ended, hostTranscript)
}

@Composable
private fun TerminalWorkspaceLayout(
    terminal: TerminalPanel,
    actions: TerminalActions,
    router: TerminalInputRouter,
    hostLabel: String,
    selectedPane: MutableState<TerminalPane>,
    shellAvailable: Boolean,
    transportConnected: Boolean,
    hostTranscript: List<String>,
    stagedInput: MutableState<String>,
    stagedCursor: MutableState<Int>,
    focusRequest: MutableState<Int>,
    pasteRequest: MutableState<Int>,
    inputFocused: MutableState<Boolean>,
    modifier: Modifier,
) {
    val focusInput = { focusRequest.value += 1 }
    val pasteInput = { pasteRequest.value += 1 }
    val shellSelected = selectedPane.value == TerminalPane.SHELL
    val ptyEnabled = shellSelected && terminal.current.ready
    val inputEnabled = !shellSelected || ptyEnabled
    Box(modifier = modifier.fillMaxSize().background(TerminalPalette.background).imePadding()) {
        Column(modifier = Modifier.fillMaxSize()) {
            TerminalPaneBar(hostLabel, selectedPane.value, shellAvailable) { selectedPane.value = it }
            if (shellSelected) {
                TerminalScreen(
                    screen = terminal.current.screen,
                    placeholder = if (terminal.current.active) "Opening adb shell…" else "adb shell is not active",
                    showCursor = terminal.current.ready,
                    onResize = actions.onResize,
                    onTap = if (ptyEnabled) focusInput else ({}),
                    onPaste = if (ptyEnabled) pasteInput else ({}),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            } else {
                LineTerminalOutput(
                    lines = hostTranscript,
                    onTap = focusInput,
                    onPaste = pasteInput,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                TerminalInputDisplay(
                    value = stagedInput.value,
                    cursor = stagedCursor.value,
                    prompt = HOST_PROMPT,
                    live = transportConnected,
                    focused = inputFocused.value,
                    onTap = focusInput,
                    onPaste = pasteInput,
                )
            }
            TerminalExtraKeys(
                controlActive = TerminalUiSession.controlActive.value,
                altActive = TerminalUiSession.altActive.value,
                onControl = router::toggleControl,
                onAlt = router::toggleAlt,
                onKey = router::onSpecialKey,
                onFocus = focusInput,
            )
        }
        TerminalInputCapture(
            focusRequest = focusRequest.value,
            pasteRequest = pasteRequest.value,
            onFocusChanged = { inputFocused.value = it },
            onText = { if (inputEnabled) router.onText(it) },
            onEnter = { if (inputEnabled) router.onEnter() },
            onBackspace = { if (inputEnabled) router.onBackspace() },
            onInterrupt = { if (ptyEnabled) actions.onInterrupt() },
            onSpecialKey = { if (inputEnabled) router.onSpecialKey(it) },
            modifier = Modifier.align(Alignment.BottomStart),
        )
    }
}

@Composable
private fun TerminalPaneBar(
    hostLabel: String,
    selected: TerminalPane,
    shellAvailable: Boolean,
    onSelect: (TerminalPane) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().background(TerminalPalette.chrome),
        horizontalArrangement = Arrangement.spacedBy(PANE_GAP),
    ) {
        PaneLabel(
            text = hostLabel,
            selected = selected == TerminalPane.HOST,
            enabled = true,
            onClick = { onSelect(TerminalPane.HOST) },
        )
        PaneLabel(
            text = "SHELL",
            selected = selected == TerminalPane.SHELL,
            enabled = shellAvailable,
            onClick = { onSelect(TerminalPane.SHELL) },
        )
    }
}

@Composable
private fun PaneLabel(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = text,
        color = when {
            selected -> TerminalPalette.live
            enabled -> TerminalPalette.foreground
            else -> TerminalPalette.muted
        },
        fontFamily = FontFamily.Monospace,
        fontSize = PANE_TEXT_SIZE,
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = PANE_HORIZONTAL_PADDING, vertical = PANE_VERTICAL_PADDING),
    )
}

@Composable
private fun AutoPrintFastbootDeviceInfo(
    generation: Long?,
    panel: DesktopCommandPanel,
    transcript: MutableList<String>,
) {
    LaunchedEffect(generation) {
        val currentGeneration = generation ?: return@LaunchedEffect
        if (TerminalUiSession.fastbootInfoGeneration != currentGeneration) {
            TerminalUiSession.fastbootInfoGeneration = currentGeneration
            transcript += "[NekoFlash] Fastboot connected"
            transcript += "$HOST_PROMPT fastboot getvar all"
            compileAndRunHostCommand("fastboot getvar all", panel, transcript)
        }
    }
}

@Composable
private fun ObserveHostAnswer(answer: String?, transcript: MutableList<String>) {
    LaunchedEffect(answer) {
        if (answer == null) {
            TerminalUiSession.previousHostAnswer = null
        } else if (answer != TerminalUiSession.previousHostAnswer) {
            TerminalUiSession.previousHostAnswer = answer
            appendTextLines(transcript, answer)
        }
    }
}

@Composable
private fun ObserveAdbCommand(state: AdbCommandState, transcript: MutableList<String>) {
    LaunchedEffect(state) {
        if (state == TerminalUiSession.previousAdbCommand) return@LaunchedEffect
        TerminalUiSession.previousAdbCommand = state
        when (state) {
            AdbCommandState.None, is AdbCommandState.Running -> Unit
            is AdbCommandState.Finished -> {
                appendTextLines(transcript, state.output)
                appendTextLines(transcript, state.errorOutput)
                if (state.exitCode != null && state.exitCode != 0) {
                    transcript += "[exit ${state.exitCode}]"
                }
            }
            is AdbCommandState.Failed -> transcript += "error: ${state.reason}"
        }
    }
}

@Composable
private fun ObserveAdbService(state: AdbRawServiceState, transcript: MutableList<String>) {
    LaunchedEffect(state) {
        if (state == TerminalUiSession.previousAdbService) return@LaunchedEffect
        TerminalUiSession.previousAdbService = state
        when (state) {
            AdbRawServiceState.None, is AdbRawServiceState.Running -> Unit
            is AdbRawServiceState.Finished -> {
                if (state.text.isNotBlank()) appendTextLines(transcript, state.text)
                else transcript += "${state.bytes} bytes"
            }
            is AdbRawServiceState.OneWay -> transcript += state.evidence
            is AdbRawServiceState.Failed -> transcript += "error: ${state.reason}"
        }
    }
}

@Composable
private fun ObserveAdbReboot(state: AdbRebootState, transcript: MutableList<String>) {
    LaunchedEffect(state) {
        if (state == TerminalUiSession.previousAdbReboot) return@LaunchedEffect
        TerminalUiSession.previousAdbReboot = state
        when (state) {
            AdbRebootState.None, is AdbRebootState.Running -> Unit
            is AdbRebootState.Accepted -> transcript += "reboot ${state.target.ifBlank { "system" }}: accepted"
            is AdbRebootState.Failed -> transcript += "error: ${state.reason} (${state.device})"
        }
    }
}

@Composable
private fun ObserveTerminalEnd(ended: String?, transcript: MutableList<String>) {
    LaunchedEffect(ended) {
        if (ended != null && ended != TerminalUiSession.previousTerminalEnded) {
            TerminalUiSession.previousTerminalEnded = ended
            transcript += "[NekoFlash] adb shell: $ended"
        }
        if (ended == null) TerminalUiSession.previousTerminalEnded = null
    }
}

@Composable
private fun ObserveFastbootOutcome(state: FastbootConsoleState, transcript: MutableList<String>) {
    LaunchedEffect(state) {
        if (state != TerminalUiSession.previousFastbootState) {
            TerminalUiSession.previousFastbootState = state
            fastbootStateLines(state).forEach(transcript::add)
        }
    }
}

@Composable
private fun LineTerminalOutput(
    lines: List<String>,
    onTap: () -> Unit,
    onPaste: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = rememberLazyListState()
    val viewport = remember { TerminalTailViewportState() }
    val userScrollConnection = remember(viewport) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) viewport.noteUserScroll()
                return Offset.Zero
            }
        }
    }

    LaunchedEffect(state, lines.lastIndex) {
        snapshotFlow { state.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) {
                val lastVisible = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                viewport.settleUserScroll(lastVisible, lines.lastIndex)
            }
        }
    }
    LaunchedEffect(lines.size, lines.lastOrNull()) {
        if (viewport.followTail && lines.isNotEmpty()) state.scrollToItem(lines.lastIndex)
    }

    LazyColumn(
        state = state,
        modifier = modifier
            .nestedScroll(userScrollConnection)
            .terminalTap(onTap, onPaste)
            .padding(OUTPUT_PADDING),
        verticalArrangement = Arrangement.spacedBy(OUTPUT_GAP),
    ) {
        itemsIndexed(lines) { _, line ->
            Text(
                text = line,
                color = TerminalPalette.foreground,
                fontFamily = FontFamily.Monospace,
                fontSize = OUTPUT_TEXT_SIZE,
                softWrap = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun compileAndRunHostCommand(
    raw: String,
    panel: DesktopCommandPanel,
    transcript: MutableList<String>,
    transport: HostTransport = HostTransport.NONE,
    noTransport: String = "",
) {
    val line = raw.trim()
    if (line.isEmpty()) return
    val command = when {
        EXPLICIT_TOOL.containsMatchIn(line) -> line
        transport == HostTransport.ADB -> "adb shell $line"
        transport == HostTransport.FASTBOOT -> "fastboot $line"
        else -> {
            transcript += noTransport.ifBlank { "[NekoFlash] no active ADB/Fastboot transport" }
            return
        }
    }
    panel.onEdit()
    when (val result = TerminalPlanCompiler().compile(command)) {
        is TerminalPlanResult.Invalid -> transcript += "error: ${result.reason}"
        is TerminalPlanResult.NotImplemented -> transcript += "not implemented: ${result.what}"
        is TerminalPlanResult.Ready -> panel.onRun(result.plan, false)
    }
}

private fun appendTextLines(transcript: MutableList<String>, text: String) {
    text.split('\n').dropLastWhile(String::isEmpty).forEach(transcript::add)
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
        buildString {
            append("download declared=${state.declaredBytes}")
            append(" sent=${state.sentBytes}")
            append(" reply=${state.reply ?: FastbootReply.UNKNOWN}")
        },
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

private val OUTPUT_PADDING = 4.dp
private val OUTPUT_GAP = 0.dp
private val OUTPUT_TEXT_SIZE = 11.sp
private val PANE_GAP = 4.dp
private val PANE_TEXT_SIZE = 10.sp
private val PANE_HORIZONTAL_PADDING = 10.dp
private val PANE_VERTICAL_PADDING = 5.dp
private val EXPLICIT_TOOL = Regex("""(?i)^(adb|fastboot)(?:\s|$)""")
private const val HOST_PROMPT = "❯"
