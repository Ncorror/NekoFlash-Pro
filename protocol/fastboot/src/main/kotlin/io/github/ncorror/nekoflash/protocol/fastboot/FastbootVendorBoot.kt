package io.github.ncorror.nekoflash.protocol.fastboot

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Отрезок нового образа: старые байты, исправленный заголовок, новый рамдиск или нули. */
public sealed interface VendorBootPiece {
    public val length: Long
    public data class Old(val offset: Long, override val length: Long) : VendorBootPiece
    public class New(public val bytes: ByteArray) : VendorBootPiece {
        override val length: Long get() = bytes.size.toLong()
    }
    /** Отрезок источника нового рамдиска; данные не хранятся в плане. */
    public data class Replacement(val offset: Long, override val length: Long) : VendorBootPiece
    public data class Zero(override val length: Long) : VendorBootPiece
}

/**
 * `replace_vendor_ramdisk` для `vendor_boot` v3/v4. `default` заменяет все рамдиски,
 * имя — один фрагмент v4. DTB, bootconfig и AVB-футер сохраняются. Новый образ той
 * же длины; выход за границы — отказ. Смещения Long, поля формата — uint32.
 * [read] читает только заголовок, отдельные записи таблицы и сигнатуру футера.
 */
public object FastbootVendorBoot {
    public fun replace(
        size: Long,
        read: (offset: Long, length: Int) -> ByteArray,
        ramdiskName: String,
        newRamdisk: ByteArray,
        newDtb: ByteArray? = null,
    ): Result<List<VendorBootPiece>> = replace(size, read, ramdiskName, newRamdisk.size.toLong(), newDtb).map { pieces ->
        pieces.map { piece ->
            if (piece is VendorBootPiece.Replacement) {
                VendorBootPiece.New(newRamdisk.copyOfRange(piece.offset.toInt(), (piece.offset + piece.length).toInt()))
            } else piece
        }
    }

    /** План для поточного источника рамдиска; длина измеряется до построения плана. */
    public fun replace(
        size: Long,
        read: (offset: Long, length: Int) -> ByteArray,
        ramdiskName: String,
        newRamdiskSize: Long,
        newDtb: ByteArray? = null,
    ): Result<List<VendorBootPiece>> = runCatching {
        check(size >= 0) { "Vendor boot image size is negative" }
        checkedSize(newRamdiskSize)
        val image = OldImage(size, read)
        if (ramdiskName == DEFAULT) {
            replaceDefault(image, newRamdiskSize, newDtb ?: ByteArray(0))
        } else {
            replaceFragment(ramdiskName, image, newRamdiskSize, newDtb ?: ByteArray(0))
        }
    }

    /** Материализация небольшого плана, в котором рамдиск уже передан массивом. */
    public fun render(old: ByteArray, pieces: List<VendorBootPiece>): ByteArray {
        val result = ByteArray(Math.toIntExact(pieces.sumOf { it.length }))
        var at = 0
        for (piece in pieces) {
            val count = Math.toIntExact(piece.length)
            when (piece) {
                is VendorBootPiece.Old -> old.copyInto(result, at, piece.offset.toInt(), piece.offset.toInt() + count)
                is VendorBootPiece.New -> piece.bytes.copyInto(result, at)
                is VendorBootPiece.Zero -> Unit
                is VendorBootPiece.Replacement -> error("Render requires ramdisk bytes")
            }
            at += count
        }
        return result
    }

    private class OldImage(val size: Long, private val reader: (Long, Int) -> ByteArray) {
        fun read(offset: Long, length: Int): ByteArray {
            check(offset >= 0 && offset <= size && length >= 0 && length.toLong() <= size - offset) {
                "Read: Boundary overflow: 0x${hex(offset)} + 0x${hex(length.toLong())} > 0x${hex(size)}"
            }
            return reader(offset, length).also { check(it.size == length) { "Vendor boot image read failed" } }
        }
    }

    private class Header(bytes: ByteArray) {
        private val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val version get() = buffer.getInt(VERSION_AT)
        val pageSize get() = uint(PAGE_SIZE_AT)
        var ramdiskSize
            get() = uint(RAMDISK_SIZE_AT)
            set(value) { buffer.putInt(RAMDISK_SIZE_AT, checkedSize(value)) }
        var dtbSize
            get() = uint(DTB_SIZE_AT)
            set(value) { buffer.putInt(DTB_SIZE_AT, checkedSize(value)) }
        var tableSize
            get() = uint(TABLE_SIZE_AT)
            set(value) { buffer.putInt(TABLE_SIZE_AT, checkedSize(value)) }
        var entryNum
            get() = uint(ENTRY_NUM_AT)
            set(value) { buffer.putInt(ENTRY_NUM_AT, checkedSize(value)) }
        var entrySize
            get() = uint(ENTRY_SIZE_AT)
            set(value) { buffer.putInt(ENTRY_SIZE_AT, checkedSize(value)) }
        val bootconfigSize get() = uint(BOOTCONFIG_AT)
        private fun uint(at: Int) = buffer.getInt(at).toLong() and UINT
    }

    private fun headerSize(version: Int): Int = when (version) {
        3 -> HEADER_V3
        4 -> HEADER_V4
        else -> error("Unrecognized vendor boot header version $version")
    }

    private fun check(image: OldImage, version: Int): ByteArray {
        check(image.size >= HEADER_V3) {
            "Size of vendor boot is 0x${hex(image.size)}, less than size of V3 header: 0x${hex(HEADER_V3.toLong())}"
        }
        val expected = headerSize(Header(image.read(0, HEADER_V3)).version)
        check(image.size >= expected) {
            "Size of vendor boot is 0x${hex(image.size)}, less than size of V$version header: 0x${hex(expected.toLong())}"
        }
        val bytes = image.read(0, expected)
        val header = Header(bytes)
        check(bytes.copyOf(MAGIC.size).contentEquals(MAGIC)) { "Vendor boot image magic mismatch" }
        check(header.pageSize != 0L) { "Page size cannot be zero" }
        check(header.version >= version) { "Require vendor boot header V$version but is V${header.version}" }
        return bytes
    }

    private fun replaceDefault(image: OldImage, ramdiskSize: Long, newDtb: ByteArray): List<VendorBootPiece> {
        val bytes = check(image, 3)
        val old = Header(bytes.copyOf())
        val new = Header(bytes)
        val o = roundUp(bytes.size.toLong(), old.pageSize)
        val p = roundUp(old.ramdiskSize, old.pageSize)
        val q = roundUp(old.dtbSize, old.pageSize)
        val updater = DataUpdater(image)
        new.ramdiskSize = ramdiskSize
        if (new.version >= 4) {
            new.entrySize = ENTRY_BYTES.toLong()
            new.entryNum = 1L
            new.tableSize = ENTRY_BYTES.toLong()
        }
        updater.put(bytes)
        updater.copy(o - bytes.size)
        updater.replaceRamdisk(old.ramdiskSize, ramdiskSize)
        val newP = roundUp(new.ramdiskSize, old.pageSize)
        updater.skip(p - old.ramdiskSize, newP - new.ramdiskSize)
        updater.checkOffset(o + p, o + newP)
        val newQ = dtb(updater, old, new, newDtb, q)
        updater.checkOffset(o + p + q, o + newP + newQ)
        if (new.version >= 4) {
            val r = roundUp(old.tableSize, old.pageSize)
            val s = roundUp(old.bootconfigSize, old.pageSize)
            val newR = roundUp(new.tableSize, old.pageSize)
            val entry = ByteBuffer.allocate(ENTRY_BYTES).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0, checkedSize(ramdiskSize)).array()
            updater.skipWith(r, newR, entry)
            updater.checkOffset(o + p + q + r, o + newP + newQ + newR)
            updater.copy(s)
        }
        return updater.finish()
    }

    private class RamdiskTable(private val image: OldImage, private val header: Header, val at: Long) {
        init {
            check(header.entrySize >= ENTRY_BYTES && header.entryNum * header.entrySize <= header.tableSize) {
                "Too many vendor ramdisk entries in table, overflow"
            }
        }
        fun read(index: Long): ByteArray = image.read(at + index * header.entrySize, ENTRY_BYTES)
        fun size(bytes: ByteArray): Long = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(0).toLong() and UINT
        fun find(name: String): Pair<Long, Long> {
            var match = -1L
            var total = 0L
            var index = 0L
            while (index < header.entryNum) {
                val bytes = read(index)
                val raw = bytes.copyOfRange(NAME_IN_ENTRY, NAME_IN_ENTRY + NAME_BYTES)
                val end = raw.indexOf(0).let { if (it < 0) NAME_BYTES else it }
                if (String(raw, 0, end, Charsets.US_ASCII) == name) {
                    check(match < 0) { "Multiple vendor ramdisk '$name' found, name should be unique" }
                    match = index
                }
                total += size(bytes)
                index++
            }
            check(match >= 0) { "Vendor ramdisk '$name' not found" }
            check(total == header.ramdiskSize) { "Vendor ramdisk table size mismatch" }
            return match to total
        }
    }

    private fun replaceFragment(name: String, image: OldImage, ramdiskSize: Long, newDtb: ByteArray): List<VendorBootPiece> {
        val bytes = check(image, 4)
        val old = Header(bytes.copyOf())
        val new = Header(bytes)
        val o = roundUp(bytes.size.toLong(), old.pageSize)
        val p = roundUp(old.ramdiskSize, old.pageSize)
        val q = roundUp(old.dtbSize, old.pageSize)
        val r = roundUp(old.tableSize, old.pageSize)
        val s = roundUp(old.bootconfigSize, old.pageSize)
        check(o + p + q + r + s <= image.size) { "Vendor boot image size is too small, overflow" }
        val table = RamdiskTable(image, old, o + p + q)
        val (target, total) = table.find(name)
        new.ramdiskSize = total - table.size(table.read(target)) + ramdiskSize
        val updater = DataUpdater(image)
        updater.put(bytes)
        updater.copy(o - bytes.size)
        var index = 0L
        while (index < old.entryNum) {
            val size = table.size(table.read(index))
            if (index == target) updater.replaceRamdisk(size, ramdiskSize) else updater.copy(size)
            index++
        }
        val newP = roundUp(new.ramdiskSize, old.pageSize)
        updater.skip(p - old.ramdiskSize, newP - new.ramdiskSize)
        updater.checkOffset(o + p, o + newP)
        val newQ = dtb(updater, old, new, newDtb, q)
        updater.checkOffset(o + p + q, o + newP + newQ)
        rewriteTable(updater, table, old, target, ramdiskSize)
        updater.copy(r - old.entryNum * old.entrySize)
        updater.checkOffset(o + p + q + r, o + newP + newQ + r)
        updater.copy(s)
        return updater.finish()
    }

    private fun rewriteTable(updater: DataUpdater, table: RamdiskTable, old: Header, target: Long, size: Long) {
        var offset = 0L
        var index = 0L
        while (index < old.entryNum) {
            val bytes = table.read(index)
            val entry = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            entry.putInt(OFFSET_IN_ENTRY, checkedSize(offset))
            if (index == target) entry.putInt(0, checkedSize(size))
            offset += table.size(bytes)
            updater.put(bytes)
            updater.copy(old.entrySize - ENTRY_BYTES)
            index++
        }
    }

    private fun dtb(updater: DataUpdater, old: Header, new: Header, bytes: ByteArray, q: Long): Long {
        new.dtbSize = if (bytes.isNotEmpty()) bytes.size.toLong() else old.dtbSize
        val newQ = roundUp(new.dtbSize, old.pageSize)
        if (bytes.isEmpty()) {
            updater.copy(q)
        } else {
            updater.replace(old.dtbSize, VendorBootPiece.New(bytes))
            updater.skip(q - old.dtbSize, newQ - new.dtbSize)
        }
        return newQ
    }

    private class DataUpdater(private val old: OldImage) {
        private val pieces = mutableListOf<VendorBootPiece>()
        private var oldAt = 0L
        private var newAt = 0L
        fun copy(count: Long) {
            if (count == 0L) return
            advance(oldAt, count, "Copy")
            advance(newAt, count, "Copy")
            pieces += VendorBootPiece.Old(oldAt, count)
            oldAt += count
            newAt += count
        }
        fun put(bytes: ByteArray) {
            advance(oldAt, bytes.size.toLong(), "Copy")
            advance(newAt, bytes.size.toLong(), "Copy")
            pieces += VendorBootPiece.New(bytes)
            oldAt += bytes.size
            newAt += bytes.size
        }
        fun replaceRamdisk(oldCount: Long, newCount: Long) =
            replace(oldCount, VendorBootPiece.Replacement(0, newCount))
        fun replace(oldCount: Long, piece: VendorBootPiece) {
            advance(oldAt, oldCount, "Replace")
            advance(newAt, piece.length, "Replace")
            oldAt += oldCount
            if (piece.length > 0) pieces += piece
            newAt += piece.length
        }
        fun skip(oldSkip: Long, newSkip: Long) = skipWith(oldSkip, newSkip, ByteArray(0))
        fun skipWith(oldSkip: Long, newSkip: Long, head: ByteArray) {
            advance(oldAt, oldSkip, "Skip")
            advance(newAt, newSkip, "Skip")
            check(head.size <= newSkip) { "Skip: Boundary overflow" }
            oldAt += oldSkip
            if (head.isNotEmpty()) pieces += VendorBootPiece.New(head)
            if (newSkip > head.size) pieces += VendorBootPiece.Zero(newSkip - head.size)
            newAt += newSkip
        }
        fun checkOffset(oldOffset: Long, newOffset: Long) {
            check(oldAt == oldOffset) { "Old offset mismatch: expected: 0x${hex(oldOffset)}, actual: 0x${hex(oldAt)}" }
            check(newAt == newOffset) { "New offset mismatch: expected: 0x${hex(newOffset)}, actual: 0x${hex(newAt)}" }
        }
        fun finish(): List<VendorBootPiece> {
            val footerAt = old.size - AVB_FOOTER_SIZE
            val footer = old.size >= AVB_FOOTER_SIZE && old.read(footerAt, AVB_MAGIC.size).contentEquals(AVB_MAGIC)
            val end = if (footer) footerAt else old.size
            val kept = truncated(end)
            val written = kept.sumOf { it.length }
            return kept + listOfNotNull(
                VendorBootPiece.Zero(end - written).takeIf { it.length > 0 },
                VendorBootPiece.Old(footerAt, AVB_FOOTER_SIZE.toLong()).takeIf { footer },
            )
        }
        private fun truncated(end: Long): List<VendorBootPiece> {
            var at = 0L
            return pieces.mapNotNull { piece ->
                val start = at
                at += piece.length
                when {
                    start >= end -> null
                    at <= end -> piece
                    else -> cut(piece, end - start)
                }
            }
        }
        private fun cut(piece: VendorBootPiece, length: Long): VendorBootPiece = when (piece) {
            is VendorBootPiece.Old -> piece.copy(length = length)
            is VendorBootPiece.New -> VendorBootPiece.New(piece.bytes.copyOf(length.toInt()))
            is VendorBootPiece.Replacement -> piece.copy(length = length)
            is VendorBootPiece.Zero -> piece.copy(length = length)
        }
        private fun advance(current: Long, count: Long, op: String) {
            check(count >= 0 && current <= old.size && count <= old.size - current) {
                "$op: Boundary overflow: 0x${hex(current)} + 0x${hex(count)} > 0x${hex(old.size)}"
            }
        }
    }

    private fun checkedSize(value: Long): Int {
        check(value in 0..UINT) { "Vendor boot uint32 size overflow: $value" }
        return value.toInt()
    }
    private fun roundUp(value: Long, page: Long): Long = (value + page - 1) / page * page
    private fun hex(value: Long): String = java.lang.Long.toHexString(value)
    private const val DEFAULT = "default"
    private val MAGIC = "VNDRBOOT".toByteArray(Charsets.US_ASCII)
    private val AVB_MAGIC = "AVBf".toByteArray(Charsets.US_ASCII)
    private const val AVB_FOOTER_SIZE = 64
    private const val HEADER_V3 = 2112
    private const val HEADER_V4 = 2128
    private const val VERSION_AT = 8
    private const val PAGE_SIZE_AT = 12
    private const val RAMDISK_SIZE_AT = 24
    private const val DTB_SIZE_AT = 2100
    private const val TABLE_SIZE_AT = 2112
    private const val ENTRY_NUM_AT = 2116
    private const val ENTRY_SIZE_AT = 2120
    private const val BOOTCONFIG_AT = 2124
    private const val ENTRY_BYTES = 108
    private const val OFFSET_IN_ENTRY = 4
    private const val NAME_IN_ENTRY = 12
    private const val NAME_BYTES = 32
    private const val UINT = 0xFFFF_FFFFL
}
