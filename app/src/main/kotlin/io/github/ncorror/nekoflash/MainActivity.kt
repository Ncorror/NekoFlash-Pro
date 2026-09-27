package io.github.ncorror.nekoflash

import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import io.github.ncorror.nekoflash.ui.NekoFlashApp
import io.github.ncorror.nekoflash.ui.OperationsPanel
import io.github.ncorror.nekoflash.ui.PaletteAction
import io.github.ncorror.nekoflash.ui.RecentEvents
import io.github.ncorror.nekoflash.adb.AdbLinkController
import io.github.ncorror.nekoflash.fastboot.FastbootConsoleState
import io.github.ncorror.nekoflash.fastboot.FastbootLinkController
import io.github.ncorror.nekoflash.fastboot.FastbootLinkState
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootMode
import io.github.ncorror.nekoflash.ui.FastbootConsolePanel
import io.github.ncorror.nekoflash.ui.FastbootPanel
import io.github.ncorror.nekoflash.usb.api.UsbInterfaceKind
import io.github.ncorror.nekoflash.usb.api.UsbSession
import io.github.ncorror.nekoflash.artifact.SafArtifactSink
import io.github.ncorror.nekoflash.artifact.SafArtifactSource
import io.github.ncorror.nekoflash.ui.FileActions
import io.github.ncorror.nekoflash.ui.FirstRun
import io.github.ncorror.nekoflash.ui.ForwardPanel
import io.github.ncorror.nekoflash.ui.RawServicePanel
import io.github.ncorror.nekoflash.ui.RebootPanel
import io.github.ncorror.nekoflash.ui.ReversePanel
import io.github.ncorror.nekoflash.ui.SideloadPanel
import io.github.ncorror.nekoflash.ui.TerminalActions
import io.github.ncorror.nekoflash.ui.WelcomeScreen
import io.github.ncorror.nekoflash.ui.theme.NekoFlashTheme
import io.github.ncorror.nekoflash.usb.api.UsbClaimResult
import io.github.ncorror.nekoflash.usb.api.UsbSessionCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Владение USB живёт на уровне приложения: подключённое устройство не
        // должно теряться при повороте экрана или пересоздании активности.
        val application = application as NekoFlashApplication
        val coordinator = application.usbSessions
        val adbLink = application.adbLink
        val fastbootLink = application.fastbootLink

        setContent {
            val firstRun = remember { FirstRun(this) }
            var welcomeSeen by rememberSaveable { mutableStateOf(firstRun.seen) }
            NekoFlashTheme {
                // Вводный экран показывается один раз и ничего не решает: это
                // «понял, дальше», а не согласие с условиями. Права быть
                // профессионалом он не выдаёт — оно уже есть (`01` §3).
                if (welcomeSeen) {
                    NekoFlashScreen(application, coordinator, adbLink, fastbootLink)
                } else {
                    WelcomeScreen(
                        onContinue = {
                            firstRun.seen = true
                            welcomeSeen = true
                        },
                    )
                }
            }
        }
    }

    /**
     * Дерево экрана целиком.
     *
     * Вынесено из `onCreate` не ради слоёв: `onCreate` перешёл порог detekt в
     * 60 строк, а поднимать пороги запрещено (`15` §4.1). Граница вышла
     * осмысленная — выше неё только владение процессом, ниже только экран.
     */
    @Composable
    private fun NekoFlashScreen(
        application: NekoFlashApplication,
        coordinator: UsbSessionCoordinator,
        adbLink: AdbLinkController,
        fastbootLink: FastbootLinkController,
    ) {
        val sessions by coordinator.sessions.collectAsState()
        val linkState by adbLink.state.collectAsState()
        val scan by coordinator.lastScan.collectAsState()
        val commandState by adbLink.command.collectAsState()
        val terminalState by adbLink.terminal.collectAsState()
        val fileState by adbLink.files.collectAsState()
        val fastbootState by fastbootLink.state.collectAsState()
        val fastbootConsole by fastbootLink.console.collectAsState()
        var exportStatus by remember { mutableStateOf<String?>(null) }

        val savedTemplate = stringResource(R.string.diagnostics_export_done)
        val failedTemplate = stringResource(R.string.diagnostics_export_failed)
        val claimFailedTemplate = stringResource(R.string.usb_claim_failed)

        // Системный диалог сохранения: файл создаёт пользователь там, где
        // ему нужно, а приложение не заводит собственного хранилища отчётов.
        val saveLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/zip"),
        ) { destination ->
            exportDiagnostics(application, destination, savedTemplate, failedTemplate) { message ->
                exportStatus = message
            }
        }

        // Скан при каждом возвращении на экран.
        //
        // Закрывает открытый вопрос `07` §6.22: приложение сканировало при
        // старте и по кнопке, и если система перечислила устройство позже,
        // не прислав `USB_DEVICE_ATTACHED` — например, пока приложение не
        // работало, — узнать об этом можно было только кнопкой.
        //
        // Тот конкретный случай из §6.22 это **не** закрывает, и там так и
        // написано: ручной скан тогда дал ноль. Закрывается другой,
        // соседний: устройство подключили при свёрнутом приложении.
        RescanOnResume(coordinator)

        NekoFlashApp(
            sessions = sessions,
            scan = scan,
            usbHostSupported = packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST),
            exportStatus = exportStatus,
            adbLink = linkState,
            adbCommand = commandState,
            terminal = terminalState,
            terminalActions = terminalActions(adbLink),
            files = fileState,
            fileActions = fileActions(adbLink),
            onRescanUsb = { coordinator.scanAttachedDevices() },
            onClaim = claimAction(coordinator, claimFailedTemplate) { exportStatus = it },
            onRelease = { session -> coordinator.release(session.generation) },
            onAdbConnect = { session -> adbLink.connect(session.generation) },
            onAdbDisconnect = { session -> adbLink.disconnect(session.generation) },
            onRunCommand = adbLink::runCommand,
            reboot = rebootPanel(adbLink),
            rawService = rawServicePanel(adbLink),
            forward = forwardPanel(adbLink),
            reverse = reversePanel(adbLink),
            sideload = sideloadPanel(adbLink),
            operations = operationsPanel(application),
            paletteActions = paletteActions(
                adbLink = adbLink,
                fastbootLink = fastbootLink,
                coordinator = coordinator,
                onExport = saveLauncher::launch,
                application = application,
            ),
            recentEvents = RecentEvents { application.recentDiagnostics() },
            fastboot = fastbootPanel(fastbootLink, fastbootState, sessions),
            fastbootConsole = fastbootConsolePanel(fastbootLink, fastbootConsole, fastbootState, sessions),
            onExportDiagnostics = { saveLauncher.launch(application.suggestedDiagnosticsFileName()) },
        )
    }
}

/**
 * История операций.
 *
 * Живые и законченные читаются из владельца операций, который живёт на уровне
 * приложения: операция не принадлежит экрану и переживает его (`06` §1).
 */
@Composable
private fun operationsPanel(application: NekoFlashApplication): OperationsPanel = OperationsPanel(
    live = application.operations.live.collectAsState().value,
    history = application.operations.history.collectAsState().value,
)

/**
 * Пересканирует USB при каждом возвращении на экран.
 *
 * Скан дешёвый: он спрашивает у системы список и ничего не захватывает. Делать
 * его по расписанию было бы хуже — шум в диагностике без нового знания, — а по
 * возвращению он приходится ровно на тот момент, когда пользователь и мог
 * что-то воткнуть.
 */
@Composable
private fun RescanOnResume(coordinator: UsbSessionCoordinator) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) coordinator.scanAttachedDevices()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

/**
 * Набор действий для палитры.
 *
 * Сюда попадают только действия **без набираемого аргумента**: путь к файлу,
 * имя раздела и текст команды набираются в своих полях, и дублировать их в
 * поиске значило бы завести второе место, где то же самое вводится иначе.
 *
 * Ничего нового палитра не добавляет и ничего не прячет: каждое действие здесь
 * есть и на своей карточке. Это второй путь к тому же — для того, кто помнит
 * название и не хочет листать.
 */
@Composable
private fun paletteActions(
    adbLink: AdbLinkController,
    fastbootLink: FastbootLinkController,
    coordinator: UsbSessionCoordinator,
    onExport: (String) -> Unit,
    application: NekoFlashApplication,
): List<PaletteAction> = listOf(
    PaletteAction(stringResource(R.string.action_rescan), "usb scan скан устройства") {
        coordinator.scanAttachedDevices()
    },
    PaletteAction(stringResource(R.string.action_export), "diagnostics отчёт логи bundle") {
        onExport(application.suggestedDiagnosticsFileName())
    },
    PaletteAction(stringResource(R.string.action_recovery_baseline), "baseline база журнал log") {
        adbLink.recovery.captureBaseline()
    },
    PaletteAction(stringResource(R.string.action_recovery_verdict), "verdict вердикт install исход") {
        adbLink.recovery.readVerdict()
    },
    PaletteAction(stringResource(R.string.action_sideload_small), "sideload пакет малый small") {
        adbLink.recovery.sideload(SMALL_PACKAGE_BYTES)
    },
    PaletteAction(stringResource(R.string.action_sideload_large), "sideload пакет большой large") {
        adbLink.recovery.sideload(LARGE_PACKAGE_BYTES)
    },
    PaletteAction(stringResource(R.string.action_reboot_system), "reboot перезагрузка система") {
        adbLink.requestReboot("")
    },
    PaletteAction(stringResource(R.string.action_reboot_bootloader), "reboot bootloader загрузчик") {
        adbLink.requestReboot("bootloader")
    },
    PaletteAction(stringResource(R.string.action_reboot_recovery), "reboot recovery рекавери") {
        adbLink.requestReboot("recovery")
    },
    PaletteAction(stringResource(R.string.action_reboot_sideload), "reboot sideload сайдлоад") {
        adbLink.requestReboot("sideload")
    },
    PaletteAction(stringResource(R.string.action_fastboot_getvar_all), "getvar all переменные") {
        fastbootLink.readAllVariables()
    },
    PaletteAction(stringResource(R.string.action_fastboot_reboot_bootloader), "fastboot reboot загрузчик") {
        fastbootLink.runCommand("reboot-bootloader")
    },
    PaletteAction(stringResource(R.string.action_fastboot_reboot_fastbootd), "fastbootd userspace") {
        fastbootLink.runCommand("reboot fastboot")
    },
)

/**
 * Размеры пакетов для палитры — те же, что на кнопках Sideload.
 *
 * Держатся рядом с палитрой, а не переиспользуются из панели: там они приватны,
 * и открывать их наружу ради двух строк значило бы расширить чужой контракт.
 */
private const val SMALL_PACKAGE_BYTES = 64L * 1024L
private const val LARGE_PACKAGE_BYTES = 16L * 1024L * 1024L

/** Проводка панели перезагрузки: состояние экрана и действие контроллера. */
@Composable
private fun rebootPanel(adbLink: AdbLinkController): RebootPanel = RebootPanel(
    state = adbLink.reboot.collectAsState().value,
    onReboot = adbLink::requestReboot,
)

/** То же для произвольного сервиса. */
@Composable
private fun rawServicePanel(adbLink: AdbLinkController): RawServicePanel = RawServicePanel(
    state = adbLink.rawService.collectAsState().value,
    onCall = adbLink::callRawService,
)

/**
 * То же для передачи пакета в Recovery — и выбор пакета через системный диалог.
 *
 * Копия непрозрачного источника кладётся в кэш приложения: она не переживает
 * операцию намеренно, и система вправе убрать её сама, если места станет мало.
 */
@Composable
private fun sideloadPanel(adbLink: AdbLinkController): SideloadPanel {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { chosen ->
        if (chosen != null) {
            adbLink.recovery.sideloadFrom(context.cacheDir) { SafArtifactSource(resolver, chosen) }
        }
    }
    return SideloadPanel(
        state = adbLink.sideload.collectAsState().value,
        onSend = adbLink.recovery::sideload,
        onChoose = { chooser.launch(arrayOf("application/zip", "*/*")) },
        onCancel = adbLink.recovery::cancelSideload,
    )
}

/** То же для пробросов портов. */
@Composable
private fun forwardPanel(adbLink: AdbLinkController): ForwardPanel = ForwardPanel(
    state = adbLink.forward.collectAsState().value,
    onAdd = adbLink.forwards::add,
    onRemove = adbLink.forwards::remove,
)

/** То же для обратных пробросов. */
@Composable
private fun reversePanel(adbLink: AdbLinkController): ReversePanel = ReversePanel(
    state = adbLink.reverse.collectAsState().value,
    onAdd = adbLink.reverses::add,
    onRefresh = adbLink.reverses::refresh,
    onRemoveAll = adbLink.reverses::removeAll,
)

private fun MainActivity.exportDiagnostics(
    application: NekoFlashApplication,
    destination: Uri?,
    savedTemplate: String,
    failedTemplate: String,
    onStatus: (String) -> Unit,
) {
    if (destination == null) return
    lifecycleScope.launch {
        val outcome = withContext(Dispatchers.IO) {
            runCatching { application.writeDiagnostics(destination) }
        }
        onStatus(
            outcome.fold(
                onSuccess = { result -> savedTemplate.format(result.sectionCount) },
                onFailure = { failure ->
                    failedTemplate.format(failure.message ?: failure.javaClass.simpleName)
                },
            ),
        )
    }
}

/** Действия терминала собраны отдельно: в теле экрана они только шумят. */
/**
 * Панель Fastboot: состояние опроса и два действия над ним.
 *
 * Вынесена из `onCreate` не ради красоты — иначе метод перерастает ориентир
 * длины, а раздувать точку входа именно тем, что легко вынести, значит начинать
 * тот путь, которым `MainActivity` Legacy дошла до 3880 строк.
 */
private fun fastbootPanel(
    link: FastbootLinkController,
    state: FastbootLinkState,
    sessions: List<UsbSession>,
): FastbootPanel = FastbootPanel(
    state = state,
    // Generation берётся из живой сессии: опрос принадлежит тому подключению,
    // в котором он начат.
    onProbe = {
        sessions.firstOrNull { it.candidate.kind == UsbInterfaceKind.FASTBOOT }
            ?.let { session -> link.connect(session.generation) }
    },
    onDisconnect = link::disconnect,
    // Тот же вход, что и у консоли: второго пути к полосе нет по построению.
    onPlan = link::runPlan,
)

@Composable
private fun fastbootConsolePanel(
    link: FastbootLinkController,
    state: FastbootConsoleState,
    linkState: FastbootLinkState,
    sessions: List<UsbSession>,
): FastbootConsolePanel {
    val resolver = LocalContext.current.contentResolver
    val pendingFetch = remember { mutableStateOf<String?>(null) }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { destination ->
        val partition = pendingFetch.value
        pendingFetch.value = null
        if (destination != null && partition != null) {
            link.fetchPartitionTo(partition) { SafArtifactSink(resolver, destination, partition) }
        }
    }

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { chosen ->
        if (chosen != null) {
            link.downloadFrom { SafArtifactSource(resolver, chosen) }
        }
    }

    return FastbootConsolePanel(
        state = state,
        onCommand = link::runCommand,
        onVariable = link::readVariable,
        onAllVariables = link::readAllVariables,
        onDownload = link::downloadGenerated,
        onFetch = link::fetchPartition,
        onFetchToFile = { partition ->
            pendingFetch.value = partition
            saveLauncher.launch(partition.ifBlank { "partition" } + ".img")
        },
        onDownloadFile = { openLauncher.launch(arrayOf("*/*")) },
        // Отпустить и взять заново — тем же входом, что и кнопки шапки.
        // Generation берётся из живой сессии, а не из прошлой связи: захват
        // принадлежит тому подключению, которое есть сейчас.
        onReclaim = {
            link.disconnect()
            sessions.firstOrNull { it.candidate.kind == UsbInterfaceKind.FASTBOOT }
                ?.let { session -> link.connect(session.generation) }
        },
        // Роль берётся из той же связи, что и в шапке, а не из отдельной
        // догадки: две строки о роли, способные разойтись, хуже одной.
        mode = (linkState as? FastbootLinkState.Connected)?.identity?.mode ?: FastbootMode.UNKNOWN,
    )
}

/**
 * Проводка файловых действий, включая два, которым нужен системный диалог.
 *
 * Диалог отдаёт `Uri` позже и в другом обратном вызове, чем нажатие кнопки,
 * поэтому путь на устройстве приходится придержать между ними. Держится он
 * ровно до возврата из диалога и сбрасывается в любом случае, включая отказ:
 * иначе следующий выбор файла достался бы прошлой команде.
 *
 * Ни источник, ни приёмник здесь не открываются: разговор с чужим провайдером
 * это ввод-вывод, и на главном потоке он подвесил бы экран. Наружу уходит
 * функция, которую контроллер вызовет на своём потоке.
 */
@Composable
private fun fileActions(link: AdbLinkController): FileActions {
    val resolver = LocalContext.current.contentResolver
    val pendingRead = remember { mutableStateOf<String?>(null) }
    val pendingWrite = remember { mutableStateOf<String?>(null) }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { destination ->
        val path = pendingRead.value
        pendingRead.value = null
        if (destination != null && path != null) {
            val shown = path.substringAfterLast('/')
            link.storage.readTo(path) { SafArtifactSink(resolver, destination, shown) }
        }
    }

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { chosen ->
        val path = pendingWrite.value
        pendingWrite.value = null
        if (chosen != null && path != null) {
            link.storage.writeFrom(path) { SafArtifactSource(resolver, chosen) }
        }
    }

    return FileActions(
        onDescribe = link.storage::describe,
        onRead = link.storage::read,
        onWrite = link.storage::write,
        onReadToFile = { path ->
            pendingRead.value = path
            saveLauncher.launch(path.substringAfterLast('/').ifBlank { "artifact.bin" })
        },
        onWriteFromFile = { path ->
            pendingWrite.value = path
            openLauncher.launch(arrayOf("*/*"))
        },
        onRecoveryBaseline = link.recovery::captureBaseline,
        onRecoveryVerdict = link.recovery::readVerdict,
    )
}

private fun terminalActions(link: AdbLinkController) = TerminalActions(
    onStart = link::startShell,
    onSend = link::sendShellInput,
    onInterrupt = link::interruptShell,
    onStop = link::stopShell,
)

/** Преобразует технический результат claim в короткое UI-сообщение. */
private fun claimAction(
    coordinator: UsbSessionCoordinator,
    failedTemplate: String,
    onFailure: (String) -> Unit,
): (UsbSession) -> Unit = { session ->
    val result = coordinator.claim(session.generation)
    if (result is UsbClaimResult.Failed) {
        onFailure(failedTemplate.format(result.reason.name))
    }
}
