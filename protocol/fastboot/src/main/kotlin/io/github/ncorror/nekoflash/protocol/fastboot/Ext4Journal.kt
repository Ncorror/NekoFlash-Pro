package io.github.ncorror.nekoflash.protocol.fastboot

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Дерево экстентов в `i_block` и в отдельном блоке — `lib/ext2fs/extent.c`, `ext3_extents.h`. */
internal object Ext4Extents {
    data class Extent(val logical: Long, val start: Long, val length: Long)

    private const val MAGIC = 0xF30A
    private const val HEADER = 12
    private const val ENTRY = 12
    private const val IN_INODE = 4
    private const val I_BLOCK_BYTES = 60
    private const val EH_ENTRIES = 2
    private const val EH_MAX = 4
    private const val EH_DEPTH = 6
    private const val EE_LEN = 4
    private const val EE_START_HI = 6
    private const val EE_START_LO = 8
    private const val HI_SHIFT = 32
    private const val LO_MASK = 0xFFFF_FFFFL

    /** Сколько экстентов помещается в `i_block`. */
    const val INLINE_CAPACITY = IN_INODE

    /** Корень в `i_block`: до четырёх экстентов листом. */
    fun inline(extents: List<Extent>): ByteArray {
        require(extents.size <= IN_INODE) { "extent tree too deep for the inode" }
        return node(I_BLOCK_BYTES, IN_INODE, depth = 0, extents.size) { buffer, at, index ->
            val extent = extents[index]
            buffer.putInt(at, extent.logical.toInt())
            buffer.putShort(at + EE_LEN, extent.length.toInt().toShort())
            buffer.putShort(at + EE_START_HI, (extent.start ushr HI_SHIFT).toInt().toShort())
            buffer.putInt(at + EE_START_LO, (extent.start and LO_MASK).toInt())
        }
    }

    /**
     * Корень в `i_block` после `extent_node_split`: заголовок и первая запись
     * становятся индексом на блок-лист [leaf], а записи 2–4 прежнего листа
     * ([before]) остаются в `i_block` как были — их никто не стирает.
     */
    fun index(leaf: Long, before: List<Extent>): ByteArray {
        val root = inline(before)
        val index = node(I_BLOCK_BYTES, IN_INODE, depth = 1, 1) { buffer, at, _ ->
            buffer.putInt(at, 0)
            buffer.putInt(at + EE_LEN, (leaf and LO_MASK).toInt())
            buffer.putShort(at + EE_START_LO, (leaf ushr HI_SHIFT).toInt().toShort())
        }
        index.copyInto(root, 0, 0, HEADER + ENTRY)
        return root
    }

    /** Блок-лист: заголовок и экстенты, вместимость — сколько войдёт в блок. */
    fun leaf(extents: List<Extent>, blockSize: Int): ByteArray {
        val capacity = (blockSize - HEADER) / ENTRY
        return node(blockSize, capacity, depth = 0, extents.size) { buffer, at, index ->
            val extent = extents[index]
            buffer.putInt(at, extent.logical.toInt())
            buffer.putShort(at + EE_LEN, extent.length.toInt().toShort())
            buffer.putShort(at + EE_START_HI, (extent.start ushr HI_SHIFT).toInt().toShort())
            buffer.putInt(at + EE_START_LO, (extent.start and LO_MASK).toInt())
        }
    }

    private fun node(
        size: Int,
        capacity: Int,
        depth: Int,
        entries: Int,
        entry: (ByteBuffer, Int, Int) -> Unit,
    ): ByteArray {
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(0, MAGIC.toShort())
        buffer.putShort(EH_ENTRIES, entries.toShort())
        buffer.putShort(EH_MAX, capacity.toShort())
        buffer.putShort(EH_DEPTH, depth.toShort())
        for (index in 0 until entries) entry(buffer, HEADER + index * ENTRY, index)
        return buffer.array()
    }

    /** Экстенты листа в `i_block` inode (только глубина 0). */
    fun parse(inode: ByteArray): List<Extent> {
        val buffer = ByteBuffer.wrap(inode).order(ByteOrder.LITTLE_ENDIAN)
        val base = Ext4Builder.I_BLOCK
        val count = buffer.getShort(base + EH_ENTRIES).toInt()
        return (0 until count).map { index ->
            val at = base + HEADER + index * ENTRY
            val high = buffer.getShort(at + EE_START_HI).toLong() and 0xFFFF
            Extent(
                logical = buffer.getInt(at).toLong() and LO_MASK,
                start = (high shl HI_SHIFT) or (buffer.getInt(at + EE_START_LO).toLong() and LO_MASK),
                length = buffer.getShort(at + EE_LEN).toLong() and 0xFFFF,
            )
        }
    }

    fun firstBlock(inode: ByteArray): Long = parse(inode).first().start
}

/**
 * Журнал в inode 8 — `ext2fs_add_journal_inode3` из `lib/ext2fs/mkjournal.c`:
 * размер по `ext2fs_default_journal_size`, начало — в группе у середины
 * раздела с наибольшим числом свободных блоков (`get_midpoint_journal_block`),
 * блоки — `ext2fs_fallocate` участками до 32768, пятый экстент выносит дерево
 * в отдельный блок, который ищется от блока перед первым экстентом
 * (`extent_node_split`). Тело журнала `mke2fs` обнуляет — в `sparse_io` это
 * пропуск, записывается только суперблок журнала.
 */
internal class Ext4Journal(
    private val groups: Ext4Groups,
    private val geometry: Ext4Geometry,
    private val uuid: ByteArray,
    private val now: Int,
) {
    class Made(val inode: ByteArray, val blocks: Map<Long, ByteArray>, val jnlBlocks: ByteArray)

    fun create(count: Long): Made {
        val extents = mutableListOf<Ext4Extents.Extent>()
        var tree = 0L
        var logical = 0L
        var goal = midpoint()
        while (logical < count) {
            val start = groups.used.firstFree(goal, geometry.blocks - 1)
                ?: groups.used.firstFree(0, goal - 1)
                ?: throw IllegalArgumentException("Could not allocate block in ext2 filesystem")
            val wanted = minOf(count - logical, MAX_INIT_LEN)
            val end = groups.used.firstUsed(start, minOf(start + wanted - 1, geometry.blocks - 1))
                ?: minOf(start + wanted, geometry.blocks)
            groups.allocate(start, end - start)
            extents += Ext4Extents.Extent(logical, start, end - start)
            if (extents.size == Ext4Extents.INLINE_CAPACITY + 1) {
                val from = extents.first().start - 1
                tree = groups.used.firstFree(from, geometry.blocks - 1)
                    ?: throw IllegalArgumentException("Could not allocate block in ext2 filesystem")
                groups.allocate(tree, 1)
            }
            logical += end - start
            goal = if (end >= geometry.blocks) 0 else end
        }
        val bytes = ByteArray(geometry.inodeSize)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val size = count * Ext4Geometry.BLOCK_SIZE
        buffer.putShort(Ext4Builder.I_MODE, FILE_MODE.toShort())
        buffer.putInt(Ext4Builder.I_SIZE, size.toInt())
        buffer.putInt(Ext4Builder.I_SIZE_HIGH, (size ushr Int.SIZE_BITS).toInt())
        buffer.putInt(Ext4Builder.I_ATIME, now)
        buffer.putInt(Ext4Builder.I_CTIME, now)
        buffer.putInt(Ext4Builder.I_MTIME, now)
        buffer.putShort(Ext4Builder.I_LINKS, 1)
        buffer.putInt(Ext4Builder.I_BLOCKS, ((count + if (tree != 0L) 1 else 0) * Ext4Builder.SECTORS_PER_BLOCK).toInt())
        buffer.putInt(Ext4Builder.I_FLAGS, Ext4Builder.EXTENTS_FL)
        val root = if (tree == 0L) {
            Ext4Extents.inline(extents)
        } else {
            Ext4Extents.index(tree, extents.take(Ext4Extents.INLINE_CAPACITY))
        }
        root.copyInto(bytes, Ext4Builder.I_BLOCK)
        if (geometry.inodeSize > Ext4Geometry.GOOD_OLD_INODE_SIZE) {
            buffer.putShort(Ext4Builder.I_EXTRA_ISIZE, Ext4Builder.EXTRA_ISIZE.toShort())
            buffer.putInt(Ext4Builder.I_CRTIME, now)
        }
        val written = linkedMapOf(extents.first().start to superblock(count))
        if (tree != 0L) written[tree] = Ext4Extents.leaf(extents, Ext4Geometry.BLOCK_SIZE)
        val jnl = ByteBuffer.allocate(JNL_WORDS * Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        jnl.put(root)
        jnl.putInt(JNL_SIZE_HIGH * Int.SIZE_BYTES, (size ushr Int.SIZE_BITS).toInt())
        jnl.putInt(JNL_SIZE * Int.SIZE_BYTES, size.toInt())
        return Made(bytes, written, jnl.array())
    }

    /** `get_midpoint_journal_block` без `flex_bg`. */
    private fun midpoint(): Long {
        val group = groups.groupOf(geometry.blocks / 2)
        val start = if (group > 0) group - 1 else group
        val end = if (group + 1 < geometry.groups) group + 1 else group
        var best = start
        for (candidate in start + 1..end) if (groups.freeBlocks[candidate] > groups.freeBlocks[best]) best = candidate
        return geometry.groupFirst(best)
    }

    /** `ext2fs_create_journal_superblock2`: big-endian, версия 2, один пользователь. */
    private fun superblock(count: Long): ByteArray {
        val block = ByteBuffer.allocate(Ext4Geometry.BLOCK_SIZE).order(ByteOrder.BIG_ENDIAN)
        block.putInt(0, JBD2_MAGIC)
        block.putInt(JSB_BLOCKTYPE, JBD2_SUPERBLOCK_V2)
        block.putInt(JSB_BLOCKSIZE, Ext4Geometry.BLOCK_SIZE)
        block.putInt(JSB_MAXLEN, count.toInt())
        block.putInt(JSB_FIRST, 1)
        block.putInt(JSB_SEQUENCE, 1)
        uuid.copyInto(block.array(), JSB_UUID)
        block.putInt(JSB_NR_USERS, 1)
        return block.array()
    }

    companion object {
        private const val MAX_INIT_LEN = 32768L
        private const val FILE_MODE = 0x8180
        private const val JNL_WORDS = 17
        private const val JNL_SIZE_HIGH = 15
        private const val JNL_SIZE = 16
        private const val JBD2_MAGIC = 0xC03B3998.toInt()
        private const val JBD2_SUPERBLOCK_V2 = 4
        private const val JSB_BLOCKTYPE = 4
        private const val JSB_BLOCKSIZE = 12
        private const val JSB_MAXLEN = 16
        private const val JSB_FIRST = 20
        private const val JSB_SEQUENCE = 24
        private const val JSB_UUID = 48
        private const val JSB_NR_USERS = 64

        /** `ext2fs_default_journal_size`; `0` — раздел мал для журнала, и `has_journal` снимается. */
        fun blocksFor(blocks: Long): Long = when {
            blocks < 2048 -> 0
            blocks < 32768 -> 1024
            blocks < 256L * 1024 -> 4096
            blocks < 512L * 1024 -> 8192
            blocks < 4096L * 1024 -> 16384
            blocks < 8192L * 1024 -> 32768
            blocks < 16384L * 1024 -> 65536
            blocks < 32768L * 1024 -> 131072
            else -> 262144
        }
    }
}
