package io.github.ncorror.nekoflash.protocol.fastboot

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Образы сверены с `mke2fs` 1.47.0 и `mke2fs.conf` AOSP (`system/extras/ext4_utils`):
 *
 * ```
 * MKE2FS_CONFIG=mke2fs.conf E2FSPROGS_FAKE_TIME=1700000000 mke2fs -q -F -t ext4 -b 4096 \
 *   -E nodiscard,lazy_itable_init=1,hash_seed=66666666-7777-8888-9999-aaaaaaaaaaaa[,stride=…,stripe-width=…] \
 *   -O uninit_bg [-I 512 | -O casefold -E …,encoding=utf8] -U 11111111-2222-3333-4444-555555555555 FILE BLOCKS
 * ```
 *
 * Эталон — SHA-256 по ненулевым блокам этого файла (номер блока, 8 байт LE,
 * и блок), где `s_kbytes_written` во всех копиях суперблока равен 1: так его
 * оставляет `sparse_io` в сборке `mke2fs` для `fastboot` — у неё нет
 * статистики записи. Нулевые блоки файла — то, что `mke2fs` обнуляет, а
 * `sparse_io` пропускает; поэтому данные образа — ровно эти блоки.
 */
class FastbootExt4Test {
    private val uuid = bytes("11111111222233334444555555555555")
    private val seed = bytes("66666666777788889999aaaaaaaaaaaa")

    private fun request(blocks: Long, erase: Long = 0, logical: Long = 0, projid: Boolean = false, casefold: Boolean = false) =
        FastbootExt4.Request(blocks * FastbootExt4.BLOCK_SIZE, erase, logical, projid, casefold, uuid, seed, NOW)

    @Test
    fun metadataSizedPartitionMatchesMke2fs() = check(request(4096), 11, "4c9f3ef03d3ce5723b867137456bb63d221833373ef8688c9a211169e74864be")

    @Test
    fun aTinyPartitionHasNoJournal() = check(request(1000), 10, "418e1374e4e4d46769680f1da3960098b1422e88fdbc1241a1ea38bcdeb72b3f")

    @Test
    fun smallPartitionMatchesMke2fs() = check(request(65536), 14, "41acac06333860f62eb3e108523ebfc9c04d1da95de5de597a1a77e0b5f1ef2f")

    @Test
    fun blocksPerGroupShrinkUntilTheLastGroupFits() =
        check(request(32800), 14, "1ac31e776164d14093aa690cf809bd900d872bf4bf5ead1a0288dc62a971754f")

    @Test
    fun defaultPartitionMatchesMke2fs() =
        check(request(2097152), 29, "99bffc29b5775c3d59a74219cdeff76f47946499432e4a06a3f0fb061684c53b")

    @Test
    fun aJournalOfFiveExtentsMovesTheTreeToABlock() =
        check(request(16777216), 81, "caa967bd2230dcd821fa241af3a02c17bba14c1d6056544b6f65c68b029cc9a0")

    @Test
    fun aJournalOfNineExtentsKeepsOneLeaf() =
        check(request(33554432), 155, "0092b35f988f42eb5ec84057a53831a30e78e5f0fb917cbdef3852420e5416a4")

    @Test
    fun strideMovesTheBitmapsBehindTheInodeTable() =
        check(request(2097152, erase = 4194304, logical = 16384), 29, "08110c30d82ee2db4ce49050efe50067c2782abe4b4f647ac404165afa69114e")

    @Test
    fun projectIdsUse512ByteInodes() =
        check(request(65536, projid = true), 15, "36bdda62be953943d7cd7b9bcac5fe522ae5bf114e605222c84988fce2a2026e")

    @Test
    fun casefoldWritesZerosInsteadOfSkipping() {
        val image = FastbootExt4.image(request(65536, casefold = true)).getOrThrow()
        check(image, 14, "6f6706d0df53cf6825d94f8f4c9e318202af817c02e2d480d9916d303be70de6")
        assertEquals(0, chunks(image).count { it.type == FastbootSparse.CHUNK_DONT_CARE })
    }

    @Test
    fun theImageSpansTheWholeRequestedPartition() {
        val image = FastbootExt4.image(request(32800)).getOrThrow()
        assertEquals(32800, ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN).getInt(TOTAL_BLOCKS))
        assertTrue(chunks(image).none { it.type == FastbootSparse.CHUNK_FILL })
    }

    @Test
    fun aPartitionTooSmallIsRefusedInMke2fsWords() {
        val failure = FastbootExt4.image(request(20)).exceptionOrNull()
        assertEquals("Filesystem too small.", failure?.message)
    }

    private fun check(request: FastbootExt4.Request, blocks: Int, digest: String) =
        check(FastbootExt4.image(request).getOrThrow(), blocks, digest)

    private fun check(image: ByteArray, blocks: Int, digest: String) {
        val sha = MessageDigest.getInstance("SHA-256")
        var count = 0
        for (chunk in chunks(image).filter { it.type == FastbootSparse.CHUNK_RAW }) {
            for (index in 0 until chunk.blocks) {
                val at = chunk.offset + index * FastbootExt4.BLOCK_SIZE
                sha.update(ByteBuffer.allocate(Long.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putLong(chunk.block + index).array())
                sha.update(image, at, FastbootExt4.BLOCK_SIZE)
                count++
            }
        }
        assertEquals(blocks, count)
        assertEquals(digest, sha.digest().joinToString("") { "%02x".format(it) })
    }

    private class Chunk(val type: Int, val block: Long, val blocks: Int, val offset: Int)

    private fun chunks(image: ByteArray): List<Chunk> {
        val buffer = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(FastbootSparse.MAGIC, buffer.getInt(0))
        val total = buffer.getInt(TOTAL_CHUNKS)
        var at = FastbootSparse.HEADER_LEN
        var block = 0L
        return List(total) {
            val type = buffer.getShort(at).toInt() and 0xFFFF
            val count = buffer.getInt(at + 4)
            val size = buffer.getInt(at + 8)
            Chunk(type, block, count, at + FastbootSparse.CHUNK_HEADER_LEN).also {
                at += size
                block += count
            }
        }
    }

    private fun bytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private companion object {
        const val NOW = 1700000000L
        const val TOTAL_BLOCKS = 16
        const val TOTAL_CHUNKS = 20
    }
}
