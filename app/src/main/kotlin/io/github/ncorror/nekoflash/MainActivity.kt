package io.github.ncorror.nekoflash

import android.content.pm.PackageManager
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import io.github.ncorror.nekoflash.ui.NekoFlashApp
import io.github.ncorror.nekoflash.ui.MiUnlockPanel
import io.github.ncorror.nekoflash.miunlock.MiLoginActivity
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
import io.github.ncorror.nekoflash.ui.FirstRun
import io.github.ncorror.nekoflash.ui.SideloadPanel
import io.github.ncorror.nekoflash.ui.TerminalActions
import io.github.ncorror.nekoflash.ui.TerminalPanel
import io.github.ncorror.nekoflash.ui.WelcomeScreen
import io.github.ncorror.nekoflash.ui.theme.NekoFlashTheme
import io.github.ncorror.nekoflash.usb.api.UsbSessionCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : LocaleAwareActivity() {
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
            var welcomeSeen by remember { mutableStateOf(FirstRun.seen) }
            NekoFlashTheme {
                // Вводный экран показывается один раз и ничего не решает: это
                // «понял, дальше», а не согласие с условиями. Права быть
                // профессионалом он не выдаёт — оно уже есть (`01` §3).
                if (welcomeSeen) {
                    NekoFlashScreen(application, coordinator, adbLink, fastbootLink)
                } else {
                    WelcomeScreen(
                        onContinue = {
                            FirstRun.seen = true
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
        val terminalTabs by adbLink.terminals.collectAsState()
        // Каждая вкладка меняется своим потоком: заголовки и выбранная читаются все.
        val terminalStates = terminalTabs.tabs.map { flow -> key(flow) { flow.collectAsState().value } }
        val fastbootState by fastbootLink.state.collectAsState()
        val fastbootConsole by fastbootLink.console.collectAsState()

        val savedTemplate = stringResource(R.string.diagnostics_export_done)
        val failedTemplate = stringResource(R.string.diagnostics_export_failed)

        // Системный диалог сохранения: файл создаёт пользователь там, где
        // ему нужно, а приложение не заводит собственного хранилища отчётов.
        val saveLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/zip"),
        ) { destination ->
            exportDiagnostics(application, destination, savedTemplate, failedTemplate) { }
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
            scan = coordinator.lastScan.collectAsState().value,
            usbHostSupported = packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST),
            adbLink = linkState,
            terminal = TerminalPanel(terminalStates, terminalTabs.selected),
            terminalActions = terminalActions(adbLink),
            sideload = sideloadPanel(adbLink),
            recentEvents = RecentEvents { application.recentDiagnostics() },
            fastboot = fastbootPanel(fastbootLink, fastbootState, sessions),
            miUnlock = miUnlockPanel(application, fastbootLink, fastbootState),
            fastbootConsole = fastbootConsolePanel(fastbootLink, fastbootConsole, fastbootState, sessions),
            desktopCommand = desktopCommandPanel(adbLink, fastbootLink, coordinator),
            onExportDiagnostics = { saveLauncher.launch(application.suggestedDiagnosticsFileName()) },
        )
    }

    @Composable
    private fun miUnlockPanel(
        application: NekoFlashApplication,
        link: FastbootLinkController,
        fastboot: FastbootLinkState,
    ): MiUnlockPanel {
        val account by application.miAccount.state.collectAsState()
        val operation by link.miUnlock.collectAsState()
        val login = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val token = result.data?.getStringExtra(MiLoginActivity.EXTRA_PASS_TOKEN)
                val user = result.data?.getStringExtra(MiLoginActivity.EXTRA_USER_ID)
                val device = result.data?.getStringExtra(MiLoginActivity.EXTRA_DEVICE_ID)
                if (!token.isNullOrBlank() && !user.isNullOrBlank() && !device.isNullOrBlank()) {
                    application.miAccount.signIn(token, user, device)
                }
            }
        }
        return MiUnlockPanel(
            account = account,
            operation = operation,
            fastboot = fastboot,
            onSignIn = { login.launch(Intent(this, MiLoginActivity::class.java)) },
            onSignOut = {
                application.miAccount.signOut()
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
            },
            onChangeZone = application.miAccount::changeZone,
            onUnlock = { application.miAccount.unlock(link) },
        )
    }
}

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
        onChoose = { chooser.launch(arrayOf("application/zip", "*/*")) },
        onCancel = adbLink.recovery::cancelSideload,
    )
}

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

private fun terminalActions(link: AdbLinkController) = TerminalActions(
    onInterrupt = link.shells::interrupt,
    onResize = link.shells::resize,
    onKey = link.shells::key,
    onType = { text -> link.shells.send(text, enter = false) },
)
