package io.github.ncorror.nekoflash.ui

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.github.ncorror.nekoflash.adb.TerminalKey

/**
 * Невидимый Android text-editor для настоящего терминального ввода.
 *
 * Экран терминала остаётся Compose-рендерером, но клавиатура фокусирует этот
 * View. Поэтому IME и аппаратная клавиатура отправляют символы прямо в живой
 * ADB PTY, а не редактируют нижнюю строку команды. Нижняя строка остаётся
 * отдельным удобным редактором «набрать целиком -> отправить».
 *
 * Реализация написана для NekoFlash с нуля. Она повторяет поведение обычного
 * terminal view (InputConnection -> bytes), но не переносит GPL-код NetHunter.
 */
@Composable
internal fun TerminalInputBridge(
    enabled: Boolean,
    enterText: String,
    ctrl: Boolean,
    alt: Boolean,
    onText: (String) -> Unit,
    onKey: (TerminalKey) -> Unit,
    onModifiersConsumed: () -> Unit,
    onViewChanged: (TerminalInputBridgeView) -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context ->
            TerminalInputBridgeView(context).also(onViewChanged)
        },
        update = { view ->
            view.terminalEnabled = enabled
            view.enterText = enterText
            view.stickyCtrl = ctrl
            view.stickyAlt = alt
            view.textSink = onText
            view.keySink = onKey
            view.modifiersConsumedSink = onModifiersConsumed
        },
        // View должен быть attached + visible для InputMethodManager, но ему не
        // нужен визуальный размер: рисует терминал Compose-слой рядом.
        modifier = modifier,
    )
}

/** Android-side bridge; открыт для package-тестов чистых преобразований рядом. */
internal class TerminalInputBridgeView(context: Context) : View(context) {
    internal var terminalEnabled: Boolean = false
    internal var enterText: String = "\r"
    internal var stickyCtrl: Boolean = false
    internal var stickyAlt: Boolean = false
    internal var textSink: (String) -> Unit = {}
    internal var keySink: (TerminalKey) -> Unit = {}
    internal var modifiersConsumedSink: () -> Unit = {}

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        minimumWidth = 1
        minimumHeight = 1
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // Без автокоррекции: терминалу нужны введённые байты, а не догадки IME.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN

        return object : BaseInputConnection(this@TerminalInputBridgeView, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val accepted = super.commitText(text, newCursorPosition)
                flushEditableToTerminal()
                return accepted
            }

            override fun finishComposingText(): Boolean {
                val accepted = super.finishComposingText()
                flushEditableToTerminal()
                return accepted
            }

            override fun deleteSurroundingText(leftLength: Int, rightLength: Int): Boolean {
                if (terminalEnabled && leftLength > 0) {
                    repeat(leftLength) { emitText(DEL_TEXT) }
                    consumeStickyModifiers()
                }
                return super.deleteSurroundingText(leftLength, rightLength)
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean =
                if (handleTerminalKeyEvent(event)) true else super.sendKeyEvent(event)

            override fun performEditorAction(editorAction: Int): Boolean {
                if (!terminalEnabled) return false
                emitText(enterText)
                consumeStickyModifiers()
                return true
            }

            private fun flushEditableToTerminal() {
                val content = editable
                if (!terminalEnabled || content.isEmpty()) return
                val normalized = normalizeCommittedTerminalText(content.toString(), enterText)
                emitText(withModifiers(normalized, stickyCtrl, stickyAlt))
                content.clear()
                consumeStickyModifiers()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        if (handleTerminalKeyEvent(event)) true else super.onKeyDown(keyCode, event)

    private fun handleTerminalKeyEvent(event: KeyEvent): Boolean {
        if (!terminalEnabled || event.action != KeyEvent.ACTION_DOWN) return false

        when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            -> emitTextAndConsumeModifiers(enterText)
            KeyEvent.KEYCODE_DEL -> emitTextAndConsumeModifiers(DEL_TEXT)
            KeyEvent.KEYCODE_FORWARD_DEL -> emitTextAndConsumeModifiers("\u001B[3~")
            KeyEvent.KEYCODE_ESCAPE -> keySink(TerminalKey.ESCAPE)
            KeyEvent.KEYCODE_TAB -> keySink(TerminalKey.TAB)
            KeyEvent.KEYCODE_DPAD_UP -> keySink(TerminalKey.UP)
            KeyEvent.KEYCODE_DPAD_DOWN -> keySink(TerminalKey.DOWN)
            KeyEvent.KEYCODE_DPAD_LEFT -> keySink(TerminalKey.LEFT)
            KeyEvent.KEYCODE_DPAD_RIGHT -> keySink(TerminalKey.RIGHT)
            KeyEvent.KEYCODE_MOVE_HOME -> keySink(TerminalKey.HOME)
            KeyEvent.KEYCODE_MOVE_END -> keySink(TerminalKey.END)
            KeyEvent.KEYCODE_PAGE_UP -> keySink(TerminalKey.PAGE_UP)
            KeyEvent.KEYCODE_PAGE_DOWN -> keySink(TerminalKey.PAGE_DOWN)
            else -> return emitPrintingKey(event)
        }
        return true
    }

    private fun emitPrintingKey(event: KeyEvent): Boolean {
        // Ctrl/Left-Alt обрабатываем сами; Right-Alt (AltGr) оставляем IME/keymap.
        var metaState = event.metaState and KeyEvent.META_CTRL_MASK.inv()
        val leftAlt = event.isAltPressed && (event.metaState and KeyEvent.META_ALT_RIGHT_ON) == 0
        if (leftAlt) {
            metaState = metaState and KeyEvent.META_ALT_MASK.inv()
        }
        val codePoint = event.getUnicodeChar(metaState)
        if (codePoint == 0) return false

        val control = event.isCtrlPressed || stickyCtrl
        val alt = leftAlt || stickyAlt
        val printed = if (control && codePoint <= Char.MAX_VALUE.code) {
            controlOf(codePoint.toChar()).toString()
        } else {
            String(Character.toChars(codePoint))
        }
        emitText((if (alt) ESC_TEXT else "") + printed)
        consumeStickyModifiers()
        return true
    }

    private fun emitTextAndConsumeModifiers(text: String) {
        emitText(text)
        consumeStickyModifiers()
    }

    private fun consumeStickyModifiers() {
        if (stickyCtrl || stickyAlt) modifiersConsumedSink()
    }

    private fun emitText(text: String) {
        if (terminalEnabled && text.isNotEmpty()) textSink(text)
    }

    private companion object {
        const val ESC_TEXT = "\u001B"
        const val DEL_TEXT = "\u007F"
    }
}

/** IME иногда присылает Enter как текстовый LF; PTY ожидает terminal Enter. */
internal fun normalizeCommittedTerminalText(text: String, enterText: String): String = buildString(text.length) {
    text.forEach { char ->
        when (char) {
            '\n' -> append(enterText)
            '\r' -> append(enterText)
            else -> append(char)
        }
    }
}
