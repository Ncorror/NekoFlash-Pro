package com.ncorror.nekoflash.pro.transport

/** Only descriptor metadata crosses the Android USB boundary. No interface is opened here. */
data class UsbEndpointDescriptor(
    val address: Int,
    val transferType: Int,
    val maxPacketSize: Int,
)

data class UsbInterfaceDescriptor(
    val configurationIndex: Int,
    val interfaceIndex: Int,
    val id: Int,
    val alternateSetting: Int,
    val interfaceClass: Int,
    val interfaceSubclass: Int,
    val interfaceProtocol: Int,
    val endpoints: List<UsbEndpointDescriptor>,
)

enum class UsbWireProtocol { ADB, FASTBOOT }

enum class UsbBulkPairStatus { AVAILABLE, MISSING_ENDPOINT, AMBIGUOUS_ENDPOINTS }

data class UsbInterfaceDetection(
    val protocol: UsbWireProtocol,
    val configurationIndex: Int,
    val interfaceIndex: Int,
    val id: Int,
    val alternateSetting: Int,
    val bulkPairStatus: UsbBulkPairStatus,
    val bulkInAddress: Int? = null,
    val bulkOutAddress: Int? = null,
) {
    val bulkPairAvailable: Boolean get() = bulkPairStatus == UsbBulkPairStatus.AVAILABLE
}

/** AOSP USB signatures: ADB FF/42/01, Fastboot FF/42/03.
 * An unambiguous bulk IN/OUT pair is required before a later transport can use an interface.
 * This says nothing about ADB authorization or a successful protocol handshake. */
object UsbInterfaceClassifier {
    fun inspect(descriptor: UsbInterfaceDescriptor): UsbInterfaceDetection? {
        if (descriptor.interfaceClass != 0xff || descriptor.interfaceSubclass != 0x42) return null
        val protocol = when (descriptor.interfaceProtocol) {
            0x01 -> UsbWireProtocol.ADB
            0x03 -> UsbWireProtocol.FASTBOOT
            else -> return null
        }

        val bulk = descriptor.endpoints.filter {
            it.transferType == 2 && it.address in 1..255 &&
                (it.address and 0x70) == 0 && (it.address and 0x0f) != 0 &&
                it.maxPacketSize > 0
        }
        val input = bulk.filter { (it.address and 0x80) != 0 }
        val output = bulk.filter { (it.address and 0x80) == 0 }
        val status = when {
            input.size == 1 && output.size == 1 -> UsbBulkPairStatus.AVAILABLE
            input.isEmpty() || output.isEmpty() -> UsbBulkPairStatus.MISSING_ENDPOINT
            else -> UsbBulkPairStatus.AMBIGUOUS_ENDPOINTS
        }
        return UsbInterfaceDetection(
            protocol = protocol,
            configurationIndex = descriptor.configurationIndex,
            interfaceIndex = descriptor.interfaceIndex,
            id = descriptor.id,
            alternateSetting = descriptor.alternateSetting,
            bulkPairStatus = status,
            bulkInAddress = if (status == UsbBulkPairStatus.AVAILABLE) input.single().address else null,
            bulkOutAddress = if (status == UsbBulkPairStatus.AVAILABLE) output.single().address else null,
        )
    }
}
