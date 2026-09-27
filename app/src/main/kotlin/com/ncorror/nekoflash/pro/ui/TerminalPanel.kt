package com.ncorror.nekoflash.pro.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ncorror.nekoflash.pro.R
import com.ncorror.nekoflash.pro.engine.TerminalParser
import com.ncorror.nekoflash.pro.engine.TerminalPlan
import com.ncorror.nekoflash.pro.engine.TerminalPlanCompiler
import com.ncorror.nekoflash.pro.engine.TerminalPlanResult
import com.ncorror.nekoflash.pro.engine.TerminalPlanStep
import com.ncorror.nekoflash.pro.model.UsbDeviceSummary
import com.ncorror.nekoflash.pro.model.UsbHostSnapshot
import com.ncorror.nekoflash.pro.model.SelectedImage
import com.ncorror.nekoflash.pro.transport.UsbWireProtocol

/** Preview and execution share the same immutable plan captured by the button. */
private data class PendingFlash(val step: TerminalPlanStep.FastbootFlashRaw,
                                val device: UsbDeviceSummary)

@Composable
internal fun TerminalPanel(
    snapshot: UsbHostSnapshot,
    busy: Boolean,
    selectedImage: SelectedImage?,
    onSelectImage: () -> Unit,
    onRunPlan: (UsbDeviceSummary, TerminalPlan) -> Unit,
) {
    val palette = MaterialTheme.colorScheme
    var adbTab by rememberSaveable { mutableStateOf(false) }
    var adbInput by rememberSaveable { mutableStateOf("shell getprop ro.product.device") }
    var fastbootInput by rememberSaveable { mutableStateOf("getvar product") }
    var pendingFlash by remember { mutableStateOf<PendingFlash?>(null) }
    val tool = if (adbTab) TerminalParser.Tool.ADB else TerminalParser.Tool.FASTBOOT
    val input = if (adbTab) adbInput else fastbootInput
    val compiler = remember { TerminalPlanCompiler() }
    val initial = remember(tool, input) { compiler.compile(tool, input) }
    val compilation = if (initial is TerminalPlanResult.RequiresImage && selectedImage != null) {
        compiler.attachImage(initial, selectedImage.displayName, selectedImage.size, selectedImage.selectionId)
    } else initial
    val ready = compilation as? TerminalPlanResult.Ready
    val protocol = when (ready?.plan?.steps?.singleOrNull()) {
        is TerminalPlanStep.FastbootGetvar -> UsbWireProtocol.FASTBOOT
        is TerminalPlanStep.FastbootFlashRaw -> UsbWireProtocol.FASTBOOT
        TerminalPlanStep.AdbProduct -> UsbWireProtocol.ADB
        else -> null
    }
    val device = snapshot.devices.singleOrNull()?.takeIf { candidate ->
        candidate.accessGranted && candidate.interfaces.count {
            it.protocol == protocol && it.bulkPairAvailable && it.configurationIndex == 0
        } == 1
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = palette.surface,
        border = BorderStroke(1.dp, palette.outline),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.terminal_title), color = palette.onSurface,
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.terminal_hint), color = palette.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { adbTab = true },
                    border = BorderStroke(1.dp, if (adbTab) palette.primary else palette.outline),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.primary)) {
                    Text(stringResource(R.string.terminal_tab_adb))
                }
                OutlinedButton(onClick = { adbTab = false },
                    border = BorderStroke(1.dp, if (!adbTab) palette.primary else palette.outline),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.primary)) {
                    Text(stringResource(R.string.terminal_tab_fastboot))
                }
            }
            OutlinedTextField(
                value = input,
                onValueChange = { value ->
                    if (value.length <= 256) {
                        if (adbTab) adbInput = value else fastbootInput = value
                    }
                },
                label = { Text(stringResource(R.string.terminal_command_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (initial is TerminalPlanResult.RequiresImage) {
                if (selectedImage != null) {
                    Text(stringResource(R.string.terminal_selected_image,
                        selectedImage.displayName, selectedImage.size),
                        color = palette.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedButton(onClick = onSelectImage, enabled = !busy,
                    border = BorderStroke(1.dp, palette.primary),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.primary)) {
                    Text(stringResource(R.string.terminal_choose_image))
                }
            }
            when (compilation) {
                is TerminalPlanResult.Ready -> {
                    Text(stringResource(R.string.terminal_preview,
                        compilation.plan.preview().joinToString("; ")),
                        color = palette.secondary, style = MaterialTheme.typography.bodyMedium)
                    if (device == null) {
                        Text(stringResource(R.string.terminal_connect_hint), color = palette.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
                is TerminalPlanResult.RequiresImage -> Text(
                    stringResource(R.string.terminal_need_image), color = palette.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium)
                is TerminalPlanResult.Invalid -> Text(
                    stringResource(R.string.terminal_invalid), color = palette.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium)
                is TerminalPlanResult.Unavailable -> Text(
                    stringResource(R.string.terminal_unavailable), color = palette.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium)
            }
            Button(
                onClick = {
                    if (device != null && ready != null) {
                        when (val step = ready.plan.steps.singleOrNull()) {
                            is TerminalPlanStep.FastbootFlashRaw -> pendingFlash = PendingFlash(step, device)
                            else -> onRunPlan(device, ready.plan)
                        }
                    }
                },
                enabled = device != null && ready != null && !busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.primary, contentColor = palette.onPrimary,
                ),
            ) { Text(stringResource(R.string.terminal_execute)) }
            Text(stringResource(R.string.terminal_result_hint), color = palette.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium)
        }
    }
    val awaiting = pendingFlash
    if (awaiting != null) {
        AlertDialog(
            onDismissRequest = { pendingFlash = null },
            title = { Text(stringResource(R.string.terminal_flash_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.terminal_flash_confirm_body,
                        awaiting.step.partition, selectedImage?.displayName.orEmpty(),
                        awaiting.step.imageSize))
                    Text(stringResource(R.string.usb_device_label, awaiting.device.vendorId,
                        awaiting.device.productId, awaiting.device.deviceId))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val current = ready?.plan?.steps?.singleOrNull()
                    if (ready != null && current == awaiting.step && device != null &&
                        device == awaiting.device && !busy) {
                        onRunPlan(device, ready.plan)
                    }
                    pendingFlash = null
                }, enabled = ready?.plan?.steps?.singleOrNull() == awaiting.step &&
                    device == awaiting.device && !busy) {
                    Text(stringResource(R.string.terminal_flash_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingFlash = null }) {
                    Text(stringResource(R.string.terminal_flash_cancel))
                }
            },
        )
    }
}
