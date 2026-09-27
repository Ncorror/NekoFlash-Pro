package io.github.ncorror.nekoflash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ncorror.nekoflash.R
import io.github.ncorror.nekoflash.fastboot.FastbootLinkState
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootMode

/** Состояние Fastboot-соединения и два действия над ним. */
data class FastbootPanel(
    val state: FastbootLinkState = FastbootLinkState.Idle,
    val onProbe: () -> Unit = {},
    val onDisconnect: () -> Unit = {},
    /** Тот же вход, что и у консоли: разрушающая кнопка своего обмена не заводит. */
    val onPlan: (List<String>) -> Unit = {},
)

/**
 * Секция устройства, отвечающего по Fastboot.
 *
 * Роль называется прямо и **без подстановки догадки**: неустановленная роль
 * показывается как неустановленная, вместе с причиной. Написать «загрузчик»
 * там, где мы этого не знаем, значило бы дать оператору опереться на
 * предположение в операциях, которые в загрузчике и в `fastbootd` ведут себя
 * по-разному.
 */
@Composable
fun FastbootLinkSection(
    fastboot: FastbootPanel,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.fastboot_section_title),
            style = MaterialTheme.typography.titleSmall,
        )

        when (val state = fastboot.state) {
            is FastbootLinkState.Idle -> Button(onClick = fastboot.onProbe) {
                Text(stringResource(R.string.fastboot_probe))
            }

            is FastbootLinkState.Probing -> Text(
                text = stringResource(R.string.fastboot_probing),
                style = MaterialTheme.typography.bodySmall,
            )

            is FastbootLinkState.Connected -> {
                FastbootIdentityLines(state, fastboot.onDisconnect)

                // Разрушающие команды живут здесь, а не в консоли, потому что
                // форму подтверждения задаёт замок **этой** generation, и он
                // известен ровно тут.
                FastbootDestructiveSection(lock = state.lock, onPlan = fastboot.onPlan)
            }

            is FastbootLinkState.Failed -> {
                Text(
                    text = stringResource(R.string.fastboot_claim_failed, state.detail),
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = fastboot.onProbe) {
                    Text(stringResource(R.string.fastboot_probe_again))
                }
            }
        }
    }
}

@Composable
private fun FastbootIdentityLines(
    state: FastbootLinkState.Connected,
    onDisconnect: () -> Unit,
) {
    Text(
        text = stringResource(
            when (state.identity.mode) {
                FastbootMode.BOOTLOADER -> R.string.fastboot_mode_bootloader
                FastbootMode.FASTBOOTD -> R.string.fastboot_mode_fastbootd
                FastbootMode.UNKNOWN -> R.string.fastboot_mode_unknown
            },
        ),
        style = MaterialTheme.typography.bodyMedium,
    )

    // Основание вывода показывается всегда, а не только при неудаче: по нему
    // видно, ответом устройства установлена роль или его молчанием.
    Text(
        text = state.identity.detail,
        style = MaterialTheme.typography.bodySmall,
    )

    Button(onClick = onDisconnect) {
        Text(stringResource(R.string.fastboot_disconnect))
    }
}
