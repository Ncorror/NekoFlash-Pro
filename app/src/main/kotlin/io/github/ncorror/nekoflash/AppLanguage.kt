package io.github.ncorror.nekoflash

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.core.content.edit
import java.util.Locale

/** App-local EN/RU selection without tying locale ownership to a Compose screen. */
object AppLanguage {
    const val ENGLISH = "en"
    const val RUSSIAN = "ru"

    private const val PREFS = "nekoflash_settings"
    private const val KEY_LANGUAGE = "language"

    fun selected(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LANGUAGE, null)

    fun set(context: Context, languageTag: String) {
        require(languageTag == ENGLISH || languageTag == RUSSIAN)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_LANGUAGE, languageTag)
        }
        context.findActivity()?.recreate()
    }

    fun wrap(base: Context): Context {
        val selected = selected(base) ?: return base
        val locale = Locale.forLanguageTag(selected)
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)
        return base.createConfigurationContext(configuration)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Every visible activity reads the same persisted locale before resources are inflated. */
open class LocaleAwareActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }
}
