package io.github.ncorror.nekoflash.miunlock

import io.github.ncorror.nekoflash.fastboot.FastbootConsoleState
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootLane
import kotlinx.coroutines.flow.MutableStateFlow

/** Exposes bounded, nonsecret unlock progress to the screen and ZIP. */
internal class MiUnlockReporter(
    private val state: MutableStateFlow<MiUnlockStatus>,
    private val emit: (String, Map<String, String>) -> Unit,
) {
    fun stage(stage: MiUnlockStatus.Stage) {
        state.value = MiUnlockStatus.Running(stage)
        emit("mi_unlock_stage", mapOf("stage" to stage.name))
    }

    fun fail(active: FastbootLane, reason: MiUnlockStatus.Reason, code: Int? = null): FastbootConsoleState {
        state.value = MiUnlockStatus.Failed(reason, code)
        emit("mi_unlock_failed", mapOf("reason" to reason.name, "code" to (code?.toString() ?: "none")))
        val detail = reason.name + (code?.let { " ($it)" } ?: "")
        return FastbootConsoleState.NotAnswered("Mi Unlock", detail, active.state)
    }
}
