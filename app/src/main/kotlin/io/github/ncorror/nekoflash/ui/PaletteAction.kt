package io.github.ncorror.nekoflash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ncorror.nekoflash.R

/**
 * Одно действие, которое можно найти по имени.
 *
 * [keywords] — то, чем это действие называют **помимо** подписи на кнопке:
 * оператор ищет «стереть», а кнопка называется «Форматировать», и наоборот.
 * Слова здесь русские и английские вперемешку намеренно: раскладку в спешке
 * переключают не всегда.
 */
data class PaletteAction(
    val label: String,
    val keywords: String,
    val run: () -> Unit,
)

/**
 * Поиск действия по имени.
 *
 * Экран профессионального инструмента неизбежно длинный: возможностей много, и
 * прятать их за «уровнями» устав запрещает прямо. Палитра решает не это, а
 * другое — **сколько листать**, чтобы добраться до знакомого действия.
 *
 * Она ничего не добавляет и ничего не убирает: каждое действие здесь есть и на
 * своём месте. Это второй путь к тому же, а не единственный, и в этом смысл:
 * оператор, который помнит название, набирает три буквы; оператор, который
 * ищет глазами, листает, как раньше.
 */
@Composable
internal fun CommandPalette(actions: List<PaletteAction>, modifier: Modifier = Modifier) {
    var query by rememberSaveable { mutableStateOf("") }
    val matches = remember(actions, query) { filter(actions, query) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { text -> query = text },
            label = { Text(stringResource(R.string.palette_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (query.isBlank()) {
            Text(
                text = stringResource(R.string.palette_hint, actions.size),
                style = MaterialTheme.typography.bodySmall,
            )
        } else if (matches.isEmpty()) {
            // Пустой ответ говорится словами: список, исчезнувший без
            // объяснения, читается как поломка поиска.
            Text(
                text = stringResource(R.string.palette_nothing, query),
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            matches.forEach { action ->
                TextButton(
                    onClick = {
                        action.run()
                        query = ""
                    },
                ) {
                    Text(action.label)
                }
            }
        }
    }
}

/**
 * Отбор по подстроке — без регистра и по обоим полям.
 *
 * Нечёткого поиска здесь нет намеренно: он угадывает, а угадавший не туда
 * инструмент прошивки дороже, чем лишняя буква при наборе.
 */
internal fun filter(actions: List<PaletteAction>, query: String): List<PaletteAction> {
    val needle = query.trim()
    return if (needle.isEmpty()) {
        emptyList()
    } else {
        actions.filter { action ->
            action.label.contains(needle, ignoreCase = true) ||
                action.keywords.contains(needle, ignoreCase = true)
        }
    }
}
