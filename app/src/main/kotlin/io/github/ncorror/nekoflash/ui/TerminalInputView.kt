package io.github.ncorror.nekoflash.ui

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import io.github.ncorror.nekoflash.adb.TerminalKey

/**
 * Invisible text-editor surface for the terminal.
 *
 * Termux and NetHunter do not put an EditText below the terminal. The terminal
 * view itself owns an InputConnection and forwards committed characters to the
 * PTY. NekoFlash keeps the same contract in a small Android View so Compose only
 * renders the VT screen and never participates in IME text layout.
 */
internal class TerminalInputView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    var onText: (String) -> Unit = {}
    var onEnter: () -> Unit = {}
    var onBackspace: () -> Unit = {}
    var onInterrupt: () -> Unit = {}
    var onTerminalKey: (TerminalKey) -> Unit = {}

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO
        setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING

        return object : BaseInputConnection(this, true) {
            override fun finishComposingText(): Boolean {
                val result = super.finishComposingText()
                flushEditable()
                return result
            }

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val result = super.commitText(text, newCursorPosition)
                flushEditable()
                return result
            }

            override fun deleteSurroundingText(leftLength: Int, rightLength: Int): Boolean {
                repeat(leftLength.coerceAtLeast(0)) { onBackspace() }
                return super.deleteSurroundingText(leftLength, rightLength)
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean =
                handleTerminalKeyEvent(event) || super.sendKeyEvent(event)

            override fun performEditorAction(actionCode: Int): Boolean {
                onEnter()
                return true
            }

            private fun flushEditable() {
                val target = editable ?: return
                val content = target.toString()
                target.clear()
                dispatchCommittedText(content)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        handleTerminalKeyEvent(event) || super.onKeyDown(keyCode, event)

    fun showKeyboard() {
        requestFocus()
        post {
            val manager = context.getSystemService(InputMethodManager::class.java)
            manager?.showSoftInput(this, 0)
        }
    }

    fun pasteClipboard() {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = clipboard.primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
        if (text.isNotEmpty()) {
            dispatchCommittedText(text)
            showKeyboard()
        }
    }

    private fun dispatchCommittedText(text: String) {
        if (text.isEmpty()) return
        val plain = StringBuilder()

        fun flushPlain() {
            if (plain.isNotEmpty()) {
                onText(plain.toString())
                plain.clear()
            }
        }

        text.codePoints().forEach { codePoint ->
            when (codePoint) {
                '\n'.code, '\r'.code -> {
                    flushPlain()
                    onEnter()
                }
                BACKSPACE_CODE, DELETE_CODE -> {
                    flushPlain()
                    onBackspace()
                }
                else -> plain.appendCodePoint(codePoint)
            }
        }
        flushPlain()
    }

    private fun handleTerminalKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        return when {
            event.isCtrlPressed && event.keyCode == KeyEvent.KEYCODE_C -> handled(onInterrupt)
            event.keyCode == KeyEvent.KEYCODE_ENTER ||
                event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER -> handled(onEnter)
            event.keyCode == KeyEvent.KEYCODE_DEL -> handled(onBackspace)
            event.keyCode == KeyEvent.KEYCODE_DPAD_UP -> handled { onTerminalKey(TerminalKey.UP) }
            event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> handled { onTerminalKey(TerminalKey.DOWN) }
            event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> handled { onTerminalKey(TerminalKey.LEFT) }
            event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> handled { onTerminalKey(TerminalKey.RIGHT) }
            event.keyCode == KeyEvent.KEYCODE_MOVE_HOME -> handled { onTerminalKey(TerminalKey.HOME) }
            event.keyCode == KeyEvent.KEYCODE_MOVE_END -> handled { onTerminalKey(TerminalKey.END) }
            event.keyCode == KeyEvent.KEYCODE_PAGE_UP -> handled { onTerminalKey(TerminalKey.PAGE_UP) }
            event.keyCode == KeyEvent.KEYCODE_PAGE_DOWN -> handled { onTerminalKey(TerminalKey.PAGE_DOWN) }
            event.keyCode == KeyEvent.KEYCODE_TAB -> handled { onTerminalKey(TerminalKey.TAB) }
            event.keyCode == KeyEvent.KEYCODE_ESCAPE -> handled { onTerminalKey(TerminalKey.ESCAPE) }
            else -> false
        }
    }

    private inline fun handled(action: () -> Unit): Boolean {
        action()
        return true
    }

    private companion object {
        const val BACKSPACE_CODE = 8
        const val DELETE_CODE = 127
    }
}
