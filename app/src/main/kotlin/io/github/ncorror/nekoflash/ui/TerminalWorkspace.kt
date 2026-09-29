package io.github.ncorror.nekoflash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ncorror.nekoflash.R
import io.github.ncorror.nekoflash.adb.AdbLinkState
import io.github.ncorror.nekoflash.adb.AdbTerminalState
import io.github.ncorror.nekoflash.adb.TerminalKey
import io.github.ncorror.nekoflash.protocol.adb.AdbShellMode

/**
 * Терминал как в NetHunter: экран во всю высоту, значки вместо подписей,
 * ввод прямо в экран и ряд клавиш, которых нет на экранной клавиатуре.
 *
 * Оболочка открывается сама, когда ADB подключён и вкладка ещё не открывалась:
 * терминал для того и открывают. Закрытую или оборвавшуюся вкладку заново не
 * открывает — это делает ▶.
 */
@Composable
internal fun TerminalWorkspace(
    adbLink: AdbLinkState,
    terminal: TerminalPanel,
    actions: TerminalActions,
    desktopCommand: DesktopCommandPanel,
    focused: Boolean,
    onToggleFocus: () -> Unit,
    showCommands: Boolean,
    onToggleCommands: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val connected = adbLink is AdbLinkState.Connected
    val current = terminal.current
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val openKeyboard = {
        runCatching { focus.requestFocus() }
        keyboard?.show()
        Unit
    }
    LaunchedEffect(connected, terminal.selected, current.active, current.ended) {
        if (connected && !current.active && current.ended == null) actions.onStart()
    }
    Column(modifier = modifier.background(TerminalPalette.background)) {
        TerminalToolbar(
            terminal = terminal,
            actions = actions,
            connected = connected,
            onKeyboard = openKeyboard,
            view = TerminalView(focused, onToggleFocus, showCommands, onToggleCommands),
        )
        if (showCommands) {
            // Команды adb и fastboot — на обычном фоне приложения: это форма, а не экран оболочки.
            Surface(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                    DesktopCommandSection(panel = desktopCommand)
                }
            }
        } else {
            TerminalSection(
                terminal = current,
                actions = actions,
                connected = connected,
                focus = focus,
                onTap = openKeyboard,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Режимы вида терминала: во весь экран и панель команд. */
private class TerminalView(
    val focused: Boolean,
    val onToggleFocus: () -> Unit,
    val showCommands: Boolean,
    val onToggleCommands: () -> Unit,
)

/** Вкладки слева, действия значками справа. */
@Composable
private fun TerminalToolbar(
    terminal: TerminalPanel,
    actions: TerminalActions,
    connected: Boolean,
    onKeyboard: () -> Unit,
    view: TerminalView,
) {
    val current = terminal.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().background(TerminalPalette.chrome).padding(start = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
        ) {
            terminal.tabs.forEachIndexed { index, tab ->
                TerminalTab(index, tab, index == terminal.selected, terminal.tabs.size > 1, actions)
            }
            ToolbarIcon(R.drawable.ic_add, stringResource(R.string.terminal_new_tab), onClick = actions.onAddTab)
        }
        ToolbarIcon(R.drawable.ic_keyboard, stringResource(R.string.terminal_keyboard), enabled = current.ready) {
            onKeyboard()
        }
        if (current.active) {
            ToolbarIcon(R.drawable.ic_stop, stringResource(R.string.terminal_stop), enabled = !current.closing) {
                actions.onStop()
            }
        } else {
            ToolbarIcon(R.drawable.ic_play, stringResource(R.string.terminal_start), enabled = connected) {
                actions.onStart()
            }
        }
        ToolbarIcon(
            icon = if (view.showCommands) R.drawable.ic_terminal else R.drawable.ic_code,
            description = stringResource(
                if (view.showCommands) R.string.terminal_shell else R.string.terminal_commands,
            ),
            onClick = view.onToggleCommands,
        )
        ToolbarIcon(
            icon = if (view.focused) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen,
            description = stringResource(
                if (view.focused) R.string.terminal_exit_fullscreen else R.string.terminal_fullscreen,
            ),
            onClick = view.onToggleFocus,
        )
    }
}

@Composable
private fun ToolbarIcon(icon: Int, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            painter = painterResource(icon),
            contentDescription = description,
            tint = if (enabled) TerminalPalette.foreground else TerminalPalette.muted,
        )
    }
}

/** Вкладка: номер и имя, у `-T` и `-x` — с ключом; у открытой — зелёная метка, у закрытой — серая. */
@Composable
private fun TerminalTab(
    index: Int,
    tab: AdbTerminalState,
    selected: Boolean,
    closable: Boolean,
    actions: TerminalActions,
) {
    Surface(
        color = if (selected) TerminalPalette.background else Color.Transparent,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.clickable(role = Role.Tab) { actions.onSelectTab(index) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp)) {
            Box(
                modifier = Modifier
                    .padding(end = 6.dp)
                    .size(7.dp)
                    .background(
                        color = if (tab.active) TerminalPalette.live else TerminalPalette.muted,
                        shape = CircleShape,
                    ),
            )
            Text(
                text = stringResource(
                    R.string.terminal_tab_title,
                    index + 1,
                    tab.title ?: stringResource(
                        when (tab.mode) {
                            AdbShellMode.PTY -> R.string.terminal_tab_shell
                            AdbShellMode.RAW -> R.string.terminal_tab_raw
                            AdbShellMode.LEGACY -> R.string.terminal_tab_legacy
                        },
                    ),
                ),
                color = if (selected) TerminalPalette.foreground else TerminalPalette.muted,
                fontFamily = FontFamily.Monospace,
                fontSize = TAB_TEXT,
                modifier = Modifier.padding(end = if (closable) 0.dp else 10.dp, top = 10.dp, bottom = 10.dp),
            )
            if (closable) {
                IconButton(onClick = { actions.onCloseTab(index) }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.terminal_close_tab_label, index + 1),
                        tint = TerminalPalette.muted,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/**
 * Экран и клавиши одной вкладки.
 *
 * Ctrl и Alt залипают на одну клавишу, как в Termux и NetHunter: нажал Ctrl,
 * потом `c` — ушёл `^C`. Набранное уходит в оболочку сразу, по символу: строку
 * редактирует сама оболочка, а не поле ввода приложения.
 */
@Composable
internal fun TerminalSection(
    terminal: AdbTerminalState,
    actions: TerminalActions,
    connected: Boolean,
    focus: FocusRequester,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    val type: (String) -> Unit = { text ->
        actions.onType(withModifiers(text, ctrl, alt))
        ctrl = false
        alt = false
    }
    val press: (TerminalKey) -> Unit = { key ->
        actions.onKey(key)
        ctrl = false
        alt = false
    }
    Column(modifier = modifier) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            TerminalScreen(
                screen = terminal.screen,
                placeholder = terminalPlaceholder(terminal, connected),
                showCursor = terminal.ready,
                onResize = actions.onResize,
                onTap = onTap,
                modifier = Modifier.fillMaxSize(),
            )
            TerminalKeyboardInput(focus = focus, enabled = terminal.ready, onText = type, onKey = press)
        }
        TerminalExtraKeys(
            enabled = terminal.ready,
            modifiers = StickyModifiers(ctrl, alt, onCtrl = { ctrl = !ctrl }, onAlt = { alt = !alt }),
            onKey = press,
            onText = type,
            onInterrupt = actions.onInterrupt,
        )
    }
}

@Composable
private fun terminalPlaceholder(terminal: AdbTerminalState, connected: Boolean): String = when {
    terminal.ended != null -> stringResource(R.string.terminal_ended, terminal.ended)
    terminal.closing -> stringResource(R.string.terminal_closing)
    terminal.active && !terminal.ready -> stringResource(R.string.terminal_opening)
    !connected -> stringResource(R.string.terminal_needs_connection)
    else -> stringResource(R.string.terminal_waiting)
}

/**
 * Невидимое поле, в которое печатает клавиатура.
 *
 * В поле всегда лежит один пробел: стёрли его — ушёл Backspace (`DEL`),
 * дописали за ним — ушли дописанные символы, Enter — `CR`, как у терминалов.
 * Тип «пароль» — чтобы клавиатура не подсказывала и не правила слова: правку
 * задним числом оболочка приняла бы за набор. Стрелки, Esc и Ctrl+буква с
 * аппаратной клавиатуры перехватываются до поля.
 */
@Composable
private fun TerminalKeyboardInput(
    focus: FocusRequester,
    enabled: Boolean,
    onText: (String) -> Unit,
    onKey: (TerminalKey) -> Unit,
) {
    var value by remember { mutableStateOf(EMPTY_INPUT) }
    BasicTextField(
        value = value,
        onValueChange = { next ->
            if (enabled) typedText(next.text)?.let(onText)
            value = EMPTY_INPUT
        },
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.None,
        ),
        modifier = Modifier
            .size(1.dp)
            .alpha(0f)
            .focusRequester(focus)
            .onPreviewKeyEvent { event -> enabled && hardwareKey(event, onText, onKey) },
    )
}

/** Аппаратная клавиша, которой нет в тексте поля; `true` — обработана. */
private fun hardwareKey(event: KeyEvent, onText: (String) -> Unit, onKey: (TerminalKey) -> Unit): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val key = HARDWARE_KEYS[event.key]
    val letter = event.nativeKeyEvent.getUnicodeChar(0).toChar()
    return when {
        key != null -> {
            onKey(key)
            true
        }

        event.isCtrlPressed && letter in 'a'..'z' -> {
            onText(controlOf(letter).toString())
            true
        }

        else -> false
    }
}

/** Что набрано относительно пробела-заглушки; `null` — ничего. */
internal fun typedText(text: String): String? = when {
    text.length < INPUT_SENTINEL.length -> DEL.toString().repeat(INPUT_SENTINEL.length - text.length)
    text.startsWith(INPUT_SENTINEL) -> text.substring(INPUT_SENTINEL.length).replace('\n', '\r').ifEmpty { null }
    else -> text.replace('\n', '\r')
}

/** Залипшие Ctrl и Alt применяются к первому символу набранного. */
internal fun withModifiers(text: String, ctrl: Boolean, alt: Boolean): String {
    if (text.isEmpty()) return text
    val first = if (ctrl) controlOf(text.first()) else text.first()
    return (if (alt) ESC else "") + first + text.substring(1)
}

/** Ctrl+символ как у терминалов: `a`–`z` и `@[\]^_` — в 0–31, `?` — в DEL. */
internal fun controlOf(char: Char): Char = when (val upper = char.uppercaseChar()) {
    in '@'..'_' -> (upper.code - '@'.code).toChar()
    ' ' -> 0.toChar()
    '?' -> DEL
    else -> char
}

/** Ctrl и Alt: состояние и переключатели. */
private class StickyModifiers(
    val ctrl: Boolean,
    val alt: Boolean,
    val onCtrl: () -> Unit,
    val onAlt: () -> Unit,
)

/**
 * Ряд клавиш как в Termux и NetHunter: два ряда по восемь.
 *
 * Слева то, чего нет на клавиатуре телефона (Esc, Tab, Ctrl, Alt), посередине
 * стрелки крестом, справа страницы и частые символы оболочки.
 */
@Composable
private fun TerminalExtraKeys(
    enabled: Boolean,
    modifiers: StickyModifiers,
    onKey: (TerminalKey) -> Unit,
    onText: (String) -> Unit,
    onInterrupt: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().background(TerminalPalette.chrome)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            ExtraKey("ESC", R.string.terminal_key_escape, enabled) { onKey(TerminalKey.ESCAPE) }
            ExtraKey("/", null, enabled) { onText("/") }
            ExtraKey("-", null, enabled) { onText("-") }
            ExtraKey("HOME", R.string.terminal_key_home, enabled) { onKey(TerminalKey.HOME) }
            ExtraKey("↑", R.string.terminal_key_up, enabled) { onKey(TerminalKey.UP) }
            ExtraKey("END", R.string.terminal_key_end, enabled) { onKey(TerminalKey.END) }
            ExtraKey("PGUP", R.string.terminal_key_page_up, enabled) { onKey(TerminalKey.PAGE_UP) }
            ExtraKey("^C", R.string.terminal_interrupt, enabled, onClick = onInterrupt)
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            ExtraKey("TAB", R.string.terminal_key_tab, enabled) { onKey(TerminalKey.TAB) }
            ExtraKey("CTRL", R.string.terminal_key_ctrl, enabled, modifiers.ctrl, modifiers.onCtrl)
            ExtraKey("ALT", R.string.terminal_key_alt, enabled, modifiers.alt, modifiers.onAlt)
            ExtraKey("←", R.string.terminal_key_left, enabled) { onKey(TerminalKey.LEFT) }
            ExtraKey("↓", R.string.terminal_key_down, enabled) { onKey(TerminalKey.DOWN) }
            ExtraKey("→", R.string.terminal_key_right, enabled) { onKey(TerminalKey.RIGHT) }
            ExtraKey("PGDN", R.string.terminal_key_page_down, enabled) { onKey(TerminalKey.PAGE_DOWN) }
            ExtraKey("|", null, enabled) { onText("|") }
        }
    }
}

/** Одна клавиша ряда; залипшая подсвечивается акцентом. */
@Composable
private fun RowScope.ExtraKey(
    label: String,
    spoken: Int?,
    enabled: Boolean,
    armed: Boolean = false,
    onClick: () -> Unit,
) {
    val description = spoken?.let { stringResource(it) } ?: label
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .weight(1f)
            .height(KEY_HEIGHT)
            .background(if (armed) MaterialTheme.colorScheme.primary else Color.Transparent)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
    ) {
        Text(
            text = label,
            color = when {
                armed -> MaterialTheme.colorScheme.onPrimary
                enabled -> TerminalPalette.foreground
                else -> TerminalPalette.muted
            },
            fontFamily = FontFamily.Monospace,
            fontSize = KEY_TEXT,
            maxLines = 1,
        )
    }
}

private val HARDWARE_KEYS = mapOf(
    Key.Escape to TerminalKey.ESCAPE,
    Key.Tab to TerminalKey.TAB,
    Key.DirectionUp to TerminalKey.UP,
    Key.DirectionDown to TerminalKey.DOWN,
    Key.DirectionLeft to TerminalKey.LEFT,
    Key.DirectionRight to TerminalKey.RIGHT,
    Key.MoveHome to TerminalKey.HOME,
    Key.MoveEnd to TerminalKey.END,
    Key.PageUp to TerminalKey.PAGE_UP,
    Key.PageDown to TerminalKey.PAGE_DOWN,
)

private const val INPUT_SENTINEL = " "
private const val DEL = '\u007F'
private const val ESC = "\u001B"
private val EMPTY_INPUT = TextFieldValue(INPUT_SENTINEL, TextRange(INPUT_SENTINEL.length))
private val KEY_HEIGHT = 44.dp
private val KEY_TEXT = 13.sp
private val TAB_TEXT = 13.sp
