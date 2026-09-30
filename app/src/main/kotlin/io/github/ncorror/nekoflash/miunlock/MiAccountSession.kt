package io.github.ncorror.nekoflash.miunlock

import io.github.ncorror.nekoflash.fastboot.FastbootLinkController
import io.github.ncorror.nekoflash.vendor.xiaomi.MiAccountClient
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Account credentials exist only in this process, never in a journal or SavedState. */
class MiAccountSession(private val executor: Executor) {
    private val mutableState = MutableStateFlow<MiAccountState>(MiAccountState.SignedOut)
    val state: StateFlow<MiAccountState> = mutableState.asStateFlow()
    @Volatile private var auth: MiAccountClient.AuthResult? = null
    private val version = AtomicLong()

    fun signIn(passToken: String, userId: String, deviceId: String) {
        if (mutableState.value == MiAccountState.SigningIn) return
        val attempt = version.incrementAndGet()
        mutableState.value = MiAccountState.SigningIn
        executor.execute {
            try {
                val result = MiAccountClient.exchangeToken(passToken, userId, deviceId)
                if (version.get() != attempt) return@execute
                auth = result
                mutableState.value = MiAccountState.SignedIn(result.userId, result.region, result.dataCenterZone)
            } catch (_: Exception) {
                if (version.get() != attempt) return@execute
                auth = null
                // Network exceptions may contain signed URLs or credentials.
                mutableState.value = MiAccountState.Failed
            }
        }
    }

    fun changeZone(zone: String) {
        val previous = auth ?: return
        val changed = MiAccountClient.withDataCenterZone(previous, zone)
        auth = changed
        mutableState.value = MiAccountState.SignedIn(changed.userId, changed.region, zone)
    }

    fun signOut() {
        version.incrementAndGet()
        auth = null
        mutableState.value = MiAccountState.SignedOut
    }

    fun unlock(link: FastbootLinkController) {
        auth?.let(link::runMiUnlock)
    }
}

sealed interface MiAccountState {
    data object SignedOut : MiAccountState
    data object SigningIn : MiAccountState
    data object Failed : MiAccountState
    data class SignedIn(val userId: String, val region: String, val zone: String) : MiAccountState
}
