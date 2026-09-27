package io.github.ncorror.nekoflash.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Отбор действий по набранному.
 *
 * Проверяется не «поиск работает», а то, что он **не угадывает**: нечёткого
 * совпадения здесь нет намеренно — инструмент прошивки, угадавший не туда,
 * дороже лишней буквы при наборе.
 */
class CommandPaletteTest {
    /** Пустой запрос не показывает всё подряд: это не список, а поиск. */
    @Test
    fun anEmptyQueryMatchesNothingRatherThanEverything() {
        assertTrue(filter(actions, "").isEmpty())
        assertTrue(filter(actions, "   ").isEmpty())
    }

    @Test
    fun aSubstringOfTheLabelIsEnough() {
        assertEquals(listOf("Стереть раздел"), filter(actions, "терет").map { it.label })
    }

    /** Регистр не важен: набирают в спешке. */
    @Test
    fun caseDoesNotMatter() {
        assertEquals(1, filter(actions, "СТЕРЕТЬ").size)
    }

    /**
     * Найти можно и по слову, которого нет на кнопке.
     *
     * Оператор ищет «erase», а кнопка называется «Стереть раздел»; раскладку в
     * спешке переключают не всегда.
     */
    @Test
    fun anActionIsFoundByTheWordTheOperatorActuallyUses() {
        assertEquals(listOf("Стереть раздел"), filter(actions, "erase").map { it.label })
    }

    /** Промах — это пустой список, а не случайное совпадение. */
    @Test
    fun aMissIsAMissAndNotTheNearestGuess() {
        assertTrue(filter(actions, "стерет ь").isEmpty())
        assertTrue(filter(actions, "zzz").isEmpty())
    }

    private val actions = listOf(
        PaletteAction("Стереть раздел", "erase wipe очистить") {},
        PaletteAction("Прочитать вердикт Recovery", "verdict install") {},
        PaletteAction("Выгрузить диагностику", "diagnostics export отчёт") {},
    )
}
