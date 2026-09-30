package io.github.ncorror.nekoflash

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.ncorror.nekoflash.pro.engine.TerminalFileNeed
import com.ncorror.nekoflash.pro.engine.TerminalParser
import com.ncorror.nekoflash.pro.engine.TerminalPlan
import com.ncorror.nekoflash.pro.engine.TerminalPlanStep
import com.ncorror.nekoflash.pro.engine.fileNeed
import io.github.ncorror.nekoflash.adb.AdbLinkController
import io.github.ncorror.nekoflash.artifact.SafArtifactSink
import io.github.ncorror.nekoflash.artifact.SafArtifactSource
import io.github.ncorror.nekoflash.artifact.SafFolder
import io.github.ncorror.nekoflash.command.DesktopCommandArea
import io.github.ncorror.nekoflash.command.DesktopCommandExecutor
import io.github.ncorror.nekoflash.command.DesktopCommandStart
import io.github.ncorror.nekoflash.command.ControllerCommandTargets
import io.github.ncorror.nekoflash.command.PickedFile
import io.github.ncorror.nekoflash.fastboot.FastbootLinkController
import io.github.ncorror.nekoflash.fastboot.FastbootLinkState
import io.github.ncorror.nekoflash.ui.DesktopCommandOutcomes
import io.github.ncorror.nekoflash.ui.DesktopCommandPanel
import io.github.ncorror.nekoflash.usb.api.UsbSessionCoordinator

/**
 * Проводка команд настольных клиентов: выбор файла, исполнение, исход.
 *
 * План, которому нужен файл, ждёт системного диалога и исполняется ровно тот,
 * что был показан: выбор привязывается к плану, а не к строке, которую за это
 * время могли поменять (`04` §3, правило 7).
 */
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
    var area by remember { mutableStateOf<DesktopCommandArea?>(null) }
    var fileProblem by remember { mutableStateOf<DesktopCommandStart?>(null) }
    var answer by remember { mutableStateOf<String?>(null) }

    fun run(plan: TerminalPlan, file: PickedFile?) {
        val started = executor.execute(plan, file)
        area = (started as? DesktopCommandStart.Started)?.area
        answer = (started as? DesktopCommandStart.Answered)?.text
        fileProblem = started.takeIf { it is DesktopCommandStart.FileMissing || it is DesktopCommandStart.FileCount }
    }

    val onRun = rememberFilePickers(::run)

    val fastbootState by fastbootLink.state.collectAsState()
    val link = fastbootState as? FastbootLinkState.Connected
    return DesktopCommandPanel(
        // Без префикса строка относится к тому, с чем связь есть сейчас: у
        // устройства в загрузчике ADB нет, а в Android нет Fastboot.
        defaultTool = if (link != null) TerminalParser.Tool.FASTBOOT else TerminalParser.Tool.ADB,
        lock = link?.lock,
        onRun = onRun,
        onEdit = {
            area = null
            fileProblem = null
            answer = null
        },
        area = area,
        answer = answer,
        fileProblem = fileProblem,
        outcomes = DesktopCommandOutcomes(
            fastboot = fastbootLink.console.collectAsState().value,
            shell = adbLink.command.collectAsState().value,
            service = adbLink.rawService.collectAsState().value,
            reboot = adbLink.reboot.collectAsState().value,
            sideload = adbLink.sideload.collectAsState().value,
            install = adbLink.install.collectAsState().value,
            transfer = adbLink.transfer.collectAsState().value,
            terminal = adbLink.terminals.collectAsState().value.let { tabs ->
                tabs.tabs[tabs.selected].collectAsState().value
            },
        ),
    )
}

/**
 * Системные диалоги выбора файла для плана.
 *
 * Возвращает вход «исполнить план»: без файла план уходит сразу, с файлом —
 * после диалога, и исполняется тот же план, что был показан.
 */
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

/** Какой диалог открыть: у «файла или папки» решает выбор оператора. */
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

/** Имя, которое предлагается в диалоге сохранения: из самой команды. */
private fun suggestedName(step: TerminalPlanStep): String = when (step) {
    is TerminalPlanStep.FastbootFetch -> step.destinationName.substringAfterLast('/')
    else -> DEFAULT_NAME
}.ifBlank { DEFAULT_NAME }

private const val DEFAULT_NAME = "artifact.bin"
