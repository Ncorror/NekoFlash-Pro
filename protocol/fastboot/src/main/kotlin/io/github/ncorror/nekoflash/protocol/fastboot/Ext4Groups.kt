package io.github.ncorror.nekoflash.protocol.fastboot

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.TreeMap

/**
 * Занятые блоки — битовая карта `mke2fs`, но участками: у раздела в сотни
 * гигабайт занято лишь начало каждой группы, и карта на все блоки не нужна.
 */
internal class UsedBlocks(private val total: Long) {
    /** Начало участка → конец (не включая). Участки не пересекаются и не соприкасаются. */
    private val runs = TreeMap<Long, Long>()

    fun mark(start: Long, count: Long) {
        if (count <= 0) return
        var from = start
        var to = start + count
        runs.floorEntry(from)?.takeIf { it.value >= from }?.let { from = it.key; to = maxOf(to, it.value) }
        while (true) {
            val next = runs.ceilingEntry(from)?.takeIf { it.key <= to } ?: break
            to = maxOf(to, next.value)
            runs.remove(next.key)
        }
        runs[from] = to
    }

    fun used(block: Long): Boolean = runs.floorEntry(block)?.let { block < it.value } == true

    /** `ext2fs_find_first_zero_block_bitmap2` на [from]..[last]. */
    fun firstFree(from: Long, last: Long): Long? {
        var at = from
        while (at <= last) {
            val run = runs.floorEntry(at)?.takeIf { at < it.value } ?: return at
            at = run.value
        }
        return null
    }

    /** `ext2fs_find_first_set_block_bitmap2` на [from]..[last]. */
    fun firstUsed(from: Long, last: Long): Long? {
        if (used(from)) return from
        val next = runs.ceilingKey(from) ?: return null
        return next.takeIf { it <= last }
    }

    /**
     * `ext2fs_get_free_blocks2`: первые [count] свободных подряд с [start].
     * [finish] сам не проверяется; за концом раздела поиск идёт с нуля, если
     * [finish] не дальше [start]. Шаг по одному блоку заменён прыжком за
     * занятый блок: все начала до него его же и задевают.
     */
    fun freeRun(start: Long, finish: Long, count: Long): Long? {
        var at = start
        val stop = if (finish == 0L) start else finish
        while (true) {
            if (at + count - 1 >= total) {
                if (stop > start) return null
                at = 0
            }
            val clash = firstUsed(at, at + count - 1) ?: return at
            if (stop in (at + 1)..(clash + 1)) return null
            at = clash + 1
        }
    }

    fun countUsed(from: Long, last: Long): Long {
        var sum = 0L
        for ((begin, end) in runs.subMap(runs.floorKey(from) ?: from, true, last, true)) {
            sum += maxOf(0, minOf(end, last + 1) - maxOf(begin, from))
        }
        return sum
    }

    /** Карта группы: бит на блок с [first], [bits] бит. */
    fun bitmap(first: Long, bits: Long, into: ByteArray) {
        val last = first + bits - 1
        for ((begin, end) in runs.subMap(runs.floorKey(first) ?: first, true, last, true)) {
            for (block in maxOf(begin, first) until minOf(end, last + 1)) {
                val bit = (block - first).toInt()
                into[bit ushr BYTE_SHIFT] = (into[bit ushr BYTE_SHIFT].toInt() or (1 shl (bit and BIT_MASK))).toByte()
            }
        }
    }

    private companion object {
        const val BYTE_SHIFT = 3
        const val BIT_MASK = 7
    }
}

/**
 * Дескрипторы групп и учёт по ним — то, что в `libext2fs` делают
 * `ext2fs_initialize`, `ext2fs_allocate_group_table` и `alloc_stats.c`.
 */
internal class Ext4Groups(private val geometry: Ext4Geometry, stride: Int) {
    val used = UsedBlocks(geometry.blocks)
    private val count = geometry.groups
    val blockBitmap = LongArray(count)
    val inodeBitmap = LongArray(count)
    val inodeTable = LongArray(count)
    val freeBlocks = LongArray(count)
    val freeInodes = IntArray(count)
    val usedDirs = IntArray(count)
    val flags = IntArray(count)
    val itableUnused = IntArray(count)
    val usedInodes = sortedSetOf<Long>()
    var totalFreeBlocks = 0L
        private set
    var totalFreeInodes = geometry.inodesCount
        private set

    init {
        var reservedInodes = Ext4Geometry.FIRST_INO
        for (group in 0 until count) {
            if (group != count - 1) flags[group] = flags[group] or BLOCK_UNINIT
            flags[group] = flags[group] or INODE_UNINIT
            val unused = maxOf(0, geometry.inodesPerGroup - reservedInodes)
            reservedInodes = maxOf(0, reservedInodes - geometry.inodesPerGroup)
            itableUnused[group] = unused
            used.mark(geometry.groupFirst(group), geometry.superAndDescBlocks(group).toLong())
            freeBlocks[group] = geometry.groupBlocks(group) -
                (2 + geometry.inodeBlocksPerGroup + geometry.superAndDescBlocks(group))
            totalFreeBlocks += freeBlocks[group]
            freeInodes[group] = geometry.inodesPerGroup
        }
        for (group in 0 until count) allocateTables(group, stride)
    }

    /** `ext2fs_allocate_group_table` без `flex_bg`; `stride` сдвигает битовые карты за таблицу inode. */
    private fun allocateTables(group: Int, stride: Int) {
        val first = geometry.groupFirst(group)
        val last = first + geometry.groupBlocks(group) - 1
        var start = first
        if (stride != 0) {
            start = (used.freeRun(first, last, 1) ?: first) + geometry.inodeBlocksPerGroup
            start += (stride.toLong() * group) % (last - start + 1)
            if (start >= last) start = first
        }
        blockBitmap[group] = take(start, first, last, 1)
        inodeBitmap[group] = take(start, first, last, 1)
        val table = used.freeRun(first, last, geometry.inodeBlocksPerGroup.toLong())
            ?: throw IllegalArgumentException("Could not allocate block in ext2 filesystem")
        used.mark(table, geometry.inodeBlocksPerGroup.toLong())
        inodeTable[group] = table
    }

    private fun take(start: Long, first: Long, last: Long, count: Long): Long {
        val block = used.freeRun(start, last, count) ?: used.freeRun(first, last, count)
            ?: throw IllegalArgumentException("Could not allocate block in ext2 filesystem")
        used.mark(block, count)
        return block
    }

    fun groupOf(block: Long): Int = (block / geometry.blocksPerGroup).toInt()

    fun groupOfInode(inode: Long): Int = ((inode - 1) / geometry.inodesPerGroup).toInt()

    /** `ext2fs_block_alloc_stats_range(+1)`. */
    fun allocate(start: Long, count: Long) {
        used.mark(start, count)
        var block = start
        var left = count
        while (left > 0) {
            val group = groupOf(block)
            val inGroup = minOf(left, geometry.groupFirst(group) + geometry.groupBlocks(group) - block)
            freeBlocks[group] -= inGroup
            flags[group] = flags[group] and BLOCK_UNINIT.inv()
            totalFreeBlocks -= inGroup
            block += inGroup
            left -= inGroup
        }
    }

    /** `ext2fs_inode_alloc_stats2(+1)`. */
    fun allocateInode(inode: Long, directory: Boolean) {
        usedInodes += inode
        val group = groupOfInode(inode)
        freeInodes[group]--
        if (directory) usedDirs[group]++
        flags[group] = flags[group] and INODE_UNINIT.inv()
        val perGroup = geometry.inodesPerGroup.toLong()
        val firstUnused = perGroup - itableUnused[group] + group * perGroup + 1
        if (inode >= firstUnused) itableUnused[group] = (group * perGroup + perGroup - inode).toInt()
        totalFreeInodes--
    }

    /** Таблица дескрипторов: по 32 байта, сумма `crc16` по UUID, номеру и полям. */
    fun descriptors(uuid: ByteArray): ByteArray {
        val table = ByteArray(geometry.descBlocks * Ext4Geometry.BLOCK_SIZE)
        val out = ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN)
        for (group in 0 until count) {
            val at = group * DESC_SIZE
            out.putInt(at, blockBitmap[group].toInt())
            out.putInt(at + BB_INODE_BITMAP, inodeBitmap[group].toInt())
            out.putInt(at + BB_INODE_TABLE, inodeTable[group].toInt())
            out.putShort(at + BB_FREE_BLOCKS, freeBlocks[group].toInt().toShort())
            out.putShort(at + BB_FREE_INODES, freeInodes[group].toShort())
            out.putShort(at + BB_USED_DIRS, usedDirs[group].toShort())
            out.putShort(at + BB_FLAGS, flags[group].toShort())
            out.putShort(at + BB_ITABLE_UNUSED, itableUnused[group].toShort())
            val number = ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(group).array()
            val crc = Crc16.update(Crc16.update(Crc16.update(Crc16.INITIAL, uuid), number), table, at, BB_CHECKSUM)
            out.putShort(at + BB_CHECKSUM, crc.toShort())
        }
        return table
    }

    /**
     * `write_bitmaps`: буфер залит единицами, в него копируется
     * `blocks_per_group / 8` байт карты; у последней группы биты за концом
     * раздела — тоже единицы.
     */
    fun blockBitmapBytes(group: Int): ByteArray {
        val bits = ByteArray(Ext4Geometry.BLOCK_SIZE) { ALL_ONES }
        bits.fill(0, 0, (geometry.blocksPerGroup / Byte.SIZE_BITS).toInt())
        used.bitmap(geometry.groupFirst(group), geometry.groupBlocks(group), bits)
        val tail = geometry.groupBlocks(group)
        if (group == count - 1 && tail % geometry.blocksPerGroup != 0L) {
            for (bit in tail.toInt() until Ext4Geometry.BLOCK_SIZE * Byte.SIZE_BITS) setBit(bits, bit)
        }
        return bits
    }

    /** `write_bitmaps`: карта inode; байты за `inodes_per_group` — единицы. */
    fun inodeBitmapBytes(group: Int): ByteArray {
        val bits = ByteArray(Ext4Geometry.BLOCK_SIZE) { ALL_ONES }
        val bytes = (geometry.inodesPerGroup + BIT_MASK) / Byte.SIZE_BITS
        bits.fill(0, 0, bytes)
        val first = group.toLong() * geometry.inodesPerGroup + 1
        for (inode in usedInodes.subSet(first, first + geometry.inodesPerGroup)) setBit(bits, (inode - first).toInt())
        return bits
    }

    private fun setBit(bits: ByteArray, bit: Int) {
        bits[bit / Byte.SIZE_BITS] = (bits[bit / Byte.SIZE_BITS].toInt() or (1 shl (bit % Byte.SIZE_BITS))).toByte()
    }

    companion object {
        const val BLOCK_UNINIT = 0x2
        const val INODE_UNINIT = 0x1
        private const val DESC_SIZE = 32
        private const val BB_INODE_BITMAP = 4
        private const val BB_INODE_TABLE = 8
        private const val BB_FREE_BLOCKS = 12
        private const val BB_FREE_INODES = 14
        private const val BB_USED_DIRS = 16
        private const val BB_FLAGS = 18
        private const val BB_ITABLE_UNUSED = 28
        private const val BB_CHECKSUM = 30
        private const val BIT_MASK = 7
        private const val ALL_ONES: Byte = -1
    }
}

/** `ext2fs_crc16`: CRC-16/ARC (полином 0xA001, отражённый), начальное значение `~0`. */
internal object Crc16 {
    const val INITIAL = 0xFFFF
    private const val POLY = 0xA001
    private const val BYTE_MASK = 0xFF
    private const val TABLE_SIZE = 256
    private val table = IntArray(TABLE_SIZE) { index ->
        var crc = index
        repeat(Byte.SIZE_BITS) { crc = if (crc and 1 != 0) (crc ushr 1) xor POLY else crc ushr 1 }
        crc
    }

    fun update(crc: Int, bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): Int {
        var value = crc
        for (index in offset until offset + length) {
            value = ((value ushr Byte.SIZE_BITS) and BYTE_MASK) xor table[(value xor bytes[index].toInt()) and BYTE_MASK]
        }
        return value and INITIAL
    }
}
