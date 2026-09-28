package io.github.ncorror.nekoflash.protocol.fastboot

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Отрезок нового образа: байты старого с места, новые байты или нули. */
public sealed interface VendorBootPiece {
    public val length: Long

    public data class Old(val offset: Long, override val length: Long) : VendorBootPiece

    public class New(public val bytes: ByteArray) : VendorBootPiece {
        override val length: Long get() = bytes.size.toLong()
    }

    public data class Zero(override val length: Long) : VendorBootPiece
}

/**
 * Замена рамдиска в образе `vendor_boot` — `replace_vendor_ramdisk` из AOSP
 * `fastboot/vendor_boot_img_utils.cpp` для `fastboot flash vendor_boot:ИМЯ`.
 *
 * `default` заменяет весь раздел рамдисков одним (таблица v4 становится одной
 * записью); другое имя заменяет в заголовке v4 один фрагмент таблицы, сдвигая
 * смещения следующих. DTB и bootconfig переносятся, AVB-футер в конце — тоже.
 * Новый образ той же длины, что прочитанный раздел; не влез — отказ.
 *
 * Образ раздела бывает в сотню МиБ, поэтому он не читается целиком: [read]
 * отдаёт заголовок и таблицу, а ответ — отрезки нового образа по порядку,
 * которые собираются потоком из прочитанного раздела.
 */
public object FastbootVendorBoot {
    public fun replace(
        size: Long,
        read: (offset: Long, length: Int) -> ByteArray,
        ramdiskName: String,
        newRamdisk: ByteArray,
        newDtb: ByteArray? = null,
    ): Result<List<VendorBootPiece>> = runCatching {
        check(size <= UINT) { "Vendor boot image is too big" }
        val image = OldImage(size.toInt(), read)
        if (ramdiskName == DEFAULT) {
            replaceDefault(image, newRamdisk, newDtb ?: ByteArray(0))
        } else {
            replaceFragment(ramdiskName, image, newRamdisk, newDtb ?: ByteArray(0))
        }
    }

    /** Весь образ одним массивом — для небольших образов и проверки. */
    public fun render(old: ByteArray, pieces: List<VendorBootPiece>): ByteArray = pieces.fold(ByteArray(0)) { acc, piece ->
        acc + when (piece) {
            is VendorBootPiece.Old -> old.copyOfRange(piece.offset.toInt(), (piece.offset + piece.length).toInt())
            is VendorBootPiece.New -> piece.bytes
            is VendorBootPiece.Zero -> ByteArray(piece.length.toInt())
        }
    }

    /** Прочитанный раздел: длина и чтение с места. */
    private class OldImage(val size: Int, private val reader: (Long, Int) -> ByteArray) {
        fun read(offset: Int, length: Int): ByteArray = reader(offset.toLong(), length)
    }

    /** Заголовок `vendor_boot_img_hdr_v3`/`v4` — поля, которые трогает замена. */
    private class Header(bytes: ByteArray) {
        private val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val version get() = buffer.getInt(VERSION_AT)
        val pageSize get() = buffer.getInt(PAGE_SIZE_AT)
        var ramdiskSize
            get() = buffer.getInt(RAMDISK_SIZE_AT)
            set(value) { buffer.putInt(RAMDISK_SIZE_AT, value) }
        var dtbSize
            get() = buffer.getInt(DTB_SIZE_AT)
            set(value) { buffer.putInt(DTB_SIZE_AT, value) }
        var tableSize
            get() = buffer.getInt(TABLE_SIZE_AT)
            set(value) { buffer.putInt(TABLE_SIZE_AT, value) }
        var entryNum
            get() = buffer.getInt(ENTRY_NUM_AT)
            set(value) { buffer.putInt(ENTRY_NUM_AT, value) }
        var entrySize
            get() = buffer.getInt(ENTRY_SIZE_AT)
            set(value) { buffer.putInt(ENTRY_SIZE_AT, value) }
        val bootconfigSize get() = buffer.getInt(BOOTCONFIG_AT)
    }

    /** `get_vendor_boot_header_size`. */
    private fun headerSize(version: Int): Int = when (version) {
        3 -> HEADER_V3
        4 -> HEADER_V4
        else -> error("Unrecognized vendor boot header version $version")
    }

    /** `check_vendor_boot_hdr`; ответ — заголовок, прочитанный целиком. */
    private fun check(image: OldImage, version: Int): ByteArray {
        check(image.size >= HEADER_V3) {
            "Size of vendor boot is 0x${hex(image.size)}, less than size of V3 header: 0x${hex(HEADER_V3)}"
        }
        val expected = headerSize(Header(image.read(0, HEADER_V3)).version)
        check(image.size >= expected) {
            "Size of vendor boot is 0x${hex(image.size)}, less than size of V$version header: 0x${hex(expected)}"
        }
        val bytes = image.read(0, expected)
        val header = Header(bytes)
        check(bytes.copyOf(MAGIC.size).contentEquals(MAGIC)) { "Vendor boot image magic mismatch" }
        check(header.pageSize != 0) { "Page size cannot be zero" }
        check(header.version >= version) { "Require vendor boot header V$version but is V${header.version}" }
        return bytes
    }

    /** `replace_default_vendor_ramdisk`. */
    private fun replaceDefault(image: OldImage, newRamdisk: ByteArray, newDtb: ByteArray): List<VendorBootPiece> {
        val old = Header(check(image, 3))
        val o = roundUp(headerSize(old.version), old.pageSize)
        val p = roundUp(old.ramdiskSize, old.pageSize)
        val q = roundUp(old.dtbSize, old.pageSize)
        val updater = DataUpdater(image)

        val headerBytes = image.read(0, minOf(o, image.size))
        val new = Header(headerBytes)
        new.ramdiskSize = newRamdisk.size
        if (new.version >= 4) {
            new.entrySize = ENTRY_BYTES
            new.entryNum = 1
            new.tableSize = ENTRY_BYTES
        }
        updater.put(o, headerBytes)
        updater.replace(old.ramdiskSize, newRamdisk)
        val newP = roundUp(new.ramdiskSize, old.pageSize)
        updater.skip(p - old.ramdiskSize, newP - new.ramdiskSize)
        updater.checkOffset(o + p, o + newP)

        val newQ = dtb(updater, old, new, newDtb, q)
        updater.checkOffset(o + p + q, o + newP + newQ)

        if (new.version >= 4) {
            val r = roundUp(old.tableSize, old.pageSize)
            val s = roundUp(old.bootconfigSize, old.pageSize)
            val newR = roundUp(new.tableSize, old.pageSize)
            // Одна запись на весь рамдиск: размер, смещение 0, тип NONE, имя и board_id — нули.
            val entry = ByteBuffer.allocate(minOf(ENTRY_BYTES, newR)).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0, new.ramdiskSize).array()
            updater.skipWith(r, newR, entry)
            updater.checkOffset(o + p + q + r, o + newP + newQ + newR)
            updater.copy(s)
        }
        return updater.finish()
    }

    /** `replace_vendor_ramdisk_fragment`. */
    private fun replaceFragment(name: String, image: OldImage, newRamdisk: ByteArray, newDtb: ByteArray): List<VendorBootPiece> {
        val old = Header(check(image, 4))
        val o = roundUp(headerSize(old.version), old.pageSize)
        val p = roundUp(old.ramdiskSize, old.pageSize)
        val q = roundUp(old.dtbSize, old.pageSize)
        val r = roundUp(old.tableSize, old.pageSize)
        val s = roundUp(old.bootconfigSize, old.pageSize)
        check(o.toLong() + p + q + r + s <= image.size) { "Vendor boot image size is too small, overflow" }
        check((old.entryNum.toLong() and UINT) * ENTRY_BYTES <= o.toLong() + p + q + r) {
            "Too many vendor ramdisk entries in table, overflow"
        }
        val tableAt = o + p + q
        val table = image.read(tableAt, old.entryNum * ENTRY_BYTES)
        val entries = ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN)
        val sizes = (0 until old.entryNum).map { entries.getInt(it * ENTRY_BYTES) }
        val index = uniqueRamdisk(name, table, old.entryNum)

        val updater = DataUpdater(image)
        val headerBytes = image.read(0, o)
        val new = Header(headerBytes)
        new.ramdiskSize = sizes.withIndex().sumOf { (entry, size) -> if (entry == index) newRamdisk.size else size }
        updater.put(o, headerBytes)
        sizes.forEachIndexed { entry, size -> if (entry == index) updater.replace(size, newRamdisk) else updater.copy(size) }
        val newP = roundUp(new.ramdiskSize, old.pageSize)
        updater.skip(p - old.ramdiskSize, newP - new.ramdiskSize)
        updater.checkOffset(o + p, o + newP)

        val newQ = dtb(updater, old, new, newDtb, q)
        updater.checkOffset(o + p + q, o + newP + newQ)

        // Таблица: размер заменённой записи и смещения всех — заново.
        val rewritten = image.read(tableAt, old.entryNum * old.entrySize)
        val out = ByteBuffer.wrap(rewritten).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 0
        for (entry in 0 until old.entryNum) {
            val at = entry * old.entrySize
            out.putInt(at + OFFSET_IN_ENTRY, offset)
            if (entry == index) out.putInt(at, newRamdisk.size)
            offset += out.getInt(at)
        }
        updater.put(old.entryNum * old.entrySize, rewritten)
        updater.copy(r - old.entryNum * old.entrySize)
        updater.checkOffset(o + p + q + r, o + newP + newQ + r)
        updater.copy(s)
        return updater.finish()
    }

    /** DTB: прежний как есть или новый с выравниванием по странице. Новая длина области — в ответе. */
    private fun dtb(updater: DataUpdater, old: Header, new: Header, newDtb: ByteArray, q: Int): Int {
        new.dtbSize = if (newDtb.isNotEmpty()) newDtb.size else old.dtbSize
        val newQ = roundUp(new.dtbSize, old.pageSize)
        if (newDtb.isEmpty()) {
            updater.copy(q)
        } else {
            updater.replace(old.dtbSize, newDtb)
            updater.skip(q - old.dtbSize, newQ - new.dtbSize)
        }
        return newQ
    }

    /** `find_unique_ramdisk`. */
    private fun uniqueRamdisk(name: String, table: ByteArray, count: Int): Int {
        val matches = (0 until count).filter { index ->
            val start = index * ENTRY_BYTES + NAME_IN_ENTRY
            val raw = table.copyOfRange(start, start + NAME_BYTES)
            val end = raw.indexOf(0).let { if (it < 0) NAME_BYTES else it }
            String(raw, 0, end, Charsets.US_ASCII) == name
        }
        check(matches.size <= 1) { "Multiple vendor ramdisk '$name' found, name should be unique" }
        return matches.singleOrNull() ?: error("Vendor ramdisk '$name' not found")
    }

    /**
     * `DataUpdater` отрезками: новый образ той же длины, что старый, где всё не
     * записанное — нули. Курсоры и проверки границ — как в AOSP.
     */
    private class DataUpdater(private val old: OldImage) {
        private val pieces = mutableListOf<VendorBootPiece>()
        private var oldAt = 0
        private var newAt = 0

        fun copy(count: Int) {
            if (count == 0) return
            advance(oldAt, old.size, count, "Copy")
            advance(newAt, old.size, count, "Copy")
            pieces += VendorBootPiece.Old(oldAt.toLong(), count.toLong())
            oldAt += count
            newAt += count
        }

        /** Копия [count] байт старого образа, уже исправленная вызывающим: [bytes]. */
        fun put(count: Int, bytes: ByteArray) {
            advance(oldAt, old.size, count, "Copy")
            advance(newAt, old.size, count, "Copy")
            pieces += VendorBootPiece.New(bytes.copyOf(count))
            oldAt += count
            newAt += count
        }

        fun replace(oldCount: Int, data: ByteArray) {
            advance(oldAt, old.size, oldCount, "Replace")
            oldAt += oldCount
            if (data.isEmpty()) return
            advance(newAt, old.size, data.size, "Replace")
            pieces += VendorBootPiece.New(data)
            newAt += data.size
        }

        fun skip(oldSkip: Int, newSkip: Int) = skipWith(oldSkip, newSkip, ByteArray(0))

        /** Пропуск, в начало которого записаны [head] байт (запись таблицы после `Skip`). */
        fun skipWith(oldSkip: Int, newSkip: Int, head: ByteArray) {
            advance(oldAt, old.size, oldSkip, "Skip")
            oldAt += oldSkip
            advance(newAt, old.size, newSkip, "Skip")
            if (head.isNotEmpty()) pieces += VendorBootPiece.New(head)
            if (newSkip > head.size) pieces += VendorBootPiece.Zero((newSkip - head.size).toLong())
            newAt += newSkip
        }

        fun checkOffset(oldOffset: Int, newOffset: Int) {
            check(oldAt == oldOffset) { "Old offset mismatch: expected: 0x${hex(oldOffset)}, actual: 0x${hex(oldAt)}" }
            check(newAt == newOffset) { "New offset mismatch: expected: 0x${hex(newOffset)}, actual: 0x${hex(newAt)}" }
        }

        /** `copy_avb_footer` и хвост нулями: последние 64 байта — футер `AVBf`, если он был. */
        fun finish(): List<VendorBootPiece> {
            val footerAt = old.size - AVB_FOOTER_SIZE
            val footer = old.size >= AVB_FOOTER_SIZE &&
                old.read(footerAt, AVB_MAGIC.size).contentEquals(AVB_MAGIC)
            val end = if (footer) footerAt else old.size
            val kept = truncated(end)
            val written = kept.sumOf { it.length }
            val tail = listOfNotNull(
                VendorBootPiece.Zero(end - written).takeIf { it.length > 0 },
                VendorBootPiece.Old(footerAt.toLong(), AVB_FOOTER_SIZE.toLong()).takeIf { footer },
            )
            return kept + tail
        }

        /** Написанное до [end]: футер ложится поверх того, что зашло на его место, как `Seek`. */
        private fun truncated(end: Int): List<VendorBootPiece> {
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
            is VendorBootPiece.Zero -> piece.copy(length = length)
        }

        private fun advance(current: Int, end: Int, count: Int, op: String) {
            check(count >= 0 && current.toLong() + count <= end) {
                "$op: Boundary overflow: 0x${hex(current)} + 0x${hex(count)} > 0x${hex(end)}"
            }
        }
    }

    private fun roundUp(value: Int, page: Int): Int = ((value.toLong() + page - 1) / page * page).toInt()

    private fun hex(value: Int): String = Integer.toHexString(value)

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
