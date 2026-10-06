package io.github.ncorror.nekoflash.fastboot.transport

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Native Linux-usbfs Fastboot DATA sender.
 *
 * A transfer owns duplicated USB/payload descriptors inside JNI and never frees a submitted
 * URB buffer until every outstanding URB has been reaped. A failed drain poisons the backend
 * for the process so later native transfers and physical USB close can fail closed.
 */
internal object NativeUsbfsBackend {
    data class BackendState(
        val poisoned: Boolean,
        val nativeTransferActive: Boolean,
        val nativeTransferToken: Long,
        val confirmedBytes: Long = 0L,
        val submittedBytes: Long = 0L,
        val totalBytes: Long = 0L,
        val startedMonotonicMs: Long = 0L,
        val lastCompletionMonotonicMs: Long = 0L,
        val stage: Int = STAGE_PREFLIGHT,
    )

    data class ProgressSnapshot(
        val confirmedBytes: Long,
        val submittedBytes: Long,
        val totalBytes: Long,
        val elapsedMs: Long,
        val stage: Int,
    )

    data class TransferResult(
        val success: Boolean,
        val bytesTransferred: Long,
        val submittedBytes: Long,
        val errnoCode: Int,
        val kernelIoctlErrno: Int,
        val urbStatus: Int,
        val actualLength: Int,
        val pendingUrbCountAtStop: Int,
        val lastCompletionAgeMs: Long,
        val drainState: Int,
        val drainErrno: Int,
        val backendPoisoned: Boolean,
        val elapsedMs: Long,
        val stage: Int,
        val message: String,
    ) {
        val cancelled: Boolean
            get() = stage == STAGE_CANCELLED || errnoCode == ERRNO_ECANCELED
    }

    private val loadFailure: Throwable? = runCatching {
        System.loadLibrary("nekoflash_usbfs")
    }.exceptionOrNull()

    private val nextTransferToken = AtomicLong(1L)
    private val activeTransferToken = AtomicLong(0L)
    private val processShutdownBlocked = AtomicBoolean(false)

    val isAvailable: Boolean
        get() = loadFailure == null

    val unavailableReason: String
        get() = loadFailure?.let { "${it.javaClass.simpleName}: ${it.message ?: "<empty>"}" } ?: "available"

    val hasActiveTransfer: Boolean
        get() = activeTransferToken.get() != NO_ACTIVE_TRANSFER

    val isProcessBlocked: Boolean
        get() = processShutdownBlocked.get() || isBackendPoisoned

    fun blockProcessAfterUnconfirmedShutdown() {
        processShutdownBlocked.set(true)
    }

    fun backendState(): BackendState {
        if (!isAvailable) return BackendState(false, false, 0L)
        val raw = try {
            nativeBackendState()
        } catch (_: Exception) {
            null
        } catch (_: LinkageError) {
            null
        }
        val unavailable = raw == null || raw.size < BACKEND_STATE_FIELD_COUNT
        return BackendState(
            poisoned = unavailable || raw.getOrNull(0) == 1L,
            nativeTransferActive = unavailable || raw.getOrNull(1) == 1L,
            nativeTransferToken = raw?.getOrNull(2) ?: 0L,
            confirmedBytes = raw?.getOrNull(3) ?: 0L,
            submittedBytes = raw?.getOrNull(4) ?: 0L,
            totalBytes = raw?.getOrNull(5) ?: 0L,
            startedMonotonicMs = raw?.getOrNull(6) ?: 0L,
            lastCompletionMonotonicMs = raw?.getOrNull(7) ?: 0L,
            stage = (raw?.getOrNull(8) ?: STAGE_PREFLIGHT.toLong()).toInt(),
        )
    }

    val isBackendPoisoned: Boolean
        get() = backendState().poisoned

    fun transportPreflightError(connection: UsbDeviceConnection?, outEndpoint: UsbEndpoint?): String? {
        if (!isAvailable) return "Native USBFS library unavailable: $unavailableReason"
        if (processShutdownBlocked.get()) return "Native USBFS/USB transport is blocked after unconfirmed shutdown"
        val state = backendState()
        if (state.poisoned) return "Native USBFS backend is poisoned after an unproven URB drain"
        if (hasActiveTransfer || state.nativeTransferActive) return "Native USBFS already has an active transfer"
        if (connection == null) return "Native USBFS requires an open UsbDeviceConnection"
        if (outEndpoint == null) return "Native USBFS requires a Fastboot bulk OUT endpoint"
        if ((outEndpoint.address and 0x80) != 0) return "Native USBFS requires an OUT endpoint"
        val fd = runCatching { connection.fileDescriptor }.getOrDefault(-1)
        if (fd < 0) return "Native USBFS could not obtain UsbDeviceConnection file descriptor"
        return null
    }

    fun payloadPreflightError(descriptor: ParcelFileDescriptor?, expectedBytes: Long): String? {
        if (descriptor == null) return "Payload source cannot provide a native read descriptor"
        if (descriptor.fd < 0) return "Payload descriptor is invalid"
        if (expectedBytes <= 0L) return "Payload size must be positive"
        val stat = try {
            Os.fstat(descriptor.fileDescriptor)
        } catch (error: Exception) {
            return "Payload descriptor fstat failed: ${error.javaClass.simpleName}"
        }
        if (!OsConstants.S_ISREG(stat.st_mode)) return "Payload descriptor is not a regular file"
        if (stat.st_size != expectedBytes) {
            return "Payload descriptor size changed: fd=${stat.st_size} expected=$expectedBytes"
        }
        return null
    }

    fun transferBulkOutUrb(
        connection: UsbDeviceConnection,
        outEndpoint: UsbEndpoint,
        payloadDescriptor: ParcelFileDescriptor,
        totalBytes: Long,
        blockBytes: Int,
        pipelineDepth: Int,
        stallTimeoutMs: Int,
        hardTimeoutMs: Int,
        onProgress: ((ProgressSnapshot) -> Unit)? = null,
    ): TransferResult {
        transportPreflightError(connection, outEndpoint)?.let { return failedPreflight(it) }
        payloadPreflightError(payloadDescriptor, totalBytes)?.let { return failedPreflight(it) }
        if (stallTimeoutMs <= 0 || hardTimeoutMs <= 0 || hardTimeoutMs < stallTimeoutMs) {
            return failedPreflight("Invalid Native USBFS deadlines: stall=${stallTimeoutMs}ms hard=${hardTimeoutMs}ms")
        }

        val token = nextToken()
        if (!activeTransferToken.compareAndSet(NO_ACTIVE_TRANSFER, token)) {
            return failedPreflight("Native USBFS transfer rejected: another transfer is still active or draining", ERRNO_EBUSY)
        }

        val polling = AtomicBoolean(onProgress != null)
        val progressStopped = CountDownLatch(if (onProgress != null) 1 else 0)
        val pollStartedNs = System.nanoTime()
        val progressThread = onProgress?.let { callback ->
            Thread({
                try {
                    while (polling.get()) {
                        val state = backendState()
                        if (state.nativeTransferActive && state.nativeTransferToken == token) {
                            val elapsedMs = ((System.nanoTime() - pollStartedNs) / 1_000_000L).coerceAtLeast(0L)
                            runCatching {
                                callback(
                                    ProgressSnapshot(
                                        confirmedBytes = state.confirmedBytes,
                                        submittedBytes = state.submittedBytes,
                                        totalBytes = state.totalBytes.takeIf { it > 0L } ?: totalBytes,
                                        elapsedMs = elapsedMs,
                                        stage = state.stage,
                                    ),
                                )
                            }
                        }
                        try {
                            Thread.sleep(PROGRESS_POLL_INTERVAL_MS)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        }
                    }
                } finally {
                    progressStopped.countDown()
                }
            }, "NekoFlash-NativeUsbfs-Progress").apply {
                isDaemon = true
                start()
            }
        }

        var progressThreadStopped = progressThread == null
        val raw: LongArray? = try {
            nativeBulkOutUrb(
                connection.fileDescriptor,
                outEndpoint.address,
                payloadDescriptor.fd,
                totalBytes,
                blockBytes,
                pipelineDepth,
                stallTimeoutMs,
                hardTimeoutMs,
                token,
            )
        } catch (error: Exception) {
            return jniFailure(error)
        } catch (error: LinkageError) {
            return jniFailure(error)
        } finally {
            polling.set(false)
            progressThread?.interrupt()
            if (progressThread != null && Thread.currentThread() !== progressThread) {
                progressThreadStopped = try {
                    progressStopped.await(PROGRESS_THREAD_JOIN_MS, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    false
                }
            }
            activeTransferToken.compareAndSet(token, NO_ACTIVE_TRANSFER)
        }

        if (raw == null) {
            return jniFailure(IllegalStateException("Native USBFS JNI returned a null result"))
        }

        val success = raw.getOrNull(IDX_SUCCESS) == 1L
        val confirmed = raw.getOrNull(IDX_CONFIRMED_BYTES) ?: 0L
        val errnoCode = (raw.getOrNull(IDX_STOP_ERRNO) ?: 0L).toInt()
        val urbStatus = (raw.getOrNull(IDX_LAST_STATUS) ?: 0L).toInt()
        val actual = (raw.getOrNull(IDX_LAST_ACTUAL) ?: 0L).toInt()
        val elapsedMs = raw.getOrNull(IDX_ELAPSED_MS) ?: 0L
        val stage = (raw.getOrNull(IDX_STAGE) ?: STAGE_UNKNOWN.toLong()).toInt()
        val submitted = raw.getOrNull(IDX_SUBMITTED_BYTES) ?: confirmed
        val pendingAtStop = (raw.getOrNull(IDX_PENDING_AT_STOP) ?: 0L).toInt()
        val lastCompletionAgeMs = raw.getOrNull(IDX_LAST_COMPLETION_AGE_MS) ?: 0L
        val drainState = (raw.getOrNull(IDX_DRAIN_STATE) ?: DRAIN_NOT_NEEDED.toLong()).toInt()
        val drainErrno = (raw.getOrNull(IDX_DRAIN_ERRNO) ?: 0L).toInt()
        val poisoned = raw.getOrNull(IDX_BACKEND_POISONED) == 1L || isBackendPoisoned
        val kernelIoctlErrno = (raw.getOrNull(IDX_KERNEL_IOCTL_ERRNO) ?: 0L).toInt()

        if (progressThreadStopped) {
            onProgress?.let { callback ->
                runCatching {
                    callback(
                        ProgressSnapshot(
                            confirmedBytes = confirmed,
                            submittedBytes = submitted,
                            totalBytes = totalBytes,
                            elapsedMs = elapsedMs,
                            stage = stage,
                        ),
                    )
                }
            }
        }

        val structured =
            "stage=${stageLabel(stage)} confirmed=$confirmed submitted=$submitted stopErrno=$errnoCode " +
                "kernelIoctlErrno=$kernelIoctlErrno urbStatus=$urbStatus actual=$actual pendingAtStop=$pendingAtStop " +
                "lastCompletionAgeMs=$lastCompletionAgeMs drain=${drainLabel(drainState)} drainErrno=$drainErrno " +
                "backendPoisoned=$poisoned elapsedMs=$elapsedMs"
        return TransferResult(
            success = success,
            bytesTransferred = confirmed,
            submittedBytes = submitted,
            errnoCode = errnoCode,
            kernelIoctlErrno = kernelIoctlErrno,
            urbStatus = urbStatus,
            actualLength = actual,
            pendingUrbCountAtStop = pendingAtStop,
            lastCompletionAgeMs = lastCompletionAgeMs,
            drainState = drainState,
            drainErrno = drainErrno,
            backendPoisoned = poisoned,
            elapsedMs = elapsedMs,
            stage = stage,
            message = "Native USBFS URB transfer ${if (success) "OK" else "FAIL"}: $structured",
        )
    }

    fun cancelActiveTransfer(): Boolean {
        val token = activeTransferToken.get()
        if (token == NO_ACTIVE_TRANSFER || !isAvailable) return false
        return try {
            nativeCancelTransfer(token)
        } catch (_: Exception) {
            false
        } catch (_: LinkageError) {
            false
        }
    }

    private fun jniFailure(error: Throwable): TransferResult = TransferResult(
        success = false,
        bytesTransferred = 0L,
        submittedBytes = 0L,
        errnoCode = 0,
        kernelIoctlErrno = 0,
        urbStatus = 0,
        actualLength = 0,
        pendingUrbCountAtStop = 0,
        lastCompletionAgeMs = 0L,
        drainState = DRAIN_NOT_NEEDED,
        drainErrno = 0,
        backendPoisoned = isBackendPoisoned,
        elapsedMs = 0L,
        stage = STAGE_JNI,
        message = "Native USBFS JNI error: ${error.javaClass.name}: ${error.message ?: "<empty>"}",
    )

    private fun failedPreflight(message: String, errno: Int = 0): TransferResult = TransferResult(
        success = false,
        bytesTransferred = 0L,
        submittedBytes = 0L,
        errnoCode = errno,
        kernelIoctlErrno = 0,
        urbStatus = 0,
        actualLength = 0,
        pendingUrbCountAtStop = 0,
        lastCompletionAgeMs = 0L,
        drainState = DRAIN_NOT_NEEDED,
        drainErrno = 0,
        backendPoisoned = isBackendPoisoned,
        elapsedMs = 0L,
        stage = STAGE_PREFLIGHT,
        message = message,
    )

    private fun nextToken(): Long {
        while (true) {
            val current = nextTransferToken.getAndIncrement()
            if (current > 0L) return current
            nextTransferToken.compareAndSet(current + 1L, 1L)
        }
    }

    private external fun nativeBulkOutUrb(
        usbFd: Int,
        endpointAddress: Int,
        payloadFd: Int,
        totalBytes: Long,
        blockBytes: Int,
        pipelineDepth: Int,
        stallTimeoutMs: Int,
        hardTimeoutMs: Int,
        transferToken: Long,
    ): LongArray

    private external fun nativeCancelTransfer(transferToken: Long): Boolean
    private external fun nativeBackendState(): LongArray
    fun stageLabel(stage: Int): String = when (stage) {
        STAGE_PREFLIGHT -> "preflight"
        STAGE_OPEN -> "open"
        STAGE_READ -> "read"
        STAGE_SUBMIT -> "submit_urb"
        STAGE_REAP -> "reap_urb"
        STAGE_TIMEOUT -> "stall_timeout"
        STAGE_STATUS -> "urb_status"
        STAGE_LENGTH -> "length_mismatch"
        STAGE_JNI -> "jni"
        STAGE_DONE -> "done"
        STAGE_CANCELLED -> "cancelled"
        STAGE_HARD_TIMEOUT -> "hard_timeout"
        STAGE_DRAIN -> "drain_failed_backend_poisoned"
        else -> "unknown"
    }

    fun drainLabel(state: Int): String = when (state) {
        DRAIN_NOT_NEEDED -> "not_needed"
        DRAIN_REAPED -> "reaped"
        DRAIN_FAILED -> "failed"
        else -> "unknown"
    }

    const val STAGE_PREFLIGHT = 1
    const val STAGE_OPEN = 2
    const val STAGE_READ = 3
    const val STAGE_SUBMIT = 4
    const val STAGE_REAP = 5
    const val STAGE_TIMEOUT = 6
    const val STAGE_STATUS = 7
    const val STAGE_LENGTH = 8
    const val STAGE_JNI = 9
    const val STAGE_DONE = 10
    const val STAGE_CANCELLED = 11
    const val STAGE_HARD_TIMEOUT = 12
    const val STAGE_DRAIN = 13
    const val STAGE_UNKNOWN = 99

    const val DRAIN_NOT_NEEDED = 0
    const val DRAIN_REAPED = 1
    const val DRAIN_FAILED = 2

    private const val IDX_SUCCESS = 0
    private const val IDX_CONFIRMED_BYTES = 1
    private const val IDX_STOP_ERRNO = 2
    private const val IDX_LAST_STATUS = 3
    private const val IDX_LAST_ACTUAL = 4
    private const val IDX_ELAPSED_MS = 5
    private const val IDX_STAGE = 6
    private const val IDX_SUBMITTED_BYTES = 7
    private const val IDX_PENDING_AT_STOP = 8
    private const val IDX_LAST_COMPLETION_AGE_MS = 9
    private const val IDX_DRAIN_STATE = 10
    private const val IDX_DRAIN_ERRNO = 11
    private const val IDX_BACKEND_POISONED = 12
    private const val IDX_KERNEL_IOCTL_ERRNO = 13
    private const val BACKEND_STATE_FIELD_COUNT = 9

    private const val NO_ACTIVE_TRANSFER = 0L
    private const val PROGRESS_POLL_INTERVAL_MS = 100L
    private const val PROGRESS_THREAD_JOIN_MS = 500L
    private const val ERRNO_EBUSY = 16
    private const val ERRNO_ECANCELED = 125
}
