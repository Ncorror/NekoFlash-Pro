package io.github.ncorror.nekoflash.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
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
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
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
    terminal: TerminalPanel = TerminalPanel(),
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

    // Рабочая область одинакова в обеих раскладках и отличается только тем,
    // как занимает место. Список аргументов длинный, и два его экземпляра уже
    // однажды разъехались, поэтому он существует в одном месте.
    val workspace: @Composable (Modifier, WorkspaceDestination) -> Unit = { modifier, shown ->
        Workspace(
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
            miUnlock = miUnlock,
            fastbootConsole = fastbootConsole,
            desktopCommand = desktopCommand,
            onExportDiagnostics = onExportDiagnostics,
            selected = selected,
            onNavigate = { destination = it; terminalFocus = false },
            destination = shown,
            terminalFocus = terminalFocus,
            onToggleTerminalFocus = { terminalFocus = !terminalFocus },
            showTerminalCommands = showTerminalCommands,
            onToggleTerminalCommands = { showTerminalCommands = !showTerminalCommands },
            modifier = modifier,
        )
    }

    BackHandler(enabled = terminalFocus && destination == WorkspaceDestination.TERMINAL) {
        terminalFocus = false
    }
    WorkspaceScaffold(
        destination = destination,
        focused = terminalFocus,
        sessions = sessions,
        selected = selected,
        onSelectSession = selectSession,
        onSelectDestination = { destination = it },
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
            Column {
                NekoFlashTopBar()
                TargetBar(sessions = sessions, selected = selected, onSelect = onSelectSession)
            }
        }
    }) { innerPadding ->
        val baseModifier = Modifier.fillMaxSize().padding(innerPadding)
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
                DestinationToolTabs(current = destination, onSelect = onSelect)
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
    adbCommand: AdbCommandState,
    terminal: TerminalPanel,
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
            adbLink = adbLink, terminal = terminal, actions = terminalActions,
            desktopCommand = desktopCommand, target = selected?.targetId?.value,
            focused = terminalFocus, onToggleFocus = onToggleTerminalFocus,
            showCommands = showTerminalCommands, onToggleCommands = onToggleTerminalCommands,
            modifier = modifier.fillMaxSize(),
        )
        return
    }
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        when (destination) {
            WorkspaceDestination.HOME -> HomeWorkspace(selected, paletteActions, onRescanUsb, onNavigate)
            WorkspaceDestination.DEVICE,
            WorkspaceDestination.ADB,
            WorkspaceDestination.FASTBOOT,
            WorkspaceDestination.RECOVERY -> {
                SectionHeading(
                    text = stringResource(destination.label), style = MaterialTheme.typography.headlineMedium,
                )
                SessionList(
                    selected = selected,
                    destination = destination,
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
 * Терминал как отдельный инструмент.
 *
 * Он переехал из карточки устройства не ради симметрии: оболочка живёт дольше
 * одной команды, и листать до неё через все протокольные секции приходилось
 * каждый раз. Здесь она открывается сразу.
 */
@Composable
private fun TerminalWorkspace(
    adbLink: AdbLinkState,
    terminal: TerminalPanel,
    actions: TerminalActions,
    desktopCommand: DesktopCommandPanel,
    target: String?,
    focused: Boolean,
    onToggleFocus: () -> Unit,
    showCommands: Boolean,
    onToggleCommands: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = target ?: stringResource(R.string.nav_terminal),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(top = 12.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onToggleCommands) {
                Text(stringResource(if (showCommands) R.string.terminal_shell else R.string.terminal_commands))
            }
            TextButton(onClick = onToggleFocus) {
                Text(stringResource(if (focused) R.string.terminal_exit_fullscreen else R.string.terminal_fullscreen))
            }
        }
        if (showCommands) {
            // Команды adb и fastboot не сжимают живой экран оболочки.
            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                DesktopCommandSection(panel = desktopCommand)
            }
        } else {
            TerminalTabsRow(panel = terminal, actions = actions)
            TerminalSection(
                terminal = terminal.current,
                actions = actions,
                connected = adbLink is AdbLinkState.Connected,
                modifier = Modifier.weight(1f),
            )
        }
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
    selected: UsbSession?,
    destination: WorkspaceDestination,
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
    if (selected == null) {
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
    SessionCard(
        session = selected,
        destination = destination,
        adbLink = adbLink,
        adbCommand = adbCommand,
        files = files,
        fileActions = fileActions,
        onClaim = { onClaim(selected) },
        onRelease = { onRelease(selected) },
        onAdbConnect = { onAdbConnect(selected) },
        onAdbDisconnect = { onAdbDisconnect(selected) },
        onRunCommand = onRunCommand,
        reboot = reboot,
        rawService = rawService,
        forward = forward,
        reverse = reverse,
        sideload = sideload,
        fastboot = fastboot,
        fastbootConsole = fastbootConsole,
    )
    if (destination == WorkspaceDestination.DEVICE) {
        Text(stringResource(R.string.mode_requires_handshake), style = MaterialTheme.typography.bodySmall)
    }
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
    destination: WorkspaceDestination,
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
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (destination == WorkspaceDestination.DEVICE) SessionIdentity(session = session)

            if (session.state == UsbSessionState.READY || session.state == UsbSessionState.CLAIMED) {
                when (destination) {
                    WorkspaceDestination.ADB -> if (session.candidate.kind == UsbInterfaceKind.ADB) {
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
                    } else Text(stringResource(R.string.tools_need_adb))
                    WorkspaceDestination.FASTBOOT -> if (session.candidate.kind == UsbInterfaceKind.FASTBOOT) {
                        FastbootLinkSection(fastboot = fastboot)
                        val link = fastboot.state
                        if (link is FastbootLinkState.Connected) {
                            FastbootConsoleSection(
                                console = fastbootConsole,
                                lock = link.lock,
                                onPlan = fastboot.onPlan,
                            )
                        }
                        Text(stringResource(R.string.fastboot_note), style = MaterialTheme.typography.bodySmall)
                    } else Text(stringResource(R.string.tools_need_fastboot))
                    WorkspaceDestination.RECOVERY -> if (session.candidate.kind == UsbInterfaceKind.ADB) {
                        SideloadSection(sideload)
                    } else Text(stringResource(R.string.tools_need_recovery))
                    WorkspaceDestination.DEVICE -> if (session.candidate.kind != UsbInterfaceKind.ADB &&
                        session.candidate.kind != UsbInterfaceKind.FASTBOOT) {
                        Button(onClick = if (claimed) onRelease else onClaim) {
                            Text(stringResource(if (claimed) R.string.usb_release else R.string.usb_claim))
                        }
                        Text(stringResource(R.string.usb_claim_hint), style = MaterialTheme.typography.bodySmall)
                    }
                    else -> Unit
                }
            } else if (destination != WorkspaceDestination.DEVICE) {
                Text(stringResource(R.string.mode_requires_handshake))
            }
        }
    }
}
