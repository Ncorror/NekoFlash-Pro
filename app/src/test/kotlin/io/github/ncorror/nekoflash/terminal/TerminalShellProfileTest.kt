package io.github.ncorror.nekoflash.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Профиль оболочки (D078). Сама оболочка проверялась mksh R59 в терминале —
 * приглашение, правка строки, код выхода; здесь — то, что от mksh не зависит.
 */
class TerminalShellProfileTest {
    @Test
    fun theCommandIsPlainAsciiAndFitsAnOpenPacket() {
        val command = TerminalShellProfile.command

        assertTrue(command.all { it == '\n' || it.code in 0x20..0x7E })
        assertTrue("строка сервиса и 4096 байт maxData", command.length < 2048)
    }

    /** Настройки доходят до `printf %s` ровно такими, какие написаны, с кавычками внутри. */
    @Test
    fun quotingRoundTripsTheSettings() {
        val quoted = TerminalShellProfile.quoted(TerminalShellProfile.settings)

        assertEquals(TerminalShellProfile.settings, unquote(quoted))
        assertTrue(TerminalShellProfile.command.contains(quoted))
    }

    /** История не трогается: оболочка запускается с `ENV`, а не получает набранную строку. */
    @Test
    fun theShellReadsTheSettingsAsItsEnvFile() {
        val command = TerminalShellProfile.command

        assertTrue(command.endsWith("| { export ENV=/proc/self/fd/9; exec sh -i 9<&0 0<&3; }"))
        assertTrue(TerminalShellProfile.settings.startsWith("[ -r /system/etc/mkshrc ] && . /system/etc/mkshrc\n"))
        assertTrue(TerminalShellProfile.settings.contains("exec 9<&-"))
    }

    @Test
    fun theBannerFitsTheWindow() {
        for (columns in listOf(14, 28, 30, 46, 47, 80, 120)) {
            val widest = TerminalShellProfile.banner(columns).split("\r\n").maxOf { visible(it).length }
            assertTrue("$columns колонок, строка $widest", widest <= columns)
        }
    }

    private fun visible(line: String): String = line.replace(Regex("\u001B\\[[0-9;]*m"), "")

    /** Разбор `'…'` с `'\''` внутри — как у `sh`. */
    private fun unquote(text: String): String = buildString {
        var quoted = false
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                char == '\'' -> quoted = !quoted
                !quoted && char == '\\' -> {
                    index += 1
                    append(text[index])
                }
                else -> append(char)
            }
            index += 1
        }
    }
}
