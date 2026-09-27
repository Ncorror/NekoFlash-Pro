package io.github.ncorror.nekoflash.fastboot

import io.github.ncorror.nekoflash.core.artifact.ArtifactAccess
import io.github.ncorror.nekoflash.core.artifact.ArtifactIdentity
import io.github.ncorror.nekoflash.core.artifact.ArtifactRandomAccess
import io.github.ncorror.nekoflash.core.artifact.ArtifactSource
import io.github.ncorror.nekoflash.core.artifact.ArtifactStamp
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootLane
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootSparse
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
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `fastboot flash` с образом больше предела — как у настольного клиента.
 *
 * Подменён только USB: «устройство» понимает `download`, `flash:` и `getvar`,
 * а куски sparse накладывает на свой раздел. Раздел после прошивки обязан
 * совпасть с образом байт в байт.
 */
class FastbootImageFlashTest {
    private val image = ByteArray(40 * 4096).also { bytes ->
        Random(3).nextBytes(bytes, 0, 12 * 4096)
        Random(4).nextBytes(bytes, 30 * 4096, 40 * 4096)
    }

    @Test
    fun anImageOverMaxDownloadSizeIsFlashedInSparsePieces() {
        val device = Device(maxDownloadSize = "0x8000")

        val state = flashImage(FastbootLane(device), Bytes(image), image.size.toLong(), null, listOf("flash:system"))

        val planned = state as FastbootConsoleState.Planned
        assertEquals(null, planned.stoppedAt)
        assertEquals(planned.commands.size, planned.applied)
        assertEquals("getvar:max-download-size", device.commands.first())
        val downloads = device.commands.filter { it.startsWith("download:") }
        assertEquals(downloads.size, device.commands.count { it == "flash:system" })
        assertEquals(true, downloads.size > 1)
        assertEquals(true, device.largestDownload <= 0x8000)
        assertArrayEquals(image, device.partition.copyOf(image.size))
    }

    @Test
    fun theRequestedLimitIsUsedWithoutAskingTheDevice() {
        val device = Device(maxDownloadSize = "0x100000")

        flashImage(FastbootLane(device), Bytes(image), image.size.toLong(), 20_000L, listOf("flash:system", "set_active:b"))

        // `current-slot` спрашивает подтверждение `set_active`, а не предел.
        assertEquals(false, "getvar:max-download-size" in device.commands)
        assertEquals(true, device.largestDownload <= 20_000)
        assertEquals("set_active:b", device.commands.last { !it.startsWith("getvar:") })
        assertArrayEquals(image, device.partition.copyOf(image.size))
    }

    @Test
    fun aFileWithinTheLimitGoesWholeAsItIs() {
        val device = Device(maxDownloadSize = "1048576")

        flashImage(FastbootLane(device), Bytes(image), image.size.toLong(), null, listOf("flash:system"))

        assertEquals(listOf("getvar:max-download-size", "download:%08x".format(image.size), "flash:system"), device.commands)
        assertArrayEquals(image, device.partition.copyOf(image.size))
    }

    @Test
    fun aRefusedFlashStopsTheRemainingPieces() {
        val device = Device(maxDownloadSize = "0x8000", refuseFlash = true)

        val state = flashImage(FastbootLane(device), Bytes(image), image.size.toLong(), null, listOf("flash:system"))

        val planned = state as FastbootConsoleState.Planned
        assertEquals(1, planned.stoppedAt)
        assertEquals(1, device.commands.count { it.startsWith("download:") })
    }

    @Test
    fun withoutMaxDownloadSizeTheFileGoesWholeAndTheDeviceDecides() {
        val device = Device(maxDownloadSize = null)

        flashImage(FastbootLane(device), Bytes(image), image.size.toLong(), null, listOf("flash:system"))

        assertEquals("download:%08x".format(image.size), device.commands[1])
    }

    private class Bytes(private val bytes: ByteArray) : ArtifactSource {
        override val identity = ArtifactIdentity("system.img", bytes.size.toLong(), ArtifactAccess.SEEKABLE)
        override val openedStamp = ArtifactStamp(bytes.size.toLong(), null, null)
        override fun stamp(): ArtifactStamp = openedStamp
        override fun open(): InputStream = ByteArrayInputStream(bytes)
        override fun randomAccess(): ArtifactRandomAccess? = null
    }

    /** Загрузчик в миниатюре: буфер загрузки и один раздел. */
    private class Device(private val maxDownloadSize: String?, private val refuseFlash: Boolean = false) :
        UsbTransportHandle {
        val commands = mutableListOf<String>()
        val partition = ByteArray(64 * 4096) { 0x5A }
        var largestDownload = 0
            private set
        private val replies = ArrayDeque<String>()
        private var buffer = ByteArray(0)
        private var expected = 0

        override val candidate: UsbInterfaceCandidate = CANDIDATE
        override val held: Boolean = true

        override fun send(source: ByteArray, offset: Int, length: Int, timeoutMillis: Int): UsbTransferResult {
            if (expected > 0) {
                buffer += source.copyOfRange(offset, offset + length)
                expected -= length
                if (expected == 0) replies += "OKAY"
            } else {
                command(String(source, offset, length, Charsets.US_ASCII))
            }
            return UsbTransferResult.Completed(length)
        }

        private fun command(text: String) {
            commands += text
            when {
                text.startsWith("download:") -> {
                    expected = text.removePrefix("download:").toInt(16)
                    largestDownload = maxOf(largestDownload, expected)
                    buffer = ByteArray(0)
                    replies += "DATA%08x".format(expected)
                }
                text.startsWith("flash:") && refuseFlash -> replies += "FAILpartition write failed"
                text.startsWith("flash:") -> replies += "OKAY".also { write(buffer) }
                text == "getvar:max-download-size" -> replies += maxDownloadSize?.let { "OKAY$it" } ?: "FAILunknown"
                else -> replies += "OKAY"
            }
        }

        /** Sparse — поверх раздела; иначе байты как есть с начала. */
        private fun write(data: ByteArray) {
            val header = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            if (data.size < 28 || header.getInt(0) != FastbootSparse.MAGIC) {
                data.copyInto(partition)
                return
            }
            val blockSize = header.getInt(12)
            val chunks = header.getInt(20)
            header.position(28)
            var at = 0
            repeat(chunks) {
                val type = header.getShort().toInt() and 0xFFFF
                header.getShort()
                val count = header.getInt()
                val total = header.getInt()
                when (type) {
                    FastbootSparse.CHUNK_RAW -> header.get(partition, at * blockSize, total - 12)
                    FastbootSparse.CHUNK_FILL -> {
                        val word = header.getInt()
                        val fill = ByteBuffer.wrap(partition, at * blockSize, count * blockSize)
                            .order(ByteOrder.LITTLE_ENDIAN)
                        repeat(count * blockSize / 4) { fill.putInt(word) }
                    }
                    else -> Unit
                }
                at += count
            }
        }

        override fun receive(destination: ByteArray, offset: Int, length: Int, timeoutMillis: Int): UsbTransferResult {
            val next = replies.removeFirstOrNull() ?: return UsbTransferResult.Failed(UsbTransferFailure.NOT_COMPLETED)
            val bytes = next.toByteArray(Charsets.US_ASCII)
            bytes.copyInto(destination, offset)
            return UsbTransferResult.Completed(bytes.size)
        }

        override fun close() = Unit
    }

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
