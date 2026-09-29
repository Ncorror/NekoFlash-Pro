package io.github.ncorror.nekoflash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.ncorror.nekoflash.R
import io.github.ncorror.nekoflash.adb.AdbCommandState
import io.github.ncorror.nekoflash.adb.AdbFileState
import io.github.ncorror.nekoflash.protocol.adb.AdbRecoveryInstall
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
import io.github.ncorror.nekoflash.adb.TerminalKey
import io.github.ncorror.nekoflash.protocol.adb.AdbShellMode
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
    files: AdbFileState,
    fileActions: FileActions,
    onAdbConnect: () -> Unit,
    onAdbDisconnect: () -> Unit,
    reboot: RebootPanel,
    rawService: RawServicePanel,
    forward: ForwardPanel,
    reverse: ReversePanel,
) {
    val linkForThisSession = adbLink.takeIf { it.generationOrNull() == session.generation }
    val connected = linkForThisSession is AdbLinkState.Connected
    val busy = linkForThisSession is AdbLinkState.Connecting ||
        linkForThisSession is AdbLinkState.WaitingForAuthorization

    ToolCard(title = stringResource(R.string.adb_state_label)) {
        StatusLine(adbLinkText(linkForThisSession))
        Button(onClick = if (connected) onAdbDisconnect else onAdbConnect, enabled = !busy) {
            Text(stringResource(if (connected) R.string.adb_disconnect else R.string.adb_connect))
        }
        if (linkForThisSession is AdbLinkState.Connected) {
            NoteText(
                stringResource(R.string.adb_features_label) + ": " +
                    linkForThisSession.features.sorted().joinToString(", ").ifEmpty {
                        stringResource(R.string.adb_features_none)
                    },
            )
        } else {
            NoteText(stringResource(R.string.adb_connect_hint))
        }
    }
    // Разовая команда, Sideload и журнал Recovery живут в своих разделах:
    // команда — в терминале, остальное — в Recovery. Здесь они были вторым
    // входом в то же самое.
    if (connected) {
        ToolCard(title = stringResource(R.string.files_label)) { FilesSection(files = files, actions = fileActions) }
        ToolCard(title = stringResource(R.string.reboot_label)) { RebootSection(panel = reboot) }
        ToolCard(title = stringResource(R.string.raw_service_label)) { RawServiceSection(panel = rawService) }
        ToolCard(title = stringResource(R.string.forward_label)) { ForwardSection(panel = forward) }
        ToolCard(title = stringResource(R.string.reverse_label)) { ReverseSection(panel = reverse) }
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
    val ready = idle && path.value.isNotBlank()

    if (!files.aboutRecoveryLog()) StatusLine(fileStateText(files))
    OutlinedTextField(
        value = path.value,
        onValueChange = { text -> path.value = text },
        label = { Text(stringResource(R.string.files_path_hint)) },
        singleLine = true,
        enabled = idle,
        modifier = Modifier.fillMaxWidth(),
    )
    ActionRow {
        OutlinedButton(onClick = { actions.onDescribe(path.value) }, enabled = ready) {
            Text(stringResource(R.string.files_describe))
        }
        Button(onClick = { actions.onReadToFile(path.value) }, enabled = ready) {
            Text(stringResource(R.string.files_read_to_file))
        }
        Button(onClick = { actions.onWriteFromFile(path.value) }, enabled = ready) {
            Text(stringResource(R.string.files_write_from_file))
        }
    }
}

/**
 * Журнал Recovery — задача раздела Recovery: база, установка, вердикт идут
 * там подряд. Кнопки и исход показываются там, а не среди файлов.
 */
@Composable
internal fun RecoveryLogSection(files: AdbFileState, actions: FileActions, enabled: Boolean) {
    val idle = files !is AdbFileState.Busy
    if (files.aboutRecoveryLog()) StatusLine(fileStateText(files))
    NoteText(stringResource(if (enabled) R.string.recovery_log_note else R.string.recovery_log_needs_adb))
    ActionRow {
        OutlinedButton(onClick = actions.onRecoveryBaseline, enabled = enabled && idle) {
            Text(stringResource(R.string.recovery_baseline))
        }
        Button(onClick = actions.onRecoveryVerdict, enabled = enabled && idle) {
            Text(stringResource(R.string.recovery_verdict_read))
        }
    }
}

/** Относится ли состояние к журналу Recovery, а не к файлу из поля пути. */
private fun AdbFileState.aboutRecoveryLog(): Boolean = when (this) {
    is AdbFileState.Verdict -> true
    is AdbFileState.Busy -> path == AdbRecoveryInstall.PRIMARY_PATH
    is AdbFileState.Failed -> path == AdbRecoveryInstall.PRIMARY_PATH
    else -> false
}

@Composable
internal fun fileStateText(files: AdbFileState): String = when (files) {
    AdbFileState.None -> ""
    is AdbFileState.Busy -> stringResource(R.string.files_busy, files.path)
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
    /** Экран измерил, сколько столбцов и строк в него помещается. */
    val onResize: (Int, Int) -> Unit = { _, _ -> },
    val onKey: (TerminalKey) -> Unit = {},
    /** Текст без Enter. */
    val onType: (String) -> Unit = {},
    val onAddTab: () -> Unit = {},
    val onSelectTab: (Int) -> Unit = {},
    val onCloseTab: (Int) -> Unit = {},
)

/** Вкладки терминала: у каждой своя оболочка. */
data class TerminalPanel(
    val tabs: List<AdbTerminalState> = listOf(AdbTerminalState()),
    val selected: Int = 0,
) {
    val current: AdbTerminalState get() = tabs.getOrElse(selected) { AdbTerminalState() }
}

/** Режим живой оболочки — как его назвал бы настольный `adb shell`. */
@Composable
internal fun terminalModeText(mode: AdbShellMode): String = stringResource(
    when (mode) {
        AdbShellMode.PTY -> R.string.command_terminal_mode_pty
        AdbShellMode.RAW -> R.string.terminal_mode_raw
        AdbShellMode.LEGACY -> R.string.terminal_mode_legacy
    },
)

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

    StatusLine(rawServiceText(panel.state))
    FieldWithAction(
        value = service.value,
        onValueChange = { text -> service.value = text },
        label = stringResource(R.string.raw_service_hint),
        onSubmit = { panel.onCall(service.value) },
        enabled = !running && service.value.isNotBlank(),
    ) {
        Button(onClick = { panel.onCall(service.value) }, enabled = !running && service.value.isNotBlank()) {
            Text(stringResource(R.string.raw_service_run))
        }
    }
    (panel.state as? AdbRawServiceState.Finished)?.takeIf { it.text.isNotEmpty() }?.let { done ->
        Text(text = done.text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
    NoteText(stringResource(R.string.raw_service_note))
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

    if (panel.state.forwards.isEmpty()) StatusLine(stringResource(R.string.forward_none))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = port.value,
            onValueChange = { text -> port.value = text },
            label = { Text(stringResource(R.string.forward_port_hint)) },
            singleLine = true,
            modifier = Modifier.weight(PORT_WEIGHT),
        )
        OutlinedTextField(
            value = address.value,
            onValueChange = { text -> address.value = text },
            label = { Text(stringResource(R.string.forward_address_hint)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
    }
    Button(
        onClick = { panel.onAdd(port.value.trim().toIntOrNull() ?: 0, address.value) },
        enabled = address.value.isNotBlank(),
    ) {
        Text(stringResource(R.string.forward_add))
    }
    panel.state.forwards.forEach { entry -> ForwardRow(entry = entry, onRemove = panel.onRemove) }
    panel.state.failure?.let { failure -> StatusLine(stringResource(R.string.forward_failed, failure)) }
    NoteText(stringResource(R.string.forward_note, AdbForwardController.MAX_CONNECTIONS))
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
    OutlinedButton(onClick = { onRemove(entry.localPort) }) {
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

    if (panel.state.reverses.isEmpty()) StatusLine(stringResource(R.string.reverse_none))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = onDevice.value,
            onValueChange = { text -> onDevice.value = text },
            label = { Text(stringResource(R.string.reverse_device_hint)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = onHost.value,
            onValueChange = { text -> onHost.value = text },
            label = { Text(stringResource(R.string.reverse_host_hint)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
    }
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
    ActionRow {
        OutlinedButton(onClick = panel.onRefresh) {
            Text(stringResource(R.string.reverse_refresh))
        }
        OutlinedButton(onClick = panel.onRemoveAll) {
            Text(stringResource(R.string.reverse_remove_all))
        }
    }
    panel.state.listing?.let { listing -> StatusLine(stringResource(R.string.reverse_listing, listing)) }
    panel.state.failure?.let { failure -> StatusLine(stringResource(R.string.reverse_failed, failure)) }
    NoteText(stringResource(R.string.reverse_note))
}

/** Размер ответа называется всегда: он известен даже тогда, когда текст бессмыслен. */
@Composable
internal fun rawServiceText(state: AdbRawServiceState): String = when (state) {
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

    StatusLine(rebootText(panel.state))
    // Частые цели — одним касанием; поле ниже принимает любую, списка разрешённых нет.
    ActionRow {
        OutlinedButton(onClick = { panel.onReboot("") }, enabled = !running) {
            Text(stringResource(R.string.reboot_target_system))
        }
        REBOOT_TARGETS.forEach { name ->
            OutlinedButton(onClick = { panel.onReboot(name) }, enabled = !running) { Text(name) }
        }
    }
    FieldWithAction(
        value = target.value,
        onValueChange = { text -> target.value = text },
        label = stringResource(R.string.reboot_target_hint),
        onSubmit = { panel.onReboot(target.value) },
        enabled = !running,
    ) {
        Button(onClick = { panel.onReboot(target.value) }, enabled = !running) {
            Text(stringResource(R.string.reboot_run))
        }
    }
    NoteText(stringResource(R.string.reboot_note))
}

/**
 * Что показать про перезагрузку.
 *
 * У отказа состояние устройства выносится вперёд: оператору сначала важно,
 * тронуто ли устройство, и только потом — почему не получилось.
 */
@Composable
internal fun rebootText(reboot: AdbRebootState): String = when (reboot) {
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

    is AdbLinkState.Failed -> stringResource(
        if (state.afterConnect) R.string.adb_state_lost else R.string.adb_state_failed,
        state.reason.name,
        state.detail,
    )
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
internal fun ShellOutcome(command: AdbCommandState) {
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

/** Цели `reboot:`, которые набирают чаще всего; остальное — полем. */
private val REBOOT_TARGETS = listOf("bootloader", "recovery", "fastboot", "sideload")

/** Порт короче адреса: поле под него уже. */
private const val PORT_WEIGHT = 0.4f
