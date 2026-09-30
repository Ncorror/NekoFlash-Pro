package io.github.ncorror.nekoflash.ui

import android.content.Context
import android.content.SharedPreferences

/**
 * Видел ли пользователь первый экран.
 *
 * Хранится в приватных настройках приложения, а значит **не переживает
 * переустановку** — и это правильно, а не упущение: `11` §11 исключает
 * app-managed state из облачной резервной копии и переноса между устройствами
 * fail-closed. Показать вводный экран ещё раз после переустановки дешевле, чем
 * завести ещё одну вещь, которая тихо переезжает между телефонами.
 *
 * Это не гейт: [seen] отвечает только на вопрос «показывать ли вводный экран»,
 * и ни на что больше не влияет.
 */
internal class FirstRun(context: Context) {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    var seen: Boolean
        get() = preferences.getBoolean(KEY, false)
        set(value) {
            preferences.edit().putBoolean(KEY, value).apply()
        }

    private companion object {
        const val STORE = "nekoflash.first-run"
        const val KEY = "welcome.seen"
    }
}
