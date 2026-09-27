package io.github.ncorror.nekoflash.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * Тема NekoFlash.
 *
 * Material 3 используется как platform toolkit, но визуальная система остаётся
 * собственной (`05` §2): цвета берутся из опорной палитры Legacy, а не из
 * генератора Material. До этой правки здесь стояли дефолтные
 * `lightColorScheme()`/`darkColorScheme()` — осознанный placeholder периода
 * bootstrap, и `05` §2 прямо называл его подлежащим замене в этой фазе.
 *
 * Dynamic color намеренно **не** включается. Он подменил бы фирменный оранжевый
 * цветом обоев пользователя, а `accent` здесь — часть узнавания продукта, а не
 * украшение. Это решение вида, а не возможности: оно ничего не запрещает.
 */
@Composable
fun NekoFlashTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content,
    )
}

/**
 * Тёмная схема — основная.
 *
 * Роли сопоставлены так же, как в Legacy `values/styles.xml`: `accent` идёт в
 * `primary`, холодный акцент — в `secondary`, а не наоборот. Порядок здесь
 * существенный: `primary` красит главные действия, и отдать его холодному
 * значило бы поменять характер продукта местами.
 */
private val DarkScheme = darkColorScheme(
    primary = NekoFlashColors.Accent,
    onPrimary = NekoFlashColors.OnAccent,
    primaryContainer = NekoFlashColors.AccentPressed,
    onPrimaryContainer = NekoFlashColors.TextPrimary,
    secondary = NekoFlashColors.CoolAccent,
    onSecondary = NekoFlashColors.OnAccent,
    secondaryContainer = NekoFlashColors.CoolAccentDim,
    onSecondaryContainer = NekoFlashColors.TextPrimary,
    tertiary = NekoFlashColors.AccentSoft,
    onTertiary = NekoFlashColors.OnAccent,
    background = NekoFlashColors.SurfaceBase,
    onBackground = NekoFlashColors.TextPrimary,
    surface = NekoFlashColors.SurfaceCard,
    onSurface = NekoFlashColors.TextPrimary,
    surfaceVariant = NekoFlashColors.SurfaceElevated,
    onSurfaceVariant = NekoFlashColors.TextSecondary,
    surfaceContainer = NekoFlashColors.SurfaceHeader,
    outline = NekoFlashColors.Stroke,
    outlineVariant = NekoFlashColors.TextMuted,
    error = NekoFlashColors.Error,
    onError = NekoFlashColors.OnAccent,
)

/** Светлая схема — выведенная, а не перенесённая: см. [NekoFlashLightColors]. */
private val LightScheme = lightColorScheme(
    primary = NekoFlashLightColors.Accent,
    onPrimary = NekoFlashLightColors.SurfaceCard,
    primaryContainer = NekoFlashColors.AccentSoft,
    onPrimaryContainer = NekoFlashLightColors.TextPrimary,
    secondary = NekoFlashLightColors.CoolAccent,
    onSecondary = NekoFlashLightColors.SurfaceCard,
    secondaryContainer = NekoFlashLightColors.CoolAccentDim,
    onSecondaryContainer = NekoFlashLightColors.TextPrimary,
    tertiary = NekoFlashColors.Accent,
    onTertiary = NekoFlashColors.OnAccent,
    background = NekoFlashLightColors.SurfaceBase,
    onBackground = NekoFlashLightColors.TextPrimary,
    surface = NekoFlashLightColors.SurfaceCard,
    onSurface = NekoFlashLightColors.TextPrimary,
    surfaceVariant = NekoFlashLightColors.SurfaceElevated,
    onSurfaceVariant = NekoFlashLightColors.TextSecondary,
    outline = NekoFlashLightColors.Stroke,
    outlineVariant = NekoFlashLightColors.TextSecondary,
    error = NekoFlashLightColors.Error,
    onError = NekoFlashLightColors.SurfaceCard,
)
