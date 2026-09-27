package io.github.ncorror.nekoflash.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Опорная палитра NekoFlash.
 *
 * Источник — **не подбор на глаз**, а действующая палитра Legacy
 * (`values/colors.xml` в `reference/archives/NekoFlash-main-legacy.zip`,
 * помеченная там «NekoFlash V6 — restrained cyber-dark palette»), с ролями из
 * `values/styles.xml` того же снапшота. Значения и роли перечислены в
 * `05_FINAL_UI_UX_AND_BRAND_RU.md` §2 и взяты оттуда дословно.
 *
 * A2 этой палитры не унаследовал — у него синие заготовки Material без
 * оранжевого, — поэтому источником бренда служит Legacy, а не A2.
 *
 * Оранжевый здесь — это `accent` **продукта**, а не цвет, снятый с картинки:
 * тёплый неон на Welcome-арте ближе к красному и служит атмосферой сцены, а не
 * токеном интерфейса.
 *
 * `MagicNumber` здесь подавлен намеренно и локально: правило требует вынести
 * число в названную константу, а этот файл **и есть** таблица названных
 * констант. Заводить над каждым цветом второй уровень имён значило бы выполнить
 * букву правила, потеряв его смысл.
 */
@Suppress("MagicNumber")
internal object NekoFlashColors {
    val Accent = Color(0xFFE9782B)
    val AccentPressed = Color(0xFFCC5D18)
    val AccentSoft = Color(0xFFF1A56D)
    val OnAccent = Color(0xFF0A0D12)

    val SurfaceBase = Color(0xFF080D13)
    val SurfaceCard = Color(0xFF121A24)
    val SurfaceElevated = Color(0xFF192431)
    val SurfaceHeader = Color(0xFF0D141D)
    val Stroke = Color(0xFF324052)

    val TextPrimary = Color(0xFFF3F6FA)
    val TextSecondary = Color(0xFFAEB8C5)
    val TextMuted = Color(0xFF738092)

    /** Холодный технологический акцент. Статусы и информационные состояния. */
    val CoolAccent = Color(0xFF59B9E7)
    val CoolAccentDim = Color(0xFF102B3A)

    val Success = Color(0xFF68C979)
    val Warning = Color(0xFFDCAA58)
    val Error = Color(0xFFE06C75)
}

/**
 * Светлая схема.
 *
 * **Выводится, а не переносится механически**: палитра Legacy построена под
 * тёмную тему, и `05` §2 требует вывести светлую отдельно. Выведена она так:
 * акцент остаётся тот же (это бренд, и на светлом он читается), фоны берутся
 * почти белыми с холодным подмесом — чтобы холодный акцент оставался родным, —
 * а тексты инвертируются от `SurfaceBase` вместо чёрного, иначе тёмная и
 * светлая схемы выглядели бы из разных продуктов.
 *
 * Холодный акцент на светлом притемняется: `#59B9E7` на белом даёт контраст
 * ниже 3:1 и на подписи не годится.
 *
 * `MagicNumber` подавлен по той же причине, что и в [NekoFlashColors].
 */
@Suppress("MagicNumber")
internal object NekoFlashLightColors {
    /**
     * Действие на светлом.
     *
     * **Не** `accent` и не `accent_pressed` из палитры: первый на белом даёт
     * 2.6:1, второй 4.08:1 — оба ниже 4.5:1, которых требует текст на кнопке.
     * Это тот же оранжевый, притемнённый до читаемого (4.94:1 на карточке,
     * 4.64:1 на фоне), а не другой цвет.
     */
    val Accent = Color(0xFFB85210)

    val SurfaceBase = Color(0xFFF6F8FB)
    val SurfaceCard = Color(0xFFFFFFFF)
    val SurfaceElevated = Color(0xFFEDF1F6)
    val Stroke = Color(0xFFC6D0DC)

    val TextPrimary = Color(0xFF0D141D)
    val TextSecondary = Color(0xFF3C4757)

    /**
     * Тот же холодный акцент, притемнённый до читаемого на белом.
     *
     * `#59B9E7` на белом даёт 2.21:1 — на подписи не годится вовсе. Здесь
     * 5.57:1 на карточке и 5.24:1 на фоне.
     */
    val CoolAccent = Color(0xFF1B6F97)
    val CoolAccentDim = Color(0xFFDCEBF4)

    val Error = Color(0xFFB3261E)
}
