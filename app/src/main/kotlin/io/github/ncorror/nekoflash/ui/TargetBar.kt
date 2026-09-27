package io.github.ncorror.nekoflash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ncorror.nekoflash.R
import io.github.ncorror.nekoflash.usb.api.UsbSession

/**
 * Какое устройство сейчас на столе.
 *
 * Рабочее место принадлежит **одному** устройству, и это не оформление:
 * соединение ADB одно, полоса Fastboot одна, и операция ведётся с одной целью.
 * Пока все подключённые устройства лежали одним списком, экран показывал то, что
 * относится к разным целям, вперемешку — и «подключено» приходилось соотносить
 * с карточкой глазами.
 *
 * Когда устройство одно — а так почти всегда, — полоса просто называет его.
 * Выбор появляется только когда есть из чего выбирать, и тогда он честный:
 * другие цели названы, а не спрятаны.
 */
@Composable
internal fun TargetBar(
    sessions: List<UsbSession>,
    selected: UsbSession,
    onSelect: (UsbSession) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            LabelledValue(
                label = stringResource(R.string.target_bar_label),
                value = selected.targetId.value,
            )
            Text(
                text = stringResource(
                    R.string.target_bar_state,
                    localizedSessionState(selected.state),
                    localizedInterfaceKind(selected.candidate.kind),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            if (sessions.size > 1) {
                Text(
                    text = stringResource(R.string.target_bar_others, sessions.size - 1),
                    style = MaterialTheme.typography.labelMedium,
                )
                sessions.filter { it.generation != selected.generation }.forEach { other ->
                    TextButton(onClick = { onSelect(other) }) {
                        Text(other.targetId.value)
                    }
                }
            }
        }
    }
}
