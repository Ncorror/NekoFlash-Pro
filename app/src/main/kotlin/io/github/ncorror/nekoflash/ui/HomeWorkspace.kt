package io.github.ncorror.nekoflash.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ncorror.nekoflash.R
import io.github.ncorror.nekoflash.usb.api.UsbSession

/** Keep the current target and every major tool visible on the home screen. */
@Composable
internal fun HomeWorkspace(
    session: UsbSession?,
    paletteActions: List<PaletteAction>,
    onRescanUsb: () -> Unit,
    onNavigate: (WorkspaceDestination) -> Unit,
) {
    SectionHeading(stringResource(R.string.nav_home), style = MaterialTheme.typography.headlineMedium)
    if (session == null) {
        EmptyHome(onRescanUsb)
    } else {
        CurrentTarget(session, onNavigate)
    }
    HomeQuickActions(onNavigate)
    CommandPalette(actions = paletteActions)
}

@Composable
private fun CurrentTarget(session: UsbSession, onNavigate: (WorkspaceDestination) -> Unit) {
    HomeOutline {
        Text(
            stringResource(R.string.home_current_target),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            session.targetId.value,
            style = MaterialTheme.typography.headlineSmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            stringResource(
                R.string.target_bar_state,
                localizedSessionState(session.state),
                localizedInterfaceKind(session.candidate.kind),
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { onNavigate(WorkspaceDestination.DEVICE) }) {
            Text(stringResource(R.string.home_device_details))
        }
    }
}

@Composable
private fun EmptyHome(onRescanUsb: () -> Unit) {
    HomeOutline {
        Text(stringResource(R.string.home_connect_device), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.usb_rescan_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onRescanUsb) { Text(stringResource(R.string.usb_rescan)) }
    }
}

@Composable
private fun HomeOutline(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun HomeQuickActions(onNavigate: (WorkspaceDestination) -> Unit) {
    SectionHeading(stringResource(R.string.home_tools), style = MaterialTheme.typography.titleLarge)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HomeAction(R.string.nav_adb, WorkspaceDestination.ADB, false, onNavigate, Modifier.weight(1f))
        HomeAction(R.string.nav_fastboot, WorkspaceDestination.FASTBOOT, false, onNavigate, Modifier.weight(1f))
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HomeAction(R.string.nav_recovery, WorkspaceDestination.RECOVERY, true, onNavigate, Modifier.weight(1f))
        HomeAction(R.string.nav_terminal, WorkspaceDestination.TERMINAL, false, onNavigate, Modifier.weight(1f))
    }
    HomeAction(R.string.nav_mi_unlock, WorkspaceDestination.MI_UNLOCK, false, onNavigate)
}

@Composable
private fun HomeAction(
    label: Int,
    destination: WorkspaceDestination,
    prominent: Boolean,
    onNavigate: (WorkspaceDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val onClick = { onNavigate(destination) }
    if (prominent) {
        Button(onClick = onClick, modifier = modifier) { Text(stringResource(label), maxLines = 2) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(stringResource(label), maxLines = 2) }
    }
}
