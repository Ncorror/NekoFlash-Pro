package io.github.ncorror.nekoflash.protocol.fastboot

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.TreeMap

/**
 * Шаги `main()` из `misc/mke2fs.c` после `ext2fs_initialize` для пустой
 * ext4: корень, `lost+found`, резерв inode 3–10, inode плохих блоков,
 * журнал (`ext2fs_add_journal_inode3`) и `ext2fs_close`.
 *
 * Записываются только блоки, которые пишет `mke2fs` через `sparse_io`:
 * обнуление там — пропуск, поэтому таблицы inode (при `lazy_itable_init`)
 * и тело журнала в образ не попадают.
 */
internal class Ext4Builder(private val geometry: Ext4Geometry, private val request: FastbootExt4.Request) {
    private val groups = Ext4Groups(geometry, request.stride)
    private val inodes = TreeMap<Long, ByteArray>()
    private val written = TreeMap<Long, ByteArray>()
    private val now = request.now.toInt()
    private val journal = Ext4Journal.blocksFor(geometry.blocks)
    private var jnlBlocks = ByteArray(0)
    private var overhead = 0L

    fun build(): TreeMap<Long, ByteArray> {
        overhead = groups.used.countUsed(0, geometry.blocks - 1)
        val root = makeDirectory(ROOT, ROOT, DIR_ROOT_MODE)
        val lostFound = makeDirectory(ROOT, 0, DIR_LOST_FOUND_MODE)
        link(root, lostFound)
        repeat(LOST_FOUND_EXTRA_BLOCKS) { expand(lostFound) }
        for (inode in ROOT + 1 until Ext4Geometry.FIRST_INO) groups.allocateInode(inode, directory = false)
        groups.allocateInode(BAD_BLOCKS, directory = false)
        inodes[BAD_BLOCKS] = inode().apply { putTimes(this, ctime = true) }
        if (journal > 0) {
            val made = Ext4Journal(groups, geometry, request.uuid, now).create(journal)
            inodes[JOURNAL] = made.inode
            made.blocks.forEach { (block, bytes) -> written[block] = bytes }
            jnlBlocks = made.jnlBlocks
            overhead += journal
        }
        close()
        return written
    }

    /** `ext2fs_mkdir`: inode (новый, если [ino] = 0), блок с `.` и `..`, счётчики. */
    private fun makeDirectory(parent: Long, ino: Long, mode: Int): Long {
        val inode = if (ino != 0L) ino else newInode(parent)
        val block = newBlock(geometry.groupFirst(groups.groupOfInode(inode)))
        written[block] = directoryBlock(listOf(DirEntry(inode, "."), DirEntry(parent, "..")))
        val bytes = inode()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(I_MODE, mode.toShort())
        buffer.putInt(I_SIZE, Ext4Geometry.BLOCK_SIZE)
        buffer.putShort(I_LINKS, 2)
        buffer.putInt(I_BLOCKS, SECTORS_PER_BLOCK)
        buffer.putInt(I_FLAGS, EXTENTS_FL)
        Ext4Extents.inline(listOf(Ext4Extents.Extent(0, block, 1))).copyInto(bytes, I_BLOCK)
        putTimes(bytes, ctime = true)
        putLarge(bytes)
        inodes[inode] = bytes
        groups.allocate(block, 1)
        groups.allocateInode(inode, directory = true)
        return inode
    }

    /** `ext2fs_new_inode` для каталога: первый свободный с `s_first_ino` в группе родителя. */
    private fun newInode(parent: Long): Long {
        val group = groups.groupOfInode(parent)
        var inode = maxOf(group.toLong() * geometry.inodesPerGroup + 1, Ext4Geometry.FIRST_INO.toLong())
        while (inode in groups.usedInodes) inode++
        return inode
    }

    /** `ext2fs_new_block2`: первый свободный с [goal], затем с начала. */
    private fun newBlock(goal: Long): Long {
        val start = if (goal >= geometry.blocks) 0 else goal
        return groups.used.firstFree(start, geometry.blocks - 1)
            ?: groups.used.firstFree(0, start - 1)
            ?: throw IllegalArgumentException("Could not allocate block in ext2 filesystem")
    }

    /** `ext2fs_link` в корень: запись `..` уступает место новой. */
    private fun link(directory: Long, child: Long) {
        val block = Ext4Extents.firstBlock(inodes.getValue(directory))
        val entries = listOf(DirEntry(directory, "."), DirEntry(directory, ".."), DirEntry(child, LOST_FOUND))
        written[block] = directoryBlock(entries)
        val bytes = inodes.getValue(directory)
        val links = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getShort(I_LINKS) + 1
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(I_LINKS, links.toShort())
    }

    /** `ext2fs_expand_dir`: пустой блок каталога сразу за последним. */
    private fun expand(directory: Long) {
        val bytes = inodes.getValue(directory)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val extents = Ext4Extents.parse(bytes)
        val lastExtent = extents.last()
        val block = newBlock(lastExtent.start + lastExtent.length - 1)
        written[block] = ByteBuffer.allocate(Ext4Geometry.BLOCK_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0, 0).putShort(DIRENT_REC_LEN, Ext4Geometry.BLOCK_SIZE.toShort()).array()
        groups.allocate(block, 1)
        val size = buffer.getInt(I_SIZE)
        val merged = if (lastExtent.start + lastExtent.length == block) {
            extents.dropLast(1) + lastExtent.copy(length = lastExtent.length + 1)
        } else {
            extents + Ext4Extents.Extent(size / Ext4Geometry.BLOCK_SIZE.toLong(), block, 1)
        }
        Ext4Extents.inline(merged).copyInto(bytes, I_BLOCK)
        buffer.putInt(I_SIZE, size + Ext4Geometry.BLOCK_SIZE)
        buffer.putInt(I_BLOCKS, buffer.getInt(I_BLOCKS) + SECTORS_PER_BLOCK)
    }

    /** `ext2fs_close`: карты, таблица inode, суперблок и его копии с дескрипторами. */
    private fun close() {
        for (group in 0 until geometry.groups) {
            if (groups.flags[group] and Ext4Groups.BLOCK_UNINIT == 0) {
                written[groups.blockBitmap[group]] = groups.blockBitmapBytes(group)
            }
            if (groups.flags[group] and Ext4Groups.INODE_UNINIT == 0) {
                written[groups.inodeBitmap[group]] = groups.inodeBitmapBytes(group)
            }
        }
        for ((number, bytes) in inodes) {
            val index = number - 1
            val group = (index / geometry.inodesPerGroup).toInt()
            val offset = (index % geometry.inodesPerGroup) * geometry.inodeSize
            val block = groups.inodeTable[group] + offset / Ext4Geometry.BLOCK_SIZE
            val target = written.getOrPut(block) { ByteArray(Ext4Geometry.BLOCK_SIZE) }
            bytes.copyInto(target, (offset % Ext4Geometry.BLOCK_SIZE).toInt())
        }
        val descriptors = groups.descriptors(request.uuid)
        val superblock = Ext4Superblock(geometry, groups, request, jnlBlocks, overhead, journal > 0)
        for (group in 0 until geometry.groups) {
            if (!geometry.hasSuper(group)) continue
            val first = geometry.groupFirst(group)
            val head = ByteArray(Ext4Geometry.BLOCK_SIZE)
            if (group == 0) {
                superblock.bytes(group).copyInto(head, SUPERBLOCK_OFFSET)
            } else {
                superblock.bytes(group).copyInto(head, 0)
            }
            written[first] = head
            for (index in 0 until geometry.descBlocks) {
                val at = index * Ext4Geometry.BLOCK_SIZE
                written[first + 1 + index] = descriptors.copyOfRange(at, at + Ext4Geometry.BLOCK_SIZE)
            }
        }
    }

    /** `struct ext2_inode_large` нулями, размером с inode файловой системы. */
    private fun inode(): ByteArray = ByteArray(geometry.inodeSize)

    private fun putTimes(bytes: ByteArray, ctime: Boolean) {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(I_ATIME, now)
        if (ctime) buffer.putInt(I_CTIME, now)
        buffer.putInt(I_MTIME, now)
    }

    /** `ext2fs_write_new_inode` для больших inode: `i_extra_isize` и `i_crtime`. */
    private fun putLarge(bytes: ByteArray) {
        if (geometry.inodeSize <= Ext4Geometry.GOOD_OLD_INODE_SIZE) return
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(I_EXTRA_ISIZE, EXTRA_ISIZE.toShort())
        buffer.putInt(I_CRTIME, now)
    }

    private data class DirEntry(val inode: Long, val name: String)

    /** Блок каталога: записи впритык, последняя тянется до конца блока; тип — каталог. */
    private fun directoryBlock(entries: List<DirEntry>): ByteArray {
        val block = ByteBuffer.allocate(Ext4Geometry.BLOCK_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        var at = 0
        entries.forEachIndexed { index, entry ->
            val name = entry.name.toByteArray(Charsets.US_ASCII)
            val length = if (index == entries.lastIndex) Ext4Geometry.BLOCK_SIZE - at else recordLength(name.size)
            block.putInt(at, entry.inode.toInt())
            block.putShort(at + DIRENT_REC_LEN, length.toShort())
            block.put(at + DIRENT_NAME_LEN, name.size.toByte())
            block.put(at + DIRENT_TYPE, FT_DIR)
            name.copyInto(block.array(), at + DIRENT_NAME)
            at += length
        }
        return block.array()
    }

    private fun recordLength(name: Int): Int = (DIRENT_NAME + name + DIRENT_ALIGN - 1) and (DIRENT_ALIGN - 1).inv()

    companion object {
        const val I_MODE = 0
        const val I_SIZE = 4
        const val I_ATIME = 8
        const val I_CTIME = 12
        const val I_MTIME = 16
        const val I_LINKS = 26
        const val I_BLOCKS = 28
        const val I_FLAGS = 32
        const val I_BLOCK = 40
        const val I_SIZE_HIGH = 108
        const val I_EXTRA_ISIZE = 128
        const val I_CRTIME = 144
        const val EXTRA_ISIZE = 32
        const val EXTENTS_FL = 0x80000
        const val SECTORS_PER_BLOCK = 8
        private const val ROOT = 2L
        private const val BAD_BLOCKS = 1L
        private const val JOURNAL = 8L
        private const val DIR_ROOT_MODE = 0x41ED
        private const val DIR_LOST_FOUND_MODE = 0x41C0
        private const val LOST_FOUND = "lost+found"
        private const val LOST_FOUND_EXTRA_BLOCKS = 3
        private const val SUPERBLOCK_OFFSET = 1024
        private const val DIRENT_REC_LEN = 4
        private const val DIRENT_NAME_LEN = 6
        private const val DIRENT_TYPE = 7
        private const val DIRENT_NAME = 8
        private const val DIRENT_ALIGN = 4
        private const val FT_DIR: Byte = 2
    }
}
