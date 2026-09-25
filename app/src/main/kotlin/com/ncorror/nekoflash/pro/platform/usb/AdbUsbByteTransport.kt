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

/** A short-lived owner of one ADB bulk interface; transfers bounded ADB packets. */
class AdbUsbByteTransport private constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val input: UsbEndpoint,
    private val output: UsbEndpoint,
    private val isAttached: () -> Boolean,
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
        return readBytes(length, TIMEOUT_MS, allowUsbPollTimeouts = false)
    }

    override fun readExactAwaitingApproval(length: Int): ByteArray =
        readBytes(length, APPROVAL_TIMEOUT_MS, allowUsbPollTimeouts = true)

    private fun readBytes(length: Int, timeoutMs: Int, allowUsbPollTimeouts: Boolean): ByteArray {
        check(!closed.get()) { "ADB USB session is closed" }
        require(length in 0..MAX_BYTES)
        val buffer = ByteArray(length)
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var offset = 0
        while (offset < length) {
            if (closed.get() || !isAttached()) throw IOException("ADB USB session closed or device disconnected")
            val count = connection.bulkTransfer(input, buffer, offset, length - offset,
                minOf(remaining(deadline), POLL_TIMEOUT_MS))
            if (count <= 0) {
                if (allowUsbPollTimeouts && !closed.get() && isAttached()) continue
                throw IOException("ADB USB read failed or timed out ($count)")
            }
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
        private const val APPROVAL_TIMEOUT_MS = 120_000
        private const val POLL_TIMEOUT_MS = 1000
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
                return AdbUsbByteTransport(connection, usbInterface, input, output) {
                    manager.deviceList[device.deviceName]?.deviceId == device.deviceId
                }
            } catch (failure: Exception) {
                connection.close()
                throw failure
            }
        }
    }
}
