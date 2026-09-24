package com.ncorror.nekoflash.pro.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.colorResource
import com.ncorror.nekoflash.pro.R

/** Brand tokens match the legacy palette; all layouts and behavior are new. */
@Composable
fun NekoFlashTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = colorResource(R.color.accent),
        onPrimary = colorResource(R.color.on_accent),
        secondary = colorResource(R.color.accent_soft),
        background = colorResource(R.color.bg_base),
        onBackground = colorResource(R.color.text_primary),
        surface = colorResource(R.color.bg_card),
        onSurface = colorResource(R.color.text_primary),
        surfaceVariant = colorResource(R.color.bg_elevated),
        onSurfaceVariant = colorResource(R.color.text_secondary),
        outline = colorResource(R.color.stroke),
    )
    MaterialTheme(colorScheme = colors, content = content)
}
