package com.ncorror.nekoflash.pro.fastboot

import com.ncorror.nekoflash.pro.transport.FastbootPacketTransport
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.util.ArrayDeque
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FastbootFlashExecutorTest {
    private class FakeTransport(vararg replies: String) : FastbootPacketTransport {
        private val incoming = ArrayDeque(replies.map { it.toByteArray(Charsets.US_ASCII) })
        val commands = mutableListOf<String>()
        val data = mutableListOf<ByteArray>()
        var closed = false
        override fun writePacket(packet: ByteArray) { check(!closed); commands += packet.toString(Charsets.US_ASCII) }
        override fun writeData(bytes: ByteArray) { check(!closed); data += bytes.copyOf() }
        override fun readPacket(maxBytes: Int): ByteArray = incoming.removeFirst()
        override fun close() { closed = true }
    }

    @Test
    fun rawImageFlashUsesDeviceLimitAndWaitsForDownloadOkay() {
        val fake = FakeTransport("OKAY 0x1000 \n", "DATA00000003", "OKAYdownloaded", "OKAYflashed")
        var opened = 0
        val result = FastbootFlashExecutor { fake }.flashRaw("boot_a", 3, {
            opened++
            ByteArrayInputStream(byteArrayOf(1, 2, 3))
        })
        assertEquals(FastbootFlashResult.Finished("flashed"), result)
        assertEquals(listOf("getvar:max-download-size", "download:00000003", "flash:boot_a"), fake.commands)
        assertEquals(listOf(3), fake.data.map(ByteArray::size))
        assertEquals(1, opened)
        assertTrue(fake.closed)
    }

    @Test
    fun limitOrDeviceRefusalNeverIssuesFlash() {
        val small = FakeTransport("OKAY2")
        assertEquals(FastbootFlashResult.TooLarge(3, 2),
            FastbootFlashExecutor { small }.flashRaw("boot", 3, { error("do not open image") }))
        assertEquals(listOf("getvar:max-download-size"), small.commands)
        assertTrue(small.closed)

        val noLimit = FakeTransport("FAILunsupported")
        assertEquals(FastbootFlashResult.MissingDownloadLimit,
            FastbootFlashExecutor { noLimit }.flashRaw("boot", 3, { error("do not open image") }))
        assertEquals(listOf("getvar:max-download-size"), noLimit.commands)
        assertTrue(noLimit.closed)

        val rejected = FakeTransport("OKAY100", "FAILdownload refused")
        assertEquals(FastbootFlashResult.DeviceRejected(FastbootFlashResult.Stage.DOWNLOAD, "download refused"),
            FastbootFlashExecutor { rejected }.flashRaw("boot", 3, { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }))
        assertEquals(listOf("getvar:max-download-size", "download:00000003"), rejected.commands)
        assertTrue(rejected.data.isEmpty() && rejected.closed)
    }

    @Test
    fun truncatedImageOrDisconnectPreventsFlashAndClosesSession() {
        val short = FakeTransport("OKAY100", "DATA00000003")
        assertFailsWith<EOFException> {
            FastbootFlashExecutor { short }.flashRaw("boot", 3,
                { ByteArrayInputStream(byteArrayOf(1, 2)) })
        }
        assertEquals(listOf("getvar:max-download-size", "download:00000003"), short.commands)
        assertTrue(short.closed)
    }

    @Test
    fun invalidPartitionOrUnsupportedSizeNeverOpenUsb() {
        var opens = 0
        val executor = FastbootFlashExecutor { opens++; FakeTransport() }
        assertFailsWith<IllegalArgumentException> {
            executor.flashRaw("boot:vendor_ramdisk", 3, { error("no image") })
        }
        assertFailsWith<IllegalArgumentException> {
            executor.flashRaw("boot", 0x1_0000_0000, { error("no image") })
        }
        assertEquals(0, opens)
    }
}
