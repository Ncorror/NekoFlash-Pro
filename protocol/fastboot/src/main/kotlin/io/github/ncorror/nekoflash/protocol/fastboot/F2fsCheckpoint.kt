package io.github.ncorror.nekoflash.protocol.fastboot

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Два пакета чекпоинта — `f2fs_write_check_point_pack` из
 * `mkfs/f2fs_format.c`: в первом `checkpoint_ver` = [version], сводки
 * сегментов, биты NAT; во втором — версия 0. Корень и три файла квот уже
 * лежат в «горячих» сегментах ([F2fsImage]).
 */
internal class F2fsCheckpoint(
    private val layout: F2fsLayout,
    private val version: Long,
    private val quotaInodes: List<Long>,
    private val nextFreeNid: Long,
) {
    private val quotaInum = quotaInodes.size
    private val quotaDnum = quotaInodes.size * F2fsImage.QUOTA_BLOCKS.toInt()
    private val natBitsBytes = layout.segmentCountNat shl NAT_BITS_SHIFT
    private val natBitsBlocks = (natBitsBytes * 2 + Long.SIZE_BYTES + F2fs.BLOCK - 1) / F2fs.BLOCK

    fun write(write: (Long, ByteArray) -> Unit, zero: (Long) -> Unit) {
        val cp = checkpoint()
        var at = layout.segment0
        write(at, cp.array())
        repeat(layout.cpPayload.toInt()) { zero(++at) }
        write(++at, compactSummary())
        write(++at, nodeSummary(hot = true))
        write(++at, nodeSummary(hot = false))
        write(++at, nodeSummary(hot = false))
        write(++at, cp.array())
        if (cp.getInt(CKPT_FLAGS) and CP_NAT_BITS_FLAG != 0) {
            val bits = natBits(cp)
            val first = layout.segment0 + F2fsLayout.BLOCKS_PER_SEG - natBitsBlocks
            for (index in 0 until natBitsBlocks.toInt()) {
                write(first + index, bits.copyOfRange(index * F2fs.BLOCK, (index + 1) * F2fs.BLOCK))
            }
        }
        cp.putLong(0, 0)
        seal(cp)
        at = layout.segment0 + F2fsLayout.BLOCKS_PER_SEG
        write(at, cp.array())
        repeat(layout.cpPayload.toInt()) { zero(++at) }
        at += cp.getInt(CP_PACK_TOTAL) - layout.cpPayload - 1
        write(at, cp.array())
    }

    /** `struct f2fs_checkpoint` первого пакета с суммой. */
    private fun checkpoint(): ByteBuffer {
        val cp = ByteBuffer.allocate(F2fs.BLOCK).order(ByteOrder.LITTLE_ENDIAN)
        cp.putLong(0, version)
        val seg = layout.curSeg
        val nodes = intArrayOf(F2fsLayout.HOT_NODE, F2fsLayout.WARM_NODE, F2fsLayout.COLD_NODE)
        val data = intArrayOf(F2fsLayout.HOT_DATA, F2fsLayout.WARM_DATA, F2fsLayout.COLD_DATA)
        for (i in 0 until MAX_ACTIVE_LOGS) {
            cp.putInt(CUR_NODE_SEGNO + i * Int.SIZE_BYTES, if (i < 3) seg[nodes[i]].toInt() else -1)
            cp.putInt(CUR_DATA_SEGNO + i * Int.SIZE_BYTES, if (i < 3) seg[data[i]].toInt() else -1)
        }
        cp.putShort(CUR_NODE_BLKOFF, (1 + quotaInum).toShort())
        cp.putShort(CUR_DATA_BLKOFF, (1 + quotaDnum).toShort())
        cp.putLong(VALID_BLOCK_COUNT, (2 + quotaInum + quotaDnum).toLong())
        val usable = layout.segmentCountMain
        val reserved = layout.reservedSegments
        cp.putInt(RSVD_SEGMENT_COUNT, reserved.toInt())
        var overprov = ((usable - reserved) * layout.overprovision / PERCENT).toLong()
        if (overprov < reserved) overprov = reserved
        overprov += 2
        require(usable > overprov) { "Not enough segments to create F2FS Volume" }
        cp.putInt(OVERPROV_SEGMENT_COUNT, overprov.toInt())
        val free = usable - F2fsLayout.CURSEG_TYPES
        cp.putInt(FREE_SEGMENT_COUNT, free.toInt())
        cp.putLong(USER_BLOCK_COUNT, (free + F2fsLayout.CURSEG_TYPES - overprov) * F2fsLayout.BLOCKS_PER_SEG)
        val total = CP_PACK_BLOCKS + layout.cpPayload
        cp.putInt(CP_PACK_TOTAL, total.toInt())
        var flags = CP_UMOUNT_FLAG or CP_COMPACT_SUM_FLAG
        if (total <= F2fsLayout.BLOCKS_PER_SEG - natBitsBlocks) flags = flags or CP_NAT_BITS_FLAG
        cp.putInt(CKPT_FLAGS, flags)
        cp.putInt(CP_PACK_START_SUM, (1 + layout.cpPayload).toInt())
        cp.putInt(VALID_NODE_COUNT, 1 + quotaInum)
        cp.putInt(VALID_INODE_COUNT, 1 + quotaInum)
        cp.putInt(NEXT_FREE_NID, nextFreeNid.toInt())
        cp.putInt(SIT_VER_BITMAP_BYTESIZE, bitmapBytes(layout.segmentCountSit))
        cp.putInt(NAT_VER_BITMAP_BYTESIZE, bitmapBytes(layout.segmentCountNat))
        cp.putInt(CHECKSUM_OFFSET, CP_CHKSUM_OFFSET)
        seal(cp)
        return cp
    }

    private fun bitmapBytes(segments: Long): Int =
        (((segments / 2) shl F2fsLayout.LOG_BLOCKS_PER_SEG) / Byte.SIZE_BITS).toInt()

    /** `f2fs_checkpoint_chksum`: CRC-32 с начальным `F2FS_SUPER_MAGIC` до `checksum_offset`. */
    private fun seal(cp: ByteBuffer) {
        cp.putInt(CP_CHKSUM_OFFSET, F2fsCrc.of(F2fs.MAGIC, cp.array(), 0, CP_CHKSUM_OFFSET))
    }

    /** Сводка данных в сжатом виде: журнал NAT, журнал SIT, сводка «горячих» данных. */
    private fun compactSummary(): ByteArray {
        val sum = ByteBuffer.allocate(F2fs.BLOCK).order(ByteOrder.LITTLE_ENDIAN)
        sum.putShort(0, (1 + quotaInum).toShort())
        val nodes = listOf(F2fsLayout.ROOT_INO) + quotaInodes
        nodes.forEachIndexed { index, nid ->
            val at = JOURNAL_ENTRIES + index * NAT_JOURNAL_ENTRY
            sum.putInt(at, nid.toInt())
            sum.putInt(at + NAT_JOURNAL_INO, nid.toInt())
            sum.putInt(at + NAT_JOURNAL_BLOCK, (hotNodeBlock() + index).toInt())
        }
        val sit = SUM_JOURNAL_SIZE
        sum.putShort(sit, F2fsLayout.CURSEG_TYPES.toShort())
        val order = intArrayOf(
            F2fsLayout.HOT_NODE, F2fsLayout.WARM_NODE, F2fsLayout.COLD_NODE,
            F2fsLayout.HOT_DATA, F2fsLayout.WARM_DATA, F2fsLayout.COLD_DATA,
        )
        order.forEachIndexed { index, type ->
            val at = sit + JOURNAL_ENTRIES + index * SIT_JOURNAL_ENTRY
            sum.putInt(at, layout.curSeg[type].toInt())
            val valid = when (type) {
                F2fsLayout.HOT_NODE -> 1 + quotaInum
                F2fsLayout.HOT_DATA -> 1 + quotaDnum
                else -> 0
            }
            sum.putShort(at + SIT_VBLOCKS, ((type shl SIT_TYPE_SHIFT) or valid).toShort())
            for (bit in 0 until valid) {
                val byte = at + SIT_VALID_MAP + bit / Byte.SIZE_BITS
                sum.put(byte, (sum.get(byte).toInt() or (HIGH_BIT ushr (bit % Byte.SIZE_BITS))).toByte())
            }
        }
        val entries = SUM_JOURNAL_SIZE * 2
        summary(sum, entries, 0, F2fsLayout.ROOT_INO, 0)
        quotaInodes.forEachIndexed { quota, nid ->
            for (block in 0 until F2fsImage.QUOTA_BLOCKS.toInt()) {
                summary(sum, entries, 1 + quota * F2fsImage.QUOTA_BLOCKS.toInt() + block, nid, block)
            }
        }
        return sum.array()
    }

    /** Сводка «горячих» узлов — корень и файлы квот; «тёплых» и «холодных» — пустая. */
    private fun nodeSummary(hot: Boolean): ByteArray {
        val sum = ByteBuffer.allocate(F2fs.BLOCK).order(ByteOrder.LITTLE_ENDIAN)
        if (hot) (listOf(F2fsLayout.ROOT_INO) + quotaInodes).forEachIndexed { index, nid -> summary(sum, 0, index, nid, 0) }
        sum.put(SUMMARY_FOOTER, SUM_TYPE_NODE)
        return sum.array()
    }

    private fun summary(sum: ByteBuffer, base: Int, index: Int, nid: Long, offset: Int) {
        val at = base + index * SUMMARY_ENTRY
        sum.putInt(at, nid.toInt())
        sum.putShort(at + SUMMARY_OFS_IN_NODE, offset.toShort())
    }

    private fun hotNodeBlock(): Long =
        layout.mainBlkaddr + layout.curSeg[F2fsLayout.HOT_NODE] * F2fsLayout.BLOCKS_PER_SEG

    /** Биты NAT: `cp_ver | crc << 32`, затем «полные» (нули) и «пустые» (единицы, кроме нулевого). */
    private fun natBits(cp: ByteBuffer): ByteArray {
        val bits = ByteBuffer.allocate((natBitsBlocks * F2fs.BLOCK).toInt()).order(ByteOrder.LITTLE_ENDIAN)
        val crc = cp.getInt(CP_CHKSUM_OFFSET).toLong() and UINT
        bits.putLong(0, cp.getLong(0) or (crc shl Int.SIZE_BITS))
        val empty = Long.SIZE_BYTES + natBitsBytes.toInt()
        bits.array().fill(ALL_ONES, empty, empty + natBitsBytes.toInt())
        bits.put(empty, (bits.get(empty).toInt() and 1.inv()).toByte())
        return bits.array()
    }

    private companion object {
        const val PERCENT = 100.0
        const val NAT_BITS_SHIFT = 5
        const val MAX_ACTIVE_LOGS = 8
        const val CP_PACK_BLOCKS = 6L
        const val VALID_BLOCK_COUNT = 16
        const val RSVD_SEGMENT_COUNT = 24
        const val OVERPROV_SEGMENT_COUNT = 28
        const val FREE_SEGMENT_COUNT = 32
        const val USER_BLOCK_COUNT = 8
        const val CUR_NODE_SEGNO = 36
        const val CUR_NODE_BLKOFF = 68
        const val CUR_DATA_SEGNO = 84
        const val CUR_DATA_BLKOFF = 116
        const val CKPT_FLAGS = 132
        const val CP_PACK_TOTAL = 136
        const val CP_PACK_START_SUM = 140
        const val VALID_NODE_COUNT = 144
        const val VALID_INODE_COUNT = 148
        const val NEXT_FREE_NID = 152
        const val SIT_VER_BITMAP_BYTESIZE = 156
        const val NAT_VER_BITMAP_BYTESIZE = 160
        const val CHECKSUM_OFFSET = 164
        const val CP_CHKSUM_OFFSET = 4092
        const val CP_UMOUNT_FLAG = 0x1
        const val CP_COMPACT_SUM_FLAG = 0x4
        const val CP_NAT_BITS_FLAG = 0x80
        const val SUM_JOURNAL_SIZE = 507
        const val JOURNAL_ENTRIES = 2
        const val NAT_JOURNAL_ENTRY = 13
        const val NAT_JOURNAL_INO = 5
        const val NAT_JOURNAL_BLOCK = 9
        const val SIT_JOURNAL_ENTRY = 78
        const val SIT_VBLOCKS = 4
        const val SIT_VALID_MAP = 6
        const val SIT_TYPE_SHIFT = 10
        const val SUMMARY_ENTRY = 7
        const val SUMMARY_OFS_IN_NODE = 5
        const val SUMMARY_FOOTER = 4091
        const val SUM_TYPE_NODE: Byte = 1
        const val HIGH_BIT = 0x80
        const val UINT = 0xFFFF_FFFFL
        const val ALL_ONES: Byte = -1
    }
}

/** `f2fs_cal_crc32`: CRC-32 (полином 0xEDB88320) без финального XOR, с заданным началом. */
internal object F2fsCrc {
    private const val POLY = 0xEDB88320.toInt()

    fun of(seed: Int, bytes: ByteArray, offset: Int, length: Int): Int {
        var crc = seed
        for (index in offset until offset + length) {
            crc = crc xor (bytes[index].toInt() and 0xFF)
            repeat(Byte.SIZE_BITS) { crc = (crc ushr 1) xor (if (crc and 1 != 0) POLY else 0) }
        }
        return crc
    }
}
