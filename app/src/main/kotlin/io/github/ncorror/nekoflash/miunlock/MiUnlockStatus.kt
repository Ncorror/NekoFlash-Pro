package io.github.ncorror.nekoflash.miunlock

/** Only stage names and outcome codes are observable; no tokens or signed responses. */
sealed interface MiUnlockStatus {
    data object Idle : MiUnlockStatus
    data class Running(val stage: Stage) : MiUnlockStatus
    data object AwaitingReconnect : MiUnlockStatus
    data object Verified : MiUnlockStatus
    data class Failed(val reason: Reason, val code: Int? = null) : MiUnlockStatus

    enum class Stage { DEVICE, NONCE, CHECK, AUTHORIZE, DOWNLOAD, UNLOCK }
    enum class Reason { DEVICE, TOKEN, SERVER, SESSION_EXPIRED, REJECTED, UNKNOWN, NOT_STARTED }
}
