package io.github.ncorror.nekoflash.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.res.stringResource
import io.github.ncorror.nekoflash.R
import io.github.ncorror.nekoflash.usb.api.TargetIdentitySource
import io.github.ncorror.nekoflash.usb.api.UsbInterfaceKind
import io.github.ncorror.nekoflash.usb.api.UsbMatchConfidence
import io.github.ncorror.nekoflash.usb.api.UsbScanSummary
import io.github.ncorror.nekoflash.usb.api.UsbSessionState

/**
 * Почему список пуст.
 *
 * Отсутствие host-режима важнее всего остального: без него разбирать нечего, и
 * говорить про кабель было бы неправдой.
 */
@Composable
internal fun emptyStateReason(scan: UsbScanSummary, usbHostSupported: Boolean): String = when {
    !usbHostSupported -> stringResource(R.string.usb_host_feature_missing)
    !scan.scanned -> stringResource(R.string.usb_scan_never)
    scan.visibleDevices == 0 -> stringResource(R.string.usb_scan_none)
    else -> stringResource(R.string.usb_scan_unusable, scan.visibleDevices)
}

/**
 * Подпись и значение под ней.
 *
 * Читается вслух как **одна** вещь, а не две: без слияния TalkBack объявляет
 * «Состояние ADB», потом отдельным шагом «подключено», и связь между ними
 * приходится держать в голове. Пустое значение при этом не озвучивается вовсе —
 * произносить подпись без значения хуже, чем промолчать.
 */
@Composable
internal fun LabelledValue(label: String, value: String) {
    Column(modifier = Modifier.semantics(mergeDescendants = true) { }) {
        Text(text = label, style = MaterialTheme.typography.labelMedium)
        if (value.isNotBlank()) {
            Text(text = value, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/**
 * Заголовок раздела.
 *
 * Помечен как heading: TalkBack умеет прыгать по заголовкам, и на экране такой
 * длины это разница между «нашёл за секунду» и «пролистал всё».
 */
@Composable
internal fun SectionHeading(text: String, style: TextStyle = MaterialTheme.typography.titleSmall) {
    Text(
        text = text,
        style = style,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
internal fun localizedSessionState(state: UsbSessionState): String = stringResource(
    when (state) {
        UsbSessionState.DISCOVERED -> R.string.session_state_discovered
        UsbSessionState.PERMISSION_PENDING -> R.string.session_state_permission_pending
        UsbSessionState.READY -> R.string.session_state_ready
        UsbSessionState.CLAIMED -> R.string.session_state_claimed
        UsbSessionState.CLOSED -> R.string.session_state_closed
    },
)

@Composable
internal fun localizedIdentitySource(source: TargetIdentitySource): String = stringResource(
    when (source) {
        TargetIdentitySource.SERIAL -> R.string.identity_source_serial
        TargetIdentitySource.USB_ATTACHMENT -> R.string.identity_source_attachment
    },
)

@Composable
internal fun localizedInterfaceKind(kind: UsbInterfaceKind): String = stringResource(
    when (kind) {
        UsbInterfaceKind.ADB -> R.string.interface_kind_adb
        UsbInterfaceKind.FASTBOOT -> R.string.interface_kind_fastboot
    },
)

@Composable
internal fun localizedMatchConfidence(confidence: UsbMatchConfidence): String = stringResource(
    when (confidence) {
        UsbMatchConfidence.CANONICAL -> R.string.match_confidence_canonical
        UsbMatchConfidence.ANDROID_COMPATIBLE -> R.string.match_confidence_android_compatible
        UsbMatchConfidence.GENERIC_VENDOR -> R.string.match_confidence_generic_vendor
    },
)
