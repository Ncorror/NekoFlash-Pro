package io.github.ncorror.nekoflash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ncorror.nekoflash.R
import io.github.ncorror.nekoflash.adb.AdbLinkState
import io.github.ncorror.nekoflash.fastboot.FastbootLinkState
import io.github.ncorror.nekoflash.usb.api.UsbInterfaceKind
import io.github.ncorror.nekoflash.usb.api.UsbScanSummary
import io.github.ncorror.nekoflash.usb.api.UsbSession
import io.github.ncorror.nekoflash.usb.api.UsbSessionState
import kotlinx.coroutines.launch

/**
 * Production shell: Terminal first, every secondary tool in a navigation drawer.
 *
 * The compact top bar contains OTG state and the selected target. There is
 * deliberately no bottom bar, home dashboard, mock panel or duplicate
 * ADB/Fastboot navigation.
 */
@Suppress("LongParameterList")
@Composable
fun NekoFlashApp(
    sessions: List<UsbSession>,
    selected: UsbSession?,
    onSelectTarget: (UsbSession) -> Unit,
    scan: UsbScanSummary,
    usbHostSupported: Boolean,
    adbLink: AdbLinkState,
    terminal: TerminalPanel,
    sideload: SideloadPanel,
    recentEvents: RecentEvents,
    fastboot: FastbootPanel,
    miUnlock: MiUnlockPanel,
    fastbootConsole: FastbootConsolePanel,
    terminalActions: TerminalActions,
    desktopCommand: DesktopCommandPanel,
    onExportDiagnostics: () -> Unit,
) {
    var destination by rememberSaveable { mutableStateOf(SidebarDestination.TERMINAL) }
    AutoConnectFastboot(selected, fastboot)
    NekoFlashDrawer(
        destination = destination,
        onSelect = { destination = it },
    ) {
        NekoFlashBody(
            destination = destination,
            sessions = sessions,
            selected = selected,
            onSelectTarget = onSelectTarget,
            scan = scan,
            usbHostSupported = usbHostSupported,
            adbLink = adbLink,
            terminal = terminal,
            sideload = sideload,
            recentEvents = recentEvents,
            fastboot = fastboot,
            miUnlock = miUnlock,
            fastbootConsole = fastbootConsole,
            terminalActions = terminalActions,
            desktopCommand = desktopCommand,
            onExportDiagnostics = onExportDiagnostics,
        )
    }
}

@Composable
private fun NekoFlashDrawer(
    destination: SidebarDestination,
    onSelect: (SidebarDestination) -> Unit,
    content: @Composable () -> Unit,
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            ModalDrawerSheet {
                SidebarDestination.entries.forEach { item ->
                    NavigationDrawerItem(
                        label = { Text(stringResource(item.label)) },
                        selected = destination == item,
                        onClick = {
                            onSelect(item)
                            scope.launch { drawerState.close() }
                        },
                    )
                }
            }
        },
        content = content,
    )
}

@Suppress("LongParameterList")
@Composable
private fun NekoFlashBody(
    destination: SidebarDestination,
    sessions: List<UsbSession>,
    selected: UsbSession?,
    onSelectTarget: (UsbSession) -> Unit,
    scan: UsbScanSummary,
    usbHostSupported: Boolean,
    adbLink: AdbLinkState,
    terminal: TerminalPanel,
    sideload: SideloadPanel,
    recentEvents: RecentEvents,
    fastboot: FastbootPanel,
    miUnlock: MiUnlockPanel,
    fastbootConsole: FastbootConsolePanel,
    terminalActions: TerminalActions,
    desktopCommand: DesktopCommandPanel,
    onExportDiagnostics: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        ConnectionStatusBar(
            adb = adbLink,
            fastboot = fastboot.state,
            sessions = sessions,
            selected = selected,
            onSelectTarget = onSelectTarget,
        )
        DestinationContent(
            destination = destination,
            selected = selected,
            scan = scan,
            usbHostSupported = usbHostSupported,
            adbLink = adbLink,
            terminal = terminal,
            sideload = sideload,
            recentEvents = recentEvents,
            fastboot = fastboot,
            miUnlock = miUnlock,
            fastbootConsole = fastbootConsole,
            terminalActions = terminalActions,
            desktopCommand = desktopCommand,
            onExportDiagnostics = onExportDiagnostics,
        )
    }
}

@Suppress("LongParameterList")
@Composable
private fun DestinationContent(
    destination: SidebarDestination,
    selected: UsbSession?,
    scan: UsbScanSummary,
    usbHostSupported: Boolean,
    adbLink: AdbLinkState,
    terminal: TerminalPanel,
    sideload: SideloadPanel,
    recentEvents: RecentEvents,
    fastboot: FastbootPanel,
    miUnlock: MiUnlockPanel,
    fastbootConsole: FastbootConsolePanel,
    terminalActions: TerminalActions,
    desktopCommand: DesktopCommandPanel,
    onExportDiagnostics: () -> Unit,
) {
    when (destination) {
        SidebarDestination.TERMINAL -> TerminalWorkspace(
            adbLink = adbLink,
            fastbootLink = fastboot.state,
            terminal = terminal,
            actions = terminalActions,
            desktopCommand = desktopCommand,
            modifier = Modifier.fillMaxSize(),
        )
        SidebarDestination.FAST_FLASHING -> ScrollWorkspace {
            FastFlashingWorkspace(selected, scan, usbHostSupported, fastboot, fastbootConsole)
        }
        SidebarDestination.SIDELOAD -> ScrollWorkspace {
            SideloadWorkspace(selected, adbLink, sideload)
        }
        SidebarDestination.MI_UNLOCK -> ScrollWorkspace { MiUnlockSection(miUnlock) }
        SidebarDestination.LOGS -> RawLogsWorkspace(
            source = recentEvents,
            onExportCleaned = onExportDiagnostics,
            modifier = Modifier.fillMaxSize(),
        )
        SidebarDestination.SETTINGS -> SettingsWorkspace(modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun AutoConnectFastboot(selected: UsbSession?, fastboot: FastbootPanel) {
    val candidate = selected?.takeIf { session ->
        !session.closed &&
            session.state == UsbSessionState.READY &&
            session.candidate.kind == UsbInterfaceKind.FASTBOOT
    }
    val generation = candidate?.generation?.value
    var handledGeneration by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(generation, fastboot.state) {
        if (generation != null && handledGeneration != generation && fastboot.state is FastbootLinkState.Idle) {
            handledGeneration = generation
            fastboot.onProbe()
        }
    }
}

@Composable
private fun ScrollWorkspace(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = WORKSPACE_PADDING, vertical = WORKSPACE_PADDING),
        verticalArrangement = Arrangement.spacedBy(WORKSPACE_GAP),
    ) {
        content()
    }
}

@Composable
private fun FastFlashingWorkspace(
    selected: UsbSession?,
    scan: UsbScanSummary,
    usbHostSupported: Boolean,
    fastboot: FastbootPanel,
    console: FastbootConsolePanel,
) {
    if (selected == null) {
        NoteText(emptyStateReason(scan, usbHostSupported))
        return
    }
    val usable = selected.state == UsbSessionState.READY || selected.state == UsbSessionState.CLAIMED
    when {
        !usable -> NoteText(stringResource(R.string.mode_requires_handshake))
        selected.candidate.kind != UsbInterfaceKind.FASTBOOT -> NoteText(stringResource(R.string.tools_need_fastboot))
        else -> FastbootWorkspace(fastboot, console)
    }
}

@Composable
private fun FastbootWorkspace(fastboot: FastbootPanel, console: FastbootConsolePanel) {
    ToolCard(title = stringResource(R.string.fastboot_card_connection)) {
        FastbootLinkSection(fastboot = fastboot)
    }
    val link = fastboot.state
    if (link is FastbootLinkState.Connected) {
        ToolCard(title = stringResource(R.string.fast_flash_card_title)) {
            FastbootQuickFlashSection(console = console, lock = link.lock)
        }
    }
}

@Composable
private fun SideloadWorkspace(
    selected: UsbSession?,
    adbLink: AdbLinkState,
    panel: SideloadPanel,
) {
    when {
        selected == null -> NoteText(stringResource(R.string.sideload_device_hint))
        selected.candidate.kind != UsbInterfaceKind.ADB -> NoteText(stringResource(R.string.tools_need_recovery))
        adbLink !is AdbLinkState.Connected -> NoteText(stringResource(R.string.sideload_device_hint))
        else -> ToolCard(title = stringResource(R.string.sideload_card_title)) {
            SideloadSection(panel)
        }
    }
}

private val WORKSPACE_PADDING = 16.dp
private val WORKSPACE_GAP = 14.dp
