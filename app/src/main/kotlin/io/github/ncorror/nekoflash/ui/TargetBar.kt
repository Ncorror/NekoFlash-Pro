package io.github.ncorror.nekoflash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ncorror.nekoflash.R
import io.github.ncorror.nekoflash.usb.api.UsbSession

/** A compact, persistent target line; the welcome screen carries the brand. */
@Composable
internal fun TargetBar(
    sessions: List<UsbSession>,
    selected: UsbSession?,
    onSelect: (UsbSession) -> Unit,
) {
    Surface(
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth().statusBarsPadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(24.dp)
                    .background(
                        if (selected == null) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(2.dp),
                    ),
            )
            Spacer(Modifier.width(12.dp))
            TargetIdentity(selected, Modifier.weight(1f))
            val others = sessions.filter { it.generation != selected?.generation }
            if (others.isNotEmpty()) TargetSwitcher(others, onSelect)
        }
    }
}

@Composable
private fun TargetIdentity(selected: UsbSession?, modifier: Modifier) {
    if (selected == null) {
        Text(
            text = stringResource(R.string.target_bar_no_device),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = selected.targetId.value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(
                R.string.target_bar_state,
                localizedSessionState(selected.state),
                localizedInterfaceKind(selected.candidate.kind),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TargetSwitcher(others: List<UsbSession>, onSelect: (UsbSession) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        val description = stringResource(R.string.target_bar_others, others.size)
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.semantics { contentDescription = description },
        ) { Text(stringResource(R.string.target_bar_switch)) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            others.forEach { other ->
                DropdownMenuItem(
                    text = { TargetChoice(other) },
                    onClick = {
                        expanded = false
                        onSelect(other)
                    },
                )
            }
        }
    }
}

@Composable
private fun TargetChoice(session: UsbSession) {
    Column {
        Text(session.targetId.value, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            text = stringResource(
                R.string.target_bar_state,
                localizedSessionState(session.state),
                localizedInterfaceKind(session.candidate.kind),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
