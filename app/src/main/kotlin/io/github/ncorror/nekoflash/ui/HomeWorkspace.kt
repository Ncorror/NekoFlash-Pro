package io.github.ncorror.nekoflash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ncorror.nekoflash.R
import io.github.ncorror.nekoflash.usb.api.UsbInterfaceKind
import io.github.ncorror.nekoflash.usb.api.UsbSession

/** Start with the selected device and only the actions supported by its current USB role. */
@Composable
internal fun HomeWorkspace(
    session: UsbSession?,
    paletteActions: List<PaletteAction>,
    onRescanUsb: () -> Unit,
    onNavigate: (WorkspaceDestination) -> Unit,
) {
    SectionHeading(stringResource(R.string.nav_home), style = MaterialTheme.typography.headlineMedium)
    if (session == null) {
        Text(stringResource(R.string.home_connect_device))
        Button(onClick = onRescanUsb) { Text(stringResource(R.string.usb_rescan)) }
        return
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.home_current_target), style = MaterialTheme.typography.titleMedium)
            Text(session.targetId.value, style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(
                    R.string.target_bar_state,
                    localizedSessionState(session.state),
                    localizedInterfaceKind(session.candidate.kind),
                ),
            )
            Button(onClick = { onNavigate(WorkspaceDestination.DEVICE) }) {
                Text(stringResource(R.string.home_device_details))
            }
            when (session.candidate.kind) {
                UsbInterfaceKind.ADB -> {
                    HomeAction(R.string.nav_adb, WorkspaceDestination.ADB, onNavigate)
                    HomeAction(R.string.nav_terminal, WorkspaceDestination.TERMINAL, onNavigate)
                    HomeAction(R.string.nav_recovery, WorkspaceDestination.RECOVERY, onNavigate)
                }
                UsbInterfaceKind.FASTBOOT -> {
                    HomeAction(R.string.nav_fastboot, WorkspaceDestination.FASTBOOT, onNavigate)
                    HomeAction(R.string.nav_mi_unlock, WorkspaceDestination.MI_UNLOCK, onNavigate)
                }
                else -> Unit
            }
        }
    }
    CommandPalette(actions = paletteActions)
}

@Composable
private fun HomeAction(label: Int, destination: WorkspaceDestination, onNavigate: (WorkspaceDestination) -> Unit) {
    Button(onClick = { onNavigate(destination) }) { Text(stringResource(label)) }
}
