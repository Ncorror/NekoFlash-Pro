package io.github.ncorror.nekoflash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
 * **Разрушающие команды живут в своей группе** «Запись», рядом с состоянием
 * замка: форму подтверждения задаёт замок (`FastbootDestructiveSection`). Здесь
 * остались команды, которые ничего не уничтожают, — поэтому и кнопки тональные,
 * а не цвета ошибки.
 *
 * Какое-то время у `flash:`, `erase:` и `format:` кнопки не было вовсе, и
 * обоснование звучало разумно — «подтверждать нечего, пока писать нечего».
 * Это было суждение о полезности, выданное за безопасность, и `01` §3 называет
 * `erase` поимённо среди того, за что NekoFlash не решает. Разбор — `07` §6.80.
 */
@Composable
fun FastbootTypedCommandsSection(onCommand: (String) -> Unit) {
    var slot by rememberSaveable { mutableStateOf("") }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = { onCommand(FastbootCommands.reboot()) }) {
            Text(stringResource(R.string.fastboot_typed_reboot))
        }
        FilledTonalButton(onClick = { onCommand(FastbootCommands.reboot(BOOTLOADER)) }) {
            Text(stringResource(R.string.fastboot_typed_reboot_bootloader))
        }
        FilledTonalButton(onClick = { onCommand(FastbootCommands.reboot(FASTBOOTD)) }) {
            Text(stringResource(R.string.fastboot_typed_reboot_fastbootd))
        }
        FilledTonalButton(onClick = { onCommand(FastbootCommands.boot()) }) {
            Text(stringResource(R.string.fastboot_typed_boot))
        }
    }

    // Пустой слот — ненабранное поле, как и пустое имя переменной:
    // `set_active:` целиком уходит с вкладки «Команда».
    FieldWithAction(
        value = slot,
        onValueChange = { slot = it },
        label = stringResource(R.string.fastboot_typed_slot_label),
        onSubmit = { onCommand(FastbootCommands.setActive(slot)) },
        enabled = slot.isNotBlank(),
    ) {
        FilledTonalButton(onClick = { onCommand(FastbootCommands.setActive(slot)) }, enabled = slot.isNotBlank()) {
            Text(stringResource(R.string.fastboot_typed_set_active))
        }
    }
}

/** Цель перезагрузки — часть команды, а не имя кнопки: `reboot-bootloader`. */
private const val BOOTLOADER = "bootloader"

/** `fastboot` как цель: так называется userspace-роль в самой команде. */
private const val FASTBOOTD = "fastboot"
