package io.github.ncorror.nekoflash.fastboot

import io.github.ncorror.nekoflash.core.artifact.ArtifactAccess
import io.github.ncorror.nekoflash.core.artifact.ArtifactIdentity
import io.github.ncorror.nekoflash.core.artifact.ArtifactRandomAccess
import io.github.ncorror.nekoflash.core.artifact.ArtifactSource
import io.github.ncorror.nekoflash.core.artifact.ArtifactStamp
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootLane
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
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `fastboot flashall` — порядок `FlashAllTool::Flash` из AOSP `fastboot.cpp`. */
class FastbootFlashAllTest {
    private val vayu = mapOf("product" to "vayu", "max-download-size" to "0x10000000")

    private fun flashAll(device: Device, set: Map<String, ByteArray>, request: FlashAllRequest = FlashAllRequest()) =
        FastbootFlashAll(FastbootLane(device), MapImageSet(set), request).run() as FastbootConsoleState.Planned

    private val images = mapOf(
        "android-info.txt" to "require board=vayu\n".toByteArray(),
        "boot.img" to ByteArray(16) { 1 },
        "dtbo.img" to ByteArray(8) { 2 },
        "vbmeta.img" to ByteArray(8) { 3 },
        "system.img" to ByteArray(32) { 4 },
        "cache.img" to ByteArray(4),
    )

    @Test
    fun bootImagesThenSystemImagesThenReboot() {
        val device = Device(vayu)

        val planned = flashAll(device, images)

        assertEquals(null, planned.stoppedAt)
        assertEquals(
            listOf("flash:boot", "flash:dtbo", "flash:vbmeta", "flash:system", "reboot"),
            device.sent.filterNot { it.startsWith("getvar:") || it.startsWith("download:") },
        )
        assertTrue(planned.detail, planned.detail.contains("Checking 'product' OKAY"))
        // `cache.img` — лишний образ: `flashall` его не прошивает.
        assertTrue("flash:cache" !in device.sent)
    }

    @Test
    fun anUnmetRequirementStopsBeforeAnyCommandUnlessForced() {
        val other = images + ("android-info.txt" to "require board=bhima\n".toByteArray())

        val stopped = flashAll(Device(vayu), other)
        assertEquals(0, stopped.stoppedAt)
        assertTrue(stopped.commands.isEmpty())
        assertTrue(stopped.detail, stopped.detail.contains("Device product is 'vayu'. Update requires 'bhima'."))
        assertTrue(stopped.detail.endsWith("requirements not met!"))

        val device = Device(vayu)
        val forced = flashAll(device, other, FlashAllRequest(force = true))
        assertEquals(null, forced.stoppedAt)
        assertTrue(forced.detail.contains("requirements not met! but proceeding due to --force"))
    }

    @Test
    fun aMissingRequiredImageStopsBeforeFlashing() {
        val device = Device(vayu)

        val planned = flashAll(device, images - "system.img")

        assertEquals("could not load 'system.img': No such file or directory", planned.detail.lines().last())
        assertTrue(device.sent.none { it.startsWith("flash:") })
    }

    @Test
    fun aSuperEmptySetFromTheBootloaderStopsBeforeAnyCommand() {
        val device = Device(vayu + ("is-userspace" to "no"))

        val planned = flashAll(device, images + ("super_empty.img" to superEmpty()))

        assertTrue(planned.detail.contains("fastboot reboot fastboot"))
        assertTrue(device.sent.all { it.startsWith("getvar:") })
    }

    @Test
    fun inFastbootdTheSuperIsUpdatedAndLogicalPartitionsResized() {
        val device = Device(
            mapOf(
                "product" to "vayu",
                "is-userspace" to "yes",
                "slot-count" to "2",
                "current-slot" to "a",
                "has-slot:boot" to "yes",
                "has-slot:system" to "yes",
                "has-slot:vbmeta" to "yes",
                "has-slot:dtbo" to "yes",
                "is-logical:system_a" to "yes",
                "super-partition-name" to "super",
            ),
        )

        flashAll(device, images + ("super_empty.img" to superEmpty()), FlashAllRequest(skipReboot = true))

        assertEquals(
            listOf(
                "set_active:a",
                "flash:boot_a", "flash:dtbo_a", "flash:vbmeta_a",
                "update-super:super",
                "resize-logical-partition:system_a:0",
                "resize-logical-partition:system_a:32",
                "flash:system_a",
            ),
            device.sent.filterNot { it.startsWith("getvar:") || it.startsWith("download:") },
        )
    }

    @Test
    fun aSignatureGoesBeforeItsImageAndSecondaryImagesTakeTheOtherSlot() {
        val device = Device(
            mapOf(
                "product" to "vayu",
                "slot-count" to "2",
                "current-slot" to "a",
                "has-slot:boot" to "yes",
                "has-slot:system" to "yes",
            ),
        )
        val set = images - "dtbo.img" - "vbmeta.img" + ("boot.sig" to ByteArray(256)) + ("system_other.img" to ByteArray(4))

        flashAll(device, set)

        val wire = device.sent.filterNot { it.startsWith("getvar:") || it.startsWith("download:") }
        assertEquals(
            listOf("set_active:a", "signature", "flash:boot_a", "flash:system_a", "flash:system_b", "reboot"),
            wire,
        )
    }

    @Test
    fun fastbootInfoLeadsWhenItIsInTheSet() {
        val device = Device(vayu + ("is-userspace" to "yes"))
        val info = """
            version 1
            # comment
            flash vbmeta
            flash --apply-vbmeta boot
            if-wipe erase userdata
            reboot fastboot
            flash system
        """.trimIndent()

        flashAll(device, images + ("fastboot-info.txt" to info.toByteArray()), FlashAllRequest(skipReboot = true))

        assertEquals(
            listOf("flash:vbmeta", "flash:boot", "flash:system"),
            device.sent.filterNot { it.startsWith("getvar:") || it.startsWith("download:") },
        )
    }

    @Test
    fun aNewerFastbootInfoOrAPlainEraseStopsBeforeAnyCommand() {
        val newer = flashAll(Device(vayu), images + ("fastboot-info.txt" to "version 2\nflash boot".toByteArray()))
        assertTrue(newer.detail.contains("not compatible with host tool version --> 1"))
        assertTrue(newer.commands.isEmpty())

        val erase = flashAll(Device(vayu), images + ("fastboot-info.txt" to "erase userdata".toByteArray()))
        assertTrue(erase.detail.contains("fb_perform_format"))
        assertTrue(erase.commands.isEmpty())
    }

    private class MapImageSet(private val files: Map<String, ByteArray>) : FastbootImageSet {
        override fun bytes(name: String): ByteArray? = files[name]

        override fun source(name: String): ArtifactSource? = files[name]?.let { Bytes(name, it) }
    }

    private class Bytes(name: String, private val bytes: ByteArray) : ArtifactSource {
        override val identity = ArtifactIdentity(name, bytes.size.toLong(), ArtifactAccess.SEEKABLE)
        override val openedStamp = ArtifactStamp(bytes.size.toLong(), null, null)
        override fun stamp(): ArtifactStamp = openedStamp
        override fun open(): InputStream = ByteArrayInputStream(bytes)
        override fun randomAccess(): ArtifactRandomAccess? = null
    }

    /** Загрузчик в миниатюре: `getvar` по таблице, `download`, остальное — `OKAY`. */
    private class Device(private val variables: Map<String, String>) : UsbTransportHandle {
        val sent = mutableListOf<String>()
        private val replies = ArrayDeque<String>()
        private var expected = 0

        override val candidate: UsbInterfaceCandidate = CANDIDATE
        override val held: Boolean = true

        override fun send(source: ByteArray, offset: Int, length: Int, timeoutMillis: Int): UsbTransferResult {
            if (expected > 0) {
                expected -= length
                if (expected == 0) replies += "OKAY"
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
                    "DATA%08x".format(expected)
                }
                text.startsWith("getvar:") -> variables[text.removePrefix("getvar:")]?.let { "OKAY$it" } ?: "FAILunknown"
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

        /** `super_empty.img` с одним логическим разделом `system` со слотом. */
        fun superEmpty(): ByteArray {
            val partition = ByteBuffer.allocate(52).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("system".toByteArray().copyOf(36))
                putInt(2)
                putInt(0)
                putInt(0)
                putInt(0)
            }.array()
            val group = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).put("default".toByteArray().copyOf(36)).array()
            val device = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN).apply {
                putLong(2048)
                putInt(0)
                putInt(0)
                putLong(1L shl 30)
                put("super".toByteArray().copyOf(36))
            }.array()
            val tables = partition + group + device
            val header = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(0x414C5030)
                putShort(10)
                putShort(0)
                putInt(128)
                put(ByteArray(32))
                putInt(tables.size)
                put(sha256(tables))
                putInt(0); putInt(1); putInt(52)
                putInt(52); putInt(0); putInt(24)
                putInt(52); putInt(1); putInt(48)
                putInt(100); putInt(1); putInt(64)
            }.array()
            sha256(header).copyInto(header, 12)
            val geometry = ByteBuffer.allocate(52).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(0x616c4467)
                putInt(52)
                put(ByteArray(32))
                putInt(65536)
                putInt(2)
                putInt(4096)
            }.array()
            sha256(geometry).copyInto(geometry, 8)
            return geometry.copyOf(4096) + header + tables
        }

        fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}
