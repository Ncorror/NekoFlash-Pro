package io.github.ncorror.nekoflash.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ncorror.nekoflash.R
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootCommands
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootPlan
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootPlans
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootLockStatus

/**
 * Разрушающие команды с формой.
 *
 * **Кнопки здесь есть потому, что их отсутствие было бы ограничением.** `01` §3
 * называет `erase` поимённо среди того, за что NekoFlash не решает, и требует не
 * отсутствия действия, а предупреждения о значимых рисках и осознанного
 * подтверждения для destructive guided actions. Убрать кнопку и назвать это
 * заботой — это и есть скрытый capability tier, которого в продукте нет.
 *
 * **Подтверждение — не разрешение.** Команда уходит устройству без изменений в
 * обоих случаях; различается только то, насколько осознанным было нажатие
 * (`03` §5.1, D031). Единственный product-level hard guard, блокировавший
 * `flash:` при `LOCKED`, отменён: отказ при замке принадлежит устройству, и
 * подменять его решением хоста запрещено.
 *
 * Форма подтверждения задаётся замком **этой** generation:
 *
 * - подтверждённый `LOCKED` — предупреждение и typed confirmation `yes`;
 * - `UNLOCKED`, `UNKNOWN`, отказ на запрос, противоречивый ответ — обычный
 *   advisory и обычное подтверждение. Приравнять `UNKNOWN` к `LOCKED` значило бы
 *   вернуть отменённый guard через чёрный ход, и `03` §5.1 запрещает это прямо.
 *
 * Поле произвольной команды всё это не затрагивает: raw console выполняет
 * `flash:`, `erase:` и `format:` **без** prompt, как и написано в `03` §5.1.
 */
@Composable
fun FastbootDestructiveSection(
    lock: FastbootLockStatus,
    onPlan: (List<String>) -> Unit,
) {
    var partition by rememberSaveable { mutableStateOf("") }
    var pending by remember { mutableStateOf<FastbootPlan?>(null) }

    // Замок виден до нажатия, а не только в подтверждении: от него зависит,
    // чего ждать от устройства, и узнавать это после выбора команды поздно.
    Text(
        text = stringResource(R.string.fastboot_destructive_lock_state, lock.detail),
        style = MaterialTheme.typography.bodySmall,
    )

    OutlinedTextField(
        value = partition,
        onValueChange = { partition = it },
        label = { Text(stringResource(R.string.fastboot_destructive_partition_label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    // Пустой раздел — ненабранное поле: `erase:` без имени уходит с вкладки
    // «Команда». Контурные кнопки цвета ошибки — чтобы разрушающие действия
    // не выглядели так же, как чтение переменной.
    val named = partition.isNotBlank()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DangerButton(R.string.fastboot_destructive_erase, named) {
            pending = FastbootPlan(listOf(FastbootCommands.erase(partition)))
        }
        DangerButton(R.string.fastboot_destructive_format, named) {
            pending = FastbootPlan(listOf(FastbootCommands.format(partition)))
        }
        DangerButton(R.string.fastboot_destructive_flash, named) {
            pending = FastbootPlan(listOf(FastbootCommands.flash(partition)))
        }
        // План из двух шагов — тот же путь, только список длиннее, и он
        // виден целиком до нажатия.
        DangerButton(R.string.fastboot_destructive_flash_and_reboot, named) {
            pending = FastbootPlans.flashBuffer(partition)
        }
    }

    pending?.let { plan ->
        FastbootConfirmation(
            plan = plan,
            lock = lock,
            onConfirm = {
                pending = null
                onPlan(plan.commands)
            },
            onCancel = { pending = null },
        )
    }
}

@Composable
private fun DangerButton(label: Int, enabled: Boolean, onClick: () -> Unit) {
    val danger = MaterialTheme.colorScheme.error
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        border = BorderStroke(1.dp, if (enabled) danger else MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = danger),
    ) {
        Text(stringResource(label))
    }
}

/**
 * Осознанное подтверждение разрушающего действия.
 *
 * Предупреждение обязано честно называть **ожидаемый исход**, а не пугать
 * вообще: при подтверждённом `LOCKED` устройство почти наверняка откажет
 * ответом `FAIL` и раздел не изменится, а на части загрузчиков сначала
 * передаётся весь объём данных и только потом приходит отказ (`03` §5.1).
 */
@Composable
private fun FastbootConfirmation(
    plan: FastbootPlan,
    lock: FastbootLockStatus,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    var typed by remember(plan.commands) { mutableStateOf("") }
    val danger = MaterialTheme.colorScheme.error

    Surface(
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, danger),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Список показывается целиком до нажатия: `01` §3 требует
            // содержательного preflight, и предпросмотр плана — это он и есть.
            // Узнавать шаги по ходу оператор не должен.
            Text(
                text = stringResource(R.string.fastboot_destructive_warning, plan.commands.joinToString(" ; ")),
                style = MaterialTheme.typography.bodyMedium,
            )

            if (lock.typedConfirmation) {
                Text(
                    text = stringResource(R.string.fastboot_destructive_locked_outcome),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text(stringResource(R.string.fastboot_destructive_type_yes, CONFIRM_WORD)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onConfirm,
                    // Слово требуется только при подтверждённом замке. В остальных
                    // случаях подтверждение обычное: `UNKNOWN` — не `LOCKED`.
                    enabled = !lock.typedConfirmation || typed.trim().equals(CONFIRM_WORD, ignoreCase = true),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = danger,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text(stringResource(R.string.fastboot_destructive_confirm))
                }
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.fastboot_destructive_cancel))
                }
            }
        }
    }
}

/** Слово подтверждения из `03` §5.1. */
private const val CONFIRM_WORD = "yes"
