package io.github.ncorror.nekoflash.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.ncorror.nekoflash.R
import io.github.ncorror.nekoflash.adb.AdbCommandState
import io.github.ncorror.nekoflash.adb.AdbFileState
import io.github.ncorror.nekoflash.protocol.adb.AdbRecoveryVerdict
import io.github.ncorror.nekoflash.adb.AdbForwardController
import io.github.ncorror.nekoflash.adb.AdbForwardEntry
import io.github.ncorror.nekoflash.adb.AdbForwardState
import io.github.ncorror.nekoflash.adb.AdbReverseEntry
import io.github.ncorror.nekoflash.adb.AdbReverseState
import io.github.ncorror.nekoflash.adb.AdbLinkState
import io.github.ncorror.nekoflash.adb.AdbRawServiceState
import io.github.ncorror.nekoflash.adb.AdbRebootState
import io.github.ncorror.nekoflash.adb.AdbTerminalState
import io.github.ncorror.nekoflash.core.model.SessionGeneration
import io.github.ncorror.nekoflash.protocol.adb.AdbPeerMode
import io.github.ncorror.nekoflash.protocol.adb.AdbSyncDestination
import io.github.ncorror.nekoflash.usb.api.UsbSession

/**
 * Состояние ADB-соединения для конкретной сессии.
 *
 * Показывается только у этой сессии: соединение принадлежит одному поколению,
 * и показывать его состояние рядом с чужим устройством означало бы сказать
 * неправду о том, к чему относится «подключено».
 */
@Composable
internal fun AdbLinkSection(
    session: UsbSession,
    adbLink: AdbLinkState,
    adbCommand: AdbCommandState,
    files: AdbFileState,
    fileActions: FileActions,
    onAdbConnect: () -> Unit,
    onAdbDisconnect: () -> Unit,
    onRunCommand: (String) -> Unit,
    reboot: RebootPanel,
    rawService: RawServicePanel,
    forward: ForwardPanel,
    reverse: ReversePanel,
    sideload: SideloadPanel,
) {
    val linkForThisSession = adbLink.takeIf { it.generationOrNull() == session.generation }
    val connected = linkForThisSession is AdbLinkState.Connected
    val busy = linkForThisSession is AdbLinkState.Connecting ||
        linkForThisSession is AdbLinkState.WaitingForAuthorization

    LabelledValue(
        label = stringResource(R.string.adb_state_label),
        value = adbLinkText(linkForThisSession),
    )
    if (linkForThisSession is AdbLinkState.Connected) {
        LabelledValue(
            label = stringResource(R.string.adb_features_label),
            value = linkForThisSession.features.sorted().joinToString(", ").ifEmpty {
                stringResource(R.string.adb_features_none)
            },
        )
    }
    Button(
        onClick = if (connected) onAdbDisconnect else onAdbConnect,
        enabled = !busy,
    ) {
        Text(
            stringResource(
                if (connected) R.string.adb_disconnect else R.string.adb_connect,
            ),
        )
    }
    Text(
        text = stringResource(R.string.adb_connect_hint),
        style = MaterialTheme.typography.bodySmall,
    )
    if (connected) {
        ShellSection(command = adbCommand, onRunCommand = onRunCommand)
        // Интерактивная оболочка живёт в своём разделе: она переживает
        // отдельную команду, и листать до неё через все протокольные секции
        // приходилось каждый раз.
        FilesSection(files = files, actions = fileActions)
        // Sideload стоит сразу за файлами не по алфавиту: база журнала
        // снимается там, вердикт читается там же, а между ними — эта передача.
        // Три шага одного дела, и разносить их по экрану значило бы заставить
        // оператора искать второй и третий.
        SideloadSection(panel = sideload)
        RebootSection(panel = reboot)
        RawServiceSection(panel = rawService)
        ForwardSection(panel = forward)
        ReverseSection(panel = reverse)
    }
}

/**
 * Произвольный сервис: состояние вызова и само действие.
 *
 * Собраны в один объект по той же причине, что и [RebootPanel].
 */
data class RawServicePanel(
    val state: AdbRawServiceState = AdbRawServiceState.None,
    val onCall: (String) -> Unit = {},
)

/**
 * Проброс портов: список живых пробросов и два действия над ними.
 *
 * Собраны в один объект по той же причине, что и [RebootPanel].
 */
data class ForwardPanel(
    val state: AdbForwardState = AdbForwardState.None,
    /** Завести проброс: локальный порт и адрес на устройстве. */
    val onAdd: (Int, String) -> Unit = { _, _ -> },
    val onRemove: (Int) -> Unit = {},
)

/** Обратный проброс: что слушает устройство и три действия над этим. */
data class ReversePanel(
    val state: AdbReverseState = AdbReverseState.None,
    /** Попросить слушать: адрес на устройстве и адрес на этом телефоне. */
    val onAdd: (String, String) -> Unit = { _, _ -> },
    val onRefresh: () -> Unit = {},
    val onRemoveAll: () -> Unit = {},
)

/**
 * Перезагрузка: состояние запроса и само действие.
 *
 * Состояние и действие здесь в одном объекте, в отличие от файлов и терминала,
 * и причина не в красоте. Список аргументов экрана уже однажды разъехался — об
 * этом сказано прямо в `NekoFlashApp`, — и каждая лишняя пара строк в нём это
 * ещё один шанс разъехаться снова.
 */
data class RebootPanel(
    val state: AdbRebootState = AdbRebootState.None,
    val onReboot: (String) -> Unit = {},
)

/** Действия с файлами устройства. */
data class FileActions(
    val onDescribe: (String) -> Unit = {},
    val onRead: (String) -> Unit = {},
    /** Записать файл заданного размера в байтах. */
    val onWrite: (String, Long) -> Unit = { _, _ -> },
    /** Прочитать файл устройства в место, которое выберет пользователь. */
    val onReadToFile: (String) -> Unit = {},
    /** Записать на устройство файл, который выберет пользователь. */
    val onWriteFromFile: (String) -> Unit = {},
    /** Снять базу журнала Recovery — до установки. Путь фиксирован. */
    val onRecoveryBaseline: () -> Unit = {},
    /** Прочитать журнал Recovery и объявить вердикт, если его разрешает база. */
    val onRecoveryVerdict: () -> Unit = {},
)

/**
 * Размеры файлов, которые приложение умеет записать само.
 *
 * Оба нужны аппаратному гейту `07` §6.34: малый проходит одним блоком,
 * большой — двумястами пятьюдесятью шестью, и его же хватает, чтобы успеть
 * выдернуть кабель посреди передачи.
 */
private const val SMALL_WRITE_BYTES = 4L * 1024L
private const val LARGE_WRITE_BYTES = 16L * 1024L * 1024L

/**
 * Операции с файлами устройства: проверка пути, чтение и запись.
 *
 * Живая оболочка им больше не мешает: у каждой операции свой логический поток
 * и свой ящик (`docs/adr/0004_CONCURRENT_ADB_DISPATCHER_RU.md`). Гасит кнопки
 * только своя же незаконченная операция — показать два результата этот экран
 * пока не умеет, и это ограничение вида, а не возможности.
 */
@Composable
private fun FilesSection(files: AdbFileState, actions: FileActions) {
    val path = remember { mutableStateOf("") }
    val idle = files !is AdbFileState.Busy

    LabelledValue(label = stringResource(R.string.files_label), value = fileStateText(files))
    OutlinedTextField(
        value = path.value,
        onValueChange = { text -> path.value = text },
        label = { Text(stringResource(R.string.files_path_hint)) },
        singleLine = true,
        enabled = idle,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = { actions.onDescribe(path.value) }, enabled = idle && path.value.isNotBlank()) {
        Text(stringResource(R.string.files_describe))
    }
    Button(onClick = { actions.onRead(path.value) }, enabled = idle && path.value.isNotBlank()) {
        Text(stringResource(R.string.files_read))
    }
    Button(onClick = { actions.onReadToFile(path.value) }, enabled = idle && path.value.isNotBlank()) {
        Text(stringResource(R.string.files_read_to_file))
    }
    Button(onClick = { actions.onWriteFromFile(path.value) }, enabled = idle && path.value.isNotBlank()) {
        Text(stringResource(R.string.files_write_from_file))
    }
    Button(
        onClick = { actions.onWrite(path.value, SMALL_WRITE_BYTES) },
        enabled = idle && path.value.isNotBlank(),
    ) {
        Text(stringResource(R.string.files_write_small))
    }
    // Две кнопки Recovery не берут путь из поля: журнал текущей сессии один, и
    // дать выбрать другой значило бы позволить снять базу с файла, который
    // продолжением не бывает по определению.
    Button(onClick = actions.onRecoveryBaseline, enabled = idle) {
        Text(stringResource(R.string.recovery_baseline))
    }
    Button(onClick = actions.onRecoveryVerdict, enabled = idle) {
        Text(stringResource(R.string.recovery_verdict_read))
    }

    Button(
        onClick = { actions.onWrite(path.value, LARGE_WRITE_BYTES) },
        enabled = idle && path.value.isNotBlank(),
    ) {
        Text(stringResource(R.string.files_write_large))
    }
    Text(
        text = stringResource(R.string.files_note),
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        text = stringResource(R.string.files_write_note),
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun fileStateText(files: AdbFileState): String = when (files) {
    AdbFileState.None -> ""
    is AdbFileState.Busy -> stringResource(R.string.files_busy, files.path)
    is AdbFileState.Read ->
        pluralStringResource(R.plurals.files_read_done, files.bytes.toQuantity(), files.bytes, files.sha256)

    is AdbFileState.Written ->
        pluralStringResource(R.plurals.files_write_done, files.bytes.toQuantity(), files.bytes, files.sha256)

    is AdbFileState.WriteFailed -> writeFailedText(files)
    is AdbFileState.Failed -> stringResource(R.string.files_failed, files.reason)
    is AdbFileState.Described -> describedText(files)
    is AdbFileState.Verdict -> verdictText(files)
    is AdbFileState.Saved -> savedText(files)
    is AdbFileState.SaveFailed -> saveFailedText(files)
    is AdbFileState.SourceChanged -> stringResource(R.string.files_source_changed, files.detail)
}

/**
 * Сохранённый файл — и оговорка про атомарную замену.
 *
 * Оговорка стоит здесь, а не в справке: у SAF атомарной замены нет, и знать об
 * этом надо тому, кто прямо сейчас решает, полагаться ли на файл (`06` §7).
 */
@Composable
private fun savedText(state: AdbFileState.Saved): String {
    val head = stringResource(R.string.files_saved, state.bytes, state.destination, state.sha256)
    return if (state.atomic) head else head + "\n" + stringResource(R.string.files_saved_not_atomic)
}

/**
 * Неудачное сохранение — и главное про него: остался ли файл на месте.
 *
 * Недописанный файл там выглядит целым, и это опаснее самой неудачи: заметить
 * подмену будет уже нечем.
 */
@Composable
private fun saveFailedText(state: AdbFileState.SaveFailed): String {
    val head = stringResource(R.string.files_save_failed, state.reason)
    val fate = if (state.destinationRemoved) {
        R.string.files_save_removed
    } else {
        R.string.files_save_left_behind
    }
    return head + "\n" + stringResource(fate)
}

/**
 * Вердикт Recovery словами.
 *
 * `UNKNOWN` показывается как полноценный исход с причиной, а не как пустое
 * место: «нет записи» — это не успех и не провал, и подменять его одним из них
 * значило бы придумать вердикт (`03` §3).
 */
@Composable
private fun verdictText(state: AdbFileState.Verdict): String {
    val head = stringResource(
        when (state.verdict) {
            AdbRecoveryVerdict.SUCCESS -> R.string.recovery_verdict_success
            AdbRecoveryVerdict.FAILED -> R.string.recovery_verdict_failed
            AdbRecoveryVerdict.UNKNOWN -> R.string.recovery_verdict_unknown
        },
        state.detail,
    )
    return state.evidence?.let { line -> head + "\n" + stringResource(R.string.recovery_verdict_evidence, line) }
        ?: head
}

/**
 * Текст неудачной записи.
 *
 * Формулировка выбирается по состоянию назначения, а не по причине отказа:
 * оператору важнее всего, что теперь с файлом на устройстве.
 */
@Composable
private fun writeFailedText(files: AdbFileState.WriteFailed): String = stringResource(
    when (files.destination) {
        AdbSyncDestination.UNTOUCHED -> R.string.files_write_failed_untouched
        AdbSyncDestination.COMMITTED -> R.string.files_write_failed_committed
        AdbSyncDestination.UNKNOWN -> R.string.files_write_failed_unknown
    },
    files.reason,
    files.path,
)

@Composable
private fun describedText(files: AdbFileState.Described): String = when {
    !files.stat.exists -> stringResource(R.string.files_missing, files.path)
    files.stat.directory -> stringResource(R.string.files_directory, files.path)
    files.stat.regularFile ->
        pluralStringResource(R.plurals.files_file, files.stat.size.toQuantity(), files.path, files.stat.size)

    else ->
        pluralStringResource(R.plurals.files_object, files.stat.size.toQuantity(), files.path, files.stat.size)
}

/**
 * Число для выбора формы множественного числа.
 *
 * Формы выбираются целым числом, а размер файла — длинное. Значения больше
 * двух гигабайт прижимаются к пределу: во всех поддерживаемых языках такие
 * числа попадают в ту же форму, что и предел, а само число выводится
 * отдельным аргументом и остаётся точным.
 */
private fun Long.toQuantity(): Int = coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

/** Действия интерактивной оболочки. Собраны вместе, чтобы не плодить параметры. */
data class TerminalActions(
    val onStart: () -> Unit = {},
    val onSend: (String) -> Unit = {},
    val onInterrupt: () -> Unit = {},
    val onStop: () -> Unit = {},
)

/**
 * Интерактивная оболочка.
 *
 * Пока сессия жива, она занимает текущий production reader, поэтому одиночные
 * команды в это время недоступны — и об этом сказано на экране, а не оставлено
 * догадкам. Интерактивные записи при этом идут отдельным writer executor и не
 * блокируют UI.
 */
@Composable
internal fun TerminalSection(terminal: AdbTerminalState, actions: TerminalActions) {
    val input = remember { mutableStateOf("") }

    LabelledValue(
        label = stringResource(R.string.terminal_label),
        value = when {
            terminal.output.isNotBlank() -> terminal.output
            terminal.ended != null -> stringResource(R.string.terminal_ended, terminal.ended)
            terminal.closing -> stringResource(R.string.terminal_closing)
            terminal.active && !terminal.ready -> stringResource(R.string.terminal_opening)
            else -> stringResource(R.string.terminal_waiting)
        },
    )
    if (terminal.active) {
        OutlinedTextField(
            value = input.value,
            onValueChange = { text -> input.value = text },
            label = { Text(stringResource(R.string.terminal_input_hint)) },
            singleLine = true,
            enabled = terminal.ready,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                actions.onSend(input.value)
                input.value = ""
            },
            enabled = terminal.ready && input.value.isNotBlank(),
        ) {
            Text(stringResource(R.string.terminal_send))
        }
        Button(onClick = actions.onInterrupt, enabled = terminal.ready) {
            Text(stringResource(R.string.terminal_interrupt))
        }
        Button(onClick = actions.onStop, enabled = !terminal.closing) {
            Text(stringResource(R.string.terminal_stop))
        }
    } else {
        Button(onClick = actions.onStart) {
            Text(stringResource(R.string.terminal_start))
        }
    }
    Text(
        text = stringResource(R.string.terminal_note),
        style = MaterialTheme.typography.bodySmall,
    )
}

/**
 * Вызов произвольного сервиса ADB.
 *
 * Имя сервиса вводится целиком — `shell:ls`, `sync:`, `exec:id`, `track-devices`.
 * Списка нет намеренно (`01` §3): какие сервисы существуют, знает устройство.
 *
 * Набранный здесь `reboot:` ведёт себя как кнопка перезагрузки: устройство
 * уходит с шины, не ответив, и показывать это отказом было бы неправдой.
 */
@Composable
private fun RawServiceSection(panel: RawServicePanel) {
    val service = remember { mutableStateOf("") }
    val running = panel.state is AdbRawServiceState.Running

    LabelledValue(
        label = stringResource(R.string.raw_service_label),
        value = rawServiceText(panel.state),
    )
    OutlinedTextField(
        value = service.value,
        onValueChange = { text -> service.value = text },
        label = { Text(stringResource(R.string.raw_service_hint)) },
        singleLine = true,
        enabled = !running,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = { panel.onCall(service.value) },
        enabled = !running && service.value.isNotBlank(),
    ) {
        Text(stringResource(R.string.raw_service_run))
    }
    (panel.state as? AdbRawServiceState.Finished)?.takeIf { it.text.isNotEmpty() }?.let { done ->
        Text(text = done.text, style = MaterialTheme.typography.bodySmall)
    }
    Text(
        text = stringResource(R.string.raw_service_note),
        style = MaterialTheme.typography.bodySmall,
    )
}

/**
 * Проброс портов.
 *
 * Порт разбирается здесь, а не в контроллере: пустое или нечисловое поле — это
 * ещё не набранное значение, а не отказ, и заводить ради него состояние ошибки
 * значило бы ругаться на человека, который ещё печатает.
 */
@Composable
private fun ForwardSection(panel: ForwardPanel) {
    val port = remember { mutableStateOf("") }
    val address = remember { mutableStateOf("") }

    LabelledValue(
        label = stringResource(R.string.forward_label),
        value = panel.state.forwards.size.takeIf { it > 0 }?.toString()
            ?: stringResource(R.string.forward_none),
    )
    OutlinedTextField(
        value = port.value,
        onValueChange = { text -> port.value = text },
        label = { Text(stringResource(R.string.forward_port_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = address.value,
        onValueChange = { text -> address.value = text },
        label = { Text(stringResource(R.string.forward_address_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = { panel.onAdd(port.value.trim().toIntOrNull() ?: 0, address.value) },
        enabled = address.value.isNotBlank(),
    ) {
        Text(stringResource(R.string.forward_add))
    }
    panel.state.forwards.forEach { entry -> ForwardRow(entry = entry, onRemove = panel.onRemove) }
    panel.state.failure?.let { failure ->
        Text(
            text = stringResource(R.string.forward_failed, failure),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Text(
        text = stringResource(R.string.forward_note, AdbForwardController.MAX_CONNECTIONS),
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun ForwardRow(entry: AdbForwardEntry, onRemove: (Int) -> Unit) {
    Text(
        text = stringResource(
            R.string.forward_entry,
            entry.localPort,
            entry.address,
            entry.live,
            // Принятое отдельно от обслуженного: «ноль обслужено» одинаково
            // читается и как «клиент не приходил», и как «пришёл и не дошёл».
            entry.accepted,
            entry.served,
        ),
        style = MaterialTheme.typography.bodySmall,
    )
    entry.lastEnd?.let { end ->
        Text(
            text = stringResource(R.string.forward_last_end, end),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Button(onClick = { onRemove(entry.localPort) }) {
        Text(stringResource(R.string.forward_remove))
    }
}

/**
 * Обратный проброс.
 *
 * Отдельной секцией, а не вкладкой к пробросу, хотя названия похожи: здесь
 * слушает устройство и само приводит соединения, а там слушаем мы. Свести их в
 * одну секцию значило бы намекнуть, что это две настройки одного, а это два
 * разных механизма.
 */
@Composable
private fun ReverseSection(panel: ReversePanel) {
    val onDevice = remember { mutableStateOf("") }
    val onHost = remember { mutableStateOf("") }

    LabelledValue(
        label = stringResource(R.string.reverse_label),
        value = panel.state.reverses.size.takeIf { it > 0 }?.toString()
            ?: stringResource(R.string.reverse_none),
    )
    OutlinedTextField(
        value = onDevice.value,
        onValueChange = { text -> onDevice.value = text },
        label = { Text(stringResource(R.string.reverse_device_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = onHost.value,
        onValueChange = { text -> onHost.value = text },
        label = { Text(stringResource(R.string.reverse_host_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = { panel.onAdd(onDevice.value, onHost.value) },
        enabled = onDevice.value.isNotBlank() && onHost.value.isNotBlank(),
    ) {
        Text(stringResource(R.string.reverse_add))
    }
    panel.state.reverses.forEach { entry -> ReverseRow(entry) }
    ReverseFooter(panel)
}

@Composable
private fun ReverseRow(entry: AdbReverseEntry) {
    Text(
        text = stringResource(
            R.string.reverse_entry,
            entry.onDevice,
            entry.onHost,
            entry.assigned,
            entry.brought,
        ),
        style = MaterialTheme.typography.bodySmall,
    )
    entry.lastEnd?.let { end ->
        Text(
            text = stringResource(R.string.reverse_last_end, end),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Ответ устройства и две кнопки: вынесено, чтобы секция не разрослась. */
@Composable
private fun ReverseFooter(panel: ReversePanel) {
    Button(onClick = panel.onRefresh) {
        Text(stringResource(R.string.reverse_refresh))
    }
    panel.state.listing?.let { listing ->
        Text(
            text = stringResource(R.string.reverse_listing, listing),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Button(onClick = panel.onRemoveAll) {
        Text(stringResource(R.string.reverse_remove_all))
    }
    panel.state.failure?.let { failure ->
        Text(
            text = stringResource(R.string.reverse_failed, failure),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Text(
        text = stringResource(R.string.reverse_note),
        style = MaterialTheme.typography.bodySmall,
    )
}

/** Размер ответа называется всегда: он известен даже тогда, когда текст бессмыслен. */
@Composable
private fun rawServiceText(state: AdbRawServiceState): String = when (state) {
    AdbRawServiceState.None -> stringResource(R.string.raw_service_none)
    is AdbRawServiceState.Running -> stringResource(R.string.raw_service_running, state.service)
    is AdbRawServiceState.Finished ->
        stringResource(R.string.raw_service_finished, state.service, state.bytes)

    is AdbRawServiceState.OneWay ->
        stringResource(R.string.raw_service_one_way, state.service, state.evidence)

    is AdbRawServiceState.Failed ->
        stringResource(R.string.raw_service_failed, state.service, state.reason)
}

/**
 * Перезагрузка устройства.
 *
 * Успех здесь выглядит как обрыв: устройство уходит с шины, и подключение
 * закрывается. Поэтому состояние запроса показывается отдельно от состояния
 * соединения — иначе «отключилось» затёрло бы «перезагружается».
 *
 * Цель — свободная строка. Списка целей нет намеренно (`01` §3): какие из них
 * существуют, знает устройство, и его отказ — это ответ, а не наша ошибка.
 */
@Composable
private fun RebootSection(panel: RebootPanel) {
    val target = remember { mutableStateOf("") }
    val running = panel.state is AdbRebootState.Running

    LabelledValue(label = stringResource(R.string.reboot_label), value = rebootText(panel.state))
    OutlinedTextField(
        value = target.value,
        onValueChange = { text -> target.value = text },
        label = { Text(stringResource(R.string.reboot_target_hint)) },
        singleLine = true,
        enabled = !running,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = { panel.onReboot(target.value) }, enabled = !running) {
        Text(stringResource(R.string.reboot_run))
    }
    Text(
        text = stringResource(R.string.reboot_note),
        style = MaterialTheme.typography.bodySmall,
    )
}

/**
 * Что показать про перезагрузку.
 *
 * У отказа состояние устройства выносится вперёд: оператору сначала важно,
 * тронуто ли устройство, и только потом — почему не получилось.
 */
@Composable
private fun rebootText(reboot: AdbRebootState): String = when (reboot) {
    AdbRebootState.None -> stringResource(R.string.reboot_none)
    is AdbRebootState.Running -> stringResource(R.string.reboot_running, reboot.service())
    is AdbRebootState.Accepted -> stringResource(R.string.reboot_accepted, reboot.service)
    is AdbRebootState.Failed -> stringResource(
        R.string.reboot_failed,
        reboot.device.name,
        reboot.reason,
    )
}

/** Пустая цель — обычная перезагрузка; показываем это словом, а не пустотой. */
@Composable
private fun AdbRebootState.Running.service(): String =
    target.ifBlank { stringResource(R.string.reboot_target_system) }

/**
 * Неинтерактивная оболочка.
 *
 * Показывается только у подключённого устройства: команда без соединения
 * никуда не уйдёт, а кнопка, которая ничего не делает, врёт о состоянии.
 *
 * Живая интерактивная оболочка команде больше не мешает: у неё свой логический
 * поток. Гасит кнопку только предыдущая незаконченная команда — на экране один
 * слот результата.
 */
/**
 * Недавние команды под полем ввода.
 *
 * Нажатие **подставляет** команду в поле, а не отправляет её. Разница
 * существенная: в этом поле бывают и мутирующие команды, и отправка по
 * случайному касанию была бы тем самым действием без подтверждения, которого
 * здесь быть не должно.
 */
@Composable
private fun CommandHistoryRow(entries: List<String>, enabled: Boolean, onPick: (String) -> Unit) {
    if (entries.isEmpty()) return
    Text(
        text = stringResource(R.string.shell_history),
        style = MaterialTheme.typography.labelMedium,
    )
    entries.forEach { entry ->
        TextButton(onClick = { onPick(entry) }, enabled = enabled) {
            Text(entry)
        }
    }
}

@Composable
private fun ShellSection(
    command: AdbCommandState,
    onRunCommand: (String) -> Unit,
) {
    val input = remember { mutableStateOf("") }
    val history = remember { CommandHistory() }
    // Список перерисовывается по счётчику, а не по самой истории: она
    // изменяемая, и Compose о её правках не узнаёт. Счётчик — то, что меняется
    // при каждой отправке, и этого достаточно.
    val remembered = remember { mutableStateOf(0) }
    val running = command is AdbCommandState.Running

    OutlinedTextField(
        value = input.value,
        onValueChange = { text -> input.value = text },
        label = { Text(stringResource(R.string.shell_label)) },
        placeholder = { Text(stringResource(R.string.shell_hint)) },
        singleLine = true,
        enabled = !running,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = {
            history.add(input.value)
            remembered.value += 1
            onRunCommand(input.value)
        },
        enabled = !running && input.value.isNotBlank(),
    ) {
        Text(stringResource(R.string.shell_run))
    }
    CommandHistoryRow(
        entries = remember(remembered.value) { history.entries() },
        enabled = !running,
        onPick = { chosen -> input.value = chosen },
    )
    ShellOutcome(command)

}

private fun AdbLinkState.generationOrNull(): SessionGeneration? = when (this) {
    AdbLinkState.Idle -> null
    is AdbLinkState.Connecting -> generation
    is AdbLinkState.WaitingForAuthorization -> generation
    is AdbLinkState.Connected -> generation
    is AdbLinkState.Failed -> generation
}

@Composable
private fun adbLinkText(state: AdbLinkState?): String = when (state) {
    null, AdbLinkState.Idle -> stringResource(R.string.adb_state_idle)
    is AdbLinkState.Connecting -> stringResource(R.string.adb_state_connecting)
    is AdbLinkState.WaitingForAuthorization -> stringResource(R.string.adb_state_waiting)
    is AdbLinkState.Connected ->
        stringResource(R.string.adb_state_connected, localizedPeerMode(state.peerMode))

    is AdbLinkState.Failed ->
        stringResource(R.string.adb_state_failed, state.reason.name, state.detail)
}

@Composable
private fun localizedPeerMode(mode: AdbPeerMode): String = stringResource(
    when (mode) {
        AdbPeerMode.DEVICE -> R.string.peer_mode_device
        AdbPeerMode.RECOVERY -> R.string.peer_mode_recovery
        AdbPeerMode.SIDELOAD -> R.string.peer_mode_sideload
        AdbPeerMode.UNKNOWN -> R.string.peer_mode_unknown
    },
)

/** Исход разовой команды: идёт, кончилась, не вышла. */
@Composable
private fun ShellOutcome(command: AdbCommandState) {
    when (command) {
        AdbCommandState.None -> Unit
        is AdbCommandState.Running -> LabelledValue(
            label = stringResource(R.string.shell_label),
            value = stringResource(R.string.shell_running, command.command),
        )

        is AdbCommandState.Finished -> {
            LabelledValue(
                label = command.command,
                value = command.output.ifBlank { stringResource(R.string.shell_empty_output) },
            )
            if (command.errorOutput.isNotBlank()) {
                LabelledValue(
                    label = stringResource(R.string.shell_stderr),
                    value = command.errorOutput,
                )
            }
            Text(
                text = command.exitCode?.let { code -> stringResource(R.string.shell_exit_code, code) }
                    ?: stringResource(R.string.shell_exit_unknown),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        is AdbCommandState.Failed -> LabelledValue(
            label = command.command,
            value = stringResource(R.string.shell_failed, command.reason),
        )
    }
    Text(
        text = stringResource(R.string.shell_note),
        style = MaterialTheme.typography.bodySmall,
    )
}
