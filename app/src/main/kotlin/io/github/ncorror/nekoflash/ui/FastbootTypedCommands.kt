package io.github.ncorror.nekoflash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ncorror.nekoflash.R
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootCommands

/**
 * Команды с формой — те же команды, тот же движок.
 *
 * Кнопка здесь **не ходит в полосу**: она собирает строку через
 * [FastbootCommands] и отдаёт её в тот же `onCommand`, что и поле произвольной
 * команды. Второго пути нет по построению, и это единственный способ сделать
 * «typed UI и raw console используют один engine» проверяемым утверждением, а не
 * обещанием: разойтись им негде.
 *
 * **Разрушающие команды живут в своей секции**, рядом с состоянием замка:
 * форму подтверждения задаёт замок, и он известен именно там
 * (`FastbootDestructiveSection`). Здесь остались команды, которые ничего не
 * уничтожают.
 *
 * Какое-то время у `flash:`, `erase:` и `format:` кнопки не было вовсе, и
 * обоснование звучало разумно — «подтверждать нечего, пока писать нечего».
 * Это было суждение о полезности, выданное за безопасность, и `01` §3 называет
 * `erase` поимённо среди того, за что NekoFlash не решает. Разбор — `07` §6.80.
 */
@Composable
fun FastbootTypedCommandsSection(
    onCommand: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var slot by remember { mutableStateOf("") }

    Column(
        modifier = modifier.fillMaxWidth().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.fastboot_typed_title),
            style = MaterialTheme.typography.titleSmall,
        )

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onCommand(FastbootCommands.reboot()) }) {
                Text(stringResource(R.string.fastboot_typed_reboot))
            }
            Button(onClick = { onCommand(FastbootCommands.reboot(BOOTLOADER)) }) {
                Text(stringResource(R.string.fastboot_typed_reboot_bootloader))
            }
            Button(onClick = { onCommand(FastbootCommands.reboot(FASTBOOTD)) }) {
                Text(stringResource(R.string.fastboot_typed_reboot_fastbootd))
            }
            Button(onClick = { onCommand(FastbootCommands.boot()) }) {
                Text(stringResource(R.string.fastboot_typed_boot))
            }
        }

        OutlinedTextField(
            value = slot,
            onValueChange = { slot = it },
            label = { Text(stringResource(R.string.fastboot_typed_slot_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { onCommand(FastbootCommands.setActive(slot)) }) {
            Text(stringResource(R.string.fastboot_typed_set_active))
        }
    }
}

/**
 * Пояснение к секции — отдельно и **ниже исхода**.
 *
 * Прогон `07` §6.76 показал, зачем: длинный текст, стоявший между последней
 * кнопкой и строкой ответа, уводил ответ за нижний край экрана. Оператор нажал
 * одну и ту же мутирующую команду три раза, решив, что ничего не произошло.
 * На запертом загрузчике это стоило только времени, но привычка повторять
 * мутирующую команду, потому что экран промолчал, — ровно то, чем ломают
 * устройства. Объяснение важно, но оно не должно стоять между действием и его
 * результатом.
 */
@Composable
fun FastbootTypedCommandsNote(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.fastboot_typed_note),
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier,
    )
}

/** Цель перезагрузки — часть команды, а не имя кнопки: `reboot-bootloader`. */
private const val BOOTLOADER = "bootloader"

/** `fastboot` как цель: так называется userspace-роль в самой команде. */
private const val FASTBOOTD = "fastboot"
