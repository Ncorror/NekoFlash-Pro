package io.github.ncorror.nekoflash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ncorror.nekoflash.R
import io.github.ncorror.nekoflash.fastboot.FastbootLinkState
import io.github.ncorror.nekoflash.miunlock.MiAccountState
import io.github.ncorror.nekoflash.miunlock.MiUnlockStatus
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootLockState
import io.github.ncorror.nekoflash.vendor.xiaomi.MiAccountClient

data class MiUnlockPanel(
    val account: MiAccountState,
    val operation: MiUnlockStatus,
    val fastboot: FastbootLinkState,
    val onSignIn: () -> Unit,
    val onSignOut: () -> Unit,
    val onChangeZone: (String) -> Unit,
    val onUnlock: () -> Unit,
)

@Composable
fun MiUnlockSection(panel: MiUnlockPanel) {
    var confirm by remember { mutableStateOf(false) }
    val connected = panel.fastboot as? FastbootLinkState.Connected
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.mi_unlock_warning), color = MaterialTheme.colorScheme.error)
            MiAccountSection(panel)
            when {
                connected == null -> Text(stringResource(R.string.mi_unlock_need_fastboot))
                connected.lock.state == FastbootLockState.UNLOCKED -> Text(stringResource(R.string.mi_unlock_already))
                else -> Text(stringResource(R.string.mi_unlock_ready))
            }
            MiUnlockProgress(panel.operation)
            val running = panel.operation is MiUnlockStatus.Running
            Button(
                onClick = { confirm = true },
                enabled = panel.account is MiAccountState.SignedIn && connected != null &&
                    connected.lock.state != FastbootLockState.UNLOCKED && !running,
            ) { Text(stringResource(R.string.mi_unlock_action)) }
        }
    }
    if (confirm && connected != null) MiUnlockConfirmationDialog(
        locked = connected.lock.state == FastbootLockState.LOCKED,
        onDismiss = { confirm = false },
        onConfirm = { confirm = false; panel.onUnlock() },
    )
}

@Composable
private fun MiAccountSection(panel: MiUnlockPanel) {
    var showZones by remember { mutableStateOf(false) }
    when (val account = panel.account) {
        MiAccountState.SignedOut -> Button(onClick = panel.onSignIn) {
            Text(stringResource(R.string.mi_unlock_sign_in))
        }
        MiAccountState.SigningIn -> Text(stringResource(R.string.mi_unlock_signing_in))
        MiAccountState.Failed -> {
            Text(stringResource(R.string.mi_unlock_auth_failed))
            Button(onClick = panel.onSignIn) { Text(stringResource(R.string.mi_unlock_sign_in)) }
        }
        is MiAccountState.SignedIn -> {
            Text(stringResource(R.string.mi_unlock_account, account.userId, account.region, account.zone))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column {
                    OutlinedButton(onClick = { showZones = true }) {
                        Text(stringResource(R.string.mi_unlock_change_zone))
                    }
                    DropdownMenu(expanded = showZones, onDismissRequest = { showZones = false }) {
                        MiAccountClient.dataCenterZones().forEach { zone ->
                            DropdownMenuItem(text = { Text(zone) }, onClick = {
                                showZones = false
                                panel.onChangeZone(zone)
                            })
                        }
                    }
                }
                OutlinedButton(onClick = panel.onSignOut) { Text(stringResource(R.string.mi_unlock_sign_out)) }
            }
        }
    }
}

@Composable
private fun MiUnlockConfirmationDialog(locked: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.mi_unlock_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.mi_unlock_warning))
                    if (locked) {
                        Text(stringResource(R.string.mi_unlock_type_word))
                        OutlinedTextField(value = typed, onValueChange = { typed = it }, singleLine = true)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !locked || typed == "UNLOCK",
                    onClick = onConfirm,
                ) { Text(stringResource(R.string.mi_unlock_action)) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.mi_unlock_cancel)) }
            },
    )
}

@Composable
private fun MiUnlockProgress(status: MiUnlockStatus) {
    val text = when (status) {
        MiUnlockStatus.Idle -> return
        is MiUnlockStatus.Running -> when (status.stage) {
            MiUnlockStatus.Stage.DEVICE -> R.string.mi_unlock_step_device
            MiUnlockStatus.Stage.NONCE -> R.string.mi_unlock_step_nonce
            MiUnlockStatus.Stage.CHECK -> R.string.mi_unlock_step_check
            MiUnlockStatus.Stage.AUTHORIZE -> R.string.mi_unlock_step_authorize
            MiUnlockStatus.Stage.DOWNLOAD -> R.string.mi_unlock_step_download
            MiUnlockStatus.Stage.UNLOCK -> R.string.mi_unlock_step_unlock
        }
        MiUnlockStatus.AwaitingReconnect -> R.string.mi_unlock_wait_reconnect
        MiUnlockStatus.Verified -> R.string.mi_unlock_verified
        is MiUnlockStatus.Failed -> when (status.reason) {
            MiUnlockStatus.Reason.DEVICE -> R.string.mi_unlock_fail_device
            MiUnlockStatus.Reason.TOKEN -> R.string.mi_unlock_fail_token
            MiUnlockStatus.Reason.SERVER -> R.string.mi_unlock_fail_server
            MiUnlockStatus.Reason.SESSION_EXPIRED -> R.string.mi_unlock_fail_session
            MiUnlockStatus.Reason.REJECTED -> R.string.mi_unlock_fail_rejected
            MiUnlockStatus.Reason.UNKNOWN -> R.string.mi_unlock_fail_unknown
            MiUnlockStatus.Reason.NOT_STARTED -> R.string.mi_unlock_fail_not_started
        }
    }
    Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
    if (status is MiUnlockStatus.Failed && status.code != null) {
        Text(stringResource(R.string.mi_unlock_server_code, status.code))
    }
}
