package io.github.ncorror.nekoflash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ncorror.nekoflash.pro.engine.TerminalParser
import com.ncorror.nekoflash.pro.engine.TerminalPlanCompiler
import com.ncorror.nekoflash.pro.engine.TerminalPlanResult
import io.github.ncorror.nekoflash.R
import io.github.ncorror.nekoflash.adb.AdbLinkState
import io.github.ncorror.nekoflash.adb.AdbTerminalState
import io.github.ncorror.nekoflash.fastboot.FastbootConsoleState
import io.github.ncorror.nekoflash.fastboot.FastbootLinkState
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootReply

/**
 * Main production workspace.
 *
 * ADB mode is a real interactive PTY. Text reaches that PTY through an Android
 * InputConnection, like Termux/NetHunter, instead of through a Compose text
 * field. Fastboot stays line-oriented because the protocol itself is.
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
    val fastbootTranscript = remember { mutableStateListOf<String>() }
    val offlineTranscript = remember { mutableStateListOf<String>() }
    val stagedInput = rememberSaveable { mutableStateOf("") }
    val focusRequest = remember { mutableStateOf(0) }
    val inputFocused = remember { mutableStateOf(false) }
    val offlineStatus = "ADB/Fastboot: ${stringResource(R.string.adb_state_idle)}.\n" +
        "[NekoFlash] ${stringResource(R.string.terminal_connect_device)}"
    val router = TerminalInputRouter(
        currentReady = current.ready,
        fastbootConnected = fastbootConnected,
        stagedInput = stagedInput,
        offlineTranscript = offlineTranscript,
        actions = actions,
        connectMessage = offlineStatus,
        offlinePrompt = ADB_PROMPT,
        onFastbootCommand = { command ->
            fastbootTranscript += "$ $command"
            compileAndRunFastboot(command, desktopCommand, fastbootTranscript)
        },
    )

    TerminalAutomation(adbLink, fastbootLink, current, actions, desktopCommand, fastbootTranscript)
    LaunchedEffect(current.ready, fastbootConnected) {
        if (current.ready && !fastbootConnected) stagedInput.value = ""
    }
    TerminalWorkspaceLayout(
        adbLink = adbLink,
        terminal = terminal,
        actions = actions,
        router = router,
        fastbootConnected = fastbootConnected,
        fastbootTranscript = fastbootTranscript,
        offlineTranscript = offlineTranscript,
        stagedInput = stagedInput,
        focusRequest = focusRequest,
        inputFocused = inputFocused,
        modifier = modifier,
    )
}

@Composable
private fun TerminalAutomation(
    adbLink: AdbLinkState,
    fastbootLink: FastbootLinkState,
    current: AdbTerminalState,
    actions: TerminalActions,
    desktopCommand: DesktopCommandPanel,
    fastbootTranscript: MutableList<String>,
) {
    val adbGeneration = (adbLink as? AdbLinkState.Connected)?.generation?.value
    val fastbootGeneration = (fastbootLink as? FastbootLinkState.Connected)?.generation?.value
    AutoStartAdbShell(adbGeneration, current.active, actions.onStart)
    AutoPrintAdbDeviceInfo(adbGeneration, current.ready, actions.onSend)
    AutoPrintFastbootDeviceInfo(fastbootGeneration, desktopCommand, fastbootTranscript)
    ObserveFastbootOutcome(desktopCommand.outcomes.fastboot, fastbootTranscript)
}

@Composable
private fun TerminalWorkspaceLayout(
    adbLink: AdbLinkState,
    terminal: TerminalPanel,
    actions: TerminalActions,
    router: TerminalInputRouter,
    fastbootConnected: Boolean,
    fastbootTranscript: List<String>,
    offlineTranscript: List<String>,
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
            TerminalOutput(
                currentReady = terminal.current.ready,
                fastbootConnected = fastbootConnected,
                fastbootTranscript = fastbootTranscript,
                offlineTranscript = offlineTranscript,
                adbLink = adbLink,
                terminal = terminal,
                actions = actions,
                onTap = focusInput,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            if (fastbootConnected || !terminal.current.ready) {
                TerminalInputDisplay(
                    value = stagedInput.value,
                    prompt = if (fastbootConnected) FASTBOOT_PROMPT else ADB_PROMPT,
                    live = fastbootConnected,
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
private fun TerminalOutput(
    currentReady: Boolean,
    fastbootConnected: Boolean,
    fastbootTranscript: List<String>,
    offlineTranscript: List<String>,
    adbLink: AdbLinkState,
    terminal: TerminalPanel,
    actions: TerminalActions,
    onTap: () -> Unit,
    modifier: Modifier,
) {
    when {
        fastbootConnected -> LineTerminalOutput(fastbootTranscript, onTap, modifier)
        !currentReady && offlineTranscript.isNotEmpty() -> LineTerminalOutput(offlineTranscript, onTap, modifier)
        else -> TerminalScreen(
            screen = terminal.current.screen,
            placeholder = terminalPlaceholder(adbLink, currentReady),
            showCursor = currentReady,
            onResize = actions.onResize,
            onTap = onTap,
            modifier = modifier,
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

@Composable
private fun LineTerminalOutput(
    lines: List<String>,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
        modifier = modifier.terminalTap(onTap).padding(OUTPUT_PADDING),
        verticalArrangement = Arrangement.spacedBy(OUTPUT_GAP),
    ) {
        itemsIndexed(lines) { _, line ->
            Text(
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
