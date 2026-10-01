package io.github.ncorror.nekoflash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ncorror.nekoflash.AppLanguage
import io.github.ncorror.nekoflash.R

/** Real production settings currently owned by the app: interface language. */
@Composable
internal fun SettingsWorkspace(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val selected = AppLanguage.selected(context)
        ?: configuration.locales[0].language

    Column(
        modifier = modifier.fillMaxWidth().padding(SETTINGS_PADDING),
        verticalArrangement = Arrangement.spacedBy(SETTINGS_GAP),
    ) {
        Text(
            text = stringResource(R.string.settings_language_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.settings_language_note),
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(LANGUAGE_GAP)) {
            if (selected == AppLanguage.ENGLISH) {
                Button(
                    onClick = { AppLanguage.set(context, AppLanguage.ENGLISH) },
                    enabled = false,
                ) {
                    Text(stringResource(R.string.settings_language_english))
                }
            } else {
                OutlinedButton(
                    onClick = { AppLanguage.set(context, AppLanguage.ENGLISH) },
                ) {
                    Text(stringResource(R.string.settings_language_english))
                }
            }
            if (selected == AppLanguage.RUSSIAN) {
                Button(
                    onClick = { AppLanguage.set(context, AppLanguage.RUSSIAN) },
                    enabled = false,
                ) {
                    Text(stringResource(R.string.settings_language_russian))
                }
            } else {
                OutlinedButton(
                    onClick = { AppLanguage.set(context, AppLanguage.RUSSIAN) },
                ) {
                    Text(stringResource(R.string.settings_language_russian))
                }
            }
        }
    }
}

private val SETTINGS_PADDING = 20.dp
private val SETTINGS_GAP = 14.dp
private val LANGUAGE_GAP = 10.dp
