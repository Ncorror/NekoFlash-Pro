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
import com.ncorror.nekoflash.pro.fastboot.FastbootVariable
import com.ncorror.nekoflash.pro.model.AdbProbePhase
import com.ncorror.nekoflash.pro.model.AdbProbeState
import com.ncorror.nekoflash.pro.model.FastbootProbePhase
import com.ncorror.nekoflash.pro.model.FastbootProbeState
import com.ncorror.nekoflash.pro.model.UsbDeviceSummary
import com.ncorror.nekoflash.pro.model.UsbHostSnapshot
import com.ncorror.nekoflash.pro.model.UsbNotice
import com.ncorror.nekoflash.pro.transport.UsbWireProtocol

/** Render immutable state and forward user actions; no Android USB APIs are used here. */
@Composable
fun UsbScreen(
    snapshot: UsbHostSnapshot,
    adbProbeState: AdbProbeState?,
    probeState: FastbootProbeState?,
    onRefresh: () -> Unit,
    onRequestAccess: (String) -> Unit,
    onProbeAdb: (UsbDeviceSummary) -> Unit,
    onAuthorizeAdb: (UsbDeviceSummary) -> Unit,
    onCancelAdbAuthorization: () -> Unit,
    onProbeFastboot: (UsbDeviceSummary, FastbootVariable) -> Unit,
    onSaveLog: () -> Unit,
    onSaveArchive: () -> Unit,
    @StringRes exportNotice: Int? = null,
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
            UsbDeviceCard(device, adbProbeState, probeState, onRequestAccess, onProbeAdb,
                onAuthorizeAdb, onCancelAdbAuthorization, onProbeFastboot)
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = palette.surface,
            border = BorderStroke(1.dp, palette.outline),
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.diagnostics_title),
                    color = palette.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.diagnostics_hint),
                    color = palette.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = onSaveArchive,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.primary,
                        contentColor = palette.onPrimary,
                    ),
                ) {
                    Text(stringResource(R.string.diagnostics_save_archive))
                }
                OutlinedButton(
                    onClick = onSaveLog,
                    border = BorderStroke(1.dp, palette.primary),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.primary),
                ) {
                    Text(stringResource(R.string.diagnostics_save))
                }
                if (exportNotice != null) {
                    Text(
                        text = stringResource(exportNotice),
                        color = palette.secondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun UsbDeviceCard(
    device: UsbDeviceSummary,
    adbProbeState: AdbProbeState?,
    probeState: FastbootProbeState?,
    onRequestAccess: (String) -> Unit,
    onProbeAdb: (UsbDeviceSummary) -> Unit,
    onAuthorizeAdb: (UsbDeviceSummary) -> Unit,
    onCancelAdbAuthorization: () -> Unit,
    onProbeFastboot: (UsbDeviceSummary, FastbootVariable) -> Unit,
) {
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
            if (device.interfaceInspectionFailed) {
                Text(
                    text = stringResource(R.string.usb_interface_inspection_failed),
                    color = palette.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else if (device.interfaces.isEmpty()) {
                Text(
                    text = stringResource(R.string.usb_no_protocol_interface),
                    color = palette.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                device.interfaces.forEach { usbInterface ->
                    Text(
                        text = stringResource(
                            if (usbInterface.bulkPairAvailable) R.string.usb_protocol_bulk_pair
                            else R.string.usb_protocol_no_bulk_pair,
                            usbInterface.protocol.name,
                            usbInterface.configurationIndex,
                            usbInterface.id,
                        ),
                        color = if (usbInterface.bulkPairAvailable) palette.secondary
                            else palette.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
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
            val probeBusy = adbProbeState?.phase in setOf(AdbProbePhase.RUNNING,
                AdbProbePhase.AUTHORIZING, AdbProbePhase.WAITING_CONFIRMATION) ||
                probeState?.phase == FastbootProbePhase.RUNNING
            val canProbeAdb = device.accessGranted && device.interfaces.count {
                it.protocol == UsbWireProtocol.ADB && it.bulkPairAvailable && it.configurationIndex == 0
            } == 1
            if (canProbeAdb) {
                OutlinedButton(
                    onClick = { onProbeAdb(device) },
                    enabled = !probeBusy,
                    border = BorderStroke(1.dp, palette.primary),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.primary),
                ) {
                    Text(stringResource(R.string.adb_probe_button))
                }
                Text(
                    text = stringResource(R.string.adb_authorize_hint),
                    color = palette.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(
                    onClick = { onAuthorizeAdb(device) },
                    enabled = !probeBusy,
                    border = BorderStroke(1.dp, palette.primary),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.primary),
                ) {
                    Text(stringResource(R.string.adb_authorize_button))
                }
            }
            val currentAdb = adbProbeState?.takeIf { it.matches(device) }
            if (currentAdb != null) {
                Text(
                    text = when (currentAdb.phase) {
                        AdbProbePhase.RUNNING -> stringResource(R.string.adb_probe_running)
                        AdbProbePhase.AUTHORIZING -> stringResource(R.string.adb_authorizing)
                        AdbProbePhase.WAITING_CONFIRMATION -> stringResource(R.string.adb_waiting_confirmation)
                        AdbProbePhase.CONNECTED -> stringResource(
                            R.string.adb_probe_connected,
                            currentAdb.protocolVersion?.let { "0x${it.toString(16).padStart(8, '0')}" } ?: "?",
                            currentAdb.maxData ?: 0L,
                        )
                        AdbProbePhase.AUTH_REQUIRED -> stringResource(R.string.adb_probe_auth_required)
                        AdbProbePhase.TLS_REQUIRED -> stringResource(R.string.adb_probe_tls_required)
                        AdbProbePhase.FAILED -> stringResource(R.string.adb_probe_failed)
                    },
                    color = palette.secondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (currentAdb.phase == AdbProbePhase.AUTHORIZING ||
                    currentAdb.phase == AdbProbePhase.WAITING_CONFIRMATION) {
                    OutlinedButton(
                        onClick = onCancelAdbAuthorization,
                        border = BorderStroke(1.dp, palette.primary),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.primary),
                    ) {
                        Text(stringResource(R.string.adb_cancel_authorization))
                    }
                }
            }
            val canProbe = device.accessGranted && device.interfaces.count {
                it.protocol == UsbWireProtocol.FASTBOOT && it.bulkPairAvailable &&
                    it.configurationIndex == 0
            } == 1
            if (canProbe) {
                OutlinedButton(
                    onClick = { onProbeFastboot(device, FastbootVariable.VERSION) },
                    enabled = !probeBusy,
                    border = BorderStroke(1.dp, palette.primary),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.primary),
                ) {
                    Text(stringResource(R.string.fastboot_probe_version_button))
                }
                OutlinedButton(
                    onClick = { onProbeFastboot(device, FastbootVariable.PRODUCT) },
                    enabled = !probeBusy,
                    border = BorderStroke(1.dp, palette.primary),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.primary),
                ) {
                    Text(stringResource(R.string.fastboot_probe_product_button))
                }
            }
            val currentProbe = probeState?.takeIf { it.matches(device) }
            if (currentProbe != null) {
                val variableName = stringResource(currentProbe.variable.labelId())
                Text(
                    text = when (currentProbe.phase) {
                        FastbootProbePhase.RUNNING -> stringResource(R.string.fastboot_probe_running, variableName)
                        FastbootProbePhase.VALUE -> stringResource(
                            R.string.fastboot_probe_value, variableName, currentProbe.detail.orEmpty(),
                        )
                        FastbootProbePhase.REJECTED -> stringResource(
                            R.string.fastboot_probe_rejected, currentProbe.variable.wireName,
                            currentProbe.detail.orEmpty(),
                        )
                        FastbootProbePhase.FAILED -> stringResource(R.string.fastboot_probe_failed, variableName)
                    },
                    color = palette.secondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@StringRes
private fun FastbootVariable.labelId(): Int = when (this) {
    FastbootVariable.VERSION -> R.string.fastboot_variable_version
    FastbootVariable.PRODUCT -> R.string.fastboot_variable_product
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
            adbProbeState = null,
            probeState = null,
            onRefresh = {},
            onRequestAccess = {},
            onProbeAdb = {},
            onAuthorizeAdb = {},
            onCancelAdbAuthorization = {},
            onProbeFastboot = { _, _ -> },
            onSaveLog = {},
            onSaveArchive = {},
        )
    }
}
