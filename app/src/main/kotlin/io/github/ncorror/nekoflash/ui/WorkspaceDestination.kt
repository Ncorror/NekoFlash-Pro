package io.github.ncorror.nekoflash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

/** Four destinations remain visible on a phone; tools keep their own named tabs. */
@Composable
internal fun DestinationBottomBar(current: WorkspaceDestination, onSelect: (WorkspaceDestination) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
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

/** Tools on a phone; the same destinations appear directly on the wide rail. */
@Composable
internal fun DestinationToolTabs(current: WorkspaceDestination, onSelect: (WorkspaceDestination) -> Unit) {
    if (current !in tools) return
    PrimaryScrollableTabRow(selectedTabIndex = tools.indexOf(current), edgePadding = 0.dp) {
        tools.forEach { destination ->
            Tab(
                selected = destination == current,
                onClick = { onSelect(destination) },
                text = { Text(stringResource(destination.label)) },
            )
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
