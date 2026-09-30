package io.github.ncorror.nekoflash.adb

import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticSink
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Test

/** Вкладки терминала: у каждой своя оболочка, последняя не исчезает. */
class AdbTerminalTabsTest {
    private val direct = Executor { it.run() }
    private val tabs = AdbTerminalTabs(direct, direct, DiagnosticSink { })

    @Test
    fun aNewTabIsSelected() {
        tabs.add()
        tabs.add()

        assertEquals(3, tabs.state.value.tabs.size)
        assertEquals(2, tabs.state.value.selected)
    }

    @Test
    fun closingKeepsTheSelectionInRange() {
        tabs.add()
        tabs.close(1)

        assertEquals(1, tabs.state.value.tabs.size)
        assertEquals(0, tabs.state.value.selected)
    }

    @Test
    fun theLastTabStays() {
        tabs.close(0)

        assertEquals(1, tabs.state.value.tabs.size)
    }

    @Test
    fun selectionFollowsTheOperator() {
        tabs.add()
        tabs.select(0)

        assertEquals(0, tabs.state.value.selected)
        assertEquals(tabs.state.value.tabs[0], tabs.current.state)
    }
}
