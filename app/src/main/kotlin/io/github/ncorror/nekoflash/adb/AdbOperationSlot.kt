package io.github.ncorror.nekoflash.adb

import io.github.ncorror.nekoflash.protocol.adb.AdbConnection
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One visible operation, bound to a connection; queued work never acquires a replacement's authority. */
internal class AdbOperationSlot<S>(private val initial: S) {
    private val lock = Any()
    private val mutableState = MutableStateFlow(initial)
    private var connection: AdbConnection? = null
    private var request: Request<S>? = null
    private var retainVisibleOutcome = false
    val state = mutableState.asStateFlow()
    val active: Boolean get() = synchronized(lock) { request != null }

    fun bind(live: AdbConnection?, discard: Boolean = false, reset: () -> Unit = { }) {
        synchronized(lock) {
            if (!discard && connection === live) return
            connection = live
            val pending = request
            // Reboot/Sideload may finish after an expected detach. Only a started
            // request can retain its own outcome; replacement always discards it.
            val canRetain = retainVisibleOutcome && (pending == null || pending.started)
            if (!discard && live == null && canRetain) return
            pending?.cancel()
            request = null
            retainVisibleOutcome = false
            mutableState.value = initial
            reset()
        }
    }

    fun begin(live: AdbConnection, shown: S, retainOutcome: Boolean = false): Request<S>? = synchronized(lock) {
        if (connection !== live || !live.active || request != null) return@synchronized null
        Request(this, live, retainOutcome).also {
            request = it
            retainVisibleOutcome = retainOutcome
            mutableState.value = shown
        }
    }

    fun cancel() = synchronized(lock) { request?.cancel() }

    fun submit(
        executor: Executor,
        pending: Request<S>,
        failure: (Throwable) -> S,
        rejected: (RejectedExecutionException) -> S = failure,
        notStarted: (Throwable) -> S = failure,
        work: () -> S,
    ) {
        try {
            executor.execute {
                val started = synchronized(lock) {
                    (request === pending && connection === pending.connection && pending.connection.active)
                        .also { if (it) pending.started = true }
                }
                if (!started) {
                    pending.finish(notStarted(IllegalStateException("ADB transport is no longer active")))
                    return@execute
                }
                val result = runCatching(work).getOrElse(failure)
                pending.finish(result)
            }
        } catch (error: RejectedExecutionException) {
            pending.finish(rejected(error))
        }
    }

    class Request<S> internal constructor(
        private val slot: AdbOperationSlot<S>,
        val connection: AdbConnection,
        internal val retainOutcome: Boolean,
    ) {
        internal var started = false
        private val cancellation = AtomicBoolean(false)
        val cancellationRequested: Boolean get() = cancellation.get() || !connected
        val current: Boolean get() = synchronized(slot.lock) { slot.request === this }
        val connected: Boolean get() = synchronized(slot.lock) {
            slot.request === this && slot.connection === connection && connection.active
        }

        fun requireConnected() { check(connected) { "ADB operation no longer owns its connection" } }

        fun cancel() { cancellation.set(true) }

        /** A short state/baseline update; never perform blocking I/O in this action. */
        fun update(action: () -> Unit): Boolean = synchronized(slot.lock) {
            if (slot.request !== this) false else { action(); true }
        }

        fun publish(value: S) = update { slot.mutableState.value = value }

        fun finish(value: S): Boolean = update {
            slot.mutableState.value = value
            slot.retainVisibleOutcome = retainOutcome && started
            slot.request = null
        }
    }
}
