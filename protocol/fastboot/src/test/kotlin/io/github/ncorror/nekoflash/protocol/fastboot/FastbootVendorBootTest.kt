package io.github.ncorror.nekoflash.protocol.fastboot

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `vendor_boot:ИМЯ` — как `replace_vendor_ramdisk` настольного клиента. */
class FastbootVendorBootTest {
    @Test
    fun streamedRamdiskAndPartitionOffsetsCanExceedSignedIntAndFourGiB() {
        val partitionSize = 6L shl 30
        val ramdiskSize = (3L shl 30) + 17
        val prefix = image().copyOf(2128)
        val calls = mutableListOf<Pair<Long, Int>>()
        val pieces = FastbootVendorBoot.replace(partitionSize, { at, count ->
            calls += at to count
            check(count <= 2128) { "whole page or image was allocated" }
            when (at) {
                0L -> prefix.copyOf(count)
                partitionSize - 64 -> "AVBf".toByteArray()
                else -> error("unexpected metadata read: $at")
            }
        }, "default", ramdiskSize).getOrThrow()

        assertEquals(partitionSize, pieces.sumOf { it.length })
        assertEquals(ramdiskSize, pieces.filterIsInstance<VendorBootPiece.Replacement>().single().length)
        val header = pieces.filterIsInstance<VendorBootPiece.New>().first().bytes
        assertEquals(ramdiskSize, int(header, 24).toLong() and 0xFFFF_FFFFL)
        assertEquals(VendorBootPiece.Old(partitionSize - 64, 64), pieces.last())
        assertTrue(calls.any { it.first > 0xFFFF_FFFFL })
    }

    @Test
    fun namedFragmentTableBeyondTwoGiBUsesBoundedReads() {
        val original = image()
        val oldRamdiskSize = (3L shl 30) + 30
        val partitionSize = 6L shl 30
        val tableAt = page + ((oldRamdiskSize + page - 1) / page * page) + page
        val header = original.copyOf(2128)
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putInt(24, oldRamdiskSize.toInt())
        val table = original.copyOfRange(3 * page, 3 * page + 216)
        ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0, ((3L shl 30) + 10).toInt()).putInt(112, ((3L shl 30) + 10).toInt())
        val pieces = FastbootVendorBoot.replace(partitionSize, { at, count ->
            check(count <= 2128)
            when (at) {
                0L -> header.copyOf(count)
                tableAt -> table.copyOfRange(0, count)
                tableAt + 108 -> table.copyOfRange(108, 108 + count)
                partitionSize - 64 -> "AVBf".toByteArray()
                else -> error("unexpected metadata read: $at")
            }
        }, "first", (3L shl 30) + 40).getOrThrow()

        assertEquals(partitionSize, pieces.sumOf { it.length })
        val entries = pieces.filterIsInstance<VendorBootPiece.New>().filter { it.bytes.size == 108 }
        assertEquals((3L shl 30) + 40, int(entries[1].bytes, 4).toLong() and 0xFFFF_FFFFL)
        assertTrue(pieces.filterIsInstance<VendorBootPiece.Old>().any { it.offset == tableAt - page })
    }

    @Test
    fun formatOverflowIsRejectedAndReplacementDtbSizeReachesHeader() {
        val bytes = image()
        val failure = FastbootVendorBoot.replace(bytes.size.toLong(), { at, count ->
            bytes.copyOfRange(at.toInt(), at.toInt() + count)
        }, "default", 1L shl 32)
        assertTrue(failure.exceptionOrNull()?.message.orEmpty().contains("uint32 size overflow"))
        val replacementDtb = ByteArray(1000) { 8 }
        val result = FastbootVendorBoot.replace(bytes.size.toLong(), { at, count ->
            bytes.copyOfRange(at.toInt(), at.toInt() + count)
        }, "default", ByteArray(20), replacementDtb).getOrThrow()
        val rendered = FastbootVendorBoot.render(bytes, result)
        assertEquals(1000, int(rendered, 2100))
        assertArrayEquals(replacementDtb, rendered.copyOfRange(2 * page, 2 * page + 1000))
    }

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
