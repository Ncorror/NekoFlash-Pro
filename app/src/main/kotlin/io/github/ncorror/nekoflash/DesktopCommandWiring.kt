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
import io.github.ncorror.nekoflash.command.DesktopCommandArea
import io.github.ncorror.nekoflash.command.DesktopCommandExecutor
import io.github.ncorror.nekoflash.command.DesktopCommandStart
import io.github.ncorror.nekoflash.command.ControllerCommandTargets
import io.github.ncorror.nekoflash.command.PickedFile
import io.github.ncorror.nekoflash.fastboot.FastbootLinkController
import io.github.ncorror.nekoflash.fastboot.FastbootLinkState
import io.github.ncorror.nekoflash.ui.DesktopCommandOutcomes
import io.github.ncorror.nekoflash.ui.DesktopCommandPanel

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
): DesktopCommandPanel {
    val context = LocalContext.current
    val executor = remember(adbLink, fastbootLink) {
        DesktopCommandExecutor(ControllerCommandTargets(adbLink, fastbootLink, context.cacheDir))
    }
    var area by remember { mutableStateOf<DesktopCommandArea?>(null) }
    var fileProblem by remember { mutableStateOf<DesktopCommandStart?>(null) }

    fun run(plan: TerminalPlan, file: PickedFile?) {
        val started = executor.execute(plan, file)
        if (started is DesktopCommandStart.Started) {
            area = started.area
            fileProblem = null
        } else {
            fileProblem = started
        }
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
        },
        area = area,
        fileProblem = fileProblem,
        outcomes = DesktopCommandOutcomes(
            fastboot = fastbootLink.console.collectAsState().value,
            shell = adbLink.command.collectAsState().value,
            service = adbLink.rawService.collectAsState().value,
            reboot = adbLink.reboot.collectAsState().value,
            files = adbLink.files.collectAsState().value,
            sideload = adbLink.sideload.collectAsState().value,
            install = adbLink.install.collectAsState().value,
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
private fun rememberFilePickers(run: (TerminalPlan, PickedFile?) -> Unit): (TerminalPlan) -> Unit {
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
    return { plan ->
        when (plan.fileStep?.fileNeed ?: TerminalFileNeed.NONE) {
            TerminalFileNeed.NONE -> run(plan, null)
            TerminalFileNeed.OPEN -> open.launch(ANY).also { pending = plan }
            TerminalFileNeed.OPEN_MANY -> openMany.launch(ANY).also { pending = plan }
            TerminalFileNeed.CREATE -> create.launch(suggestedName(plan)).also { pending = plan }
        }
    }
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
    is TerminalPlanStep.AdbPull -> step.localName.substringAfterLast('/')
    else -> DEFAULT_NAME
}.ifBlank { DEFAULT_NAME }

private const val DEFAULT_NAME = "artifact.bin"
