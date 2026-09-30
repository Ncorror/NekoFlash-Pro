package io.github.ncorror.nekoflash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.ncorror.nekoflash.R
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootMode

/**
 * Группы действий Fastboot. На экране видна одна, остальные — одним касанием.
 *
 * Прогон этапа 2 показал цену одной длинной ленты: два десятка одинаковых
 * кнопок подряд, а ответ устройства — под последней из них, в трёх экранах от
 * нажатой. Группа — не уровень доступа: все вкладки видны всегда, и ни одна не
 * требует ничего, кроме касания.
 *
 * Порядок — по тому, как часто к группе обращаются, а разрушающая стоит
 * последней, чтобы до неё не доходили по привычке.
 */
internal enum class FastbootTool(val label: Int, val note: Int?) {
    READ(R.string.fastboot_tool_read, null),
    COMMAND(R.string.fastboot_tool_command, R.string.fastboot_console_note),
    BUFFER(R.string.fastboot_tool_buffer, null),
    REBOOT(R.string.fastboot_tool_reboot, R.string.fastboot_typed_note),
    WRITE(R.string.fastboot_tool_write, R.string.fastboot_destructive_note),
}

/**
 * Переключатель групп.
 *
 * Чипы с переносом, а не прокручиваемые вкладки: за краем прокрутки на телефоне
 * оставалась бы именно последняя группа, а искать то, чего не видно, никто не
 * станет. Разрушающая группа подписана цветом ошибки в обоих состояниях.
 */
@Composable
internal fun FastbootToolChooser(current: FastbootTool, onSelect: (FastbootTool) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FastbootTool.entries.forEach { tool ->
            val danger = MaterialTheme.colorScheme.error.takeIf { tool == FastbootTool.WRITE }
            FilterChip(
                selected = tool == current,
                onClick = { onSelect(tool) },
                label = { Text(stringResource(tool.label)) },
                colors = if (danger == null) {
                    FilterChipDefaults.filterChipColors()
                } else {
                    FilterChipDefaults.filterChipColors(labelColor = danger, selectedLabelColor = danger)
                },
            )
        }
    }
}

/**
 * Поле и его действие в одну строку.
 *
 * Раньше кнопка стояла под полем во всю строку, и каждая пара занимала два
 * экранных ряда. Клавиша ввода на клавиатуре делает то же, что кнопка: набрав
 * имя, тянуться к кнопке незачем.
 */
@Composable
internal fun FieldWithAction(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    onSubmit: () -> Unit,
    enabled: Boolean,
    imeAction: ImeAction = ImeAction.Go,
    action: @Composable RowScope.() -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = imeAction),
            keyboardActions = KeyboardActions { if (enabled) onSubmit() },
            modifier = Modifier.weight(1f),
        )
        action()
    }
}

/**
 * Всё, что только читает: переменная, весь список и раздел целиком.
 *
 * Поля живут через `rememberSaveable`: вкладку сохраняет держатель состояния
 * секции, и набранное имя переживает переход на соседнюю вкладку и обратно.
 */
@Composable
internal fun FastbootReadTool(console: FastbootConsolePanel) {
    var variable by rememberSaveable { mutableStateOf("") }
    var partition by rememberSaveable { mutableStateOf("") }

    // Пустое имя — это ненабранное поле, а не запрос: `getvar:` целиком можно
    // отправить на вкладке «Команда» (прогон этапа 2, шаг F).
    val canRead = variable.isNotBlank()
    FieldWithAction(
        value = variable,
        onValueChange = { variable = it },
        label = stringResource(R.string.fastboot_variable_label),
        onSubmit = { console.onVariable(variable) },
        enabled = canRead,
    ) {
        Button(onClick = { console.onVariable(variable) }, enabled = canRead) {
            Text(stringResource(R.string.fastboot_variable_read))
        }
    }

    // Частые имена — сокращение набора, а не список разрешённого: поле выше
    // принимает любое имя, и устройство отвечает на него само.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        COMMON_VARIABLES.forEach { name ->
            SuggestionChip(onClick = { console.onVariable(name) }, label = { Text(name) })
        }
    }
    OutlinedButton(onClick = console.onAllVariables) {
        Text(stringResource(R.string.fastboot_variable_read_all))
    }

    OutlinedTextField(
        value = partition,
        onValueChange = { partition = it },
        label = { Text(stringResource(R.string.fastboot_fetch_label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    FastbootFetchRole(console.mode)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { console.onFetchToFile(partition) }, enabled = partition.isNotBlank()) {
            Text(stringResource(R.string.fastboot_fetch_to_file))
        }
    }
}

/**
 * Произвольная команда.
 *
 * Поле свободное и списка команд нет: что бывает у Fastboot, знает устройство,
 * и запирать его набором значило бы решать за оператора, чего просить
 * (`01` §3). Отказ устройства показывается как ответ устройства, а не как наша
 * ошибка.
 */
@Composable
internal fun FastbootCommandTool(onCommand: (String) -> Unit) {
    var command by rememberSaveable { mutableStateOf("") }
    FieldWithAction(
        value = command,
        onValueChange = { command = it },
        label = stringResource(R.string.fastboot_command_label),
        onSubmit = { onCommand(command) },
        enabled = true,
        imeAction = ImeAction.Send,
    ) {
        Button(onClick = { onCommand(command) }) {
            Text(stringResource(R.string.fastboot_command_send))
        }
    }
}

/**
 * Загрузка в буфер устройства.
 *
 * Буфер — не раздел: `download:` ничего не прошивает.
 */
@Composable
internal fun FastbootBufferTool(console: FastbootConsolePanel) {
    Button(onClick = console.onDownloadFile) {
        Text(stringResource(R.string.fastboot_download_file))
    }
}

/**
 * Роль рядом с полем раздела — и подсказка, а не запрет.
 *
 * Legacy печатает перед `fetch:` ровно такое предупреждение и **отправляет
 * команду** (`07` §6.81). Здесь так же: кнопка работает в любой роли, потому
 * что где у этого устройства реализован `fetch:`, знает устройство, а не мы
 * (`01` §3, `03` §5.1). Сказано только то, что известно из архива: команда
 * обычно живёт в `fastbootd`. Чем ответит аппарат, здесь не предсказывается.
 */
@Composable
private fun FastbootFetchRole(mode: FastbootMode) {
    Text(
        text = stringResource(
            R.string.fastboot_fetch_role,
            stringResource(
                when (mode) {
                    FastbootMode.BOOTLOADER -> R.string.fastboot_mode_bootloader
                    FastbootMode.FASTBOOTD -> R.string.fastboot_mode_fastbootd
                    FastbootMode.UNKNOWN -> R.string.fastboot_mode_unknown
                },
            ),
        ),
        style = MaterialTheme.typography.bodySmall,
    )
    if (mode == FastbootMode.BOOTLOADER) {
        Text(
            text = stringResource(R.string.fastboot_fetch_role_bootloader),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * Имена, которые спрашивают чаще прочих: кто это, заперт ли, какой слот,
 * сколько примет за раз и в какой роли.
 */
private val COMMON_VARIABLES = listOf(
    "product",
    "unlocked",
    "current-slot",
    "max-download-size",
    "is-userspace",
    "version-bootloader",
)

