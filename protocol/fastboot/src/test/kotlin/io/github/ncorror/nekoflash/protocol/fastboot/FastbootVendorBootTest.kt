package io.github.ncorror.nekoflash.protocol.fastboot

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `vendor_boot:ИМЯ` — как `replace_vendor_ramdisk` настольного клиента. */
class FastbootVendorBootTest {
    private val page = 4096
    private val first = ByteArray(10) { 1 }
    private val second = ByteArray(20) { 2 }
    private val dtb = ByteArray(100) { 3 }
    private val bootconfig = ByteArray(50) { 4 }
    private val footer = "AVBf".toByteArray() + ByteArray(60) { 5 }

    /** v4: заголовок, рамдиски `first`+`second`, DTB, таблица, bootconfig, футер в самом конце. */
    private fun image(size: Int = 8 * page): ByteArray {
        val out = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        out.put("VNDRBOOT".toByteArray())
        out.putInt(8, 4)
        out.putInt(12, page)
        out.putInt(24, first.size + second.size)
        out.putInt(2100, dtb.size)
        out.putInt(2112, 2 * 108)
        out.putInt(2116, 2)
        out.putInt(2120, 108)
        out.putInt(2124, bootconfig.size)
        val bytes = out.array()
        (first + second).copyInto(bytes, page)
        dtb.copyInto(bytes, 2 * page)
        entry(bytes, 3 * page, first.size, 0, "first")
        entry(bytes, 3 * page + 108, second.size, first.size, "second")
        bootconfig.copyInto(bytes, 4 * page)
        footer.copyInto(bytes, size - 64)
        return bytes
    }

    private fun entry(bytes: ByteArray, at: Int, size: Int, offset: Int, name: String) {
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(at, size).putInt(at + 4, offset)
        name.toByteArray().copyInto(bytes, at + 12)
    }

    private fun replace(image: ByteArray, name: String, ramdisk: ByteArray): Result<ByteArray> =
        FastbootVendorBoot.replace(image.size.toLong(), { offset, length ->
            image.copyOfRange(offset.toInt(), offset.toInt() + length)
        }, name, ramdisk).map { FastbootVendorBoot.render(image, it) }

    private fun int(bytes: ByteArray, at: Int) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(at)

    @Test
    fun aNamedFragmentIsReplacedAndLaterOffsetsMove() {
        val replacement = ByteArray(30) { 9 }

        val out = replace(image(), "first", replacement).getOrThrow()

        assertEquals(50, int(out, 24))
        assertArrayEquals(replacement + second, out.copyOfRange(page, page + 50))
        assertArrayEquals(dtb, out.copyOfRange(2 * page, 2 * page + 100))
        assertEquals(30, int(out, 3 * page))
        assertEquals(0, int(out, 3 * page + 4))
        assertEquals(20, int(out, 3 * page + 108))
        assertEquals(30, int(out, 3 * page + 108 + 4))
        assertArrayEquals(bootconfig, out.copyOfRange(4 * page, 4 * page + 50))
        assertArrayEquals(footer, out.copyOfRange(out.size - 64, out.size))
        assertEquals(image().size, out.size)
    }

    @Test
    fun defaultReplacesAllRamdisksWithOneTableEntry() {
        val replacement = ByteArray(5000) { 7 }

        val out = replace(image(), "default", replacement).getOrThrow()

        assertEquals(5000, int(out, 24))
        assertEquals(1, int(out, 2116))
        assertEquals(108, int(out, 2112))
        assertArrayEquals(replacement, out.copyOfRange(page, page + 5000))
        // Рамдиск занял две страницы: DTB, таблица и bootconfig сдвинулись на одну.
        assertArrayEquals(dtb, out.copyOfRange(3 * page, 3 * page + 100))
        assertEquals(5000, int(out, 4 * page))
        assertTrue(out.copyOfRange(4 * page + 4, 4 * page + 108).all { it == 0.toByte() })
        assertArrayEquals(bootconfig, out.copyOfRange(5 * page, 5 * page + 50))
        assertArrayEquals(footer, out.copyOfRange(out.size - 64, out.size))
    }

    @Test
    fun failuresAreNamedLikeAosp() {
        assertEquals(
            "Vendor ramdisk 'none' not found",
            replace(image(), "none", ByteArray(1)).exceptionOrNull()?.message,
        )
        assertEquals(
            "Unrecognized vendor boot header version 0",
            replace(ByteArray(3 * page), "default", ByteArray(1)).exceptionOrNull()?.message,
        )
        val foreign = image().also { it[0] = 'X'.code.toByte() }
        assertEquals(
            "Vendor boot image magic mismatch",
            replace(foreign, "default", ByteArray(1)).exceptionOrNull()?.message,
        )
        val tooBig = replace(image(size = 5 * page), "default", ByteArray(4 * page))
        assertTrue(tooBig.exceptionOrNull()?.message.orEmpty().contains("Boundary overflow"))
    }
}
