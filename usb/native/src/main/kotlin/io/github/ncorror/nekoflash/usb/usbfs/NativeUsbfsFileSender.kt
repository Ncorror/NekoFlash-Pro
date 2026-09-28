package io.github.ncorror.nekoflash.usb.usbfs

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.os.ParcelFileDescriptor
import io.github.ncorror.nekoflash.fastboot.transport.NativeUsbfsBackend
import io.github.ncorror.nekoflash.usb.api.UsbFileSendResult
import io.github.ncorror.nekoflash.usb.api.UsbFileSender
import io.github.ncorror.nekoflash.usb.transport.UsbTransportShutdownPolicy

/**
 * [UsbFileSender] поверх `NativeUsbfsBackend` из A2 (D077).
 *
 * Параметры — те, с которыми legacy прошил сотни образов
 * (`FastbootProtocol.NATIVE_USBFS_*`) и которые A2 оставил без изменений: два
 * URB по 256 КиБ, 30 с без подтверждённого байта, 90 с на всю передачу.
 * Предпроверка — как `FastbootDataTransfer.resolveMode` у A2: транспорт и
 * дескриптор проверяются до `download:`, и её отказ означает Java-путь, а не
 * ошибку.
 */
public class NativeUsbfsFileSender(
    private val connection: UsbDeviceConnection,
    private val endpointOut: UsbEndpoint,
) : UsbFileSender {
    /** Идёт ли передача **этого** соединения: чужую закрытие не трогает. */
    @Volatile
    public var sending: Boolean = false
        private set

    override fun unavailable(payloadFd: Int, totalBytes: Long): String? {
        val transport = NativeUsbfsBackend.transportPreflightError(connection, endpointOut)
        val descriptor = if (transport == null) duplicate(payloadFd) else null
        return when {
            transport != null -> transport
            descriptor == null -> "Payload descriptor $payloadFd cannot be duplicated"
            else -> descriptor.use { NativeUsbfsBackend.payloadPreflightError(it, totalBytes) }
        }
    }

    override fun send(payloadFd: Int, totalBytes: Long, progress: (Long) -> Unit): UsbFileSendResult {
        val descriptor = duplicate(payloadFd)
            ?: return UsbFileSendResult(false, 0L, false, "Payload descriptor $payloadFd cannot be duplicated")
        sending = true
        val result = try {
            descriptor.use { transfer(it, totalBytes, progress) }
        } finally {
            sending = false
        }
        return UsbFileSendResult(result.success, result.bytesTransferred, result.cancelled, result.message)
    }

    private fun transfer(
        descriptor: ParcelFileDescriptor,
        totalBytes: Long,
        progress: (Long) -> Unit,
    ): NativeUsbfsBackend.TransferResult = NativeUsbfsBackend.transferBulkOutUrb(
        connection = connection,
        outEndpoint = endpointOut,
        payloadDescriptor = descriptor,
        totalBytes = totalBytes,
        blockBytes = BLOCK_BYTES,
        pipelineDepth = PIPELINE_DEPTH,
        stallTimeoutMs = STALL_TIMEOUT_MS,
        hardTimeoutMs = HARD_TIMEOUT_MS,
        onProgress = { snapshot -> progress(snapshot.confirmedBytes) },
    )

    /** Отменяет только свою передачу: backend один на процесс. */
    override fun cancel(): Boolean = sending && NativeUsbfsBackend.cancelActiveTransfer()

    /** Своя копия дескриптора вызывающего: `ParcelFileDescriptor` закроет её, а не чужой. */
    private fun duplicate(fd: Int): ParcelFileDescriptor? = runCatching { ParcelFileDescriptor.fromFd(fd) }.getOrNull()

    private companion object {
        const val BLOCK_BYTES = 256 * 1024
        const val PIPELINE_DEPTH = 2
        const val STALL_TIMEOUT_MS = 30_000
        const val HARD_TIMEOUT_MS = 90_000
    }
}

/**
 * Закрытие соединения вокруг нативных передач — `FastbootUsbTransport.close`
 * и `connectAndQualify` из A2 (`USB_BEHAVIOR_CONTRACT.md`, «Transport shutdown»).
 *
 * Соединение закрывается, только когда обе стороны — Kotlin и JNI — не
 * передают. Идущая передача отменяется и дожидается дочитывания URB до
 * [CLOSE_WAIT_MS]; не дождались или backend отравлен — соединение остаётся
 * открытым, а новые захваты до перезапуска приложения отклоняются: ядро может
 * ещё читать буфер, который иначе был бы освобождён.
 */
public object NativeUsbfsShutdown {
    /** Новые соединения запрещены до перезапуска. */
    public val blocked: Boolean
        get() = NativeUsbfsBackend.isAvailable && NativeUsbfsBackend.isProcessBlocked

    /**
     * Можно ли закрыть соединение [owner]; `false` — закрывать нельзя, процесс
     * заблокирован. Отменяется и дожидается только передача самого [owner]:
     * передача другого устройства к этому соединению отношения не имеет.
     */
    public fun prepareClose(owner: NativeUsbfsFileSender): Boolean {
        if (!NativeUsbfsBackend.isAvailable) return true
        val mine = owner.sending
        if (mine) NativeUsbfsBackend.cancelActiveTransfer()
        val drained = !mine || awaitIdle()
        val state = NativeUsbfsBackend.backendState()
        val canClose = drained && !state.poisoned && (
            !mine || UsbTransportShutdownPolicy.canCloseUsb(
                kotlinTransferActive = NativeUsbfsBackend.hasActiveTransfer,
                nativeTransferActive = state.nativeTransferActive,
            )
            )
        if (!canClose) NativeUsbfsBackend.blockProcessAfterUnconfirmedShutdown()
        return canClose
    }

    /** Дождались ли, что обе стороны не передают, за [CLOSE_WAIT_MS]; прерывание — не дождались. */
    private fun awaitIdle(): Boolean {
        val startedNs = System.nanoTime()
        var idle = isIdle()
        var interrupted = false
        while (!idle && !interrupted && (System.nanoTime() - startedNs) / NANOS_PER_MILLI < CLOSE_WAIT_MS) {
            try {
                Thread.sleep(CLOSE_POLL_MS)
                idle = isIdle()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                interrupted = true
            }
        }
        return idle
    }

    private fun isIdle(): Boolean =
        !NativeUsbfsBackend.hasActiveTransfer && !NativeUsbfsBackend.backendState().nativeTransferActive

    /** A2 `NATIVE_USBFS_CLOSE_WAIT_MS`. */
    private const val CLOSE_WAIT_MS = 100_000L
    private const val CLOSE_POLL_MS = 10L
    private const val NANOS_PER_MILLI = 1_000_000L
}
