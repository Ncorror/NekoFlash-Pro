package io.github.ncorror.nekoflash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ncorror.nekoflash.R
import io.github.ncorror.nekoflash.fastboot.FastbootConsoleState
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootLaneState
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootMode
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootMutationClass
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootPartitionIndex
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootMutationOutcome
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootReply
import kotlinx.coroutines.delay

/** Консоль Fastboot: произвольная команда, переменная по имени и весь список. */
data class FastbootConsolePanel(
    val state: FastbootConsoleState = FastbootConsoleState.Idle,
    val onCommand: (String) -> Unit = {},
    val onVariable: (String) -> Unit = {},
    val onAllVariables: () -> Unit = {},
    /** Загрузить в буфер устройства столько порождённых приложением байт. */
    val onDownload: (Long) -> Unit = {},
    /** Прочитать раздел с устройства — фаза DATA IN. Ничего не меняет. */
    val onFetch: (String) -> Unit = {},
    /** Прочитать раздел в файл, который выберет пользователь. */
    val onFetchToFile: (String) -> Unit = {},
    /** Загрузить в буфер устройства файл, который выберет пользователь. */
    val onDownloadFile: () -> Unit = {},
    /**
     * Перезахват интерфейса — выход из потерянной рамки.
     *
     * Не «повтор» и не «сброс устройства»: интерфейс отпускается и берётся
     * заново, аппарат при этом не трогается. Кнопка нужна затем, что до `07`
     * §6.89 выхода из `STALLED` на экране не было вовсе — слово печаталось,
     * а делать с ним было нечего.
     */
    val onReclaim: () -> Unit = {},
    /**
     * Роль устройства — **здесь**, а не только в шапке связи.
     *
     * Загрузчик и `fastbootd` перечисляются одним интерфейсом и на экране
     * отличаются одной строкой; оператор набирает раздел здесь и шапку в этот
     * момент не видит. Прогон `07` §6.88 прошёл целиком не в той роли, и
     * прочитать это по журналу удалось, а по экрану — нет.
     */
    val mode: FastbootMode = FastbootMode.UNKNOWN,
)

/**
 * Секция произвольного обмена по Fastboot.
 *
 * Поля свободные и списка команд нет: что бывает у Fastboot, знает устройство,
 * и запирать его набором значило бы решать за оператора, чего просить
 * (`01` §3). Отказ устройства показывается как ответ устройства, а не как наша
 * ошибка.
 */
@Composable
fun FastbootConsoleSection(
    console: FastbootConsolePanel,
    modifier: Modifier = Modifier,
) {
    var command by remember { mutableStateOf("") }

    Column(
        modifier = modifier.fillMaxWidth().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SectionHeading(
            text = stringResource(R.string.fastboot_console_title),
        )

        OutlinedTextField(
            value = command,
            onValueChange = { command = it },
            label = { Text(stringResource(R.string.fastboot_command_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { console.onCommand(command) }) {
            Text(stringResource(R.string.fastboot_command_send))
        }

        FastbootReadControls(console)

        FastbootTypedCommandsSection(onCommand = console.onCommand)

        // Исход стоит сразу за последней кнопкой, и оба пояснения — под ним.
        // Прогон §6.76: пояснение между кнопкой и ответом увело ответ за край
        // экрана, и оператор трижды повторил мутирующую команду, решив, что
        // ничего не произошло. Текст, стоящий между действием и результатом,
        // стоит дороже, чем то, что он объясняет.
        FastbootConsoleOutcome(console.state)

        FastbootStalledNotice(console.state.lane, console.onReclaim)

        Text(
            text = stringResource(R.string.fastboot_console_note),
            style = MaterialTheme.typography.bodySmall,
        )

        FastbootTypedCommandsNote()
    }
}

/**
 * Всё, что только читает: переменная, весь список и раздел целиком.
 *
 * Загрузка в буфер стоит здесь же, потому что буфер — не раздел: `download:`
 * ничего не прошивает. Разрушающие команды живут отдельно, рядом с замком.
 */
@Composable
private fun FastbootReadControls(console: FastbootConsolePanel) {
    var variable by remember { mutableStateOf("") }
    var partition by remember { mutableStateOf("") }

    OutlinedTextField(
        value = variable,
        onValueChange = { variable = it },
        label = { Text(stringResource(R.string.fastboot_variable_label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { console.onVariable(variable) }) {
            Text(stringResource(R.string.fastboot_variable_read))
        }
        Button(onClick = console.onAllVariables) {
            Text(stringResource(R.string.fastboot_variable_read_all))
        }
    }

    OutlinedTextField(
        value = partition,
        onValueChange = { partition = it },
        label = { Text(stringResource(R.string.fastboot_fetch_label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    FastbootFetchRole(console.mode)

    Button(onClick = { console.onFetch(partition) }) {
        Text(stringResource(R.string.fastboot_fetch_read))
    }
    Button(onClick = { console.onFetchToFile(partition) }) {
        Text(stringResource(R.string.fastboot_fetch_to_file))
    }

    SectionHeading(
        text = stringResource(R.string.fastboot_download_title),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { console.onDownload(SMALL_DOWNLOAD_BYTES) }) {
            Text(stringResource(R.string.fastboot_download_small))
        }
        Button(onClick = { console.onDownload(LARGE_DOWNLOAD_BYTES) }) {
            Text(stringResource(R.string.fastboot_download_large))
        }
    }
    Button(onClick = console.onDownloadFile) {
        Text(stringResource(R.string.fastboot_download_file))
    }
}

/**
 * Ожидание, которое видно.
 *
 * Строка тикает раз в секунду, потому что молчать здесь можно долго по
 * построению: терпение на фазу данных — две минуты, и неподвижное «ждём
 * ответа» за это время читается как зависание. Прогон `07` §6.95 оборвали на
 * 68-й секунде ровно поэтому — экран не отличался от повисшего.
 *
 * Отсчёт ведётся от отметки, поставленной при отправке, а не от появления
 * строки на экране: «сколько ждали» должно измеряться там же, где начинается
 * ожидание (`07` §6.91 — тот же урок, только про журнал).
 */
@Composable
private fun FastbootWaitingLine(state: FastbootConsoleState.Running) {
    var seconds by remember(state.startedAtMillis) { mutableStateOf(0L) }
    LaunchedEffect(state.startedAtMillis) {
        while (true) {
            seconds = (System.currentTimeMillis() - state.startedAtMillis).coerceAtLeast(0L) / MILLIS_IN_SECOND
            delay(MILLIS_IN_SECOND)
        }
    }
    Text(
        text = stringResource(R.string.fastboot_console_waiting, state.command, seconds),
        style = MaterialTheme.typography.bodySmall,
    )
}

/**
 * Потерянная рамка — с объяснением и выходом, а не одним словом.
 *
 * `STALLED` печаталось и раньше, но только как слово в строке полосы: после
 * него каждая следующая команда отвечала «полоса занята», и что делать, экран
 * не говорил (`07` §6.89). Это не защита от оператора и не запрет: полоса
 * действительно не может нести вторую команду, пока устройство ждёт байты
 * первой, — и единственный выход показан здесь же кнопкой.
 */
@Composable
private fun FastbootStalledNotice(lane: FastbootLaneState?, onReclaim: () -> Unit) {
    if (lane != FastbootLaneState.STALLED) return
    Text(
        text = stringResource(R.string.fastboot_console_lane_stalled),
        style = MaterialTheme.typography.bodySmall,
    )
    Button(onClick = onReclaim) {
        Text(stringResource(R.string.fastboot_console_lane_reclaim))
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

@Composable
private fun FastbootConsoleOutcome(state: FastbootConsoleState) {
    when (state) {
        is FastbootConsoleState.Idle -> Unit

        is FastbootConsoleState.Running -> FastbootWaitingLine(state)

        is FastbootConsoleState.Answered -> FastbootAnswer(state)

        is FastbootConsoleState.NotAnswered -> {
            Text(
                text = stringResource(R.string.fastboot_console_no_answer, state.command, state.detail),
                style = MaterialTheme.typography.bodyMedium,
            )
            FastbootLaneLine(state.lane.name)
        }

        is FastbootConsoleState.Variables -> FastbootVariableList(state)

        is FastbootConsoleState.Downloaded -> FastbootDownloadOutcomeLines(state)

        is FastbootConsoleState.Mutated -> FastbootMutationLines(state)

        is FastbootConsoleState.Fetched -> FastbootFetchLines(state)

        is FastbootConsoleState.Planned -> FastbootPlanLines(state)
    }
}

/**
 * Исход плана.
 *
 * Главное — где он остановился, а не сколько шагов прошло: устройство после
 * обрыва между «до» и «после», и по одному счётчику этого не увидеть.
 */
@Composable
private fun FastbootPlanLines(state: FastbootConsoleState.Planned) {
    Text(
        text = stringResource(
            if (state.stoppedAt == null) R.string.fastboot_plan_completed else R.string.fastboot_plan_stopped,
            state.applied,
            state.commands.size,
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
    state.commands.forEachIndexed { index, command ->
        Text(
            text = stringResource(
                when {
                    state.stoppedAt == null || index < state.stoppedAt -> R.string.fastboot_plan_step_done
                    index == state.stoppedAt -> R.string.fastboot_plan_step_stopped
                    else -> R.string.fastboot_plan_step_skipped
                },
                command,
            ),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (state.detail.isNotBlank()) {
        Text(text = state.detail, style = MaterialTheme.typography.bodySmall)
    }
    FastbootLaneLine(state.lane.name)
}

/**
 * Исход чтения раздела.
 *
 * Главная строка — полнота, а не объём: частичное чтение обязано называться
 * частичным. Отпечаток показывается всегда, но он доказывает **прочитанное**, а
 * не содержимое раздела на устройстве.
 */
@Composable
private fun FastbootFetchLines(state: FastbootConsoleState.Fetched) {
    Text(
        text = stringResource(
            if (state.complete) R.string.fastboot_fetch_complete else R.string.fastboot_fetch_partial,
            state.partition,
            state.bytes,
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
    if (state.sha256.isNotEmpty()) {
        Text(
            text = stringResource(R.string.fastboot_fetch_sha256, state.sha256),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (state.detail.isNotBlank()) {
        Text(text = state.detail, style = MaterialTheme.typography.bodySmall)
    }
    FastbootLaneLine(state.lane.name)
}

/**
 * Исход команды, способной изменить устройство.
 *
 * Главная строка — про состояние устройства, и она разная там, где разница
 * есть. Отказ устройства это его слово, и целости раздела оно не доказывает.
 * Уход по нашей же просьбе — не то же, что молчание неизвестно почему. А
 * оборванная запись в раздел говорится прямо: что в разделе, неизвестно.
 */
@Composable
private fun FastbootMutationLines(state: FastbootConsoleState.Mutated) {
    when (val outcome = state.outcome) {
        is FastbootMutationOutcome.Applied -> FastbootAppliedLines(outcome)

        is FastbootMutationOutcome.Refused -> FastbootRefusedLines(outcome)

        is FastbootMutationOutcome.Unconfirmed -> Text(
            text = stringResource(
                R.string.fastboot_mutation_unconfirmed,
                outcome.command,
                outcome.expected,
                outcome.observed,
            ),
            style = MaterialTheme.typography.bodyMedium,
        )

        is FastbootMutationOutcome.Departed -> Text(
            text = stringResource(R.string.fastboot_mutation_departed, outcome.command, outcome.waitedMillis),
            style = MaterialTheme.typography.bodyMedium,
        )

        is FastbootMutationOutcome.Unknown -> FastbootUnknownLines(outcome)

        is FastbootMutationOutcome.NotStarted -> Text(
            text = stringResource(R.string.fastboot_mutation_not_started, outcome.command, outcome.detail),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    FastbootLaneLine(state.lane.name)
}

@Composable
private fun FastbootAppliedLines(outcome: FastbootMutationOutcome.Applied) {
    Text(
        text = stringResource(R.string.fastboot_console_okay, outcome.command, outcome.payload.ifBlank { "—" }),
        style = MaterialTheme.typography.bodyMedium,
    )
    // Подтверждение показывается там, где согласие устройства доказательством
    // не является: по строке видно, чем именно исход подтверждён.
    outcome.confirmation?.let { confirmed ->
        Text(
            text = stringResource(R.string.fastboot_mutation_confirmed, confirmed),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    outcome.info.forEach { line ->
        Text(
            text = stringResource(R.string.fastboot_console_info, line),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun FastbootRefusedLines(outcome: FastbootMutationOutcome.Refused) {
    Text(
        text = stringResource(R.string.fastboot_console_fail, outcome.command, outcome.detail),
        style = MaterialTheme.typography.bodyMedium,
    )
    // Про раздел и про замок говорится прямо: отказ — слово устройства, а не
    // доказательство целости. Иначе оператор прочитал бы «FAIL» как «ничего не
    // случилось». Формулировки разные, потому что разное и то, о чём речь.
    refusalCaveatOf(outcome.mutation)?.let { caveat ->
        Text(text = stringResource(caveat), style = MaterialTheme.typography.bodySmall)
    }
}

private fun refusalCaveatOf(mutation: FastbootMutationClass): Int? = when (mutation) {
    FastbootMutationClass.PARTITION -> R.string.fastboot_mutation_refusal_is_not_proof
    FastbootMutationClass.LOCK -> R.string.fastboot_mutation_lock_refusal
    else -> null
}

@Composable
private fun FastbootUnknownLines(outcome: FastbootMutationOutcome.Unknown) {
    Text(
        text = stringResource(R.string.fastboot_console_no_answer, outcome.command, outcome.detail),
        style = MaterialTheme.typography.bodyMedium,
    )
    // Неизвестное состояние называется тем словом, которое ему подходит: у
    // раздела — раздел, у замка — замок и данные вместе с ним.
    unknownCaveatOf(outcome.mutation)?.let { caveat ->
        Text(text = stringResource(caveat), style = MaterialTheme.typography.bodyMedium)
    }
}

private fun unknownCaveatOf(mutation: FastbootMutationClass): Int? = when (mutation) {
    FastbootMutationClass.PARTITION -> R.string.fastboot_mutation_partition_unknown
    FastbootMutationClass.LOCK -> R.string.fastboot_mutation_lock_unknown
    else -> null
}

/**
 * Исход загрузки.
 *
 * Главная строка здесь — про состояние устройства, а не про успех. «Ничего не
 * изменилось» говорится **только** когда это правда: при отказе до фазы данных.
 * Во всех прочих случаях в буфере неизвестно что, и прошивать из него нельзя.
 */
@Composable
private fun FastbootDownloadOutcomeLines(state: FastbootConsoleState.Downloaded) {
    Text(
        text = stringResource(
            R.string.fastboot_download_result,
            state.sentBytes,
            state.declaredBytes,
            state.reply?.name ?: stringResource(R.string.fastboot_download_no_reply),
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        text = stringResource(
            if (state.untouched) {
                R.string.fastboot_download_untouched
            } else {
                R.string.fastboot_download_buffer_unknown
            },
        ),
        style = MaterialTheme.typography.bodySmall,
    )
    if (state.detail.isNotBlank()) {
        Text(text = state.detail, style = MaterialTheme.typography.bodySmall)
    }
    FastbootLaneLine(state.lane.name)
}

@Composable
private fun FastbootAnswer(state: FastbootConsoleState.Answered) {
    // Отказ и согласие различаются словом устройства, а не местом на экране:
    // прятать `FAIL` в другую секцию значило бы подменять ответ оценкой.
    Text(
        text = stringResource(
            if (state.reply == FastbootReply.FAIL) {
                R.string.fastboot_console_fail
            } else {
                R.string.fastboot_console_okay
            },
            state.command,
            state.payload.ifBlank { "—" },
        ),
        style = MaterialTheme.typography.bodyMedium,
    )

    state.info.forEach { line ->
        Text(
            text = stringResource(R.string.fastboot_console_info, line),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    FastbootLaneLine(state.lane.name)
}

@Composable
private fun FastbootVariableList(state: FastbootConsoleState.Variables) {
    val snapshot = state.snapshot
    Text(
        text = stringResource(
            if (snapshot.complete) R.string.fastboot_variables_all else R.string.fastboot_variables_partial,
            snapshot.variables.size,
        ),
        style = MaterialTheme.typography.bodyMedium,
    )

    // Инвентарь считается из того же ответа и показывается рядом: «названо»
    // и «подтверждено» — разные числа, потому что `has-slot` отвечают и про
    // имена, которых у устройства нет. Показать одно вместо двух значило бы
    // выдать догадку за перечень.
    val inventory = FastbootPartitionIndex.of(snapshot)
    if (inventory.partitions.isNotEmpty()) {
        Text(
            text = stringResource(
                R.string.fastboot_partitions_summary,
                inventory.partitions.size,
                inventory.concrete.size,
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    snapshot.variables.forEach { (name, value) ->
        Text(
            text = stringResource(R.string.fastboot_variable_entry, name, value),
            style = MaterialTheme.typography.bodySmall,
        )
    }

    // Расхождение под одним именем и неразобранная строка показываются, а не
    // прячутся: это наблюдения об устройстве, и по ним видно, что разбор неполон.
    snapshot.duplicates.filter { it.conflicting }.forEach { duplicate ->
        Text(
            text = stringResource(
                R.string.fastboot_variables_conflict,
                duplicate.name,
                duplicate.values.joinToString(", "),
            ),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (snapshot.ignored.isNotEmpty()) {
        Text(
            text = stringResource(R.string.fastboot_variables_ignored, snapshot.ignored.size),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    FastbootLaneLine(state.lane.name)
}

/**
 * Состояние полосы.
 *
 * Показывается всегда: у Fastboot полоса одна, и потеря рамки липкая. Скрыв её,
 * мы дали бы оператору нажимать кнопку, которая заведомо не сработает.
 */
@Composable
private fun FastbootLaneLine(lane: String) {
    Text(
        text = stringResource(R.string.fastboot_console_lane, lane),
        style = MaterialTheme.typography.bodySmall,
    )
}

/** Миллисекунд в секунде — чтобы деление не выглядело магическим числом. */
private const val MILLIS_IN_SECOND = 1_000L

/** Маленькая загрузка: доказывает путь, ничего не занимая. */
private const val SMALL_DOWNLOAD_BYTES = 4L * 1024

/**
 * Большая загрузка: несколько блоков по 16 КиБ.
 *
 * Нужна затем, что путь в один блок не проверяет дописывание короткой записи и
 * счёт байтов через границу блока — а именно там ломалось у Legacy и A2.
 */
private const val LARGE_DOWNLOAD_BYTES = 2L * 1024 * 1024
