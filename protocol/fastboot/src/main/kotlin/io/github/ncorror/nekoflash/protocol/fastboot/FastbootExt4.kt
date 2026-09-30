package io.github.ncorror.nekoflash.protocol.fastboot

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Пустая ext4 для `fastboot format` — `generate_ext4_image` из AOSP
 * `fastboot/fs.cpp`: `mke2fs -t ext4 -b 4096 -E android_sparse[,stride=…,
 * stripe-width=…] -O uninit_bg [-I 512] [-O casefold -E encoding=utf8]` с
 * `mke2fs.conf` из `system/extras/ext4_utils`. Внешней программы здесь нет,
 * поэтому её вывод собирается напрямую ([Ext4Geometry], [Ext4Builder]).
 */
public object FastbootExt4 {
    public const val BLOCK_SIZE: Int = Ext4Geometry.BLOCK_SIZE

    /**
     * [eraseBlockSize] и [logicalBlockSize] — `erase-block-size` и
     * `logical-block-size` устройства (0 — не сказало); [uuid] и [hashSeed]
     * — по 16 случайных байт, как `uuid_generate`; [now] — секунды Unix.
     */
    public class Request(
        public val sizeBytes: Long,
        public val eraseBlockSize: Long,
        public val logicalBlockSize: Long,
        public val projectIds: Boolean,
        public val casefold: Boolean,
        public val uuid: ByteArray,
        public val hashSeed: ByteArray,
        public val now: Long,
    ) {
        /**
         * `stride`: наибольшее из 8 КиБ и логического блока, в блоках. С
         * `casefold` настольный клиент передаёт `-E` второй раз, и `mke2fs`
         * забывает первый (`android_sparse`, `stride`) — так и здесь.
         */
        internal val stride: Int
            get() = if (casefold || eraseBlockSize == 0L || logicalBlockSize == 0L) {
                0
            } else {
                (maxOf(logicalBlockSize, MIN_STRIDE_BYTES) / BLOCK_SIZE).toInt()
            }

        internal val stripeWidth: Int
            get() = if (stride == 0) 0 else maxOf((eraseBlockSize / BLOCK_SIZE).toInt(), stride)
    }

    /**
     * Образ в формате sparse. Блоки, которые `mke2fs` обнуляет, — пропуск
     * (`DONT_CARE`); с `casefold` образ сплошной, как у настольного клиента,
     * и нули пишутся заполнением.
     */
    public fun image(request: Request): Result<ByteArray> = runCatching {
        val requested = request.sizeBytes / BLOCK_SIZE
        val geometry = Ext4Geometry.of(requested, Ext4Profile.of(requested, request.projectIds))
        val blocks = Ext4Builder(geometry, request).build()
        val chunks = mutableListOf<SparseChunk>()
        var runStart = -1L
        var run = java.io.ByteArrayOutputStream()
        var next = -1L
        fun flush() {
            if (runStart >= 0) chunks += SparseChunk.Raw(runStart, run.toByteArray())
            run = java.io.ByteArrayOutputStream()
        }
        for ((block, bytes) in blocks) {
            if (block != next) {
                flush()
                if (request.casefold) {
                    val gapStart = if (next < 0) 0 else next
                    if (block > gapStart) chunks += SparseChunk.Fill(gapStart, block - gapStart)
                }
                runStart = block
            }
            run.write(bytes)
            next = block + 1
        }
        flush()
        if (request.casefold && next < requested) chunks += SparseChunk.Fill(maxOf(next, 0), requested - maxOf(next, 0))
        SparseWriter.write(BLOCK_SIZE, requested, chunks)
    }

    private const val MIN_STRIDE_BYTES = 8192L
}

/** `struct ext2_super_block` пустой ext4 — поля, которые заполняет `mke2fs`. */
internal class Ext4Superblock(
    private val geometry: Ext4Geometry,
    private val groups: Ext4Groups,
    private val request: FastbootExt4.Request,
    private val jnlBlocks: ByteArray,
    private val overhead: Long,
    private val journal: Boolean,
) {
    /** Копия для группы [group]: у запасных снят `EXT2_VALID_FS` и записан номер группы. */
    fun bytes(group: Int): ByteArray {
        val out = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(S_INODES_COUNT, geometry.inodesCount.toInt())
        out.putInt(S_BLOCKS_COUNT, geometry.blocks.toInt())
        out.putInt(S_R_BLOCKS_COUNT, geometry.reservedBlocks.toInt())
        out.putInt(S_FREE_BLOCKS, groups.totalFreeBlocks.toInt())
        out.putInt(S_FREE_INODES, groups.totalFreeInodes.toInt())
        out.putInt(S_LOG_BLOCK_SIZE, LOG_BLOCK)
        out.putInt(S_LOG_CLUSTER_SIZE, LOG_BLOCK)
        out.putInt(S_BLOCKS_PER_GROUP, geometry.blocksPerGroup.toInt())
        out.putInt(S_CLUSTERS_PER_GROUP, geometry.blocksPerGroup.toInt())
        out.putInt(S_INODES_PER_GROUP, geometry.inodesPerGroup)
        out.putInt(S_WTIME, request.now.toInt())
        out.putShort(S_MAX_MNT_COUNT, (-1).toShort())
        out.putShort(S_MAGIC, MAGIC.toShort())
        out.putShort(S_STATE, (if (group == 0) VALID_FS else 0).toShort())
        out.putShort(S_ERRORS, ERRORS_CONTINUE.toShort())
        out.putInt(S_LASTCHECK, request.now.toInt())
        out.putInt(S_REV_LEVEL, DYNAMIC_REV)
        out.putInt(S_FIRST_INO, Ext4Geometry.FIRST_INO)
        out.putShort(S_INODE_SIZE, geometry.inodeSize.toShort())
        out.putShort(S_BLOCK_GROUP_NR, minOf(group, MAX_GROUP_NR).toShort())
        out.putInt(S_FEATURE_COMPAT, COMPAT_EXT_ATTR or COMPAT_DIR_INDEX or (if (journal) COMPAT_HAS_JOURNAL else 0))
        out.putInt(S_FEATURE_INCOMPAT, INCOMPAT_FILETYPE or INCOMPAT_EXTENTS or (if (request.casefold) INCOMPAT_CASEFOLD else 0))
        out.putInt(S_FEATURE_RO_COMPAT, RO_COMPAT)
        request.uuid.copyInto(out.array(), S_UUID)
        if (journal) out.putInt(S_JOURNAL_INUM, JOURNAL_INO)
        request.hashSeed.copyInto(out.array(), S_HASH_SEED)
        out.put(S_DEF_HASH_VERSION, HASH_HALF_MD4)
        if (journal) out.put(S_JNL_BACKUP_TYPE, JNL_BACKUP_BLOCKS)
        out.putInt(S_DEFAULT_MOUNT_OPTS, MOUNT_USER_XATTR or MOUNT_ACL)
        out.putInt(S_MKFS_TIME, request.now.toInt())
        if (journal) jnlBlocks.copyInto(out.array(), S_JNL_BLOCKS)
        if (geometry.inodeSize > Ext4Geometry.GOOD_OLD_INODE_SIZE) {
            out.putShort(S_MIN_EXTRA_ISIZE, Ext4Builder.EXTRA_ISIZE.toShort())
            out.putShort(S_WANT_EXTRA_ISIZE, Ext4Builder.EXTRA_ISIZE.toShort())
        }
        out.putInt(S_FLAGS, FLAGS_SIGNED_HASH)
        out.putShort(S_RAID_STRIDE, request.stride.toShort())
        out.putInt(S_RAID_STRIPE_WIDTH, request.stripeWidth)
        out.putLong(S_KBYTES_WRITTEN, 1)
        out.putInt(S_OVERHEAD_CLUSTERS, overhead.toInt())
        if (request.casefold) out.putShort(S_ENCODING, ENCODING_UTF8_12_1.toShort())
        return out.array()
    }

    private companion object {
        const val SIZE = 1024
        const val S_INODES_COUNT = 0x00
        const val S_BLOCKS_COUNT = 0x04
        const val S_R_BLOCKS_COUNT = 0x08
        const val S_FREE_BLOCKS = 0x0C
        const val S_FREE_INODES = 0x10
        const val S_LOG_BLOCK_SIZE = 0x18
        const val S_LOG_CLUSTER_SIZE = 0x1C
        const val S_BLOCKS_PER_GROUP = 0x20
        const val S_CLUSTERS_PER_GROUP = 0x24
        const val S_INODES_PER_GROUP = 0x28
        const val S_WTIME = 0x30
        const val S_MAX_MNT_COUNT = 0x36
        const val S_MAGIC = 0x38
        const val S_STATE = 0x3A
        const val S_ERRORS = 0x3C
        const val S_LASTCHECK = 0x40
        const val S_REV_LEVEL = 0x4C
        const val S_FIRST_INO = 0x54
        const val S_INODE_SIZE = 0x58
        const val S_BLOCK_GROUP_NR = 0x5A
        const val S_FEATURE_COMPAT = 0x5C
        const val S_FEATURE_INCOMPAT = 0x60
        const val S_FEATURE_RO_COMPAT = 0x64
        const val S_UUID = 0x68
        const val S_JOURNAL_INUM = 0xE0
        const val S_HASH_SEED = 0xEC
        const val S_DEF_HASH_VERSION = 0xFC
        const val S_JNL_BACKUP_TYPE = 0xFD
        const val S_DEFAULT_MOUNT_OPTS = 0x100
        const val S_MKFS_TIME = 0x108
        const val S_JNL_BLOCKS = 0x10C
        const val S_MIN_EXTRA_ISIZE = 0x15C
        const val S_WANT_EXTRA_ISIZE = 0x15E
        const val S_FLAGS = 0x160
        const val S_RAID_STRIDE = 0x164
        const val S_RAID_STRIPE_WIDTH = 0x170
        const val S_KBYTES_WRITTEN = 0x178
        const val S_OVERHEAD_CLUSTERS = 0x248
        const val S_ENCODING = 0x27C
        const val LOG_BLOCK = 2
        const val MAGIC = 0xEF53
        const val VALID_FS = 1
        const val ERRORS_CONTINUE = 1
        const val DYNAMIC_REV = 1
        const val MAX_GROUP_NR = 0xFFFF
        const val JOURNAL_INO = 8
        const val COMPAT_HAS_JOURNAL = 0x4
        const val COMPAT_EXT_ATTR = 0x8
        const val COMPAT_DIR_INDEX = 0x20
        const val INCOMPAT_FILETYPE = 0x2
        const val INCOMPAT_EXTENTS = 0x40
        const val INCOMPAT_CASEFOLD = 0x20000

        /** `sparse_super`, `large_file`, `huge_file`, `uninit_bg`, `dir_nlink`, `extra_isize`. */
        const val RO_COMPAT = 0x1 or 0x2 or 0x8 or 0x10 or 0x20 or 0x40
        const val HASH_HALF_MD4: Byte = 1
        const val JNL_BACKUP_BLOCKS: Byte = 1
        const val MOUNT_USER_XATTR = 0x4
        const val MOUNT_ACL = 0x8
        const val FLAGS_SIGNED_HASH = 1
        const val ENCODING_UTF8_12_1 = 1
    }
}
