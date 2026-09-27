package io.github.ncorror.nekoflash

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
    val resolver = context.contentResolver
    val executor = remember(adbLink, fastbootLink) {
        DesktopCommandExecutor(ControllerCommandTargets(adbLink, fastbootLink, context.cacheDir))
    }
    var pending by remember { mutableStateOf<TerminalPlan?>(null) }
    var area by remember { mutableStateOf<DesktopCommandArea?>(null) }
    var fileMissing by remember { mutableStateOf(false) }

    fun run(plan: TerminalPlan, file: PickedFile?) {
        when (val started = executor.execute(plan, file)) {
            is DesktopCommandStart.Started -> {
                area = started.area
                fileMissing = false
            }
            DesktopCommandStart.FileMissing -> fileMissing = true
        }
    }

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { chosen ->
        val plan = pending
        pending = null
        if (plan != null) run(plan, chosen?.let { uri -> PickedFile.Open { SafArtifactSource(resolver, uri) } })
    }
    val createLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { destination ->
        val plan = pending
        pending = null
        val name = plan?.fileStep?.let(::suggestedName) ?: DEFAULT_NAME
        if (plan != null) {
            run(plan, destination?.let { uri -> PickedFile.Create { SafArtifactSink(resolver, uri, name) } })
        }
    }

    val fastbootState by fastbootLink.state.collectAsState()
    val link = fastbootState as? FastbootLinkState.Connected
    return DesktopCommandPanel(
        // Без префикса строка относится к тому, с чем связь есть сейчас: у
        // устройства в загрузчике ADB нет, а в Android нет Fastboot.
        defaultTool = if (link != null) TerminalParser.Tool.FASTBOOT else TerminalParser.Tool.ADB,
        lock = link?.lock,
        onRun = { plan ->
            when (plan.fileStep?.fileNeed ?: TerminalFileNeed.NONE) {
                TerminalFileNeed.NONE -> run(plan, null)
                TerminalFileNeed.OPEN -> {
                    pending = plan
                    openLauncher.launch(arrayOf("*/*"))
                }
                TerminalFileNeed.CREATE -> {
                    pending = plan
                    createLauncher.launch(plan.fileStep?.let(::suggestedName) ?: DEFAULT_NAME)
                }
            }
        },
        area = area,
        fileMissing = fileMissing,
        outcomes = DesktopCommandOutcomes(
            fastboot = fastbootLink.console.collectAsState().value,
            shell = adbLink.command.collectAsState().value,
            service = adbLink.rawService.collectAsState().value,
            reboot = adbLink.reboot.collectAsState().value,
            files = adbLink.files.collectAsState().value,
            sideload = adbLink.sideload.collectAsState().value,
        ),
    )
}

/** Имя, которое предлагается в диалоге сохранения: из самой команды. */
private fun suggestedName(step: TerminalPlanStep): String = when (step) {
    is TerminalPlanStep.FastbootFetch -> step.destinationName.substringAfterLast('/')
    is TerminalPlanStep.AdbPull -> step.localName.substringAfterLast('/')
    else -> DEFAULT_NAME
}.ifBlank { DEFAULT_NAME }

private const val DEFAULT_NAME = "artifact.bin"
