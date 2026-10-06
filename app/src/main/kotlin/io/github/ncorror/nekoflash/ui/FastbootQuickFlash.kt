package io.github.ncorror.nekoflash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ncorror.nekoflash.R
import io.github.ncorror.nekoflash.fastboot.FastbootConsoleState
import io.github.ncorror.nekoflash.fastboot.FastbootOperationStage
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootLockStatus
import java.util.Locale

/**
 * Focused Fastboot image workflow.
 *
 * Diagnostic and arbitrary Fastboot work stays in the terminal. This screen
 * only answers what is being flashed, how far it has gone, and how it ended.
 */
@Composable
internal fun FastbootQuickFlashSection(
    console: FastbootConsolePanel,
    lock: FastbootLockStatus,
    modifier: Modifier = Modifier,
) {
    var partition by rememberSaveable { mutableStateOf("") }
    val running = console.state is FastbootConsoleState.Running

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.fast_flash_lock, lock.detail),
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = partition,
            onValueChange = { partition = it },
            label = { Text(stringResource(R.string.fast_flash_partition)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QUICK_PARTITIONS.forEach { name ->
                SuggestionChip(onClick = { partition = name }, label = { Text(name) })
            }
        }
        Button(
            onClick = { console.onFlashImage(partition.trim()) },
            enabled = partition.isNotBlank() && !running,
        ) {
            Text(stringResource(R.string.fast_flash_choose_image))
        }
        Text(
            text = stringResource(R.string.fast_flash_hint),
            style = MaterialTheme.typography.bodySmall,
        )
        QuickFlashStatus(console.state)

        val completed = console.state as? FastbootConsoleState.Planned
        if (completed?.isSuccessfulFlash() == true) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { console.onCommand("reboot") }) {
                    Text(stringResource(R.string.fast_flash_reboot))
                }
                OutlinedButton(onClick = { console.onCommand("reboot-recovery") }) {
                    Text(stringResource(R.string.fast_flash_reboot_recovery))
                }
            }
        }
    }
}

@Composable
private fun QuickFlashStatus(state: FastbootConsoleState) {
    when (state) {
        is FastbootConsoleState.Running -> if (state.target != null) RunningFlashStatus(state) else ReadyStatus()
        is FastbootConsoleState.Planned -> PlannedFlashStatus(state)
        is FastbootConsoleState.NotAnswered -> if (state.command.contains(FLASH)) {
            Text(
                text = stringResource(R.string.fast_flash_failed, state.detail),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            ReadyStatus()
        }
        else -> ReadyStatus()
    }
}

@Composable
private fun PlannedFlashStatus(state: FastbootConsoleState.Planned) {
    val target = state.flashTarget()
    when {
        target == null -> ReadyStatus()
        state.stoppedAt == null -> Text(
            text = stringResource(R.string.fast_flash_complete, target),
            style = MaterialTheme.typography.bodyMedium,
        )
        else -> Text(
            text = stringResource(
                R.string.fast_flash_failed,
                state.detail.ifBlank { "step ${state.stoppedAt + 1}/${state.commands.size}" },
            ),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun RunningFlashStatus(state: FastbootConsoleState.Running) {
    val target = state.target.orEmpty()
    when (state.stage) {
        FastbootOperationStage.PREPARING -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.fast_flash_preparing, target))
        }
        FastbootOperationStage.TRANSFERRING -> {
            val total = state.totalBytes ?: 0L
            val fraction = progressFraction(state.transferredBytes, total)
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Text(
                stringResource(
                    R.string.fast_flash_transferring,
                    target,
                    (fraction * PERCENT_FLOAT).toInt(),
                    readableBytes(state.transferredBytes),
                    readableBytes(total),
                    readableRate(state.transferredBytes, state.startedAtMillis),
                ),
            )
        }
        FastbootOperationStage.WRITING -> {
            LinearProgressIndicator(progress = { 1f }, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.fast_flash_writing, target))
        }
        FastbootOperationStage.WAITING -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.fast_flash_preparing, target))
        }
    }
}

@Composable
private fun ReadyStatus() {
    Text(
        text = stringResource(R.string.fast_flash_ready),
        style = MaterialTheme.typography.bodySmall,
    )
}

private fun progressFraction(done: Long, total: Long): Float =
    if (total > 0L) (done.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat() else 0f

private fun FastbootConsoleState.Planned.flashTarget(): String? =
    commands.firstOrNull { it.startsWith(FLASH) }?.removePrefix(FLASH)

private fun FastbootConsoleState.Planned.isSuccessfulFlash(): Boolean =
    stoppedAt == null && flashTarget() != null

private fun readableRate(bytes: Long, startedAtMillis: Long): String {
    val elapsed = (System.currentTimeMillis() - startedAtMillis).coerceAtLeast(1L)
    val perSecond = bytes * MILLIS_PER_SECOND / elapsed
    return readableBytes(perSecond) + "/s"
}

private fun readableBytes(bytes: Long): String {
    val value = bytes.coerceAtLeast(0L)
    return when {
        value >= GIB -> String.format(Locale.US, "%.2f GiB", value.toDouble() / GIB)
        value >= MIB -> String.format(Locale.US, "%.1f MiB", value.toDouble() / MIB)
        value >= KIB -> String.format(Locale.US, "%.1f KiB", value.toDouble() / KIB)
        else -> "$value B"
    }
}

private val QUICK_PARTITIONS = listOf("boot", "recovery", "vendor_boot")
private const val FLASH = "flash:"
private const val KIB = 1024L
private const val MIB = 1024L * KIB
private const val GIB = 1024L * MIB
private const val MILLIS_PER_SECOND = 1_000L
private const val PERCENT_FLOAT = 100f
