package io.github.ncorror.nekoflash.fastboot

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
 * Загрузчик в миниатюре: `getvar` по таблице [variables], `download` с
 * данными, команды из [refused] — `FAIL`, прочие — `OKAY`.
 */
internal class ScriptedBootloader(
    private val variables: Map<String, String>,
    private val refused: Set<String> = emptySet(),
) : UsbTransportHandle {
    val sent = mutableListOf<String>()
    val downloads = mutableListOf<ByteArray>()
    private val replies = ArrayDeque<String>()
    private var expected = 0
    private var buffer = ByteArray(0)

    override val candidate: UsbInterfaceCandidate = CANDIDATE
    override val held: Boolean = true

    override fun send(source: ByteArray, offset: Int, length: Int, timeoutMillis: Int): UsbTransferResult {
        if (expected > 0) {
            buffer += source.copyOfRange(offset, offset + length)
            expected -= length
            if (expected == 0) {
                downloads += buffer
                replies += "OKAY"
            }
        } else {
            command(String(source, offset, length, Charsets.US_ASCII))
        }
        return UsbTransferResult.Completed(length)
    }

    private fun command(text: String) {
        sent += text
        replies += when {
            text.startsWith("download:") -> {
                expected = text.removePrefix("download:").toInt(16)
                buffer = ByteArray(0)
                "DATA%08x".format(expected)
            }
            text.startsWith("getvar:") -> variables[text.removePrefix("getvar:")]?.let { "OKAY$it" } ?: "FAILunknown"
            text in refused -> "FAILrefused"
            // `set_active` подтверждается перечитыванием `current-slot` — у фейка он неизменен.
            else -> "OKAY"
        }
    }

    override fun receive(destination: ByteArray, offset: Int, length: Int, timeoutMillis: Int): UsbTransferResult {
        val next = replies.removeFirstOrNull() ?: return UsbTransferResult.Failed(UsbTransferFailure.NOT_COMPLETED)
        val bytes = next.toByteArray(Charsets.US_ASCII)
        bytes.copyInto(destination, offset)
        return UsbTransferResult.Completed(bytes.size)
    }

    override fun close() = Unit

    private companion object {
        val CANDIDATE = UsbInterfaceCandidate(
            device = UsbDeviceDescriptor(deviceId = 1, deviceName = "/dev/bus/usb/001/002", vendorId = 0x18D1, productId = 0xD00D),
            kind = UsbInterfaceKind.FASTBOOT,
            confidence = UsbMatchConfidence.CANONICAL,
            interfaceIndex = 0,
            interfaceId = 0,
            interfaceClass = 0xFF,
            interfaceSubclass = 0x42,
            interfaceProtocol = 0x03,
            endpointIn = UsbEndpointDescriptor(0x81, UsbEndpointDirection.IN, UsbTransferType.BULK, 512),
            endpointOut = UsbEndpointDescriptor(0x01, UsbEndpointDirection.OUT, UsbTransferType.BULK, 512),
        )
    }
}
