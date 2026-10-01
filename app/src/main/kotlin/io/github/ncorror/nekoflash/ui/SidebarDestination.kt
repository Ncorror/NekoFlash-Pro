package io.github.ncorror.nekoflash.ui

import androidx.annotation.StringRes
import io.github.ncorror.nekoflash.R

/**
 * Единственная навигация production UI.
 *
 * Главный экран всегда Terminal. Остальные возможности вынесены в drawer,
 * чтобы рабочая область терминала не была окружена панелями и тестовыми
 * элементами.
 */
internal enum class SidebarDestination(@StringRes val label: Int) {
    TERMINAL(R.string.nav_terminal),
    FAST_FLASHING(R.string.nav_fast_flashing),
    SIDELOAD(R.string.nav_sideload),
    MI_UNLOCK(R.string.nav_mi_unlock),
    LOGS(R.string.nav_logs),
    SETTINGS(R.string.nav_settings),
}
