package io.github.ncorror.nekoflash

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.ncorror.nekoflash.pro.engine.TerminalFileNeed
import com.ncorror.nekoflash.pro.engine.TerminalPlan
import com.ncorror.nekoflash.pro.engine.TerminalPlanStep
import com.ncorror.nekoflash.pro.engine.fileNeed
import io.github.ncorror.nekoflash.adb.AdbLinkController
import io.github.ncorror.nekoflash.artifact.SafArtifactSink
import io.github.ncorror.nekoflash.artifact.SafArtifactSource
import io.github.ncorror.nekoflash.artifact.SafFolder
import io.github.ncorror.nekoflash.command.ControllerCommandTargets
import io.github.ncorror.nekoflash.command.DesktopCommandExecutor
import io.github.ncorror.nekoflash.command.DesktopCommandStart
import io.github.ncorror.nekoflash.command.PickedFile
import io.github.ncorror.nekoflash.fastboot.FastbootLinkController
import io.github.ncorror.nekoflash.ui.DesktopCommandOutcomes
import io.github.ncorror.nekoflash.ui.DesktopCommandPanel
import io.github.ncorror.nekoflash.usb.api.UsbSessionCoordinator

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
    var answer by remember { mutableStateOf<String?>(null) }

    fun run(plan: TerminalPlan, file: PickedFile?) {
        val started = executor.execute(plan, file)
        answer = (started as? DesktopCommandStart.Answered)?.text
    }

    return DesktopCommandPanel(
        onRun = rememberFilePickers(::run),
        onEdit = { answer = null },
        answer = answer,
        outcomes = DesktopCommandOutcomes(
            fastboot = fastbootLink.console.collectAsState().value,
            shell = adbLink.command.collectAsState().value,
            service = adbLink.rawService.collectAsState().value,
            reboot = adbLink.reboot.collectAsState().value,
        ),
    )
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
