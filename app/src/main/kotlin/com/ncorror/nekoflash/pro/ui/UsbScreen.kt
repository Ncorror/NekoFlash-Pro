package com.ncorror.nekoflash.pro.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.ncorror.nekoflash.pro.R
import com.ncorror.nekoflash.pro.model.UsbDeviceSummary
import com.ncorror.nekoflash.pro.model.UsbHostSnapshot
import com.ncorror.nekoflash.pro.model.UsbNotice

/** Render immutable state and forward user actions; no Android USB APIs are used here. */
@Composable
fun UsbScreen(
    snapshot: UsbHostSnapshot,
    onRefresh: () -> Unit,
    onRequestAccess: (String) -> Unit,
) {
    val palette = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.usb_eyebrow),
            color = palette.primary,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.app_name),
            color = palette.onBackground,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
        )
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = palette.surface,
            border = BorderStroke(1.dp, palette.outline),
        ) {
            Column(Modifier.padding(18.dp)) {
                Text(
                    text = stringResource(R.string.usb_preview_title),
                    color = palette.secondary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.prototype_status),
                    color = palette.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Text(
            text = stringResource(R.string.usb_section_title),
            color = palette.onBackground,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Button(
            onClick = onRefresh,
            colors = ButtonDefaults.buttonColors(
                containerColor = palette.primary,
                contentColor = palette.onPrimary,
            ),
        ) {
            Text(stringResource(R.string.usb_refresh))
        }
        Text(
            text = when {
                !snapshot.supported -> stringResource(R.string.usb_host_unsupported)
                snapshot.devices.isEmpty() -> stringResource(R.string.usb_no_devices)
                else -> stringResource(R.string.usb_device_count, snapshot.devices.size)
            },
            color = palette.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
        if (snapshot.notice != null) {
            Text(
                text = stringResource(snapshot.notice.stringId()),
                color = palette.secondary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (snapshot.devices.isEmpty() && snapshot.supported) {
            Text(
                text = stringResource(R.string.usb_hint),
                color = palette.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        snapshot.devices.forEach { device ->
            UsbDeviceCard(device, onRequestAccess)
        }
    }
}

@Composable
private fun UsbDeviceCard(device: UsbDeviceSummary, onRequestAccess: (String) -> Unit) {
    val palette = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = palette.surface,
        border = BorderStroke(1.dp, palette.outline),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = stringResource(
                    R.string.usb_device_label, device.vendorId, device.productId, device.deviceId,
                ),
                color = palette.onSurface,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(
                    when {
                        device.accessGranted -> R.string.usb_access_granted
                        device.accessPending -> R.string.usb_access_pending
                        else -> R.string.usb_access_missing
                    },
                ),
                color = if (device.accessGranted) palette.secondary else palette.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!device.accessGranted) {
                OutlinedButton(
                    onClick = { onRequestAccess(device.key) },
                    enabled = !device.accessPending,
                    border = BorderStroke(1.dp, palette.primary),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.primary),
                ) {
                    Text(stringResource(R.string.usb_request_permission))
                }
            }
        }
    }
}

@StringRes
private fun UsbNotice.stringId(): Int = when (this) {
    UsbNotice.DEVICE_CHANGED -> R.string.usb_device_changed
    UsbNotice.DEVICE_DISCONNECTED -> R.string.usb_device_disconnected
    UsbNotice.ACCESS_GRANTED -> R.string.usb_permission_granted
    UsbNotice.ACCESS_DENIED -> R.string.usb_permission_denied
    UsbNotice.REQUEST_UNAVAILABLE -> R.string.usb_permission_unavailable
}

@Preview(showBackground = true)
@Composable
private fun UsbScreenPreview() {
    NekoFlashTheme {
        UsbScreen(
            snapshot = UsbHostSnapshot(devices = listOf(
                UsbDeviceSummary("preview", 0x18D1, 0x4EE0, 1, false, false),
            )),
            onRefresh = {},
            onRequestAccess = {},
        )
    }
}
