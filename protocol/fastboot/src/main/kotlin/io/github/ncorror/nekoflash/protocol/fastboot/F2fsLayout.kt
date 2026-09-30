package io.github.ncorror.nekoflash.protocol.fastboot

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Разметка пустой f2fs — `f2fs_prepare_super_block` из `mkfs/f2fs_format.c`
 * (f2fs-tools 1.16.0, сборка AOSP с `WITH_ANDROID`) для того, что задаёт
 * `make_f2fs -S РАЗМЕР -g android`: сектор 512 байт (у sparse-файла другого
 * не узнать), блок 4096, сегмент 512 блоков, секция и зона — по сегменту,
 * без `heap`, без зон и нескольких устройств.
 */
internal class F2fsLayout private constructor(val features: Int, sizeBytes: Long) {
    val totalSectors = sizeBytes / SECTOR
    val blockCount = totalSectors shr LOG_SECTORS_PER_BLOCK
    val segment0 = (2L * F2fs.BLOCK + SEGMENT_BYTES - 1) / SEGMENT_BYTES * SEGMENT_BYTES / F2fs.BLOCK
    val segmentCount: Long = (totalSectors * SECTOR - segment0 * F2fs.BLOCK) / SEGMENT_BYTES
    val sitBlkaddr = segment0 + CKPT_SEGMENTS * BLOCKS_PER_SEG
    val segmentCountSit = divCeil(divCeil(segmentCount, SIT_ENTRY_PER_BLOCK), BLOCKS_PER_SEG) * 2
    val natBlkaddr = sitBlkaddr + segmentCountSit * BLOCKS_PER_SEG
    val cpPayload: Long
    val segmentCountNat: Long
    val ssaBlkaddr: Long
    val segmentCountSsa: Long
    val mainBlkaddr: Long
    val sectionCount: Long
    val segmentCountMain: Long
    val overprovision: Double
    val reservedSegments: Long
    val curSeg = LongArray(CURSEG_TYPES)

    init {
        var natSegments = divCeil(
            divCeil((segmentCount - (CKPT_SEGMENTS + segmentCountSit)) * BLOCKS_PER_SEG, NAT_ENTRY_PER_BLOCK),
            BLOCKS_PER_SEG,
        )
        val sitBitmap = ((segmentCountSit / 2) shl LOG_BLOCKS_PER_SEG) / Byte.SIZE_BITS
        val maxSitBitmap = minOf(sitBitmap, MAX_SIT_BITMAP_SIZE)
        val maxNatBitmap: Long
        if (maxSitBitmap > MAX_SIT_BITMAP_SIZE_IN_CKPT) {
            maxNatBitmap = MAX_BITMAP_SIZE_IN_CKPT
            cpPayload = divCeil(maxSitBitmap, F2fs.BLOCK.toLong())
        } else {
            maxNatBitmap = MAX_BITMAP_SIZE_IN_CKPT - maxSitBitmap
            cpPayload = 0
        }
        val maxNatSegments = (maxNatBitmap * Byte.SIZE_BITS) shr LOG_BLOCKS_PER_SEG
        natSegments = minOf(natSegments, maxNatSegments)
        segmentCountNat = natSegments * 2
        ssaBlkaddr = natBlkaddr + segmentCountNat * BLOCKS_PER_SEG
        val validBlocks = (segmentCount - (CKPT_SEGMENTS + segmentCountSit + segmentCountNat)) * BLOCKS_PER_SEG
        segmentCountSsa = divCeil(validBlocks / BLOCKS_PER_SEG + 1, BLOCKS_PER_SEG)
        val metaSegments = CKPT_SEGMENTS + segmentCountSit + segmentCountNat + segmentCountSsa
        mainBlkaddr = segment0 + metaSegments * BLOCKS_PER_SEG
        val zones = segmentCount - metaSegments
        require(zones > 0) { TOO_SMALL }
        sectionCount = zones
        segmentCountMain = zones
        overprovision = bestOverprovision(segmentCountMain, sectionCount)
        reservedSegments = ((PERCENT / overprovision + 1 + CURSEG_TYPES) * divCeil(segmentCountMain, sectionCount))
            .toLong()
        require(overprovision != 0.0 && segmentCount >= MIN_SEGMENTS && segmentCountMain - CURSEG_TYPES >= reservedSegments) {
            TOO_SMALL
        }
        require(zones > AVAIL_ZONES) { "$zones zones: Need more zones by shrinking zone size" }
        curSeg[HOT_NODE] = 0
        curSeg[WARM_NODE] = curSeg[HOT_NODE] + 1
        curSeg[COLD_NODE] = curSeg[WARM_NODE] + 1
        curSeg[HOT_DATA] = curSeg[COLD_NODE] + 1
        curSeg[COLD_DATA] = maxOf((zones shr 2) - 1, curSeg[HOT_DATA] + 1)
        curSeg[WARM_DATA] = maxOf((zones shr 1) - 1, curSeg[COLD_DATA] + 1)
        verifyCurSegs()
    }

    /** `verify_cur_segs`: совпадение — раскладка подряд с нуля. */
    private fun verifyCurSegs() {
        val clash = curSeg.indices.any { i -> (i + 1 until CURSEG_TYPES).any { curSeg[it] == curSeg[i] } }
        if (!clash) return
        curSeg[0] = 0
        for (i in 1 until CURSEG_TYPES) curSeg[i] = curSeg[i - 1] + 1
    }

    fun has(feature: Int): Boolean = features and feature != 0

    /** `struct f2fs_super_block` (3072 байта) — `f2fs_prepare_super_block` и `cure_extension_list`. */
    fun superblock(uuid: ByteArray, kernelRelease: String, quotaInodes: List<Long>): ByteArray {
        val sb = ByteBuffer.allocate(SB_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        sb.putInt(0, F2fs.MAGIC)
        sb.putShort(SB_MAJOR, MAJOR_VERSION.toShort())
        sb.putShort(SB_MINOR, MINOR_VERSION.toShort())
        sb.putInt(SB_LOG_SECTORSIZE, LOG_SECTORSIZE)
        sb.putInt(SB_LOG_SECTORS_PER_BLOCK, LOG_SECTORS_PER_BLOCK)
        sb.putInt(SB_LOG_BLOCKSIZE, LOG_BLOCKSIZE)
        sb.putInt(SB_LOG_BLOCKS_PER_SEG, LOG_BLOCKS_PER_SEG)
        sb.putInt(SB_SEGS_PER_SEC, 1)
        sb.putInt(SB_SECS_PER_ZONE, 1)
        sb.putLong(SB_BLOCK_COUNT, blockCount)
        sb.putInt(SB_SECTION_COUNT, sectionCount.toInt())
        sb.putInt(SB_SEGMENT_COUNT, segmentCount.toInt())
        sb.putInt(SB_SEGMENT_COUNT_CKPT, CKPT_SEGMENTS.toInt())
        sb.putInt(SB_SEGMENT_COUNT_SIT, segmentCountSit.toInt())
        sb.putInt(SB_SEGMENT_COUNT_NAT, segmentCountNat.toInt())
        sb.putInt(SB_SEGMENT_COUNT_SSA, segmentCountSsa.toInt())
        sb.putInt(SB_SEGMENT_COUNT_MAIN, segmentCountMain.toInt())
        sb.putInt(SB_SEGMENT0_BLKADDR, segment0.toInt())
        sb.putInt(SB_CP_BLKADDR, segment0.toInt())
        sb.putInt(SB_SIT_BLKADDR, sitBlkaddr.toInt())
        sb.putInt(SB_NAT_BLKADDR, natBlkaddr.toInt())
        sb.putInt(SB_SSA_BLKADDR, ssaBlkaddr.toInt())
        sb.putInt(SB_MAIN_BLKADDR, mainBlkaddr.toInt())
        sb.putInt(SB_ROOT_INO, ROOT_INO.toInt())
        sb.putInt(SB_NODE_INO, NODE_INO.toInt())
        sb.putInt(SB_META_INO, META_INO.toInt())
        uuid.copyInto(sb.array(), SB_UUID)
        extensions(sb)
        sb.putInt(SB_CP_PAYLOAD, cpPayload.toInt())
        val version = kernelRelease.toByteArray(Charsets.UTF_8).let { it.copyOf(minOf(it.size, VERSION_NAME_LEN - 1)) }
        version.copyInto(sb.array(), SB_VERSION)
        version.copyInto(sb.array(), SB_INIT_VERSION)
        sb.putInt(SB_FEATURE, features)
        quotaInodes.forEachIndexed { index, inode -> sb.putInt(SB_QF_INO + index * Int.SIZE_BYTES, inode.toInt()) }
        if (has(F2fs.FEATURE_CASEFOLD)) sb.putShort(SB_S_ENCODING, ENCODING_UTF8_12_1.toShort())
        return sb.array()
    }

    /** `cure_extension_list` с `WITH_ANDROID`: холодные — медиа, горячая — `db`. */
    private fun extensions(sb: ByteBuffer) {
        (COLD_EXTENSIONS + HOT_EXTENSIONS).forEachIndexed { index, name ->
            name.toByteArray(Charsets.US_ASCII).copyInto(sb.array(), SB_EXTENSION_LIST + index * EXTENSION_LEN)
        }
        sb.putInt(SB_EXTENSION_COUNT, COLD_EXTENSIONS.size)
        sb.put(SB_HOT_EXT_COUNT, HOT_EXTENSIONS.size.toByte())
    }

    companion object {
        const val SECTOR = 512L
        const val LOG_SECTORSIZE = 9
        const val LOG_SECTORS_PER_BLOCK = 3
        const val LOG_BLOCKSIZE = 12
        const val LOG_BLOCKS_PER_SEG = 9
        const val BLOCKS_PER_SEG = 512L
        const val SEGMENT_BYTES = BLOCKS_PER_SEG * F2fs.BLOCK
        const val CKPT_SEGMENTS = 2L
        const val CURSEG_TYPES = 6
        const val HOT_DATA = 0
        const val WARM_DATA = 1
        const val COLD_DATA = 2
        const val HOT_NODE = 3
        const val WARM_NODE = 4
        const val COLD_NODE = 5
        const val ROOT_INO = 3L
        const val NODE_INO = 1L
        const val META_INO = 2L
        const val TOO_SMALL = "Device size is not sufficient for F2FS volume"
        private const val SIT_ENTRY_PER_BLOCK = 55L
        private const val NAT_ENTRY_PER_BLOCK = 455L
        private const val MAX_SIT_BITMAP_SIZE = 19072L
        private const val MAX_BITMAP_SIZE_IN_CKPT = 3900L
        private const val MAX_SIT_BITMAP_SIZE_IN_CKPT = 3836L
        private const val MIN_SEGMENTS = 9L
        private const val AVAIL_ZONES = 6L
        private const val PERCENT = 100.0
        private const val MAJOR_VERSION = 1
        private const val MINOR_VERSION = 16
        private const val SB_SIZE = 3072
        private const val SB_MAJOR = 4
        private const val SB_MINOR = 6
        private const val SB_LOG_SECTORSIZE = 8
        private const val SB_LOG_SECTORS_PER_BLOCK = 12
        private const val SB_LOG_BLOCKSIZE = 16
        private const val SB_LOG_BLOCKS_PER_SEG = 20
        private const val SB_SEGS_PER_SEC = 24
        private const val SB_SECS_PER_ZONE = 28
        private const val SB_BLOCK_COUNT = 36
        private const val SB_SECTION_COUNT = 44
        private const val SB_SEGMENT_COUNT = 48
        private const val SB_SEGMENT_COUNT_CKPT = 52
        private const val SB_SEGMENT_COUNT_SIT = 56
        private const val SB_SEGMENT_COUNT_NAT = 60
        private const val SB_SEGMENT_COUNT_SSA = 64
        private const val SB_SEGMENT_COUNT_MAIN = 68
        private const val SB_SEGMENT0_BLKADDR = 72
        private const val SB_CP_BLKADDR = 76
        private const val SB_SIT_BLKADDR = 80
        private const val SB_NAT_BLKADDR = 84
        private const val SB_SSA_BLKADDR = 88
        private const val SB_MAIN_BLKADDR = 92
        private const val SB_ROOT_INO = 96
        private const val SB_NODE_INO = 100
        private const val SB_META_INO = 104
        private const val SB_UUID = 108
        private const val SB_EXTENSION_COUNT = 1148
        private const val SB_EXTENSION_LIST = 1152
        private const val SB_CP_PAYLOAD = 1664
        private const val SB_VERSION = 1668
        private const val SB_INIT_VERSION = 1924
        private const val SB_FEATURE = 2180
        private const val SB_QF_INO = 2745
        private const val SB_HOT_EXT_COUNT = 2757
        private const val SB_S_ENCODING = 2758
        private const val EXTENSION_LEN = 8
        private const val VERSION_NAME_LEN = 252
        private const val ENCODING_UTF8_12_1 = 1

        /** `media_ext_lists` из `f2fs_format.c`. */
        private val COLD_EXTENSIONS = listOf(
            "mp", "wm", "og", "jp", "avi", "m4v", "m4p", "mkv", "mov", "webm", "wav", "m4a", "3gp", "opus", "flac",
            "gif", "png", "svg", "webp", "jar", "deb", "iso", "gz", "xz", "zst", "pdf", "pyc", "ttc", "ttf", "exe",
            "apk", "cnt", "exo", "odex", "vdex", "so",
        )

        /** `hot_ext_lists` с `WITH_ANDROID`: без образов виртуальных машин. */
        private val HOT_EXTENSIONS = listOf("db")

        fun of(sizeBytes: Long, features: Int): F2fsLayout {
            require(sizeBytes <= MAX_BYTES) { "F2FS can support 16TB at most!!!" }
            val aligned = sizeBytes / F2fs.BLOCK * F2fs.BLOCK
            // Меньше первого сегмента: у AOSP вычитание уходит в переполнение
            // и кончается той же проверкой «мало места».
            require(aligned > SEGMENT_BYTES) { TOO_SMALL }
            return F2fsLayout(features, aligned)
        }

        private const val MAX_BYTES = 8388608L * 2 * 1024 * 1024

        /** `get_best_overprovision`: перебор с шагом как у AOSP, в `double`. */
        fun bestOverprovision(mainSegments: Long, sections: Long): Double {
            var candidate: Double
            val end: Double
            val diff: Double
            if (mainSegments < 256) {
                candidate = 10.0
                end = 95.0
                diff = 5.0
            } else {
                candidate = 0.01
                end = 10.0
                diff = 0.01
            }
            var maxOvp = 0.0
            var maxSpace = 0.0
            val usable = mainSegments.toDouble()
            val perSection = divCeil(mainSegments, sections).toDouble()
            while (candidate <= end) {
                val reserved = (PERCENT / candidate + 1 + CURSEG_TYPES) * perSection
                val ovp = (usable - reserved) * candidate / PERCENT
                if (ovp >= 0) {
                    val space = usable - maxOf(reserved, ovp) - 2
                    if (maxSpace < space) {
                        maxSpace = space
                        maxOvp = candidate
                    }
                }
                candidate += diff
            }
            return maxOvp
        }

        fun divCeil(a: Long, b: Long): Long = (a + b - 1) / b
    }
}
