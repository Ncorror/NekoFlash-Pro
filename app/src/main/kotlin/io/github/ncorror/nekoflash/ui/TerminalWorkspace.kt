package io.github.ncorror.nekoflash.ui

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.KeyEvent as AndroidKeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
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
    var editor by remember { mutableStateOf<EditText?>(null) }
    val openKeyboard = {
        if (current.ready && !showCommands) editor?.let { field ->
            field.requestFocus()
            val input = field.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            input?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
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
                tabIndex = terminal.selected,
                actions = actions,
                connected = connected,
                onEditorChanged = { editor = it },
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
 * Ctrl и Alt залипают на одну клавишу: нажал Ctrl, потом `c` — ушёл `^C`.
 * Команду в нижнем поле редактирует Android; она уходит в оболочку только после
 * Enter. Дополнительные управляющие клавиши идут прямо в живой терминал.
 */
@Composable
internal fun TerminalSection(
    terminal: AdbTerminalState,
    tabIndex: Int,
    actions: TerminalActions,
    connected: Boolean,
    onEditorChanged: (EditText) -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    var draft by rememberSaveable(tabIndex) { mutableStateOf("") }
    var input by remember(tabIndex) { mutableStateOf<EditText?>(null) }
    val type: (String) -> Unit = { text ->
        if (ctrl || alt) {
            actions.onType(withModifiers(text, ctrl, alt))
        } else {
            val field = input
            if (field == null) draft += text else field.text?.insert(field.selectionStart.coerceAtLeast(0), text)
        }
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
        }
        key(tabIndex) {
            TerminalCommandInput(
                enabled = terminal.ready,
                draft = draft,
                onDraftChange = { draft = it },
                onSend = { line ->
                    actions.onSend(line)
                    draft = ""
                },
                onText = actions.onType,
                onKey = press,
                onEditorChanged = { field -> input = field; onEditorChanged(field) },
            )
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

/** Компактная строка ввода на фоне самого экрана терминала. */
@Composable
private fun TerminalCommandInput(
    enabled: Boolean,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: (String) -> Unit,
    onText: (String) -> Unit,
    onKey: (TerminalKey) -> Unit,
    onEditorChanged: (EditText) -> Unit,
) {
    val context = LocalContext.current
    val clipboard = remember(context) {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    }
    val hint = stringResource(R.string.terminal_input_hint)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().background(TerminalPalette.background).padding(start = 12.dp, end = 4.dp),
    ) {
        Text(
            text = stringResource(R.string.terminal_prompt_prefix),
            color = TerminalPalette.live,
            fontFamily = FontFamily.Monospace,
            fontSize = 16.sp,
        )
        TerminalLineEditor(
            draft, hint, enabled, onDraftChange, onSend, onText, onKey, onEditorChanged,
        )
        ToolbarIcon(R.drawable.ic_paste, stringResource(R.string.terminal_paste), enabled = enabled) {
            val item = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
            val pasted = item?.coerceToText(context)?.toString()?.let(::clipboardLine).orEmpty()
            if (pasted.isNotEmpty()) onDraftChange(draft + pasted)
        }
        ToolbarIcon(R.drawable.ic_send, stringResource(R.string.terminal_send), enabled = enabled) {
            onSend(draft)
        }
    }
}

/** Настоящий Android EditText принимает IME, аппаратный ввод и системную вставку. */
@Composable
private fun RowScope.TerminalLineEditor(
    draft: String,
    hint: String,
    enabled: Boolean,
    onDraftChange: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onText: (String) -> Unit,
    onKey: (TerminalKey) -> Unit,
    onEditorChanged: (EditText) -> Unit,
) {
    AndroidView(
        factory = { context -> nativeLineEditor(context, draft, onDraftChange, onEditorChanged) },
        update = { field ->
            field.isEnabled = enabled
            field.hint = hint
            if (field.text.toString() != draft) {
                field.setText(draft)
                field.setSelection(draft.length)
            }
            field.setOnEditorActionListener { _, action, event ->
                val enter = event?.keyCode == AndroidKeyEvent.KEYCODE_ENTER &&
                    event.action == AndroidKeyEvent.ACTION_DOWN
                if (enabled && (action == EditorInfo.IME_ACTION_SEND || enter)) {
                    onSubmit(field.text.toString())
                    true
                } else false
            }
            field.setOnKeyListener { _, code, event ->
                if (!enabled || event.action != AndroidKeyEvent.ACTION_DOWN || field.text.isNotEmpty()) {
                    false
                } else {
                    val key = HARDWARE_KEYS[code]
                    if (key != null) {
                        onKey(key)
                        true
                    } else {
                        val letter = event.getUnicodeChar(0).toChar()
                        if (event.isCtrlPressed && letter in 'a'..'z') {
                            onText(controlOf(letter).toString())
                            true
                        } else false
                    }
                }
            }
        },
        modifier = Modifier.weight(1f).heightIn(min = 44.dp),
    )
}

/** Плоское поле без системной рамки, в палитре терминала. */
private fun nativeLineEditor(
    context: Context,
    draft: String,
    onDraftChange: (String) -> Unit,
    onEditorChanged: (EditText) -> Unit,
): EditText = EditText(context).apply {
    background = null
    setSingleLine(true)
    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
    typeface = Typeface.MONOSPACE
    textSize = EDITOR_TEXT_SP
    setTextColor(TerminalPalette.foreground.toArgb())
    setHintTextColor(TerminalPalette.muted.toArgb())
    setPadding((EDITOR_PADDING_DP * resources.displayMetrics.density).toInt(), 0, 0, 0)
    setText(draft)
    addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) { onDraftChange(s?.toString().orEmpty()) }
    })
    onEditorChanged(this)
}

/** Одно нажатие «Отправить» исполняет одну строку даже при многострочном буфере. */
internal fun clipboardLine(text: String): String = text.substringBefore('\n').substringBefore('\r')

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
    AndroidKeyEvent.KEYCODE_ESCAPE to TerminalKey.ESCAPE,
    AndroidKeyEvent.KEYCODE_TAB to TerminalKey.TAB,
    AndroidKeyEvent.KEYCODE_DPAD_UP to TerminalKey.UP,
    AndroidKeyEvent.KEYCODE_DPAD_DOWN to TerminalKey.DOWN,
    AndroidKeyEvent.KEYCODE_DPAD_LEFT to TerminalKey.LEFT,
    AndroidKeyEvent.KEYCODE_DPAD_RIGHT to TerminalKey.RIGHT,
    AndroidKeyEvent.KEYCODE_MOVE_HOME to TerminalKey.HOME,
    AndroidKeyEvent.KEYCODE_MOVE_END to TerminalKey.END,
    AndroidKeyEvent.KEYCODE_PAGE_UP to TerminalKey.PAGE_UP,
    AndroidKeyEvent.KEYCODE_PAGE_DOWN to TerminalKey.PAGE_DOWN,
)

private const val EDITOR_TEXT_SP = 16f
private const val EDITOR_PADDING_DP = 8
private const val DEL = '\u007F'
private const val ESC = "\u001B"
private val KEY_HEIGHT = 44.dp
private val KEY_TEXT = 13.sp
private val TAB_TEXT = 13.sp
