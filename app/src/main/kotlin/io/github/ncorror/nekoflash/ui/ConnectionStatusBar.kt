package io.github.ncorror.nekoflash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ncorror.nekoflash.R
import io.github.ncorror.nekoflash.adb.AdbLinkState
import io.github.ncorror.nekoflash.fastboot.FastbootLinkState
import io.github.ncorror.nekoflash.usb.api.UsbSession

/**
 * Compact status line: OTG state on the left, selected target on the right.
 * Navigation remains a drawer gesture and does not take terminal space.
 */
@Composable
internal fun ConnectionStatusBar(
    adb: AdbLinkState,
    fastboot: FastbootLinkState,
    sessions: List<UsbSession>,
    selected: UsbSession?,
    onSelectTarget: (UsbSession) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visual = connectionVisual(adb, fastboot)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(STATUS_BAR_HEIGHT)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = STATUS_HORIZONTAL_PADDING),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(STATUS_GAP),
    ) {
        Box(
            modifier = Modifier
                .size(STATUS_DOT_SIZE)
                .background(visual.color, CircleShape),
        )
        Text(
            text = stringResource(visual.label),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = STATUS_TEXT_SIZE,
        )
        Spacer(Modifier.weight(1f))
        TargetSelector(
            sessions = sessions,
            selected = selected,
            onSelect = onSelectTarget,
        )
    }
}

private data class ConnectionVisual(val label: Int, val color: Color)

@Suppress("MagicNumber")
private fun connectionVisual(adb: AdbLinkState, fastboot: FastbootLinkState): ConnectionVisual = when {
    fastboot is FastbootLinkState.Connected -> ConnectionVisual(R.string.otg_fastboot, Color(0xFF3B82F6))
    adb is AdbLinkState.Connected -> ConnectionVisual(R.string.otg_adb, Color(0xFF22C55E))
    fastboot is FastbootLinkState.Probing ||
        adb is AdbLinkState.Connecting ||
        adb is AdbLinkState.WaitingForAuthorization ->
        ConnectionVisual(R.string.otg_connecting, Color(0xFFF59E0B))
    adb is AdbLinkState.Failed || fastboot is FastbootLinkState.Failed ->
        ConnectionVisual(R.string.otg_error, Color(0xFFEF4444))
    else -> ConnectionVisual(R.string.otg_disconnected, Color(0xFFEF4444))
}

private val STATUS_BAR_HEIGHT = 30.dp
private val STATUS_HORIZONTAL_PADDING = 10.dp
private val STATUS_GAP = 7.dp
private val STATUS_DOT_SIZE = 7.dp
private val STATUS_TEXT_SIZE = 11.sp