package io.github.ncorror.nekoflash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ncorror.nekoflash.R

/**
 * Первый экран.
 *
 * `05` §3 требует от него ровно четырёх вещей: представить продукт, сохранить
 * бренд, **коротко объяснить модель Android USB Host** и привести к рабочему
 * месту. Объяснение здесь не украшение: без него первое, что видит человек, —
 * это пустой список устройств, и непонятно, чей это отказ.
 *
 * Чего здесь нет и не будет, сказано там же прямым текстом:
 *
 * - обязательного «принимаю риски прошивки» как **разрешения пользоваться**
 *   продуктом;
 * - чеклиста батареи, файлов и уведомлений как продуктового capability gate;
 * - повторных пугалок перед «экспертными» инструментами.
 *
 * Кнопка внизу — это «понял, дальше», а не согласие с условиями. Welcome не
 * выдаёт пользователю право быть профессионалом: у него оно уже есть
 * (`01` §3).
 *
 * Разрешения спрашиваются контекстно, когда они действительно нужны платформе,
 * и ни одно из них не спрашивается здесь.
 */
@Composable
internal fun WelcomeScreen(onContinue: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionHeading(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge,
            )
            Text(
                text = stringResource(R.string.app_tagline),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
            Text(
                text = stringResource(R.string.welcome_capabilities),
                style = MaterialTheme.typography.bodyLarge,
            )
            SectionHeading(text = stringResource(R.string.welcome_usb_title))
            Text(
                text = stringResource(R.string.welcome_usb_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onContinue) {
                Text(stringResource(R.string.welcome_continue))
            }
        }
    }
}
