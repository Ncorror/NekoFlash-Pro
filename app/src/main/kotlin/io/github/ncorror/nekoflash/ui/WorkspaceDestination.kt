package io.github.ncorror.nekoflash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.ncorror.nekoflash.R

/** One destination is shared by the phone navigation and the wide workspace. */
internal enum class WorkspaceDestination(val label: Int) {
    HOME(R.string.nav_home),
    DEVICE(R.string.nav_device),
    ADB(R.string.nav_adb),
    FASTBOOT(R.string.nav_fastboot),
    RECOVERY(R.string.nav_recovery),
    TERMINAL(R.string.nav_terminal),
    MI_UNLOCK(R.string.nav_mi_unlock),
    OPERATIONS(R.string.nav_operations),
    DIAGNOSTICS(R.string.nav_diagnostics),
}

private val tools = listOf(
    WorkspaceDestination.ADB,
    WorkspaceDestination.FASTBOOT,
    WorkspaceDestination.RECOVERY,
    WorkspaceDestination.TERMINAL,
    WorkspaceDestination.MI_UNLOCK,
    WorkspaceDestination.DIAGNOSTICS,
)

private enum class PrimaryDestination(val label: Int, val icon: Int, val destination: WorkspaceDestination) {
    HOME(R.string.nav_home, R.drawable.nav_home, WorkspaceDestination.HOME),
    DEVICE(R.string.nav_device, R.drawable.nav_device, WorkspaceDestination.DEVICE),
    TOOLS(R.string.nav_tools, R.drawable.nav_tools, WorkspaceDestination.ADB),
    OPERATIONS(R.string.nav_operations, R.drawable.nav_operations, WorkspaceDestination.OPERATIONS),
}

/** Scaffold already consumes the system inset; avoid padding the bottom bar twice. */
@Composable
internal fun DestinationBottomBar(current: WorkspaceDestination, onSelect: (WorkspaceDestination) -> Unit) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        windowInsets = WindowInsets(0, 0, 0, 0),
    ) {
        PrimaryDestination.entries.forEach { item ->
            NavigationBarItem(
                selected = when (item) {
                    PrimaryDestination.TOOLS -> current in tools
                    else -> current == item.destination
                },
                onClick = { onSelect(item.destination) },
                icon = { Icon(painterResource(item.icon), contentDescription = null) },
                label = { Text(stringResource(item.label)) },
            )
        }
    }
}

/** A single selector replaces the clipped horizontal strip of six tabs. */
@Composable
internal fun DestinationToolPicker(current: WorkspaceDestination, onSelect: (WorkspaceDestination) -> Unit) {
    if (current !in tools) return
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(current.label),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        Box {
            TextButton(onClick = { expanded = true }) {
                Text(stringResource(R.string.tool_picker_open))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                tools.forEach { destination ->
                    DropdownMenuItem(
                        text = { Text(stringResource(destination.label)) },
                        onClick = {
                            expanded = false
                            onSelect(destination)
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun DestinationRail(
    current: WorkspaceDestination,
    onSelect: (WorkspaceDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.nav_workspace),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            WorkspaceDestination.entries.forEach { destination ->
                RailItem(destination, destination == current) { onSelect(destination) }
            }
        }
    }
}

@Composable
private fun RailItem(destination: WorkspaceDestination, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, role = Role.Tab, onClick = onClick),
    ) {
        Text(
            text = stringResource(destination.label),
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}
