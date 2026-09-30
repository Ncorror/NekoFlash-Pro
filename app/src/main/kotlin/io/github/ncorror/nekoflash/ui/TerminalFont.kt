package io.github.ncorror.nekoflash.ui

import android.content.res.AssetManager
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext

/** One asset name shared by Compose and the Android EditText bridge. */
internal const val NERD_FONT_ASSET = "fonts/NerdFontMono-Regular.ttf"

/**
 * Loads Nerd Font once per asset manager and keeps a safe monospace fallback.
 * The fallback is deliberate: the source archive does not ship a third-party
 * font, and missing optional assets must not prevent the terminal from opening.
 */
@OptIn(ExperimentalTextApi::class)
@Composable
internal fun rememberTerminalFontFamily(): FontFamily {
    val assets = LocalContext.current.assets
    return remember(assets) {
        runCatching { FontFamily(Font(NERD_FONT_ASSET, assets, FontWeight.Normal, FontStyle.Normal)) }
            .getOrDefault(FontFamily.Monospace)
    }
}

internal fun nerdTypefaceOrMonospace(assets: AssetManager): Typeface =
    runCatching { Typeface.createFromAsset(assets, NERD_FONT_ASSET) }
        .getOrDefault(Typeface.MONOSPACE)
