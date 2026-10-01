package io.github.ncorror.nekoflash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ncorror.nekoflash.pro.engine.TerminalParser
import com.ncorror.nekoflash.pro.engine.TerminalPlanCompiler
import com.ncorror.nekoflash.pro.engine.TerminalPlanResult
import io.github.ncorror.nekoflash.adb.AdbCommandState
import io.github.ncorror.nekoflash.adb.AdbLinkState
import io.github.ncorror.nekoflash.adb.AdbRawServiceState
import io.github.ncorror.nekoflash.adb.AdbRebootState
import io.github.ncorror.nekoflash.adb.AdbTerminalState
import io.github.ncorror.nekoflash.fastboot.FastbootConsoleState
import io.github.ncorror.nekoflash.fastboot.FastbootLinkState
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootReply

/** Process-owned terminal UI state. Sidebar destinations may leave composition. */
private object TerminalUiSession {
    val hostTranscript = mutableStateListOf<String>()
    val stagedInput = mutableStateOf("")
    val hostCommandSerial = mutableStateOf(0L)
    var fastbootInfoGeneration: Long? = null
    var previousFastbootState: FastbootConsoleState? = null
    var previousHostAnswer: String? = null
    var previousAdbCommand: AdbCommandState? = null
    var previousAdbService: AdbRawServiceState? = null
    var previousAdbReboot: AdbRebootState? = null
    var previousTerminalEnded: String? = null
}

/**
 * NekoFlash terminal has two explicit modes.
 *
 * The default is a host CLI: `adb ...` and `fastboot ...` are compiled into the
 * same plans used by the rest of the application. Only an explicit `adb shell`
 * opens the remote PTY. While that PTY is ready, IME text and special keys go
 * directly to the remote shell through the InputConnection bridge.
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
    val fastbootConnected = fastbootLink is FastbootLinkState.Connected
    val adbConnected = adbLink is AdbLinkState.Connected
    val hostTranscript = TerminalUiSession.hostTranscript
    val stagedInput = TerminalUiSession.stagedInput
    val focusRequest = remember { mutableStateOf(0) }
    val inputFocused = remember { mutableStateOf(false) }
    val prompt = when {
        fastbootConnected -> FASTBOOT_PROMPT
        adbConnected -> ADB_HOST_PROMPT
        else -> HOST_PROMPT
    }
    val router = TerminalInputRouter(
        currentReady = current.ready,
        stagedInput = stagedInput,
        hostTranscript = hostTranscript,
        actions = actions,
        hostPrompt = prompt,
        onHostCommand = { command ->
            compileAndRunHostCommand(command, desktopCommand, hostTranscript)
        },
    )

    TerminalAutomation(fastbootLink, current, desktopCommand, hostTranscript)
    LaunchedEffect(current.ready) {
        if (current.ready) stagedInput.value = ""
    }
    TerminalWorkspaceLayout(
        terminal = terminal,
        actions = actions,
        router = router,
        transportConnected = adbConnected || fastbootConnected,
        hostPrompt = prompt,
        hostTranscript = hostTranscript,
        stagedInput = stagedInput,
        focusRequest = focusRequest,
        inputFocused = inputFocused,
        modifier = modifier,
    )
}

@Composable
private fun TerminalAutomation(
    fastbootLink: FastbootLinkState,
    current: AdbTerminalState,
    desktopCommand: DesktopCommandPanel,
    hostTranscript: MutableList<String>,
) {
    val fastbootGeneration = (fastbootLink as? FastbootLinkState.Connected)?.generation?.value
    val serial = TerminalUiSession.hostCommandSerial.value
    AutoPrintFastbootDeviceInfo(fastbootGeneration, desktopCommand, hostTranscript)
    ObserveHostAnswer(desktopCommand.answer, serial, hostTranscript)
    ObserveAdbCommand(desktopCommand.outcomes.shell, serial, hostTranscript)
    ObserveAdbService(desktopCommand.outcomes.service, serial, hostTranscript)
    ObserveAdbReboot(desktopCommand.outcomes.reboot, serial, hostTranscript)
    ObserveFastbootOutcome(desktopCommand.outcomes.fastboot, hostTranscript)
    ObserveTerminalEnd(current.ended, hostTranscript)
}

@Composable
private fun TerminalWorkspaceLayout(
    terminal: TerminalPanel,
    actions: TerminalActions,
    router: TerminalInputRouter,
    transportConnected: Boolean,
    hostPrompt: String,
    hostTranscript: List<String>,
    stagedInput: MutableState<String>,
    focusRequest: MutableState<Int>,
    inputFocused: MutableState<Boolean>,
    modifier: Modifier,
) {
    val focusInput = { focusRequest.value += 1 }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(TerminalPalette.background)
            .imePadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (terminal.current.ready) {
                TerminalScreen(
                    screen = terminal.current.screen,
                    placeholder = "",
                    showCursor = true,
                    onResize = actions.onResize,
                    onTap = focusInput,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            } else {
                LineTerminalOutput(
                    lines = hostTranscript,
                    onTap = focusInput,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                TerminalInputDisplay(
                    value = stagedInput.value,
                    prompt = hostPrompt,
                    live = transportConnected,
                    focused = inputFocused.value,
                    onTap = focusInput,
                )
            }
        }
        TerminalInputCapture(
            focusRequest = focusRequest.value,
            onFocusChanged = { inputFocused.value = it },
            onText = router::onText,
            onEnter = router::onEnter,
            onBackspace = router::onBackspace,
            onInterrupt = actions.onInterrupt,
            onSpecialKey = router::onSpecialKey,
            modifier = Modifier.align(Alignment.BottomStart),
        )
    }
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
            transcript += "$FASTBOOT_PROMPT getvar all"
            compileAndRunHostCommand("fastboot getvar all", panel, transcript)
        }
    }
}

@Composable
private fun ObserveHostAnswer(answer: String?, serial: Long, transcript: MutableList<String>) {
    LaunchedEffect(answer, serial) {
        if (answer != null && answer != TerminalUiSession.previousHostAnswer) {
            TerminalUiSession.previousHostAnswer = answer
            appendTextLines(transcript, answer)
        }
    }
}

@Composable
private fun ObserveAdbCommand(state: AdbCommandState, serial: Long, transcript: MutableList<String>) {
    LaunchedEffect(state, serial) {
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
private fun ObserveAdbService(state: AdbRawServiceState, serial: Long, transcript: MutableList<String>) {
    LaunchedEffect(state, serial) {
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
private fun ObserveAdbReboot(state: AdbRebootState, serial: Long, transcript: MutableList<String>) {
    LaunchedEffect(state, serial) {
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
    modifier: Modifier = Modifier,
) {
    val state = rememberLazyListState()
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
        modifier = modifier.terminalTap(onTap).padding(OUTPUT_PADDING),
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
) {
    val line = raw.trim()
    if (line.isEmpty()) return
    panel.onEdit()
    TerminalUiSession.previousHostAnswer = null
    TerminalUiSession.hostCommandSerial.value += 1
    when (val result = TerminalPlanCompiler().compile(panel.defaultTool, line)) {
        is TerminalPlanResult.Invalid -> transcript += "error: ${result.reason}"
        is TerminalPlanResult.NotImplemented -> transcript += "not implemented: ${result.what}"
        is TerminalPlanResult.Ready -> panel.onRun(result.plan, false)
    }
}

private fun appendTextLines(transcript: MutableList<String>, text: String) {
    text.lineSequence().dropLastWhile(String::isEmpty).forEach(transcript::add)
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
private const val HOST_PROMPT = "❯"
private const val ADB_HOST_PROMPT = "adb ❯"
private const val FASTBOOT_PROMPT = "fastboot ❯"
