package io.github.ncorror.nekoflash.ui

import androidx.compose.ui.graphics.Color
import io.github.ncorror.nekoflash.ui.theme.NekoFlashColors
import io.github.ncorror.nekoflash.ui.theme.NekoFlashLightColors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Опорная палитра и её читаемость.
 *
 * Цвета — ровно тот сорт значений, который тихо уезжает: одна правка «чуть
 * светлее» никем не замечается, а подпись перестаёт читаться. Поэтому здесь
 * проверяется и **что** за значения (они взяты из `05` §2 дословно), и
 * **хватает ли контраста** там, где на цвете стоит текст.
 *
 * Порог 4.5:1 — обычный текст по WCAG AA. Выбран не из любви к стандартам:
 * приложение читают с телефона в руке, второй рукой держа кабель, и часто при
 * дневном свете.
 */
class NekoFlashPaletteTest {
    /** Фирменный оранжевый — тот самый, из палитры Legacy. */
    @Test
    fun theAccentIsTheOneFromTheReferencePalette() {
        assertEquals(Color(0xFFE9782B), NekoFlashColors.Accent)
        assertEquals(Color(0xFF59B9E7), NekoFlashColors.CoolAccent)
        assertEquals(Color(0xFF080D13), NekoFlashColors.SurfaceBase)
    }

    /** Подпись на карточке читается. */
    @Test
    fun textOnTheDarkCardIsReadable() {
        assertReadable(NekoFlashColors.TextPrimary, NekoFlashColors.SurfaceCard)
        assertReadable(NekoFlashColors.TextSecondary, NekoFlashColors.SurfaceCard)
    }

    /** Акцент на тёмном читается, и надпись на самом акценте тоже. */
    @Test
    fun theAccentWorksBothAsInkAndAsBackground() {
        assertReadable(NekoFlashColors.Accent, NekoFlashColors.SurfaceCard)
        assertReadable(NekoFlashColors.OnAccent, NekoFlashColors.Accent)
    }

    /**
     * Холодный акцент палитры на белом **не** читается — и потому на светлой
     * схеме стоит не он.
     *
     * Тест сторожит именно это: если кто-то «упростит» светлую схему, взяв
     * `#59B9E7` из палитры напрямую, подписи станут нечитаемыми, и молча.
     */
    @Test
    fun theDarkCoolAccentIsNotReusedOnLightBecauseItCannotBe() {
        assertTrue(
            "холодный акцент палитры на белом даёт меньше 3:1",
            contrast(NekoFlashColors.CoolAccent, NekoFlashLightColors.SurfaceCard) < BARELY,
        )
        assertReadable(NekoFlashLightColors.CoolAccent, NekoFlashLightColors.SurfaceCard)
        assertReadable(NekoFlashLightColors.CoolAccent, NekoFlashLightColors.SurfaceBase)
    }

    /**
     * Оранжевый на светлой схеме тоже притемнён, и по той же причине.
     *
     * `accent` даёт на белом 2.6:1, `accent_pressed` — 4.08:1; оба ниже порога
     * для текста на кнопке.
     */
    @Test
    fun theLightAccentIsDarkenedEnoughToCarryButtonText() {
        assertReadable(NekoFlashLightColors.SurfaceCard, NekoFlashLightColors.Accent)
        assertTrue(
            "непритемнённый accent_pressed порога не берёт",
            contrast(NekoFlashColors.AccentPressed, NekoFlashLightColors.SurfaceCard) < MINIMUM,
        )
    }

    @Test
    fun textOnTheLightSurfacesIsReadable() {
        assertReadable(NekoFlashLightColors.TextPrimary, NekoFlashLightColors.SurfaceCard)
        assertReadable(NekoFlashLightColors.TextSecondary, NekoFlashLightColors.SurfaceCard)
        assertReadable(NekoFlashLightColors.TextPrimary, NekoFlashLightColors.SurfaceBase)
    }

    private fun assertReadable(ink: Color, background: Color) {
        val value = contrast(ink, background)
        assertTrue("контраст $value ниже порога $MINIMUM", value >= MINIMUM)
    }

    private fun contrast(first: Color, second: Color): Double {
        val a = luminance(first)
        val b = luminance(second)
        return (max(a, b) + OFFSET) / (min(a, b) + OFFSET)
    }

    private fun luminance(color: Color): Double =
        RED * channel(color.red) + GREEN * channel(color.green) + BLUE * channel(color.blue)

    private fun channel(value: Float): Double {
        val c = value.toDouble()
        return if (c <= THRESHOLD) c / DIVISOR else ((c + ALPHA) / (1 + ALPHA)).pow(GAMMA)
    }

    private companion object {
        const val MINIMUM = 4.5
        const val BARELY = 3.0
        const val OFFSET = 0.05
        const val RED = 0.2126
        const val GREEN = 0.7152
        const val BLUE = 0.0722
        const val THRESHOLD = 0.03928
        const val DIVISOR = 12.92
        const val ALPHA = 0.055
        const val GAMMA = 2.4
    }
}
