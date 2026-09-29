package io.github.ncorror.nekoflash.terminal

/**
 * Вид оболочки как у Kali NetHunter (D078): логотип при открытии, приглашение
 * `┌──(shell㉿vayu)-[/sdcard]` / `└─$`, цветные `ls` и `grep`.
 *
 * **Логотип рисует приложение**, до первого байта устройства: это вывод
 * терминала, а не оболочки. **Приглашение настраивает сама оболочка**, и
 * настраивает её не набранная за оператора строка: та осталась бы в истории
 * `↑` и мелькнула бы эхом. Вместо этого терминал открывается командой, как
 * `adb shell -t КОМАНДА`: `sh -c` передаёт настройки через канал и запускает
 * интерактивный `sh` с `ENV=/proc/self/fd/9` — mksh читает `$ENV` вместо
 * `/system/etc/mkshrc` (`main.c`, `${ENV:-MKSHRC_PATH}`), поэтому настройки
 * сначала подключают `mkshrc` сами. На устройстве ничего не пишется: ни файла,
 * ни истории; выход из оболочки кончает сессию, как и раньше.
 *
 * Разделитель `\001` в начале `PS1` с `\r` за ним — правило mksh для
 * непечатаемых кусков приглашения: без него редактор строки считал бы
 * управляющие последовательности символами и ставил курсор мимо.
 */
public object TerminalShellProfile {
    /** Строка команды после `pty:`; только ASCII, чтобы строка сервиса не зависела от кодировки. */
    public val command: String
        get() = "exec 3<&0; printf %s ${quoted(settings)} | { export ENV=/proc/self/fd/9; exec sh -i 9<&0 0<&3; }"

    /**
     * Логотип под ширину окна: во всю ширину — кошка и «NekoFlash» крупно,
     * на узком экране — кошка и название строкой. Цвет — переход от
     * фирменного оранжевого к холодному акценту, как на Welcome.
     */
    public fun banner(columns: Int): String {
        val lines = when {
            columns >= WIDE_BANNER -> CAT.zip(FIGLET) { cat, word -> cat(cat) + gradient(word) } + tagline(PAD)
            columns >= NARROW_BANNER -> CAT.zip(NARROW) { cat, text -> cat(cat) + text }
            else -> listOf(bold(NAME))
        }
        return lines.joinToString(separator = CRLF, postfix = CRLF + CRLF) { it.trimEnd() + RESET }
    }

    private fun cat(line: String): String = CAT_COLOR + line.padEnd(PAD) + RESET

    private fun tagline(indent: Int): String = " ".repeat(indent) + DIM + TAGLINE + RESET

    private fun bold(text: String): String = "$ESC[1m$text$RESET"

    /** Каждой колонке — свой цвет между двумя концами перехода. */
    private fun gradient(line: String): String = buildString {
        val last = (FIGLET_WIDTH - 1).coerceAtLeast(1)
        line.forEachIndexed { column, char ->
            if (char == ' ') {
                append(char)
            } else {
                val (r, g, b) = FROM.indices.map { i -> FROM[i] + (TO[i] - FROM[i]) * column / last }
                append("$ESC[1;38;2;$r;$g;${b}m").append(char)
            }
        }
    }

    /** Внутри `'…'` одинарная кавычка закрывается, экранируется и открывается заново. */
    internal fun quoted(text: String): String = "'" + text.replace("'", "'\\''") + "'"

    private const val ESC = "\u001B"
    private const val CRLF = "\r\n"
    private const val RESET = "$ESC[0m"
    private const val DIM = "$ESC[2m"
    private const val CAT_COLOR = "$ESC[1;38;2;89;185;231m"
    private const val NAME = "NekoFlash Pro"
    private const val TAGLINE = "adb · fastboot · recovery · usb otg"

    /** Сколько колонок занимает кошка вместе с отступом до надписи. */
    private const val PAD = 10
    private const val FIGLET_WIDTH = 37
    private const val WIDE_BANNER = PAD + FIGLET_WIDTH
    /** Самая длинная строка узкого логотипа — `recovery · usb otg`. */
    private const val NARROW_TEXT = 18
    private const val NARROW_BANNER = PAD + NARROW_TEXT

    /** Концы перехода: фирменный оранжевый и холодный акцент палитры (`NekoFlashColors`). */
    @Suppress("MagicNumber")
    private val FROM = intArrayOf(0xE9, 0x78, 0x2B)

    @Suppress("MagicNumber")
    private val TO = intArrayOf(0x59, 0xB9, 0xE7)

    private val CAT = listOf(
        """  /\_/\""",
        """ ( o.o )""",
        """  > ^ <""",
        """ (")_(")""",
    )

    /** `NekoFlash` шрифтом figlet `small`. */
    private val FIGLET = listOf(
        """ _  _     _       ___ _         _""",
        """| \| |___| |_____| __| |__ _ __| |_""",
        """| .` / -_) / / _ \ _|| / _` (_-< ' \""",
        """|_|\_\___|_\_\___/_| |_\__,_/__/_||_|""",
    )

    private val NARROW = listOf(
        "$ESC[1;38;2;233;120;43m$NAME$RESET",
        DIM + "adb · fastboot" + RESET,
        DIM + "recovery · usb otg" + RESET,
        "",
    )

    /**
     * Настройки оболочки; читает их mksh как `$ENV`.
     *
     * Цвета — как у Kali: у обычного пользователя рамка зелёная и имя синее,
     * у `root` рамка синяя, имя и `#` красные. Строка с моделью и версией —
     * то, что NetHunter показывает при входе.
     */
    internal val settings: String = listOf(
        "[ -r /system/etc/mkshrc ] && . /system/etc/mkshrc",
        "exec 9<&-",
        "set -U",
        "alias ls='ls --color=auto' grep='grep --color=auto'",
        "if [ \"\$(id -u)\" = 0 ]; then _nfc=\$'\\e[0;34m' _nfh=\$'\\e[1;31m' _nfp='#';" +
            " else _nfc=\$'\\e[0;32m' _nfh=\$'\\e[1;34m' _nfp='\$'; fi",
        "PS1=\$'\\001\\r\\001\${_nfc}\\001\\u250c\\u2500\\u2500(\\001\${_nfh}\\001\${USER:-shell}\\u327f" +
            "\${HOSTNAME:-android}\\001\${_nfc}\\001)-[\\001\\e[0;1m\\001\${PWD:-?}\\001\${_nfc}\\001]\\n" +
            "\\u2514\\u2500\\001\${_nfh}\\001\${_nfp}\\001\\e[0m\\001 '",
        "printf '\\033[1;36m%s %s\\033[0m \\033[2m|\\033[0m Android %s \\033[2m|\\033[0m SDK %s" +
            " \\033[2m|\\033[0m %s\\n\\n' \"\$(getprop ro.product.manufacturer)\" \"\$(getprop ro.product.model)\"" +
            " \"\$(getprop ro.build.version.release)\" \"\$(getprop ro.build.version.sdk)\" \"\$(uname -m)\"",
    ).joinToString(separator = "\n", postfix = "\n")
}
