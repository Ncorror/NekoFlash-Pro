package com.ncorror.nekoflash.pro.platform.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import com.ncorror.nekoflash.pro.transport.AdbByteTransport
import com.ncorror.nekoflash.pro.transport.UsbInterfaceDetection
import com.ncorror.nekoflash.pro.transport.UsbWireProtocol
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** A short-lived owner of one ADB bulk interface; reads only the first bounded reply. */
class AdbUsbByteTransport private constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val input: UsbEndpoint,
    private val output: UsbEndpoint,
) : AdbByteTransport {
    private val closed = AtomicBoolean(false)

    override fun write(bytes: ByteArray) {
        check(!closed.get()) { "ADB USB session is closed" }
        require(bytes.size in 1..MAX_BYTES)
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        var offset = 0
        while (offset < bytes.size) {
            val count = connection.bulkTransfer(output, bytes, offset, bytes.size - offset, remaining(deadline))
            if (count <= 0) throw IOException("ADB USB write failed or timed out ($count)")
            offset += count
        }
    }

    override fun readExact(length: Int): ByteArray {
        check(!closed.get()) { "ADB USB session is closed" }
        require(length in 0..MAX_BYTES)
        val buffer = ByteArray(length)
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        var offset = 0
        while (offset < length) {
            val count = connection.bulkTransfer(input, buffer, offset, length - offset, remaining(deadline))
            if (count <= 0) throw IOException("ADB USB read failed or timed out ($count)")
            offset += count
        }
        return buffer
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                connection.releaseInterface(usbInterface)
            } finally {
                connection.close()
            }
        }
    }

    private fun remaining(deadline: Long): Int {
        val millis = deadline - SystemClock.elapsedRealtime()
        if (millis <= 0) throw IOException("ADB USB transfer timed out")
        return millis.coerceAtMost(TIMEOUT_MS.toLong()).toInt()
    }

    companion object {
        private const val TIMEOUT_MS = 4000
        private const val MAX_BYTES = 4096

        /** Claim only an unambiguous ADB interface; never change USB configuration. */
        fun open(manager: UsbManager, device: UsbDevice, detection: UsbInterfaceDetection): AdbUsbByteTransport {
            require(detection.protocol == UsbWireProtocol.ADB && detection.bulkPairAvailable)
            require(detection.configurationIndex == 0)
            require(UsbInterfaceScanner.scan(device).count { it == detection } == 1) {
                "ADB descriptors changed since selection"
            }
            val usbInterface = device.getConfiguration(0).getInterface(detection.interfaceIndex)
            fun endpoint(address: Int) = (0 until usbInterface.endpointCount)
                .map(usbInterface::getEndpoint)
                .singleOrNull { it.address == address && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
                ?: throw IOException("ADB bulk endpoint disappeared")
            val input = endpoint(detection.bulkInAddress!!)
            val output = endpoint(detection.bulkOutAddress!!)
            val connection = manager.openDevice(device) ?: throw IOException("Android could not open the ADB USB device")
            try {
                if (!connection.claimInterface(usbInterface, false)) {
                    throw IOException("Android could not claim the ADB USB interface")
                }
                return AdbUsbByteTransport(connection, usbInterface, input, output)
            } catch (failure: Exception) {
                connection.close()
                throw failure
            }
        }
    }
}
