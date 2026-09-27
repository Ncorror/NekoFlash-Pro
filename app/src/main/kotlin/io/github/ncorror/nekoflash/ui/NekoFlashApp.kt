package io.github.ncorror.nekoflash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ncorror.nekoflash.R
import io.github.ncorror.nekoflash.adb.AdbCommandState
import io.github.ncorror.nekoflash.adb.AdbFileState
import io.github.ncorror.nekoflash.adb.AdbTerminalState
import io.github.ncorror.nekoflash.adb.AdbLinkState
import io.github.ncorror.nekoflash.usb.api.UsbScanSummary
import io.github.ncorror.nekoflash.fastboot.FastbootLinkState
import io.github.ncorror.nekoflash.usb.api.UsbInterfaceKind
import io.github.ncorror.nekoflash.usb.api.UsbSession
import io.github.ncorror.nekoflash.usb.api.UsbSessionState

@OptIn(ExperimentalMaterial3Api::class)
/** Название и подзаголовок приложения: своя вещь, и в теле экрана ей тесно. */
@Composable
private fun NekoFlashTopBar() {
    TopAppBar(
        title = {
            Column {
                Text(stringResource(R.string.app_name))
                Text(
                    text = stringResource(R.string.app_tagline),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        },
    )
}

@Composable
fun NekoFlashApp(
    sessions: List<UsbSession> = emptyList(),
    scan: UsbScanSummary = UsbScanSummary.NEVER_SCANNED,
    usbHostSupported: Boolean = true,
    exportStatus: String? = null,
    adbLink: AdbLinkState = AdbLinkState.Idle,
    adbCommand: AdbCommandState = AdbCommandState.None,
    terminal: AdbTerminalState = AdbTerminalState(),
    files: AdbFileState = AdbFileState.None,
    onRescanUsb: () -> Unit = {},
    onClaim: (UsbSession) -> Unit = {},
    onRelease: (UsbSession) -> Unit = {},
    onAdbConnect: (UsbSession) -> Unit = {},
    onAdbDisconnect: (UsbSession) -> Unit = {},
    onRunCommand: (String) -> Unit = {},
    reboot: RebootPanel = RebootPanel(),
    rawService: RawServicePanel = RawServicePanel(),
    forward: ForwardPanel = ForwardPanel(),
    reverse: ReversePanel = ReversePanel(),
    sideload: SideloadPanel = SideloadPanel(),
    operations: OperationsPanel = OperationsPanel(),
    paletteActions: List<PaletteAction> = emptyList(),
    recentEvents: RecentEvents = RecentEvents { emptyList() },
    fastboot: FastbootPanel = FastbootPanel(),
    fastbootConsole: FastbootConsolePanel = FastbootConsolePanel(),
    terminalActions: TerminalActions = TerminalActions(),
    fileActions: FileActions = FileActions(),
    onExportDiagnostics: () -> Unit = {},
) {
    // Рабочая область одинакова в обеих раскладках и отличается только тем,
    // как занимает место. Список аргументов длинный, и два его экземпляра уже
    // однажды разъехались, поэтому он существует в одном месте.
    val workspace: @Composable (Modifier, WorkspaceDestination) -> Unit = { modifier, shown ->
        Workspace(
            sessions = sessions,
            scan = scan,
            usbHostSupported = usbHostSupported,
            exportStatus = exportStatus,
            adbLink = adbLink,
            adbCommand = adbCommand,
            terminal = terminal,
            terminalActions = terminalActions,
            files = files,
            fileActions = fileActions,
            onRescanUsb = onRescanUsb,
            onClaim = onClaim,
            onRelease = onRelease,
            onAdbConnect = onAdbConnect,
            onAdbDisconnect = onAdbDisconnect,
            onRunCommand = onRunCommand,
            reboot = reboot,
            rawService = rawService,
            forward = forward,
            reverse = reverse,
            sideload = sideload,
            operations = operations,
            paletteActions = paletteActions,
            recentEvents = recentEvents,
            fastboot = fastboot,
            fastbootConsole = fastbootConsole,
            onExportDiagnostics = onExportDiagnostics,
            destination = shown,
            modifier = modifier,
        )
    }

    // Выбранный раздел живёт здесь, а не в контроллере: это состояние взгляда,
    // а не состояние устройства. Поворот экрана его сохраняет, перезапуск — нет,
    // и это правильно: после перезапуска оператор смотрит на устройство.
    var destination by rememberSaveable { mutableStateOf(WorkspaceDestination.DEVICE) }

    Scaffold(topBar = { NekoFlashTopBar() }) { innerPadding ->
        WorkspaceLayout(
            destination = destination,
            onSelect = { chosen -> destination = chosen },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            workspace = workspace,
        )
    }
}

/**
 * Раскладка: навигация сбоку на широком экране и сверху на узком.
 *
 * Порог один и тот же для обоих: на 840 dp вертикальный список перестаёт
 * отъедать заметную долю ширины, а до него он съедал бы её у самой работы.
 */
@Composable
private fun WorkspaceLayout(
    destination: WorkspaceDestination,
    onSelect: (WorkspaceDestination) -> Unit,
    modifier: Modifier,
    workspace: @Composable (Modifier, WorkspaceDestination) -> Unit,
) {
    BoxWithConstraints(modifier = modifier) {
        if (maxWidth >= WIDE_SCREEN) {
            Row(modifier = Modifier.fillMaxSize()) {
                DestinationRail(
                    current = destination,
                    onSelect = onSelect,
                    modifier = Modifier
                        .width(RAIL_WIDTH)
                        .fillMaxSize(),
                )
                workspace(Modifier.weight(1f), destination)
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                DestinationTabs(current = destination, onSelect = onSelect)
                workspace(Modifier.weight(1f), destination)
            }
        }
    }
}

/** С этой ширины навигация переезжает вбок: сверху она отъедала бы высоту у работы. */
private val WIDE_SCREEN = 840.dp

private val RAIL_WIDTH = 240.dp

@Composable
private fun Workspace(
    sessions: List<UsbSession>,
    scan: UsbScanSummary,
    usbHostSupported: Boolean,
    exportStatus: String?,
    adbLink: AdbLinkState,
    adbCommand: AdbCommandState,
    terminal: AdbTerminalState,
    terminalActions: TerminalActions,
    files: AdbFileState,
    fileActions: FileActions,
    onRescanUsb: () -> Unit,
    onClaim: (UsbSession) -> Unit,
    onRelease: (UsbSession) -> Unit,
    onAdbConnect: (UsbSession) -> Unit,
    onAdbDisconnect: (UsbSession) -> Unit,
    onRunCommand: (String) -> Unit,
    reboot: RebootPanel,
    rawService: RawServicePanel,
    forward: ForwardPanel,
    reverse: ReversePanel,
    sideload: SideloadPanel,
    operations: OperationsPanel,
    paletteActions: List<PaletteAction>,
    recentEvents: RecentEvents,
    fastboot: FastbootPanel,
    fastbootConsole: FastbootConsolePanel,
    onExportDiagnostics: () -> Unit,
    destination: WorkspaceDestination,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        when (destination) {
            WorkspaceDestination.DEVICE -> {
                SectionHeading(
                    text = stringResource(R.string.sessions_title),
                    style = MaterialTheme.typography.headlineMedium,
                )
                // Палитра стоит над списком, а не под ним: она существует
                // ровно затем, чтобы до знакомого действия не листать.
                CommandPalette(actions = paletteActions)
            }

            WorkspaceDestination.TERMINAL -> TerminalWorkspace(adbLink, terminal, terminalActions)
            WorkspaceDestination.OPERATIONS -> OperationsSection(panel = operations)
            WorkspaceDestination.DIAGNOSTICS -> DiagnosticsWorkspace(
                exportStatus = exportStatus,
                recentEvents = recentEvents,
                onRescanUsb = onRescanUsb,
                onExportDiagnostics = onExportDiagnostics,
            )
        }
        if (destination != WorkspaceDestination.DEVICE) return@Column

        SessionList(
            sessions = sessions,
            scan = scan,
            usbHostSupported = usbHostSupported,
            adbLink = adbLink,
            adbCommand = adbCommand,
            files = files,
            fileActions = fileActions,
            onClaim = onClaim,
            onRelease = onRelease,
            onAdbConnect = onAdbConnect,
            onAdbDisconnect = onAdbDisconnect,
            onRunCommand = onRunCommand,
            reboot = reboot,
            rawService = rawService,
            forward = forward,
            reverse = reverse,
            sideload = sideload,
            fastboot = fastboot,
            fastbootConsole = fastbootConsole,
        )
    }
}

/**
 * Терминал отдельным разделом.
 *
 * Он переехал из карточки устройства не ради симметрии: оболочка живёт дольше
 * одной команды, и листать до неё через все протокольные секции приходилось
 * каждый раз. Здесь она открывается сразу.
 */
@Composable
private fun TerminalWorkspace(
    adbLink: AdbLinkState,
    terminal: AdbTerminalState,
    actions: TerminalActions,
) {
    SectionHeading(
        text = stringResource(R.string.nav_terminal),
        style = MaterialTheme.typography.headlineMedium,
    )
    if (adbLink is AdbLinkState.Connected) {
        TerminalSection(terminal = terminal, actions = actions)
    } else {
        // Не «кнопка погасла», а сказано, чего не хватает: пустой экран без
        // объяснения читается как поломка.
        Text(
            text = stringResource(R.string.terminal_needs_connection),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** Диагностика и то, что к ней относится: пересканировать, выгрузить, сверить сборку. */
@Composable
private fun DiagnosticsWorkspace(
    exportStatus: String?,
    recentEvents: RecentEvents,
    onRescanUsb: () -> Unit,
    onExportDiagnostics: () -> Unit,
) {
    SectionHeading(
        text = stringResource(R.string.nav_diagnostics),
        style = MaterialTheme.typography.headlineMedium,
    )
    ActionsCard(
        exportStatus = exportStatus,
        onRescanUsb = onRescanUsb,
        onExportDiagnostics = onExportDiagnostics,
    )
    DiagnosticsPane(source = recentEvents)
    BuildBaselineCard()
}

@Composable
private fun SessionList(
    sessions: List<UsbSession>,
    scan: UsbScanSummary,
    usbHostSupported: Boolean,
    adbLink: AdbLinkState,
    adbCommand: AdbCommandState,
    files: AdbFileState,
    fileActions: FileActions,
    onClaim: (UsbSession) -> Unit,
    onRelease: (UsbSession) -> Unit,
    onAdbConnect: (UsbSession) -> Unit,
    onAdbDisconnect: (UsbSession) -> Unit,
    onRunCommand: (String) -> Unit,
    reboot: RebootPanel,
    rawService: RawServicePanel,
    forward: ForwardPanel,
    reverse: ReversePanel,
    sideload: SideloadPanel,
    fastboot: FastbootPanel,
    fastbootConsole: FastbootConsolePanel,
) {
    if (sessions.isEmpty()) {
        Text(
            text = stringResource(R.string.sessions_empty),
            style = MaterialTheme.typography.bodyLarge,
        )
        // Пустой список сам по себе ничего не объясняет. Разбор 2026-09-04
        // показал цену этого молчания: по выгруженному evidence нельзя было
        // сказать, видит ли система устройство вообще.
        Text(
            text = emptyStateReason(scan, usbHostSupported),
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }
    // Рабочее место принадлежит одному устройству: соединение ADB одно, полоса
    // Fastboot одна, и операция ведётся с одной целью. Показывать рядом то, что
    // относится к разным целям, значило бы заставлять соотносить их глазами.
    val chosen = rememberSaveable { mutableStateOf(sessions.first().generation.value) }
    // Выбранное устройство могли отключить: тогда берём то, что есть, а не
    // показываем пустоту на месте существующей цели.
    val session = sessions.firstOrNull { it.generation.value == chosen.value } ?: sessions.first()

    TargetBar(
        sessions = sessions,
        selected = session,
        onSelect = { other -> chosen.value = other.generation.value },
    )
    SessionCard(
        session = session,
        adbLink = adbLink,
        adbCommand = adbCommand,
        files = files,
        fileActions = fileActions,
        onClaim = { onClaim(session) },
        onRelease = { onRelease(session) },
        onAdbConnect = { onAdbConnect(session) },
        onAdbDisconnect = { onAdbDisconnect(session) },
        onRunCommand = onRunCommand,
        reboot = reboot,
        rawService = rawService,
        forward = forward,
        reverse = reverse,
        sideload = sideload,
        fastboot = fastboot,
        fastbootConsole = fastbootConsole,
    )
    Text(
        text = stringResource(R.string.mode_requires_handshake),
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun ActionsCard(
    exportStatus: String?,
    onRescanUsb: () -> Unit,
    onExportDiagnostics: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(onClick = onRescanUsb) {
                Text(stringResource(R.string.usb_rescan))
            }
            Text(
                text = stringResource(R.string.usb_rescan_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onExportDiagnostics) {
                Text(stringResource(R.string.diagnostics_export))
            }
            if (exportStatus != null) {
                Text(text = exportStatus, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun BuildBaselineCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.build_baseline_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(stringResource(R.string.build_baseline_values))
            Text(stringResource(R.string.professional_capability_policy))
        }
    }
}

/**
 * Пять строк, которыми сессия себя называет.
 *
 * Вынесены из карточки не ради длины, а потому что это одна вещь: кто перед
 * нами, чем опознан, в какой роли, в каком состоянии и в каком поколении.
 * Читать их порознь незачем.
 */
@Composable
private fun SessionIdentity(session: UsbSession) {
    LabelledValue(
        label = stringResource(R.string.target_label),
        value = session.targetId.value,
    )
    LabelledValue(
        label = stringResource(R.string.session_identity_label),
        value = localizedIdentitySource(session.identity.source),
    )
    LabelledValue(
        label = stringResource(R.string.session_interface_label),
        value = localizedInterfaceKind(session.candidate.kind) + " · " +
            localizedMatchConfidence(session.candidate.confidence),
    )
    LabelledValue(
        label = stringResource(R.string.session_state_label),
        value = localizedSessionState(session.state),
    )
    LabelledValue(
        label = stringResource(R.string.session_generation_label),
        value = session.generation.value.toString(),
    )
}

@Composable
private fun SessionCard(
    session: UsbSession,
    adbLink: AdbLinkState,
    adbCommand: AdbCommandState,
    files: AdbFileState,
    fileActions: FileActions,
    onClaim: () -> Unit,
    onRelease: () -> Unit,
    onAdbConnect: () -> Unit,
    onAdbDisconnect: () -> Unit,
    onRunCommand: (String) -> Unit,
    reboot: RebootPanel,
    rawService: RawServicePanel,
    forward: ForwardPanel,
    reverse: ReversePanel,
    sideload: SideloadPanel,
    fastboot: FastbootPanel,
    fastbootConsole: FastbootConsolePanel,
) {
    // Удерживается ли интерфейс, видно по самому состоянию сессии. Отдельный
    // список захваченных был бы вторым источником истины о том же самом.
    val claimed = session.state == UsbSessionState.CLAIMED
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SessionIdentity(session = session)

            if (session.state == UsbSessionState.READY || session.state == UsbSessionState.CLAIMED) {
                // У интерфейса ADB владение одно: подключение захватывает
                // интерфейс, отключение отпускает. Отдельная кнопка захвата
                // была вторым органом управления тем же ресурсом, и прогон
                // 2026-09-03 показал, к чему это приводит: интерфейс отпущен,
                // а экран продолжает утверждать, что ADB подключён.
                // Захват без протокольного обмена ничего не даёт, поэтому
                // терять здесь нечего.
                if (session.candidate.kind == UsbInterfaceKind.ADB) {
                    AdbLinkSection(
                        session = session,
                        adbLink = adbLink,
                        adbCommand = adbCommand,
                        files = files,
                        fileActions = fileActions,
                        onAdbConnect = onAdbConnect,
                        onAdbDisconnect = onAdbDisconnect,
                        onRunCommand = onRunCommand,
                        reboot = reboot,
                        rawService = rawService,
                        forward = forward,
                        reverse = reverse,
                        sideload = sideload,
                    )
                } else if (session.candidate.kind == UsbInterfaceKind.FASTBOOT) {
                    FastbootLinkSection(fastboot = fastboot)
                    if (fastboot.state is FastbootLinkState.Connected) {
                        FastbootConsoleSection(console = fastbootConsole)
                    }
                    Text(
                        text = stringResource(R.string.fastboot_note),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Button(onClick = if (claimed) onRelease else onClaim) {
                        Text(
                            stringResource(
                                if (claimed) R.string.usb_release else R.string.usb_claim,
                            ),
                        )
                    }
                    Text(
                        text = stringResource(R.string.usb_claim_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
