package io.github.ncorror.nekoflash.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import io.github.ncorror.nekoflash.R

/**
 * The first screen keeps the legacy artwork visible. The copy and action stay
 * inside system insets, including on phones with three-button navigation.
 * It explains USB ownership without asking for permissions or capability gates.
 */
@Composable
internal fun WelcomeScreen(onContinue: () -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val compact = maxHeight < 520.dp
        val titleStyle = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineLarge
        Image(
            painter = painterResource(R.drawable.bg_welcome),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = titleStyle.onArtwork(),
            )
            if (!compact) {
                Text(
                    text = stringResource(R.string.app_tagline),
                    style = MaterialTheme.typography.titleSmall.onArtwork(),
                )
            }
            Spacer(Modifier.weight(1f))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = if (compact) 140.dp else 220.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.welcome_usb_title),
                    style = MaterialTheme.typography.titleMedium.onArtwork(),
                )
                Text(
                    text = stringResource(R.string.welcome_usb_body),
                    style = MaterialTheme.typography.bodyMedium.onArtwork(),
                )
                Text(
                    text = stringResource(R.string.welcome_capabilities),
                    style = MaterialTheme.typography.labelMedium.onArtwork(),
                )
            }
            Button(
                onClick = onContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .heightIn(min = 56.dp),
            ) {
                Text(stringResource(R.string.welcome_continue))
            }
        }
    }
}

private fun TextStyle.onArtwork(): TextStyle = copy(
    color = Color.White,
    shadow = Shadow(
        color = Color.Black.copy(alpha = 0.9f),
        offset = Offset(0f, 2f),
        blurRadius = 8f,
    ),
)
