package io.github.ncorror.nekoflash.adb

import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticSink
import io.github.ncorror.nekoflash.core.model.SessionGeneration
import io.github.ncorror.nekoflash.protocol.adb.AdbConnection
import io.github.ncorror.nekoflash.protocol.adb.AdbHandshakeFailure
import io.github.ncorror.nekoflash.protocol.adb.AdbHandshakeOutcome
import io.github.ncorror.nekoflash.protocol.adb.AdbKeyStore
import io.github.ncorror.nekoflash.protocol.adb.AdbMailboxEnd
import io.github.ncorror.nekoflash.protocol.adb.AdbShellOutcome
import io.github.ncorror.nekoflash.usb.api.UsbAutoConnectPolicy
import io.github.ncorror.nekoflash.usb.api.UsbClaimResult
import io.github.ncorror.nekoflash.usb.api.UsbInterfaceKind
import io.github.ncorror.nekoflash.usb.api.UsbSession
import io.github.ncorror.nekoflash.usb.api.UsbSessionCoordinator
import io.github.ncorror.nekoflash.usb.api.UsbSessionState
import io.github.ncorror.nekoflash.usb.api.UsbTransportHandle
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Owns a claim before its task starts; every callback belongs to that exact attempt. */
internal class AdbLinkLifecycle(
    private val coordinator: UsbSessionCoordinator,
    private val keyStore: AdbKeyStore,
    private val apiLevel: Int,
    private val executor: Executor,
    private val diagnostics: DiagnosticSink,
    private val onReplacing: (SessionGeneration) -> Unit,
    private val onConnected: (AdbConnection) -> Unit,
    private val onDisconnected: () -> Unit,
) {
    private class Attempt(val generation: SessionGeneration) {
        var handle: UsbTransportHandle? = null
        var connection: AdbConnection? = null
        var established = false
        var end: Pair<AdbMailboxEnd, String>? = null
    }

    private val lifecycleLock = Any()
    /** A stale caller cannot start its physical claim after a newer attempt. */
    private val claimLock = Any()
    // Binding/cleanup may acquire protocol locks. Never hold lifecycleLock there:
    // an inbound callback can itself report transport termination.
    private val bindingLock = Any()
    private var owner: Attempt? = null
    private var bound: Attempt? = null
    private var selection: SessionGeneration? = null
    private val handled = mutableSetOf<Long>()
    private val mutableState = MutableStateFlow<AdbLinkState>(AdbLinkState.Idle)
    private val mutableCommand = MutableStateFlow<AdbCommandState>(AdbCommandState.None)
    val state = mutableState.asStateFlow()
    val command = mutableCommand.asStateFlow()

    val connection: AdbConnection?
        get() = synchronized(lifecycleLock) {
            if (mutableState.value is AdbLinkState.Connected) owner?.connection else null
        }

    fun connect(generation: SessionGeneration) {
        val attempt = Attempt(generation)
        val previous = synchronized(lifecycleLock) {
            if (owner?.generation == generation) return
            handled.add(generation.value)
            owner.also {
                owner = attempt
                selection = generation
                mutableCommand.value = AdbCommandState.None
                mutableState.value = AdbLinkState.Connecting(generation)
            }
        }
        previous?.let(::dispose)
        replaceBinding(attempt)
        val claimed = synchronized(claimLock) {
            if (!synchronized(lifecycleLock) { owner === attempt }) return
            runCatching { coordinator.claim(generation) }
        }
        when (val result = claimed.getOrNull()) {
            is UsbClaimResult.Claimed -> retainClaim(attempt, result.handle)
            is UsbClaimResult.Failed -> fail(attempt, AdbHandshakeFailure.TRANSPORT_CLOSED, result.reason.name)
            null -> fail(attempt, AdbHandshakeFailure.TRANSPORT_CLOSED, claimed.exceptionOrNull().typeName())
        }
    }

    private fun replaceBinding(attempt: Attempt) {
        synchronized(bindingLock) {
            if (synchronized(lifecycleLock) { owner === attempt }) onReplacing(attempt.generation)
        }
    }

    private fun retainClaim(attempt: Attempt, handle: UsbTransportHandle) {
        val retained = synchronized(lifecycleLock) {
            if (owner !== attempt) false else {
                attempt.handle = handle
                attempt.connection = AdbConnection(
                    handle, keyStore, apiLevel, diagnostics,
                    onPublicKeySent = { authorizationSent(attempt) },
                    onTransportEnd = { reason, detail -> transportEnded(attempt, reason, detail) },
                )
                true
            }
        }
        if (!retained) {
            coordinator.release(attempt.generation, handle)
            handle.close()
            return
        }
        try {
            executor.execute { runHandshake(attempt) }
        } catch (rejected: RejectedExecutionException) {
            fail(attempt, AdbHandshakeFailure.TRANSPORT_CLOSED, rejected.javaClass.simpleName)
        }
    }

    private fun runHandshake(attempt: Attempt) {
        val live = synchronized(lifecycleLock) {
            if (owner === attempt) attempt.connection else null
        } ?: return
        val outcome = runCatching { live.connect() }
        when (val result = outcome.getOrNull()) {
            is AdbHandshakeOutcome.Connected -> publishConnection(attempt, live, result)
            is AdbHandshakeOutcome.Failed -> fail(attempt, result.reason, result.detail)
            null -> fail(attempt, AdbHandshakeFailure.TRANSPORT_CLOSED, outcome.exceptionOrNull().typeName())
        }
    }

    private fun publishConnection(attempt: Attempt, live: AdbConnection, result: AdbHandshakeOutcome.Connected) {
        val end = synchronized(lifecycleLock) {
            if (owner !== attempt) return
            attempt.established = true
            attempt.end
        }
        if (end != null) {
            fail(attempt, end.first.handshakeFailure(), end.second)
            return
        }
        val published = runCatching {
            synchronized(bindingLock) {
                val active = synchronized(lifecycleLock) { owner === attempt && attempt.handle?.held == true }
                if (!active) return@synchronized false
                bound = attempt
                onConnected(live)
                synchronized(lifecycleLock) {
                    if (owner !== attempt || attempt.handle?.held != true) false else {
                        mutableState.value = AdbLinkState.Connected(
                            attempt.generation, result.banner.peerMode, result.banner.banner, result.banner.features,
                        )
                        true
                    }
                }
            }
        }
        if (published.isFailure) {
            fail(attempt, AdbHandshakeFailure.TRANSPORT_CLOSED, published.exceptionOrNull().typeName())
        } else if (published.getOrNull() != true) {
            fail(attempt, AdbHandshakeFailure.TRANSPORT_CLOSED, "claimed interface is no longer held")
            dispose(attempt)
        }
    }

    private fun authorizationSent(attempt: Attempt) {
        synchronized(lifecycleLock) {
            if (owner === attempt) mutableState.value = AdbLinkState.WaitingForAuthorization(attempt.generation)
        }
    }

    private fun transportEnded(attempt: Attempt, reason: AdbMailboxEnd, detail: String) {
        val established = synchronized(lifecycleLock) {
            if (owner !== attempt) return
            if (attempt.end == null) attempt.end = reason to detail
            attempt.established
        }
        // During CNXN/AUTH the handshake owns the failure vocabulary. If CNXN
        // succeeded, an end during reverse binding must still prevent publication.
        if (established) fail(attempt, reason.handshakeFailure(), detail)
    }

    private fun fail(attempt: Attempt, reason: AdbHandshakeFailure, detail: String) {
        synchronized(lifecycleLock) {
            if (owner !== attempt) return
            owner = null
            mutableCommand.value = AdbCommandState.None
            mutableState.value = AdbLinkState.Failed(attempt.generation, reason, detail, attempt.established)
        }
        dispose(attempt)
    }

    private fun dispose(attempt: Attempt) {
        attempt.connection?.close()
        synchronized(bindingLock) {
            if (bound === attempt) {
                bound = null
                onDisconnected()
            }
        }
        attempt.handle?.let { handle ->
            coordinator.release(attempt.generation, handle)
            handle.close()
        }
    }

    fun disconnect(generation: SessionGeneration) {
        val previous = synchronized(lifecycleLock) {
            handled.add(generation.value)
            if (mutableState.value.generationOrNull() != generation) return
            owner.also {
                owner = null
                mutableCommand.value = AdbCommandState.None
                mutableState.value = AdbLinkState.Idle
            }
        }
        previous?.let(::dispose)
    }

    fun onUsbSessionsChanged(sessions: List<UsbSession>) {
        val dropped = synchronized(lifecycleLock) {
            val shown = mutableState.value
            val generation = shown.generationOrNull()
            val claiming = owner?.handle == null
            val present = sessions.any { it.generation == generation && shown.accepts(it, claiming) }
            if (generation == null || present) null else {
                owner.also {
                    owner = null
                    mutableCommand.value = AdbCommandState.None
                    mutableState.value = AdbLinkState.Idle
                }
            }
        }
        dropped?.let(::dispose)
        reconcileSelection(sessions.singleOrNull()?.generation)
        connectAutomatically(sessions)
    }

    private fun reconcileSelection(generation: SessionGeneration?) {
        if (generation == null) return
        synchronized(bindingLock) {
            val changed = synchronized(lifecycleLock) {
                if (selection == generation) false else { selection = generation; true }
            }
            if (changed) onReplacing(generation)
        }
    }

    private fun connectAutomatically(sessions: List<UsbSession>) {
        val candidate = synchronized(lifecycleLock) {
            if (mutableState.value !is AdbLinkState.Idle) return
            sessions.firstOrNull { it.autoConnectCandidate() && it.generation.value !in handled }
        } ?: return
        connect(candidate.generation)
    }

    fun runCommand(command: String) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return
        val attempt = synchronized(lifecycleLock) {
            val current = owner
            if (current == null || mutableState.value !is AdbLinkState.Connected ||
                mutableCommand.value is AdbCommandState.Running
            ) null else {
                mutableCommand.value = AdbCommandState.Running(trimmed)
                current
            }
        } ?: return
        try {
            executor.execute { executeCommand(attempt, trimmed) }
        } catch (rejected: RejectedExecutionException) {
            publishCommand(attempt, AdbCommandState.Failed(trimmed, rejected.javaClass.simpleName))
        }
    }

    private fun executeCommand(attempt: Attempt, command: String) {
        val live = synchronized(lifecycleLock) {
            if (owner === attempt && mutableState.value is AdbLinkState.Connected) attempt.connection else null
        } ?: return
        val outcome = runCatching { live.shell(command) }
        publishCommand(attempt, outcome.commandState(command))
    }

    private fun publishCommand(attempt: Attempt, result: AdbCommandState) {
        synchronized(lifecycleLock) {
            if (owner === attempt && mutableState.value is AdbLinkState.Connected) mutableCommand.value = result
        }
    }
}

private fun Throwable?.typeName(): String = this?.javaClass?.simpleName ?: "unknown"

private fun AdbMailboxEnd.handshakeFailure(): AdbHandshakeFailure =
    if (this == AdbMailboxEnd.FRAMING_LOST) AdbHandshakeFailure.FRAMING_LOST else AdbHandshakeFailure.TRANSPORT_CLOSED

private fun AdbLinkState.generationOrNull(): SessionGeneration? = when (this) {
    AdbLinkState.Idle -> null
    is AdbLinkState.Connecting -> generation
    is AdbLinkState.WaitingForAuthorization -> generation
    is AdbLinkState.Connected -> generation
    is AdbLinkState.Failed -> generation
}

private fun AdbLinkState.accepts(session: UsbSession, claiming: Boolean): Boolean {
    if (session.closed) return false
    return when {
        this is AdbLinkState.Failed -> true
        this is AdbLinkState.Connecting && claiming ->
            session.state == UsbSessionState.READY || session.state == UsbSessionState.CLAIMED
        else -> session.state == UsbSessionState.CLAIMED
    }
}

private fun UsbSession.autoConnectCandidate(): Boolean {
    if (closed || state != UsbSessionState.READY) return false
    return candidate.kind == UsbInterfaceKind.ADB && UsbAutoConnectPolicy.allowsAutomaticConnect(candidate)
}

private fun Result<AdbShellOutcome>.commandState(command: String): AdbCommandState = when (val result = getOrNull()) {
    is AdbShellOutcome.Finished -> AdbCommandState.Finished(
        command, result.output.stdout.trimEnd('\n', '\r'),
        result.output.stderr.trimEnd('\n', '\r'), result.output.exitCode,
    )
    is AdbShellOutcome.Failed -> AdbCommandState.Failed(command, "${result.reason.name}: ${result.detail}")
    null -> AdbCommandState.Failed(command, exceptionOrNull().typeName())
}
