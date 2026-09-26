package com.ncorror.nekoflash.pro.platform.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import com.ncorror.nekoflash.pro.transport.FastbootPacketTransport
import com.ncorror.nekoflash.pro.transport.UsbInterfaceDetection
import com.ncorror.nekoflash.pro.transport.UsbWireProtocol
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** One short-lived owner of a claimed Fastboot interface and bounded bulk transfers. */
class FastbootUsbPacketTransport private constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val input: UsbEndpoint,
    private val output: UsbEndpoint,
) : FastbootPacketTransport {
    private val closed = AtomicBoolean(false)
    @Volatile private var replyTimeoutMs = TRANSFER_TIMEOUT_MS

    override fun writePacket(packet: ByteArray) {
        check(!closed.get()) { "Fastboot USB session is closed" }
        require(packet.size in 1..MAX_COMMAND_BYTES) { "Fastboot command exceeds one packet" }
        val count = connection.bulkTransfer(output, packet, packet.size, TRANSFER_TIMEOUT_MS)
        if (count != packet.size) throw IOException("Fastboot USB write incomplete: $count/${packet.size}")
    }

    override fun writeData(bytes: ByteArray) {
        check(!closed.get()) { "Fastboot USB session is closed" }
        require(bytes.size in 1..MAX_DATA_CHUNK_BYTES)
        var offset = 0
        while (offset < bytes.size) {
            // Android < 9 silently truncates bulk writes above 16 KiB.
            val length = minOf(bytes.size - offset,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) MAX_DATA_CHUNK_BYTES else LEGACY_BULK_BYTES)
            val count = connection.bulkTransfer(output, bytes, offset, length, TRANSFER_TIMEOUT_MS)
            if (count !in 1..length) throw IOException("Fastboot USB data transfer failed or timed out ($count)")
            offset += count
        }
    }

    override fun expectLongRunningOperation() {
        replyTimeoutMs = FLASH_REPLY_TIMEOUT_MS
    }

    override fun readPacket(maxBytes: Int): ByteArray {
        check(!closed.get()) { "Fastboot USB session is closed" }
        require(maxBytes in 4..MAX_REPLY_BYTES) { "Fastboot probe accepts responses up to 256 bytes" }
        val buffer = ByteArray(maxBytes)
        val count = connection.bulkTransfer(input, buffer, buffer.size, replyTimeoutMs)
        if (count < 0) throw IOException("Fastboot USB read failed or timed out ($count)")
        return buffer.copyOf(count)
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

    companion object {
        private const val TRANSFER_TIMEOUT_MS = 4000
        private const val FLASH_REPLY_TIMEOUT_MS = 300_000
        private const val MAX_COMMAND_BYTES = 4096
        private const val MAX_REPLY_BYTES = 256
        private const val MAX_DATA_CHUNK_BYTES = 64 * 1024
        private const val LEGACY_BULK_BYTES = 16 * 1024

        /** Do not change configuration or detach a kernel driver for a diagnostic probe. */
        fun open(
            manager: UsbManager,
            device: UsbDevice,
            detection: UsbInterfaceDetection,
        ): FastbootUsbPacketTransport {
            require(detection.protocol == UsbWireProtocol.FASTBOOT && detection.bulkPairAvailable) {
                "An unambiguous Fastboot bulk pair is required"
            }
            require(detection.configurationIndex == 0) {
                "Fastboot probe supports the first USB configuration only"
            }
            require(UsbInterfaceScanner.scan(device).count { it == detection } == 1) {
                "Fastboot descriptors changed since selection"
            }
            val configuration = device.getConfiguration(detection.configurationIndex)
            val usbInterface = configuration.getInterface(detection.interfaceIndex)
            fun endpoint(address: Int) = (0 until usbInterface.endpointCount)
                .map(usbInterface::getEndpoint)
                .singleOrNull { it.address == address && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
                ?: throw IOException("Fastboot bulk endpoint disappeared")
            val input = endpoint(detection.bulkInAddress!!)
            val output = endpoint(detection.bulkOutAddress!!)
            val connection = manager.openDevice(device) ?: throw IOException("Android could not open the USB device")
            try {
                if (!connection.claimInterface(usbInterface, false)) {
                    throw IOException("Android could not claim the Fastboot USB interface")
                }
                return FastbootUsbPacketTransport(connection, usbInterface, input, output)
            } catch (failure: Exception) {
                connection.close()
                throw failure
            }
        }
    }
}
