package io.github.ncorror.nekoflash.protocol.fastboot

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.TreeMap

/**
 * Пустая f2fs для `fastboot format` — `generate_f2fs_image` из AOSP
 * `fastboot/fs.cpp`: `make_f2fs -S РАЗМЕР -g android [-O project_quota,
 * extra_attr] [-O casefold -C utf8] [-O compression -O extra_attr]`,
 * f2fs-tools 1.16.0 в сборке AOSP (`WITH_ANDROID`). Внешней программы здесь
 * нет, поэтому её вывод собирается напрямую ([F2fsLayout], [F2fsImage]).
 */
public object FastbootF2fs {
    /**
     * [uuid] — 16 случайных байт (`uuid_generate`); [now] — секунды Unix
     * (`mkfs_time`); [checkpointVersion] — `rand() | 1` после `srand(time)`;
     * [kernelRelease] — `uname -r` машины, где собирается образ (у телефона
     * — его ядро).
     */
    public class Request(
        public val sizeBytes: Long,
        public val projectIds: Boolean,
        public val casefold: Boolean,
        public val compression: Boolean,
        public val uuid: ByteArray,
        public val now: Long,
        public val checkpointVersion: Long,
        public val kernelRelease: String,
    )

    /** Образ в формате sparse, как его пишет `make_f2fs`: обнулённое — заполнением, остальное — пропуском. */
    public fun image(request: Request): Result<ByteArray> {
        val output = ByteArrayOutputStream()
        return write(request, output).map { output.toByteArray() }
    }

    /** Пишет sparse-образ последовательно; большие RAW-runs не склеиваются в ByteArray. */
    public fun write(request: Request, output: OutputStream): Result<Long> = runCatching {
        var features = F2fs.ANDROID_FEATURES
        if (request.projectIds) features = features or F2fs.FEATURE_PRJQUOTA or F2fs.FEATURE_EXTRA_ATTR
        if (request.casefold) features = features or F2fs.FEATURE_CASEFOLD
        if (request.compression) features = features or F2fs.FEATURE_COMPRESSION or F2fs.FEATURE_EXTRA_ATTR
        val layout = F2fsLayout.of(request.sizeBytes, features)
        val blocks = F2fsImage(layout, request).build()
        val chunks = mutableListOf<SparseChunk>()
        var runStart = -1L
        var next = -1L
        var runBytes = 0L
        var run = mutableListOf<ByteArray>()
        fun flush() {
            if (runStart >= 0 && run.isNotEmpty()) chunks += SparseChunk.Raw(runStart, run)
            run = mutableListOf()
            runStart = -1L
            runBytes = 0L
        }
        for ((block, bytes) in blocks) {
            if (bytes == null) {
                flush()
                val last = chunks.lastOrNull()
                if (last is SparseChunk.Fill && last.block + last.blocks == block) {
                    chunks[chunks.lastIndex] = last.copy(blocks = last.blocks + 1)
                } else {
                    chunks += SparseChunk.Fill(block, 1)
                }
            } else {
                if (block != next || runBytes >= MAX_CHUNK_BYTES) flush()
                if (runStart < 0) runStart = block
                run += bytes
                runBytes += bytes.size
            }
            next = block + 1
        }
        flush()
        SparseWriter.writeTo(output, F2fs.BLOCK, layout.totalSectors * F2fsLayout.SECTOR / F2fs.BLOCK, chunks)
    }

    /** `MAX_CHUNK_SIZE` у `f2fs_finalize_device`: кусок данных не длиннее 1 ГиБ. */
    private const val MAX_CHUNK_BYTES = 1L shl 30
}

/** Постоянные f2fs, общие для разметки и записи. */
internal object F2fs {
    const val BLOCK = 4096
    const val MAGIC = 0xF2F52010.toInt()
    const val FEATURE_ENCRYPT = 0x1
    const val FEATURE_EXTRA_ATTR = 0x8
    const val FEATURE_PRJQUOTA = 0x10
    const val FEATURE_QUOTA_INO = 0x80
    const val FEATURE_VERITY = 0x400
    const val FEATURE_CASEFOLD = 0x1000
    const val FEATURE_COMPRESSION = 0x2000

    /** `add_default_options` для `-g android`: `encrypt`, `quota`, `project_quota`, `extra_attr`, `verity`. */
    const val ANDROID_FEATURES = FEATURE_ENCRYPT or FEATURE_QUOTA_INO or FEATURE_PRJQUOTA or FEATURE_EXTRA_ATTR or
        FEATURE_VERITY
}

/**
 * Запись `f2fs_format_device`: обнуление SIT и NAT, корень и файлы квот,
 * NAT корня, записи `.` и `..`, два пакета чекпоинта, две копии
 * суперблока. Карта — номер блока → байты или `null` (обнулён,
 * `sparse_write_zeroed_blk`); запись поверх обнулённого побеждает, обнуление
 * поверх записанного — нет.
 */
internal class F2fsImage(private val layout: F2fsLayout, private val request: FastbootF2fs.Request) {
    private val blocks = TreeMap<Long, ByteArray?>()
    private val quotaInodes = listOf(QUOTA_USR, QUOTA_GRP, QUOTA_PRJ).indices.map { F2fsLayout.ROOT_INO + 1 + it }
    private val nextFreeNid = F2fsLayout.ROOT_INO + 1 + quotaInodes.size
    private val now = request.now.toInt()
    private var quotaData = 0

    fun build(): TreeMap<Long, ByteArray?> {
        zeroSegments(layout.sitBlkaddr, layout.segmentCountSit / 2, step = 1)
        zeroSegments(layout.natBlkaddr, layout.segmentCountNat / 2, step = 2)
        write(hotNode(0), rootInode())
        quotaInodes.forEachIndexed { index, inode -> quotaInode(index, inode) }
        write(layout.natBlkaddr, natRoot())
        write(hotData(0), rootDentry())
        F2fsCheckpoint(layout, request.checkpointVersion, quotaInodes, nextFreeNid).write(::write, ::zero)
        val superblock = ByteArray(F2fs.BLOCK)
        layout.superblock(request.uuid, request.kernelRelease, quotaInodes).copyInto(superblock, SUPER_OFFSET)
        write(0, superblock)
        write(1, superblock)
        return blocks
    }

    private fun write(block: Long, bytes: ByteArray) {
        blocks[block] = bytes.copyOf(F2fs.BLOCK)
    }

    private fun zero(block: Long) {
        if (!blocks.containsKey(block)) blocks[block] = null
    }

    private fun zeroSegments(start: Long, count: Long, step: Long) {
        for (index in 0 until count) {
            val first = start + index * step * F2fsLayout.BLOCKS_PER_SEG
            for (block in first until first + F2fsLayout.BLOCKS_PER_SEG) zero(block)
        }
    }

    private fun hotNode(offset: Long) = layout.mainBlkaddr + layout.curSeg[F2fsLayout.HOT_NODE] *
        F2fsLayout.BLOCKS_PER_SEG + offset

    private fun hotData(offset: Long) = layout.mainBlkaddr + layout.curSeg[F2fsLayout.HOT_DATA] *
        F2fsLayout.BLOCKS_PER_SEG + offset

    /** `calc_extra_isize` для набора возможностей. */
    private fun extraIsize(): Int = when {
        layout.has(F2fs.FEATURE_COMPRESSION) -> I_EXTRA_END
        layout.has(F2fs.FEATURE_PRJQUOTA) -> I_INODE_CHECKSUM
        else -> I_PROJID
    } - I_EXTRA_ISIZE

    /** Общее у inode корня и файлов квот. */
    private fun inode(ino: Long, mode: Int, links: Int, size: Long, iblocks: Long): ByteBuffer {
        val node = ByteBuffer.allocate(F2fs.BLOCK).order(ByteOrder.LITTLE_ENDIAN)
        node.putInt(FOOTER_NID, ino.toInt())
        node.putInt(FOOTER_INO, ino.toInt())
        node.putLong(FOOTER_CP_VER, 1)
        node.putShort(I_MODE, mode.toShort())
        node.putInt(I_LINKS, links)
        node.putLong(I_SIZE, size)
        node.putLong(I_BLOCKS, iblocks)
        node.putLong(I_ATIME, now.toLong())
        node.putLong(I_CTIME, now.toLong())
        node.putLong(I_MTIME, now.toLong())
        if (layout.has(F2fs.FEATURE_EXTRA_ATTR)) {
            node.put(I_INLINE, F2FS_EXTRA_ATTR)
            node.putShort(I_EXTRA_ISIZE, extraIsize().toShort())
        }
        return node
    }

    private fun addrIndex(): Int = if (layout.has(F2fs.FEATURE_EXTRA_ATTR)) extraIsize() / Int.SIZE_BYTES else 0

    /** `f2fs_write_root_inode`. */
    private fun rootInode(): ByteArray {
        val node = inode(F2fsLayout.ROOT_INO, ROOT_MODE, 2, F2fs.BLOCK.toLong(), 2)
        node.putInt(FOOTER_NEXT_BLKADDR, hotNode(1).toInt())
        node.putInt(I_CURRENT_DEPTH, 1)
        node.putInt(I_ADDR + addrIndex() * Int.SIZE_BYTES, hotData(0).toInt())
        return node.array()
    }

    /** `f2fs_write_qf_inode` и `f2fs_write_default_quota`. */
    private fun quotaInode(index: Int, ino: Long) {
        val node = inode(ino, QUOTA_MODE, 1, QUOTA_FILE_SIZE, 1 + QUOTA_BLOCKS)
        node.putInt(FOOTER_NEXT_BLKADDR, hotNode(1L + index + 1).toInt())
        node.putInt(I_FLAGS, FS_IMMUTABLE_FL)
        val data = hotData(1L + index * QUOTA_BLOCKS)
        quotaFile(index).forEachIndexed { block, bytes -> write(data + block, bytes) }
        quotaData += QUOTA_BLOCKS.toInt()
        for (block in 0 until QUOTA_BLOCKS.toInt()) {
            node.putInt(I_ADDR + (addrIndex() + block) * Int.SIZE_BYTES, (data + block).toInt())
        }
        write(hotNode(1L + index), node.array())
    }

    /** Пустой файл квот v2 на два блока — как `f2fs_write_default_quota`. */
    private fun quotaFile(qtype: Int): List<ByteArray> {
        val file = ByteBuffer.allocate(2 * F2fs.BLOCK).order(ByteOrder.LITTLE_ENDIAN)
        file.putInt(0, QUOTA_MAGICS[qtype])
        file.putInt(Int.SIZE_BYTES, 1)
        file.putInt(DQINFO, MAX_DQ_TIME)
        file.putInt(DQINFO + 4, MAX_IQ_TIME)
        file.putInt(DQINFO + 12, QT_TREEOFF + 5)
        file.putInt(DQINFO + 20, 5)
        for ((at, value) in listOf(1024 to 2, 2048 to 3, 3072 to 4, 4096 to 5, 5128 to 1)) file.put(at, value.toByte())
        file.putLong(DQBLK + 24, 1)
        file.putLong(DQBLK + 48, F2fs.BLOCK.toLong())
        val bytes = file.array()
        return listOf(bytes.copyOfRange(0, F2fs.BLOCK), bytes.copyOfRange(F2fs.BLOCK, 2 * F2fs.BLOCK))
    }

    /** `f2fs_update_nat_root`: квоты, корень, узлы и мета. */
    private fun natRoot(): ByteArray {
        val nat = ByteBuffer.allocate(F2fs.BLOCK).order(ByteOrder.LITTLE_ENDIAN)
        fun entry(nid: Long, block: Long) {
            val at = (nid * NAT_ENTRY).toInt()
            nat.putInt(at + 1, nid.toInt())
            nat.putInt(at + 5, block.toInt())
        }
        quotaInodes.forEachIndexed { index, ino -> entry(ino, hotNode(index + 1L)) }
        entry(F2fsLayout.ROOT_INO, hotNode(0))
        entry(F2fsLayout.NODE_INO, 1)
        entry(F2fsLayout.META_INO, 1)
        return nat.array()
    }

    /** `f2fs_add_default_dentry_root`: `.` и `..`. */
    private fun rootDentry(): ByteArray {
        val dentry = ByteBuffer.allocate(F2fs.BLOCK).order(ByteOrder.LITTLE_ENDIAN)
        for ((slot, name) in listOf(".", "..").withIndex()) {
            val at = DENTRY + slot * DIR_ENTRY
            dentry.putInt(at + 4, F2fsLayout.ROOT_INO.toInt())
            dentry.putShort(at + 8, name.length.toShort())
            dentry.put(at + 10, FT_DIR)
            name.toByteArray(Charsets.US_ASCII).copyInto(dentry.array(), FILENAME + slot * SLOT_LEN)
        }
        dentry.put(0, 0b11)
        return dentry.array()
    }

    companion object {
        const val QUOTA_BLOCKS = 2L
        private const val QUOTA_USR = 0
        private const val QUOTA_GRP = 1
        private const val QUOTA_PRJ = 2
        private const val SUPER_OFFSET = 1024
        private const val FOOTER = 4072
        private const val FOOTER_NID = FOOTER
        private const val FOOTER_INO = FOOTER + 4
        private const val FOOTER_CP_VER = FOOTER + 12
        private const val FOOTER_NEXT_BLKADDR = FOOTER + 20
        private const val I_MODE = 0
        private const val I_INLINE = 3
        private const val I_LINKS = 12
        private const val I_SIZE = 16
        private const val I_BLOCKS = 24
        private const val I_ATIME = 32
        private const val I_CTIME = 40
        private const val I_MTIME = 48
        private const val I_CURRENT_DEPTH = 72
        private const val I_FLAGS = 80
        private const val I_ADDR = 360
        private const val I_EXTRA_ISIZE = 360
        private const val I_PROJID = 364
        private const val I_INODE_CHECKSUM = 368
        private const val I_EXTRA_END = 396
        private const val F2FS_EXTRA_ATTR: Byte = 0x20
        private const val ROOT_MODE = 0x41ed
        private const val QUOTA_MODE = 0x8180
        private const val QUOTA_FILE_SIZE = 1024L * 6
        private const val FS_IMMUTABLE_FL = 0x10
        private const val NAT_ENTRY = 9
        private const val DENTRY = 30
        private const val DIR_ENTRY = 11
        private const val FILENAME = 2384
        private const val SLOT_LEN = 8
        private const val FT_DIR: Byte = 2
        private const val DQINFO = 8
        private const val DQBLK = 5136
        private const val MAX_DQ_TIME = 604800
        private const val MAX_IQ_TIME = 604800
        private const val QT_TREEOFF = 1
        private val QUOTA_MAGICS = intArrayOf(0xd9c01f11.toInt(), 0xd9c01927.toInt(), 0xd9c03f14.toInt())
    }
}
