package io.github.ncorror.nekoflash

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import com.ncorror.nekoflash.pro.engine.TerminalFileNeed
import com.ncorror.nekoflash.pro.engine.TerminalPlan
import com.ncorror.nekoflash.pro.engine.TerminalPlanStep
import com.ncorror.nekoflash.pro.engine.fileNeed
import io.github.ncorror.nekoflash.adb.AdbInstallState
import io.github.ncorror.nekoflash.adb.AdbLinkController
import io.github.ncorror.nekoflash.adb.AdbSideloadState
import io.github.ncorror.nekoflash.adb.AdbTransferState
import io.github.ncorror.nekoflash.artifact.SafArtifactSink
import io.github.ncorror.nekoflash.artifact.SafArtifactSource
import io.github.ncorror.nekoflash.artifact.SafFolder
import io.github.ncorror.nekoflash.command.ControllerCommandTargets
import io.github.ncorror.nekoflash.command.DesktopCommandExecutor
import io.github.ncorror.nekoflash.command.DesktopCommandStart
import io.github.ncorror.nekoflash.command.PickedFile
import io.github.ncorror.nekoflash.fastboot.FastbootLinkController
import io.github.ncorror.nekoflash.protocol.adb.AdbSideloadOutcome
import io.github.ncorror.nekoflash.ui.DesktopCommandOutcomes
import io.github.ncorror.nekoflash.ui.DesktopCommandPanel
import io.github.ncorror.nekoflash.usb.api.UsbSessionCoordinator
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** Command execution wiring used by the host terminal. */
@Composable
internal fun desktopCommandPanel(
    adbLink: AdbLinkController,
    fastbootLink: FastbootLinkController,
    coordinator: UsbSessionCoordinator,
): DesktopCommandPanel {
    val context = LocalContext.current
    val executor = remember(adbLink, fastbootLink, coordinator) {
        DesktopCommandExecutor(
            ControllerCommandTargets(adbLink, fastbootLink, context.cacheDir) { coordinator.sessions.value },
        )
    }
    val terminalEvents = remember { DesktopTerminalEvents() }

    LaunchedEffect(terminalEvents) {
        terminalEvents.pump()
    }
    ObserveAdbCommandOutcomes(adbLink, terminalEvents)

    fun run(plan: TerminalPlan, file: PickedFile?) {
        desktopStartTerminalText(executor.execute(plan, file))?.let(terminalEvents::emit)
    }

    return DesktopCommandPanel(
        onRun = rememberFilePickers(::run),
        onEdit = terminalEvents::clear,
        answer = terminalEvents.answer.value,
        outcomes = DesktopCommandOutcomes(
            fastboot = fastbootLink.console.collectAsState().value,
            shell = adbLink.command.collectAsState().value,
            service = adbLink.rawService.collectAsState().value,
            reboot = adbLink.reboot.collectAsState().value,
        ),
    )
}

/**
 * Bridges asynchronous command outcomes into the host terminal's existing
 * answer slot without losing repeated identical messages.
 *
 * The slot is cleared for one frame before every event. That matters because
 * the terminal intentionally suppresses recompositions of the same String;
 * two identical `Success` replies are still two completed commands and must
 * both be printed. The channel also serializes concurrent ADB streams instead
 * of letting one completion overwrite another.
 */
private class DesktopTerminalEvents {
    val answer = mutableStateOf<String?>(null)
    private val events = Channel<String>(Channel.UNLIMITED)

    fun emit(message: String) {
        if (message.isNotBlank()) events.trySend(message)
    }

    fun clear() {
        answer.value = null
    }

    suspend fun pump() {
        for (message in events) {
            answer.value = null
            withFrameNanos { }
            answer.value = message
            withFrameNanos { }
        }
    }
}

/**
 * Watches only results which were orphaned when the old command panel was
 * removed. `drop(1)` prevents a finished result from a previous screen visit
 * being printed as if a new command had just completed.
 */
@Composable
private fun ObserveAdbCommandOutcomes(adbLink: AdbLinkController, terminalEvents: DesktopTerminalEvents) {
    LaunchedEffect(adbLink, terminalEvents) {
        launch {
            adbLink.install.drop(1).collect { state ->
                adbInstallTerminalText(state)?.let(terminalEvents::emit)
            }
        }
        launch {
            adbLink.transfer.drop(1).collect { state ->
                adbTransferTerminalText(state)?.let(terminalEvents::emit)
            }
        }
        launch {
            adbLink.sideload.drop(1).collect { state ->
                adbSideloadTerminalText(state)?.let(terminalEvents::emit)
            }
        }
    }
}

/** Immediate host-side result before a device operation starts. */
internal fun desktopStartTerminalText(started: DesktopCommandStart): String? = when (started) {
    is DesktopCommandStart.Started -> null
    is DesktopCommandStart.Answered -> started.text
    DesktopCommandStart.FileMissing -> "error: required file or folder was not selected"
    is DesktopCommandStart.FileCount ->
        "error: command expects ${started.expected} files, selected ${started.picked}"
}

/** Final `adb install`/`install-multiple` answer. */
internal fun adbInstallTerminalText(state: AdbInstallState): String? = when (state) {
    AdbInstallState.None, is AdbInstallState.Running -> null
    is AdbInstallState.Finished -> state.answer.ifBlank { if (state.success) "Success" else "Failure" }
    is AdbInstallState.Failed -> "error: install: ${state.detail}"
}

/** Final `adb push`/`pull`/`sync` report. */
internal fun adbTransferTerminalText(state: AdbTransferState): String? = when (state) {
    AdbTransferState.None, is AdbTransferState.Running -> null
    is AdbTransferState.Failed -> "error: ${state.what}: ${state.detail}"
    is AdbTransferState.Finished -> buildString {
        state.report.messages.forEach { message ->
            append(message)
            append('\n')
        }
        append(state.what)
        append(if (state.report.success) ": complete" else ": completed with errors")
        append("; files=")
        append(state.report.files)
        append(" bytes=")
        append(state.report.bytes)
        if (state.report.skipped > 0) {
            append(" skipped=")
            append(state.report.skipped)
        }
    }
}

/** Final Sideload transfer result. Installation success still belongs to Recovery evidence. */
internal fun adbSideloadTerminalText(state: AdbSideloadState): String? = when (state) {
    AdbSideloadState.None, is AdbSideloadState.Running, is AdbSideloadState.Staging -> null
    is AdbSideloadState.Refused -> "error: sideload: ${state.detail}"
    is AdbSideloadState.Finished -> {
        val verification = if (state.verificationPending) "; Recovery verification required" else ""
        when (val outcome = state.outcome) {
            AdbSideloadOutcome.TransferComplete -> "sideload: transfer complete$verification"
            is AdbSideloadOutcome.ClosedBeforeDoneDone ->
                "sideload: Recovery closed before DONEDONE at ${outcome.percent}% unique coverage: " +
                    "${outcome.detail}$verification"
            is AdbSideloadOutcome.InterruptedAfterPayload ->
                "sideload: interrupted after payload at ${outcome.percent}% unique coverage: " +
                    "${outcome.detail}$verification"
            AdbSideloadOutcome.Cancelled -> "sideload: cancelled before payload"
            is AdbSideloadOutcome.NotInSideloadMode ->
                "error: sideload: device is not in Sideload mode (${outcome.mode})"
            is AdbSideloadOutcome.Failed ->
                "error: sideload ${outcome.kind.name.lowercase()}: ${outcome.detail}"
        }
    }
}

/** File/folder pickers required by command plans. */
@Composable
private fun rememberFilePickers(run: (TerminalPlan, PickedFile?) -> Unit): (TerminalPlan, Boolean) -> Unit {
    val resolver = LocalContext.current.contentResolver
    var pending by remember { mutableStateOf<TerminalPlan?>(null) }
    fun take(): TerminalPlan? = pending.also { pending = null }

    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { chosen ->
        take()?.let { plan -> run(plan, opened(resolver, chosen)) }
    }
    val openMany = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { chosen ->
        val origins = chosen.map { uri -> { SafArtifactSource(resolver, uri) } }
        take()?.let { plan -> run(plan, origins.takeIf { it.isNotEmpty() }?.let(PickedFile::OpenMany)) }
    }
    val create = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { destination ->
        take()?.let { plan -> run(plan, created(resolver, destination, suggestedName(plan))) }
    }
    val tree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { chosen ->
        take()?.let { plan -> run(plan, chosen?.let { uri -> PickedFile.Folder { SafFolder(resolver, uri) } }) }
    }
    return { plan, asFolder ->
        val need = plan.fileStep?.fileNeed ?: TerminalFileNeed.NONE
        if (need == TerminalFileNeed.NONE) {
            run(plan, null)
        } else {
            pending = plan
            when (dialogFor(need, asFolder)) {
                TerminalFileNeed.FOLDER -> tree.launch(null)
                TerminalFileNeed.OPEN_MANY -> openMany.launch(ANY)
                TerminalFileNeed.CREATE -> create.launch(suggestedName(plan))
                else -> open.launch(ANY)
            }
        }
    }
}

private fun dialogFor(need: TerminalFileNeed, asFolder: Boolean): TerminalFileNeed =
    if (need == TerminalFileNeed.OPEN_OR_FOLDER) {
        if (asFolder) TerminalFileNeed.FOLDER else TerminalFileNeed.OPEN
    } else {
        need
    }

private val ANY = arrayOf("*/*")

private fun opened(resolver: ContentResolver, uri: Uri?): PickedFile? =
    uri?.let { PickedFile.Open { SafArtifactSource(resolver, it) } }

private fun created(resolver: ContentResolver, uri: Uri?, name: String): PickedFile? =
    uri?.let { PickedFile.Create { SafArtifactSink(resolver, it, name) } }

private fun suggestedName(plan: TerminalPlan): String = plan.fileStep?.let(::suggestedName) ?: DEFAULT_NAME

private fun suggestedName(step: TerminalPlanStep): String = when (step) {
    is TerminalPlanStep.FastbootFetch -> step.destinationName.substringAfterLast('/')
    else -> DEFAULT_NAME
}.ifBlank { DEFAULT_NAME }

private const val DEFAULT_NAME = "artifact.bin"
