package io.github.ncorror.nekoflash.terminal

/**
 * Оформление клетки экрана.
 *
 * Цвет — [DEFAULT], номер палитры 0–255 или `RGB_FLAG or 0xRRGGBB` для
 * 24-битного цвета SGR `38;2;R;G;B`.
 */
data class VtStyle(
    val foreground: Int = DEFAULT,
    val background: Int = DEFAULT,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val inverse: Boolean = false,
) {
    companion object {
        const val DEFAULT: Int = -1
        const val RGB_FLAG: Int = 0x1000000
        val PLAIN: VtStyle = VtStyle()
    }
}

/**
 * SGR — `CSI … m`, как его понимает xterm: сброс, яркость, курсив,
 * подчёркивание, инверсия, 8 и 16 цветов, 256 цветов и 24-битный цвет.
 * Незнакомый параметр пропускается, как у xterm.
 */
internal object VtSgr {
    fun apply(style: VtStyle, params: List<Int>): VtStyle {
        if (params.isEmpty()) return VtStyle.PLAIN
        var current = style
        var index = 0
        while (index < params.size) {
            val code = params[index]
            if (code == FG_EXTENDED || code == BG_EXTENDED) {
                val (color, used) = extendedColor(params, index + 1)
                current = withColor(current, code == FG_EXTENDED, color)
                index += used
            } else {
                current = simple(current, code)
            }
            index++
        }
        return current
    }

    private fun withColor(style: VtStyle, foreground: Boolean, color: Int?): VtStyle = when {
        color == null -> style
        foreground -> style.copy(foreground = color)
        else -> style.copy(background = color)
    }

    private fun simple(style: VtStyle, code: Int): VtStyle = when (code) {
        RESET -> VtStyle.PLAIN
        BOLD -> style.copy(bold = true)
        DIM -> style.copy(dim = true)
        ITALIC -> style.copy(italic = true)
        UNDERLINE -> style.copy(underline = true)
        INVERSE -> style.copy(inverse = true)
        NORMAL_INTENSITY -> style.copy(bold = false, dim = false)
        NO_ITALIC -> style.copy(italic = false)
        NO_UNDERLINE -> style.copy(underline = false)
        NO_INVERSE -> style.copy(inverse = false)
        in FG_BASE..FG_BASE + LAST_COLOR -> style.copy(foreground = code - FG_BASE)
        FG_DEFAULT -> style.copy(foreground = VtStyle.DEFAULT)
        in BG_BASE..BG_BASE + LAST_COLOR -> style.copy(background = code - BG_BASE)
        BG_DEFAULT -> style.copy(background = VtStyle.DEFAULT)
        in FG_BRIGHT..FG_BRIGHT + LAST_COLOR -> style.copy(foreground = code - FG_BRIGHT + BRIGHT_OFFSET)
        in BG_BRIGHT..BG_BRIGHT + LAST_COLOR -> style.copy(background = code - BG_BRIGHT + BRIGHT_OFFSET)
        else -> style
    }

    /** `5;N` или `2;R;G;B` после 38/48; второе — сколько параметров занято. */
    private fun extendedColor(params: List<Int>, at: Int): Pair<Int?, Int> = when (params.getOrNull(at)) {
        PALETTE -> params.getOrNull(at + 1)?.takeIf { it in 0..MAX_PALETTE } to 2
        TRUECOLOR -> {
            val rgb = params.subList(at + 1, minOf(at + RGB_PARTS + 1, params.size))
            val color = if (rgb.size == RGB_PARTS && rgb.all { it in 0..MAX_PALETTE }) {
                VtStyle.RGB_FLAG or (rgb[0] shl RED_SHIFT) or (rgb[1] shl GREEN_SHIFT) or rgb[2]
            } else {
                null
            }
            color to RGB_PARTS + 1
        }
        else -> null to 1
    }

    private const val RESET = 0
    private const val BOLD = 1
    private const val DIM = 2
    private const val ITALIC = 3
    private const val UNDERLINE = 4
    private const val INVERSE = 7
    private const val NORMAL_INTENSITY = 22
    private const val NO_ITALIC = 23
    private const val NO_UNDERLINE = 24
    private const val NO_INVERSE = 27
    private const val FG_BASE = 30
    private const val FG_EXTENDED = 38
    private const val FG_DEFAULT = 39
    private const val BG_BASE = 40
    private const val BG_EXTENDED = 48
    private const val BG_DEFAULT = 49
    private const val FG_BRIGHT = 90
    private const val BG_BRIGHT = 100
    private const val BRIGHT_OFFSET = 8
    private const val PALETTE = 5
    private const val TRUECOLOR = 2
    private const val RGB_PARTS = 3
    private const val MAX_PALETTE = 255
    private const val LAST_COLOR = 7
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
}
