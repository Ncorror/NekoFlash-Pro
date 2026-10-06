package io.github.ncorror.nekoflash.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ncorror.nekoflash.R
import io.github.ncorror.nekoflash.usb.api.UsbInterfaceKind
import io.github.ncorror.nekoflash.usb.api.UsbSession

/** Small target switcher embedded in the existing OTG status line. */
@Composable
internal fun TargetSelector(
    sessions: List<UsbSession>,
    selected: UsbSession?,
    onSelect: (UsbSession) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (sessions.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val canSwitch = selected == null || sessions.any { it.generation != selected.generation }
    val label = selected?.targetId?.value ?: stringResource(R.string.target_select)
    val trigger = if (canSwitch) "$label ▾" else label
    Box(modifier = modifier.widthIn(max = TARGET_MAX_WIDTH)) {
        Text(
            text = trigger,
            modifier = Modifier.then(if (canSwitch) Modifier.clickable { expanded = true } else Modifier),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
            fontSize = TARGET_TEXT_SIZE,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            sessions.forEach { session ->
                DropdownMenuItem(
                    enabled = session.generation != selected?.generation,
                    text = {
                        Column {
                            Text(
                                text = session.targetId.value,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = stringResource(interfaceLabel(session.candidate.kind)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(session)
                    },
                )
            }
        }
    }
}

private fun interfaceLabel(kind: UsbInterfaceKind): Int = when (kind) {
    UsbInterfaceKind.ADB -> R.string.interface_kind_adb
    UsbInterfaceKind.FASTBOOT -> R.string.interface_kind_fastboot
}

private val TARGET_MAX_WIDTH = 176.dp
private val TARGET_TEXT_SIZE = 10.sp
