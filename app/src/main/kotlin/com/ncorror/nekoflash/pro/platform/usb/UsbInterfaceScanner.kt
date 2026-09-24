package com.ncorror.nekoflash.pro.platform.usb

import android.hardware.usb.UsbDevice
import com.ncorror.nekoflash.pro.transport.UsbEndpointDescriptor
import com.ncorror.nekoflash.pro.transport.UsbInterfaceClassifier
import com.ncorror.nekoflash.pro.transport.UsbInterfaceDescriptor
import com.ncorror.nekoflash.pro.transport.UsbInterfaceDetection
import java.util.Locale

/** Android-specific descriptor adapter. Scanning never claims or opens an interface. */
object UsbInterfaceScanner {
    fun scan(device: UsbDevice): List<UsbInterfaceDetection> = buildList {
        for (configurationIndex in 0 until device.configurationCount) {
            val configuration = device.getConfiguration(configurationIndex)
            for (interfaceIndex in 0 until configuration.interfaceCount) {
                val usbInterface = configuration.getInterface(interfaceIndex)
                val descriptor = UsbInterfaceDescriptor(
                    configurationIndex = configurationIndex,
                    interfaceIndex = interfaceIndex,
                    id = usbInterface.id,
                    alternateSetting = usbInterface.alternateSetting,
                    interfaceClass = usbInterface.interfaceClass,
                    interfaceSubclass = usbInterface.interfaceSubclass,
                    interfaceProtocol = usbInterface.interfaceProtocol,
                    endpoints = (0 until usbInterface.endpointCount).map { endpointIndex ->
                        val endpoint = usbInterface.getEndpoint(endpointIndex)
                        UsbEndpointDescriptor(endpoint.address, endpoint.type, endpoint.maxPacketSize)
                    },
                )
                UsbInterfaceClassifier.inspect(descriptor)?.let { detection -> add(detection) }
            }
        }
    }

    /** Suitable for the diagnostic ZIP and redacted event log. */
    fun describe(result: UsbInterfaceDetection): String = buildString {
        append("${result.protocol} configuration=${result.configurationIndex} ")
        append("interface=${result.interfaceIndex} id=${result.id} alt=${result.alternateSetting} ")
        append("bulkPair=${result.bulkPairStatus}")
        if (result.bulkPairAvailable) {
            append(" IN=0x${hex(result.bulkInAddress!!)} OUT=0x${hex(result.bulkOutAddress!!)}")
        }
    }

    private fun hex(address: Int) = String.format(Locale.US, "%02X", address)
}
