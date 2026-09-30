package io.github.ncorror.nekoflash.fastboot

import io.github.ncorror.nekoflash.usb.api.UsbClaimFailure
import io.github.ncorror.nekoflash.usb.api.UsbClaimResult
import io.github.ncorror.nekoflash.usb.api.UsbDeviceDescriptor
import io.github.ncorror.nekoflash.usb.api.UsbEndpointDescriptor
import io.github.ncorror.nekoflash.usb.api.UsbEndpointDirection
import io.github.ncorror.nekoflash.usb.api.UsbInterfaceCandidate
import io.github.ncorror.nekoflash.usb.api.UsbInterfaceKind
import io.github.ncorror.nekoflash.usb.api.UsbMatchConfidence
import io.github.ncorror.nekoflash.usb.api.UsbTransferFailure
import io.github.ncorror.nekoflash.usb.api.UsbTransferResult
import io.github.ncorror.nekoflash.usb.api.UsbTransferType
import io.github.ncorror.nekoflash.usb.api.UsbTransportHandle

/**
 * Интерфейс, отвечающий заранее назначенными кадрами.
 *
 * Подменяет только USB. Разбор протокола при этом настоящий: полоса и опрос
 * берутся из `:protocol:fastboot` как есть, иначе проверялась бы подделка.
 */
internal class FakeFastbootHandle(replies: List<String>) : UsbTransportHandle {
    private val pending = replies.toMutableList()

    val sent: MutableList<String> = mutableListOf()
    var closed: Boolean = false
        private set

    /** С какой команды считать записи данными, а не командами. */
    var dataAfterCommands: Int? = null

    /** Ронять только записи данных, оставив команды проходящими. */
    var failDataWrite: Boolean = false

    /** Сколько байт получено в фазе данных. */
    var dataBytes: Long = 0L
        private set

    private var commandsSeen: Int = 0

    override val candidate: UsbInterfaceCandidate = CANDIDATE

    override val held: Boolean get() = !closed

    override fun receive(destination: ByteArray, offset: Int, length: Int, timeoutMillis: Int): UsbTransferResult {
        val next = pending.removeFirstOrNull()
            ?: return UsbTransferResult.Failed(UsbTransferFailure.NOT_COMPLETED)
        val bytes = next.toByteArray(Charsets.US_ASCII)
        bytes.copyInto(destination, offset, 0, bytes.size)
        return UsbTransferResult.Completed(bytes.size)
    }

    override fun send(source: ByteArray, offset: Int, length: Int, timeoutMillis: Int): UsbTransferResult {
        val boundary = dataAfterCommands
        val isData = boundary != null && commandsSeen >= boundary
        return if (isData) {
            dataBytes += length.toLong()
            if (failDataWrite) {
                UsbTransferResult.Failed(UsbTransferFailure.NOT_COMPLETED)
            } else {
                UsbTransferResult.Completed(length)
            }
        } else {
            sent += String(source, offset, length, Charsets.US_ASCII)
            commandsSeen += 1
            UsbTransferResult.Completed(length)
        }
    }

    override fun close() {
        closed = true
    }

    private companion object {
        val CANDIDATE = UsbInterfaceCandidate(
            device = UsbDeviceDescriptor(
                deviceId = 1,
                deviceName = "/dev/bus/usb/001/002",
                vendorId = 0x2717,
                productId = 0xD00D,
            ),
            kind = UsbInterfaceKind.FASTBOOT,
            confidence = UsbMatchConfidence.CANONICAL,
            interfaceIndex = 0,
            interfaceId = 0,
            interfaceClass = 0xFF,
            interfaceSubclass = 0x42,
            interfaceProtocol = 0x03,
            endpointIn = UsbEndpointDescriptor(
                address = 0x81,
                direction = UsbEndpointDirection.IN,
                transferType = UsbTransferType.BULK,
                maxPacketSize = 512,
            ),
            endpointOut = UsbEndpointDescriptor(
                address = 0x01,
                direction = UsbEndpointDirection.OUT,
                transferType = UsbTransferType.BULK,
                maxPacketSize = 512,
            ),
        )
    }
}

/** Захват, который удаётся и отдаёт [FakeFastbootHandle]. */
internal class ClaimingCoordinator(private val replies: List<String>) {
    var lastHandle: FakeFastbootHandle? = null
        private set

    fun claim(): UsbClaimResult {
        val handle = FakeFastbootHandle(replies)
        lastHandle = handle
        return UsbClaimResult.Claimed(handle)
    }
}

/** Захват, который не удаётся: до протокола дело не доходит. */
internal class RefusingCoordinator {
    fun claim(): UsbClaimResult = UsbClaimResult.Failed(UsbClaimFailure.OPEN_REFUSED)
}
