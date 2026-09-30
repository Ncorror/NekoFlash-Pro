package io.github.ncorror.nekoflash.protocol.fastboot

/**
 * Размеры пустой ext4 — то, что `mke2fs` выводит из числа блоков до первой
 * записи: `PRS()` из `misc/mke2fs.c` с профилем `mke2fs.conf` из AOSP
 * `system/extras/ext4_utils` и `ext2fs_initialize()` из
 * `lib/ext2fs/initialize.c` (e2fsprogs ветки Android 14).
 *
 * Блок — всегда 4096 байт (`-b 4096` у `generate_ext4_image`), без `64bit`,
 * `flex_bg`, `resize_inode` и `meta_bg`: их нет ни в профиле, ни в ключах.
 */
internal class Ext4Geometry private constructor(
    /** Блоков, заявленных `mke2fs`: столько же в заголовке sparse. */
    val requestedBlocks: Long,
    /** Блоков в файловой системе: `ext2fs_initialize` может срезать неполную последнюю группу. */
    val blocks: Long,
    val reservedBlocks: Long,
    val blocksPerGroup: Long,
    val groups: Int,
    val descBlocks: Int,
    val inodeSize: Int,
    val inodesPerGroup: Int,
    val inodeBlocksPerGroup: Int,
) {
    val inodesCount: Long get() = inodesPerGroup.toLong() * groups

    fun groupFirst(group: Int): Long = group * blocksPerGroup

    /** `ext2fs_group_blocks_count`: у последней группы — сколько осталось. */
    fun groupBlocks(group: Int): Long =
        if (group == groups - 1) blocks - groupFirst(group) else blocksPerGroup

    /** `ext2fs_bg_has_super` при `sparse_super`: 0, 1 и степени 3, 5, 7. */
    fun hasSuper(group: Int): Boolean =
        group <= 1 || group and 1 == 1 && (isPower(group, 3) || isPower(group, 5) || isPower(group, 7))

    /** Блоков под суперблок и таблицу дескрипторов в начале группы. */
    fun superAndDescBlocks(group: Int): Int = if (hasSuper(group)) 1 + descBlocks else 0

    companion object {
        const val BLOCK_SIZE = 4096
        const val GOOD_OLD_INODE_SIZE = 128
        const val FIRST_INO = 11
        private const val DESC_SIZE = 32
        private const val MAX_32 = 0xFFFF_FFFFL
        private const val MIN_BLOCKS_PER_GROUP = 256
        private const val BLOCK_STEP = 8
        private const val RETRY_SLACK = 50
        private const val PERCENT = 100.0
        private const val OVERHEAD_SHARE_NUM = 3
        private const val OVERHEAD_SHARE_DEN = 4

        /**
         * `PRS()` и `ext2fs_initialize()`. Отказ — словами `mke2fs`, чтобы
         * отчёт совпадал с настольным клиентом.
         */
        fun of(blocks: Long, profile: Ext4Profile): Ext4Geometry {
            require(blocks > 0) { "invalid blocks '0' on device" }
            require(blocks <= MAX_32) {
                "Size of device (0x${blocks.toString(HEX)} blocks) too big to be expressed in 32 bits " +
                    "using a blocksize of $BLOCK_SIZE."
            }
            val inodeCount = blocks * BLOCK_SIZE / profile.inodeRatio
            require(inodeCount <= MAX_32) { "too many inodes ($inodeCount), raise inode ratio?" }
            require(inodeCount * profile.inodeSize < blocks * BLOCK_SIZE) {
                "inode_size (${profile.inodeSize}) * inodes_count ($inodeCount) too big for a filesystem " +
                    "with $blocks blocks, specify higher inode_ratio (-i) or lower inode count (-N)."
            }
            val reserved = (profile.reservedRatio * blocks / PERCENT).toLong()
            return initialize(blocks, reserved, inodeCount, profile.inodeSize)
        }

        @Suppress("LongMethod", "CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
        private fun initialize(requested: Long, requestedReserved: Long, requestedInodes: Long, inodeSize: Int): Ext4Geometry {
            var blocksPerGroup = BLOCK_SIZE * Byte.SIZE_BITS.toLong()
            var blocks = requested
            var reserved = requestedReserved
            require(reserved < requested) { "Invalid argument passed to ext2 library" }
            while (true) {
                val groups = divCeil(blocks, blocksPerGroup)
                require(groups > 0) { "Filesystem too small." }
                val descBlocks = divCeil(groups, (BLOCK_SIZE / DESC_SIZE).toLong())
                val inodes = maxOf(requestedInodes, FIRST_INO + 1L)
                var ipg = divCeil(inodes, groups)
                if (ipg > BLOCK_SIZE * Byte.SIZE_BITS) {
                    require(blocksPerGroup >= MIN_BLOCKS_PER_GROUP) { "Too many inodes" }
                    blocksPerGroup -= BLOCK_STEP
                    blocks = requested
                    continue
                }
                val inodesPerBlock = BLOCK_SIZE / inodeSize
                ipg = minOf(ipg, (1L shl Short.SIZE_BITS) - inodesPerBlock)
                var inodesPerGroup: Long
                var inodeBlocks: Long
                while (true) {
                    inodeBlocks = divCeil(ipg * inodeSize, BLOCK_SIZE.toLong())
                    inodesPerGroup = maxOf(inodeBlocks * BLOCK_SIZE / inodeSize, BLOCK_STEP.toLong()) and BLOCK_STEP.inv().toLong()
                    inodeBlocks = divCeil(inodesPerGroup * inodeSize, BLOCK_SIZE.toLong())
                    if (inodesPerGroup * groups <= MAX_32) break
                    ipg--
                }
                require(descBlocks <= blocksPerGroup * OVERHEAD_SHARE_NUM / OVERHEAD_SHARE_DEN) {
                    "meta_bg would be needed for $groups block groups"
                }
                require(3 + inodeBlocks + descBlocks <= blocksPerGroup) { "Too many inodes" }
                val geometry = Ext4Geometry(
                    requested, blocks, reserved, blocksPerGroup, groups.toInt(), descBlocks.toInt(),
                    inodeSize, inodesPerGroup.toInt(), inodeBlocks.toInt(),
                )
                val last = geometry.groups - 1
                val lastOverhead = 2 + inodeBlocks + geometry.superAndDescBlocks(last)
                val rem = blocks % blocksPerGroup
                require(!(groups == 1L && rem != 0L && rem < lastOverhead)) { "Filesystem too small." }
                if (rem != 0L && rem < lastOverhead + RETRY_SLACK) {
                    val ratio = PERCENT * requestedReserved / requested
                    blocks -= rem
                    reserved = (ratio * blocks / PERCENT).toLong()
                    continue
                }
                return geometry
            }
        }

        private fun divCeil(a: Long, b: Long): Long = (a + b - 1) / b

        private fun isPower(value: Int, base: Int): Boolean {
            var n = value.toLong()
            while (n % base == 0L) n /= base
            return n == 1L
        }

        private const val HEX = 16
    }
}

/**
 * Что `mke2fs` берёт из `mke2fs.conf` AOSP для `-t ext4`: типы
 * `ext4` и размерный (`floppy`, `small`, `default`, `big`, `huge`),
 * последнее значение побеждает. `-I 512` у `generate_ext4_image` — при
 * `projid`.
 */
internal data class Ext4Profile(val inodeSize: Int, val inodeRatio: Int, val reservedRatio: Double) {
    companion object {
        private const val MEG = 256L
        private const val FLOPPY_MEGS = 3
        private const val SMALL_MEGS = 512
        private const val DEFAULT_MEGS = 4L * 1024 * 1024
        private const val BIG_MEGS = 16L * 1024 * 1024
        private const val SMALL_INODE = 128
        private const val INODE = 256
        private const val PROJECT_INODE = 512
        private const val RESERVED_RATIO = 1.0

        fun of(blocks: Long, projectIds: Boolean): Ext4Profile {
            val (size, ratio) = when {
                blocks < FLOPPY_MEGS * MEG -> SMALL_INODE to FLOPPY_RATIO
                blocks < SMALL_MEGS * MEG -> SMALL_INODE to SMALL_RATIO
                blocks < DEFAULT_MEGS * MEG -> INODE to DEFAULT_RATIO
                blocks < BIG_MEGS * MEG -> INODE to BIG_RATIO
                else -> INODE to HUGE_RATIO
            }
            return Ext4Profile(if (projectIds) PROJECT_INODE else size, ratio, RESERVED_RATIO)
        }

        private const val FLOPPY_RATIO = 8192
        private const val SMALL_RATIO = 4096
        private const val DEFAULT_RATIO = 16384
        private const val BIG_RATIO = 32768
        private const val HUGE_RATIO = 65536
    }
}
