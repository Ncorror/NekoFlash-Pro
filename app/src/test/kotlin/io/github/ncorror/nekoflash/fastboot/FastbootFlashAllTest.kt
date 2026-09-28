package io.github.ncorror.nekoflash.fastboot

import io.github.ncorror.nekoflash.core.artifact.ArtifactAccess
import io.github.ncorror.nekoflash.core.artifact.ArtifactIdentity
import io.github.ncorror.nekoflash.core.artifact.ArtifactRandomAccess
import io.github.ncorror.nekoflash.core.artifact.ArtifactSource
import io.github.ncorror.nekoflash.core.artifact.ArtifactStamp
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootLane
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

    private fun flashAll(device: ScriptedBootloader, set: Map<String, ByteArray>, request: FlashAllRequest = FlashAllRequest()) =
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
        val device = ScriptedBootloader(vayu)

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

        val stopped = flashAll(ScriptedBootloader(vayu), other)
        assertEquals(0, stopped.stoppedAt)
        assertTrue(stopped.commands.isEmpty())
        assertTrue(stopped.detail, stopped.detail.contains("Device product is 'vayu'. Update requires 'bhima'."))
        assertTrue(stopped.detail.endsWith("requirements not met!"))

        val device = ScriptedBootloader(vayu)
        val forced = flashAll(device, other, FlashAllRequest(force = true))
        assertEquals(null, forced.stoppedAt)
        assertTrue(forced.detail.contains("requirements not met! but proceeding due to --force"))
    }

    @Test
    fun aMissingRequiredImageStopsBeforeFlashing() {
        val device = ScriptedBootloader(vayu)

        val planned = flashAll(device, images - "system.img")

        assertEquals("could not load 'system.img': No such file or directory", planned.detail.lines().last())
        assertTrue(device.sent.none { it.startsWith("flash:") })
    }

    @Test
    fun aSuperEmptySetFromTheBootloaderStopsBeforeAnyCommand() {
        val device = ScriptedBootloader(vayu + ("is-userspace" to "no"))

        val planned = flashAll(device, images + ("super_empty.img" to superEmpty()))

        assertTrue(planned.detail.contains("fastboot reboot fastboot"))
        assertTrue(device.sent.all { it.startsWith("getvar:") })
    }

    @Test
    fun inFastbootdTheSuperIsUpdatedAndLogicalPartitionsResized() {
        val device = ScriptedBootloader(
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
        val device = ScriptedBootloader(
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
    fun fromTheBootloaderTheSetGoesOnInFastbootdAfterTheDeviceReturns() {
        val bootloader = ScriptedBootloader(vayu + ("is-userspace" to "no"))
        val fastbootd = ScriptedBootloader(vayu + ("is-userspace" to "yes"))
        val rejoin = object : FastbootRejoin {
            override val possible = true
            override fun await() = FastbootLane(fastbootd)
        }

        val planned = FastbootFlashAll(
            FastbootLane(bootloader),
            MapImageSet(images + ("super_empty.img" to superEmpty())),
            FlashAllRequest(),
            rejoin,
        ).run() as FastbootConsoleState.Planned

        assertEquals(null, planned.stoppedAt)
        assertEquals(
            listOf("flash:boot", "flash:dtbo", "flash:vbmeta", "reboot-fastboot"),
            bootloader.sent.filterNot { it.startsWith("getvar:") || it.startsWith("download:") },
        )
        assertEquals(
            listOf("update-super:super", "flash:system", "reboot"),
            fastbootd.sent.filterNot { it.startsWith("getvar:") || it.startsWith("download:") },
        )
    }

    @Test
    fun aDeviceThatDoesNotReturnStopsTheSet() {
        val rejoin = object : FastbootRejoin {
            override val possible = true
            override fun await(): FastbootLane? = null
        }

        val planned = FastbootFlashAll(
            FastbootLane(ScriptedBootloader(vayu)),
            MapImageSet(images + ("super_empty.img" to superEmpty())),
            FlashAllRequest(),
            rejoin,
        ).run() as FastbootConsoleState.Planned

        assertEquals("The device did not come back after reboot-fastboot.", planned.detail.lines().last())
    }

    @Test
    fun fastbootInfoLeadsWhenItIsInTheSet() {
        val device = ScriptedBootloader(vayu + ("is-userspace" to "yes"))
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
    fun aNewerFastbootInfoStopsBeforeAnyCommand() {
        val newer = flashAll(ScriptedBootloader(vayu), images + ("fastboot-info.txt" to "version 2\nflash boot".toByteArray()))
        assertTrue(newer.detail.contains("not compatible with host tool version --> 1"))
        assertTrue(newer.commands.isEmpty())
    }

    /** `erase` набора — `WipeTask`; `-w` добавляет `if-wipe` и очистку трёх разделов перед `reboot`. */
    @Test
    fun eraseInTheSetAndWipeFormatOnTheHost() {
        val formatted = vayu + mapOf("partition-type:userdata" to "ext4", "partition-size:userdata" to "0x1000000")
        val info = "flash boot\nif-wipe erase userdata\nerase userdata".toByteArray()

        val plain = ScriptedBootloader(formatted)
        flashAll(plain, images + ("fastboot-info.txt" to info), FlashAllRequest(skipReboot = true))
        assertEquals(
            listOf("flash:boot", "erase:userdata", "flash:userdata"),
            plain.sent.filterNot { it.startsWith("getvar:") || it.startsWith("download:") },
        )

        val wiped = ScriptedBootloader(formatted)
        val planned = flashAll(wiped, images + ("fastboot-info.txt" to info), FlashAllRequest(wipe = true))
        assertEquals(
            listOf(
                "flash:boot",
                "erase:userdata", "flash:userdata",
                "erase:userdata", "flash:userdata",
                "erase:userdata", "flash:userdata",
                "reboot",
            ),
            wiped.sent.filterNot { it.startsWith("getvar:") || it.startsWith("download:") },
        )
        assertTrue(planned.detail.contains("wipe task partition not found: cache"))
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

    internal companion object {
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
