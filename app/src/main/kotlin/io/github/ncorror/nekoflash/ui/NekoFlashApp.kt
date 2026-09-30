package io.github.ncorror.nekoflash.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ncorror.nekoflash.R
import io.github.ncorror.nekoflash.adb.AdbFileState
import io.github.ncorror.nekoflash.adb.AdbLinkState
import io.github.ncorror.nekoflash.usb.api.UsbScanSummary
import io.github.ncorror.nekoflash.fastboot.FastbootLinkState
import io.github.ncorror.nekoflash.usb.api.UsbInterfaceKind
import io.github.ncorror.nekoflash.usb.api.UsbSession
import io.github.ncorror.nekoflash.usb.api.UsbSessionState

@Composable
fun NekoFlashApp(
    sessions: List<UsbSession> = emptyList(),
    scan: UsbScanSummary = UsbScanSummary.NEVER_SCANNED,
    usbHostSupported: Boolean = true,
    exportStatus: String? = null,
    adbLink: AdbLinkState = AdbLinkState.Idle,
    terminal: TerminalPanel = TerminalPanel(),
    files: AdbFileState = AdbFileState.None,
    onRescanUsb: () -> Unit = {},
    onClaim: (UsbSession) -> Unit = {},
    onRelease: (UsbSession) -> Unit = {},
    onAdbConnect: (UsbSession) -> Unit = {},
    onAdbDisconnect: (UsbSession) -> Unit = {},
    reboot: RebootPanel = RebootPanel(),
    rawService: RawServicePanel = RawServicePanel(),
    forward: ForwardPanel = ForwardPanel(),
    reverse: ReversePanel = ReversePanel(),
    sideload: SideloadPanel = SideloadPanel(),
    operations: OperationsPanel = OperationsPanel(),
    paletteActions: List<PaletteAction> = emptyList(),
    recentEvents: RecentEvents = RecentEvents { emptyList() },
    fastboot: FastbootPanel = FastbootPanel(),
    miUnlock: MiUnlockPanel = MiUnlockPanel(),
    fastbootConsole: FastbootConsolePanel = FastbootConsolePanel(),
    terminalActions: TerminalActions = TerminalActions(),
    fileActions: FileActions = FileActions(),
    desktopCommand: DesktopCommandPanel = DesktopCommandPanel(),
    onExportDiagnostics: () -> Unit = {},
) {
    var destination by rememberSaveable { mutableStateOf(WorkspaceDestination.HOME) }
    var terminalFocus by rememberSaveable { mutableStateOf(false) }
    var showTerminalCommands by rememberSaveable { mutableStateOf(false) }
    var chosenGeneration by rememberSaveable { mutableStateOf<Long?>(null) }
    val selected = sessions.firstOrNull { it.generation.value == chosenGeneration } ?: sessions.firstOrNull()
    val selectSession: (UsbSession) -> Unit = { chosenGeneration = it.generation.value }
    val go: (WorkspaceDestination) -> Unit = { destination = it; terminalFocus = it == WorkspaceDestination.TERMINAL }

    // Рабочая область одинакова в обеих раскладках и отличается только тем,
    // как занимает место. Список аргументов длинный, и два его экземпляра уже
    // однажды разъехались, поэтому он существует в одном месте.
    val workspace: @Composable (Modifier, WorkspaceDestination) -> Unit = { modifier, shown ->
        Workspace(
            scan = scan,
            usbHostSupported = usbHostSupported,
            exportStatus = exportStatus,
            adbLink = adbLink,
            terminal = terminal,
            terminalActions = terminalActions,
            files = files,
            fileActions = fileActions,
            onRescanUsb = onRescanUsb,
            onClaim = onClaim,
            onRelease = onRelease,
            onAdbConnect = onAdbConnect,
            onAdbDisconnect = onAdbDisconnect,
            reboot = reboot,
            rawService = rawService,
            forward = forward,
            reverse = reverse,
            sideload = sideload,
            operations = operations,
            paletteActions = paletteActions,
            recentEvents = recentEvents,
            fastboot = fastboot,
            miUnlock = miUnlock,
            fastbootConsole = fastbootConsole,
            desktopCommand = desktopCommand,
            onExportDiagnostics = onExportDiagnostics,
            selected = selected,
            onNavigate = go,
            destination = shown,
            terminalFocus = terminalFocus,
            onToggleTerminalFocus = { terminalFocus = !terminalFocus },
            showTerminalCommands = showTerminalCommands,
            onToggleTerminalCommands = { showTerminalCommands = !showTerminalCommands },
            modifier = modifier,
        )
    }

    BackHandler(enabled = terminalFocus && destination == WorkspaceDestination.TERMINAL) { terminalFocus = false }
    WorkspaceScaffold(
        destination = destination,
        focused = terminalFocus,
        sessions = sessions,
        selected = selected,
        onSelectSession = selectSession,
        onSelectDestination = go,
        workspace = workspace,
    )
}

@Composable
private fun WorkspaceScaffold(
    destination: WorkspaceDestination,
    focused: Boolean,
    sessions: List<UsbSession>,
    selected: UsbSession?,
    onSelectSession: (UsbSession) -> Unit,
    onSelectDestination: (WorkspaceDestination) -> Unit,
    workspace: @Composable (Modifier, WorkspaceDestination) -> Unit,
) {
    // Выбранный раздел живёт здесь, а не в контроллере: это состояние взгляда,
    // а не состояние устройства. Поворот экрана его сохраняет, перезапуск — нет,
    // и это правильно: после перезапуска оператор смотрит на устройство.
    Scaffold(topBar = {
        if (!focused || destination != WorkspaceDestination.TERMINAL) {
            TargetBar(sessions = sessions, selected = selected, onSelect = onSelectSession)
        }
    }) { innerPadding ->
        // Отступ, уже отданный панелям, клавиатуре не повторяется: иначе
        // между рядом клавиш терминала и клавиатурой оставалась пустая полоса.
        val baseModifier = Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding)
        val contentModifier = if (destination == WorkspaceDestination.TERMINAL) {
            baseModifier.imePadding()
        } else {
            baseModifier
        }
        if (focused && destination == WorkspaceDestination.TERMINAL) {
            workspace(contentModifier, destination)
        } else {
            WorkspaceLayout(
                destination = destination,
                onSelect = onSelectDestination,
                modifier = contentModifier,
                workspace = workspace,
            )
        }
    }
}

/**
 * Раскладка: боковая навигация на широком экране и нижняя на телефоне.
 *
 * От 600 dp используем прямой список разделов на широком экране.
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
                DestinationToolPicker(current = destination, onSelect = onSelect)
                workspace(Modifier.weight(1f), destination)
                DestinationBottomBar(current = destination, onSelect = onSelect)
            }
        }
    }
}

/** Ширина для боковой навигации в альбомной ориентации и на планшете. */
private val WIDE_SCREEN = 600.dp

private val RAIL_WIDTH = 196.dp

@Composable
private fun Workspace(
    scan: UsbScanSummary,
    usbHostSupported: Boolean,
    exportStatus: String?,
    adbLink: AdbLinkState,
    terminal: TerminalPanel,
    terminalActions: TerminalActions,
    files: AdbFileState,
    fileActions: FileActions,
    onRescanUsb: () -> Unit,
    onClaim: (UsbSession) -> Unit,
    onRelease: (UsbSession) -> Unit,
    onAdbConnect: (UsbSession) -> Unit,
    onAdbDisconnect: (UsbSession) -> Unit,
    reboot: RebootPanel,
    rawService: RawServicePanel,
    forward: ForwardPanel,
    reverse: ReversePanel,
    sideload: SideloadPanel,
    operations: OperationsPanel,
    paletteActions: List<PaletteAction>,
    recentEvents: RecentEvents,
    fastboot: FastbootPanel,
    miUnlock: MiUnlockPanel,
    fastbootConsole: FastbootConsolePanel,
    desktopCommand: DesktopCommandPanel,
    onExportDiagnostics: () -> Unit,
    selected: UsbSession?,
    onNavigate: (WorkspaceDestination) -> Unit,
    destination: WorkspaceDestination,
    terminalFocus: Boolean,
    onToggleTerminalFocus: () -> Unit,
    showTerminalCommands: Boolean,
    onToggleTerminalCommands: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (destination == WorkspaceDestination.TERMINAL) {
        TerminalWorkspace(
            adbLink = adbLink, terminal = terminal, actions = terminalActions, desktopCommand = desktopCommand,
            focused = terminalFocus, onToggleFocus = onToggleTerminalFocus,
            showCommands = showTerminalCommands, onToggleCommands = onToggleTerminalCommands,
            modifier = modifier.fillMaxSize(),
        )
        return
    }
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (destination) {
            WorkspaceDestination.HOME -> HomeWorkspace(selected, paletteActions, onRescanUsb, onNavigate)
            WorkspaceDestination.DEVICE,
            WorkspaceDestination.ADB,
            WorkspaceDestination.FASTBOOT -> SessionList(
                selected = selected, destination = destination, scan = scan,
                usbHostSupported = usbHostSupported, adbLink = adbLink, files = files, fileActions = fileActions,
                onClaim = onClaim, onRelease = onRelease, onAdbConnect = onAdbConnect,
                onAdbDisconnect = onAdbDisconnect, reboot = reboot, rawService = rawService,
                forward = forward, reverse = reverse, fastboot = fastboot, fastbootConsole = fastbootConsole,
            )
            WorkspaceDestination.RECOVERY -> RecoveryWorkspace(selected, adbLink, sideload, files, fileActions)
            WorkspaceDestination.TERMINAL -> Unit
            WorkspaceDestination.MI_UNLOCK -> MiUnlockSection(miUnlock)
            WorkspaceDestination.OPERATIONS -> OperationsSection(panel = operations)
            WorkspaceDestination.DIAGNOSTICS -> DiagnosticsWorkspace(
                exportStatus = exportStatus,
                recentEvents = recentEvents,
                onRescanUsb = onRescanUsb,
                onExportDiagnostics = onExportDiagnostics,
            )
        }
    }
}

/**
 * Recovery — всё, что относится к установке пакета, подряд: база журнала до
 * установки, сама передача, вердикт после. Раньше передача была здесь и ещё
 * раз во вкладке ADB, а база с вердиктом — среди файлов.
 */
@Composable
private fun RecoveryWorkspace(
    selected: UsbSession?,
    adbLink: AdbLinkState,
    sideload: SideloadPanel,
    files: AdbFileState,
    fileActions: FileActions,
) {
    NoteText(
        when {
            selected == null -> stringResource(R.string.sideload_device_hint)
            selected.candidate.kind != UsbInterfaceKind.ADB -> stringResource(R.string.tools_need_recovery)
            else -> stringResource(R.string.sideload_mode_hint)
        },
    )
    ToolCard(title = stringResource(R.string.sideload_card_title)) { SideloadSection(panel = sideload) }
    ToolCard(title = stringResource(R.string.recovery_log_title)) {
        RecoveryLogSection(files = files, actions = fileActions, enabled = adbLink is AdbLinkState.Connected)
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
    ToolCard(title = stringResource(R.string.nav_diagnostics)) {
        ActionRow {
            Button(onClick = onExportDiagnostics) { Text(stringResource(R.string.diagnostics_export)) }
            OutlinedButton(onClick = onRescanUsb) { Text(stringResource(R.string.usb_rescan)) }
        }
        exportStatus?.let { StatusLine(it) }
        NoteText(stringResource(R.string.usb_rescan_hint))
    }
    DiagnosticsPane(source = recentEvents)
    ToolCard(title = stringResource(R.string.build_baseline_title)) {
        Text(stringResource(R.string.build_baseline_values))
        NoteText(stringResource(R.string.professional_capability_policy))
    }
}

@Composable
private fun SessionList(
    selected: UsbSession?,
    destination: WorkspaceDestination,
    scan: UsbScanSummary,
    usbHostSupported: Boolean,
    adbLink: AdbLinkState,
    files: AdbFileState,
    fileActions: FileActions,
    onClaim: (UsbSession) -> Unit,
    onRelease: (UsbSession) -> Unit,
    onAdbConnect: (UsbSession) -> Unit,
    onAdbDisconnect: (UsbSession) -> Unit,
    reboot: RebootPanel,
    rawService: RawServicePanel,
    forward: ForwardPanel,
    reverse: ReversePanel,
    fastboot: FastbootPanel,
    fastbootConsole: FastbootConsolePanel,
) {
    if (selected == null) {
        ToolCard(title = stringResource(R.string.sessions_empty)) {
            // Пустой список сам по себе ничего не объясняет. Разбор 2026-09-04
            // показал цену этого молчания: по выгруженному evidence нельзя было
            // сказать, видит ли система устройство вообще.
            NoteText(emptyStateReason(scan, usbHostSupported))
        }
        return
    }
    val usable = selected.state == UsbSessionState.READY || selected.state == UsbSessionState.CLAIMED
    when {
        destination == WorkspaceDestination.DEVICE -> DeviceCard(
            session = selected,
            onClaim = { onClaim(selected) },
            onRelease = { onRelease(selected) },
        )
        !usable -> NoteText(stringResource(R.string.mode_requires_handshake))
        destination == WorkspaceDestination.ADB -> if (selected.candidate.kind == UsbInterfaceKind.ADB) {
            AdbLinkSection(
                session = selected,
                adbLink = adbLink,
                files = files,
                fileActions = fileActions,
                onAdbConnect = { onAdbConnect(selected) },
                onAdbDisconnect = { onAdbDisconnect(selected) },
                reboot = reboot,
                rawService = rawService,
                forward = forward,
                reverse = reverse,
            )
        } else {
            NoteText(stringResource(R.string.tools_need_adb))
        }
        destination == WorkspaceDestination.FASTBOOT -> if (selected.candidate.kind == UsbInterfaceKind.FASTBOOT) {
            FastbootWorkspace(fastboot = fastboot, fastbootConsole = fastbootConsole)
        } else {
            NoteText(stringResource(R.string.tools_need_fastboot))
        }
        else -> Unit
    }
}

/** Fastboot: связь с ролью устройства — и действия, когда связь есть. */
@Composable
private fun FastbootWorkspace(fastboot: FastbootPanel, fastbootConsole: FastbootConsolePanel) {
    ToolCard(title = stringResource(R.string.fastboot_card_connection)) {
        FastbootLinkSection(fastboot = fastboot)
        NoteText(stringResource(R.string.fastboot_note))
    }
    val link = fastboot.state
    if (link is FastbootLinkState.Connected) {
        ToolCard(title = stringResource(R.string.fastboot_card_actions)) {
            FastbootConsoleSection(console = fastbootConsole, lock = link.lock, onPlan = fastboot.onPlan)
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

/**
 * Устройство: кто оно и, если это не ADB и не Fastboot, — захват интерфейса.
 *
 * Удерживается ли интерфейс, видно по самому состоянию сессии. Отдельный
 * список захваченных был бы вторым источником истины о том же самом.
 */
@Composable
private fun DeviceCard(session: UsbSession, onClaim: () -> Unit, onRelease: () -> Unit) {
    val claimed = session.state == UsbSessionState.CLAIMED
    val usable = session.state == UsbSessionState.READY || claimed
    ToolCard(title = stringResource(R.string.nav_device)) {
        SessionIdentity(session = session)
        val kind = session.candidate.kind
        if (usable && kind != UsbInterfaceKind.ADB && kind != UsbInterfaceKind.FASTBOOT) {
            Button(onClick = if (claimed) onRelease else onClaim) {
                Text(stringResource(if (claimed) R.string.usb_release else R.string.usb_claim))
            }
            NoteText(stringResource(R.string.usb_claim_hint))
        }
        NoteText(stringResource(R.string.mode_requires_handshake))
    }
}
