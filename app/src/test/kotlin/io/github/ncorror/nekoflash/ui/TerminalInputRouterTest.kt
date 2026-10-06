package io.github.ncorror.nekoflash.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import io.github.ncorror.nekoflash.adb.TerminalKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalInputRouterTest {
    @Test
    fun ctrlCInPtyIsOneShot() {
        val typed = mutableListOf<String>()
        val control = mutableStateOf(false)
        val alt = mutableStateOf(false)
        val router = router(
            pty = true,
            control = control,
            alt = alt,
            actions = testActions(onType = typed::add),
        )

        router.toggleControl()
        assertTrue(control.value)
        router.onText("c")

        assertEquals(listOf("\u0003"), typed)
        assertFalse(control.value)
        assertFalse(alt.value)
    }

    @Test
    fun altPrefixesOnlyFirstPtyCodePoint() {
        val typed = mutableListOf<String>()
        val control = mutableStateOf(false)
        val alt = mutableStateOf(false)
        val router = router(
            pty = true,
            control = control,
            alt = alt,
            actions = testActions(onType = typed::add),
        )

        router.toggleAlt()
        router.onText("xy")

        assertEquals(listOf("\u001Bx", "y"), typed)
        assertFalse(control.value)
        assertFalse(alt.value)
    }

    @Test
    fun modifierButtonsAreMutuallyExclusive() {
        val control = mutableStateOf(false)
        val alt = mutableStateOf(false)
        val router = router(pty = true, control = control, alt = alt)

        router.toggleControl()
        assertTrue(control.value)
        assertFalse(alt.value)

        router.toggleAlt()
        assertFalse(control.value)
        assertTrue(alt.value)
    }

    @Test
    fun plainPtySpecialKeyRoutesAndClearsModifierState() {
        val keys = mutableListOf<TerminalKey>()
        val control = mutableStateOf(false)
        val alt = mutableStateOf(false)
        val router = router(
            pty = true,
            control = control,
            alt = alt,
            actions = testActions(onKey = keys::add),
        )

        router.onSpecialKey(TerminalKey.LEFT)

        assertEquals(listOf(TerminalKey.LEFT), keys)
        assertFalse(control.value)
        assertFalse(alt.value)
    }

    @Test
    fun ctrlNavigationUsesXtermModifierFive() {
        val typed = mutableListOf<String>()
        val keys = mutableListOf<TerminalKey>()
        val control = mutableStateOf(false)
        val alt = mutableStateOf(false)
        val router = router(
            pty = true,
            control = control,
            alt = alt,
            actions = testActions(onKey = keys::add, onType = typed::add),
        )
        val cases = listOf(
            TerminalKey.UP to "\u001B[1;5A",
            TerminalKey.DOWN to "\u001B[1;5B",
            TerminalKey.RIGHT to "\u001B[1;5C",
            TerminalKey.LEFT to "\u001B[1;5D",
            TerminalKey.HOME to "\u001B[1;5H",
            TerminalKey.END to "\u001B[1;5F",
            TerminalKey.PAGE_UP to "\u001B[5;5~",
            TerminalKey.PAGE_DOWN to "\u001B[6;5~",
        )

        cases.forEach { (key, _) ->
            router.toggleControl()
            router.onSpecialKey(key)
            assertFalse(control.value)
            assertFalse(alt.value)
        }

        assertEquals(cases.map { it.second }, typed)
        assertTrue(keys.isEmpty())
    }

    @Test
    fun altNavigationUsesXtermModifierThree() {
        val typed = mutableListOf<String>()
        val keys = mutableListOf<TerminalKey>()
        val control = mutableStateOf(false)
        val alt = mutableStateOf(false)
        val router = router(
            pty = true,
            control = control,
            alt = alt,
            actions = testActions(onKey = keys::add, onType = typed::add),
        )
        val cases = listOf(
            TerminalKey.UP to "\u001B[1;3A",
            TerminalKey.DOWN to "\u001B[1;3B",
            TerminalKey.RIGHT to "\u001B[1;3C",
            TerminalKey.LEFT to "\u001B[1;3D",
            TerminalKey.HOME to "\u001B[1;3H",
            TerminalKey.END to "\u001B[1;3F",
            TerminalKey.PAGE_UP to "\u001B[5;3~",
            TerminalKey.PAGE_DOWN to "\u001B[6;3~",
        )

        cases.forEach { (key, _) ->
            router.toggleAlt()
            router.onSpecialKey(key)
            assertFalse(control.value)
            assertFalse(alt.value)
        }

        assertEquals(cases.map { it.second }, typed)
        assertTrue(keys.isEmpty())
    }

    @Test
    fun modifierDoesNotInventSequencesForEscapeOrTab() {
        val typed = mutableListOf<String>()
        val keys = mutableListOf<TerminalKey>()
        val control = mutableStateOf(false)
        val alt = mutableStateOf(false)
        val router = router(
            pty = true,
            control = control,
            alt = alt,
            actions = testActions(onKey = keys::add, onType = typed::add),
        )

        router.toggleControl()
        router.onSpecialKey(TerminalKey.ESCAPE)
        router.toggleAlt()
        router.onSpecialKey(TerminalKey.TAB)

        assertEquals(listOf(TerminalKey.ESCAPE, TerminalKey.TAB), keys)
        assertTrue(typed.isEmpty())
        assertFalse(control.value)
        assertFalse(alt.value)
    }

    @Test
    fun hostCtrlCCancelsCurrentLine() {
        val fixture = hostFixture("adb devices")
        val control = mutableStateOf(false)
        val alt = mutableStateOf(false)
        val router = TerminalInputRouter(
            ptyInput = false,
            hostEditor = fixture.editor,
            actions = testActions(),
            controlActive = control,
            altActive = alt,
        )

        router.toggleControl()
        router.onText("c")

        assertEquals("", fixture.input.value)
        assertEquals(0, fixture.cursor.value)
        assertEquals(listOf("^C"), fixture.transcript)
        assertFalse(control.value)
    }

    @Test
    fun hostAltLeftMovesByWordAndClearsModifier() {
        val fixture = hostFixture("one two")
        val control = mutableStateOf(false)
        val alt = mutableStateOf(false)
        val router = TerminalInputRouter(
            ptyInput = false,
            hostEditor = fixture.editor,
            actions = testActions(),
            controlActive = control,
            altActive = alt,
        )

        router.toggleAlt()
        router.onSpecialKey(TerminalKey.LEFT)

        assertEquals(4, fixture.cursor.value)
        assertFalse(alt.value)
    }

    private fun testActions(
        onInterrupt: () -> Unit = {},
        onResize: (Int, Int) -> Unit = { _, _ -> },
        onKey: (TerminalKey) -> Unit = {},
        onType: (String) -> Unit = {},
    ): TerminalActions = TerminalActions(
        onInterrupt = onInterrupt,
        onResize = onResize,
        onKey = onKey,
        onType = onType,
    )

    private fun router(
        pty: Boolean,
        control: MutableState<Boolean>,
        alt: MutableState<Boolean>,
        actions: TerminalActions = testActions(),
    ): TerminalInputRouter = TerminalInputRouter(
        ptyInput = pty,
        hostEditor = hostFixture("").editor,
        actions = actions,
        controlActive = control,
        altActive = alt,
    )

    private fun hostFixture(initial: String): HostFixture {
        val input = mutableStateOf(initial)
        val cursor = mutableStateOf(initial.length)
        val historyIndex = mutableStateOf(0)
        val history = mutableListOf<String>()
        val transcript = mutableListOf<String>()
        return HostFixture(
            input = input,
            cursor = cursor,
            transcript = transcript,
            editor = HostLineEditor(
                input = input,
                cursor = cursor,
                historyIndex = historyIndex,
                history = history,
                transcript = transcript,
                prompt = "❯",
                onCommand = {},
            ),
        )
    }

    private data class HostFixture(
        val input: MutableState<String>,
        val cursor: MutableState<Int>,
        val transcript: MutableList<String>,
        val editor: HostLineEditor,
    )
}
