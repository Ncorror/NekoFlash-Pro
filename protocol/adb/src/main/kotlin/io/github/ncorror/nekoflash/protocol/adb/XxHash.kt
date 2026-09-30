package io.github.ncorror.nekoflash.protocol.adb

/** XXH32 потоком — сумма заголовка, блоков и содержимого кадра LZ4. */
internal class XxHash32(private val seed: Int = 0) {
    private val v = intArrayOf(seed + P1 + P2, seed + P2, seed, seed - P1)
    private val stripe = ByteArray(STRIPE)
    private var buffered = 0
    private var total = 0L

    fun update(data: ByteArray, offset: Int = 0, length: Int = data.size): XxHash32 {
        total += length
        var at = offset
        val end = offset + length
        while (at < end) {
            val take = minOf(end - at, STRIPE - buffered)
            data.copyInto(stripe, buffered, at, at + take)
            buffered += take
            at += take
            if (buffered == STRIPE) {
                for (lane in 0 until LANES) v[lane] = round(v[lane], intLe(stripe, lane * Int.SIZE_BYTES))
                buffered = 0
            }
        }
        return this
    }

    fun digest(): Int {
        var acc = if (total >= STRIPE) {
            Integer.rotateLeft(v[0], 1) + Integer.rotateLeft(v[1], 7) + Integer.rotateLeft(v[2], 12) +
                Integer.rotateLeft(v[3], 18)
        } else {
            seed + P5
        }
        acc += total.toInt()
        var at = 0
        while (at + Int.SIZE_BYTES <= buffered) {
            acc = Integer.rotateLeft(acc + intLe(stripe, at) * P3, 17) * P4
            at += Int.SIZE_BYTES
        }
        while (at < buffered) {
            acc = Integer.rotateLeft(acc + (stripe[at].toInt() and BYTE) * P5, 11) * P1
            at++
        }
        acc = acc xor (acc ushr 15)
        acc *= P2
        acc = acc xor (acc ushr 13)
        acc *= P3
        return acc xor (acc ushr 16)
    }

    private fun round(acc: Int, lane: Int): Int = Integer.rotateLeft(acc + lane * P2, 13) * P1

    companion object {
        private const val P1 = 0x9E3779B1.toInt()
        private const val P2 = 0x85EBCA77.toInt()
        private const val P3 = 0xC2B2AE3D.toInt()
        private const val P4 = 0x27D4EB2F
        private const val P5 = 0x165667B1
        private const val STRIPE = 16
        private const val LANES = 4
        private const val BYTE = 0xFF

        fun of(data: ByteArray, offset: Int = 0, length: Int = data.size): Int =
            XxHash32().update(data, offset, length).digest()

        fun intLe(data: ByteArray, at: Int): Int =
            (data[at].toInt() and BYTE) or ((data[at + 1].toInt() and BYTE) shl 8) or
                ((data[at + 2].toInt() and BYTE) shl 16) or ((data[at + 3].toInt() and BYTE) shl 24)
    }
}

/** XXH64 потоком — сумма содержимого кадра zstd (младшие 32 бита). */
internal class XxHash64(private val seed: Long = 0) {
    private val v = longArrayOf(seed + P1 + P2, seed + P2, seed, seed - P1)
    private val stripe = ByteArray(STRIPE)
    private var buffered = 0
    private var total = 0L

    fun update(data: ByteArray, offset: Int = 0, length: Int = data.size): XxHash64 {
        total += length
        var at = offset
        val end = offset + length
        while (at < end) {
            val take = minOf(end - at, STRIPE - buffered)
            data.copyInto(stripe, buffered, at, at + take)
            buffered += take
            at += take
            if (buffered == STRIPE) {
                for (lane in 0 until LANES) v[lane] = round(v[lane], longLe(stripe, lane * Long.SIZE_BYTES))
                buffered = 0
            }
        }
        return this
    }

    fun digest(): Long {
        var acc: Long
        if (total >= STRIPE) {
            acc = java.lang.Long.rotateLeft(v[0], 1) + java.lang.Long.rotateLeft(v[1], 7) +
                java.lang.Long.rotateLeft(v[2], 12) + java.lang.Long.rotateLeft(v[3], 18)
            for (lane in v) acc = merge(acc, lane)
        } else {
            acc = seed + P5
        }
        acc += total
        var at = 0
        while (at + Long.SIZE_BYTES <= buffered) {
            acc = acc xor round(0, longLe(stripe, at))
            acc = java.lang.Long.rotateLeft(acc, 27) * P1 + P4
            at += Long.SIZE_BYTES
        }
        if (at + Int.SIZE_BYTES <= buffered) {
            acc = acc xor ((XxHash32.intLe(stripe, at).toLong() and UINT) * P1)
            acc = java.lang.Long.rotateLeft(acc, 23) * P2 + P3
            at += Int.SIZE_BYTES
        }
        while (at < buffered) {
            acc = acc xor ((stripe[at].toLong() and BYTE) * P5)
            acc = java.lang.Long.rotateLeft(acc, 11) * P1
            at++
        }
        acc = acc xor (acc ushr 33)
        acc *= P2
        acc = acc xor (acc ushr 29)
        acc *= P3
        return acc xor (acc ushr 32)
    }

    private fun round(acc: Long, lane: Long): Long = java.lang.Long.rotateLeft(acc + lane * P2, 31) * P1

    private fun merge(acc: Long, lane: Long): Long = (acc xor round(0, lane)) * P1 + P4

    private companion object {
        const val P1 = -0x61c8864e7a143579L
        const val P2 = -0x3d4d51c2d82b14b1L
        const val P3 = 0x165667B19E3779F9L
        const val P4 = -0x7a1435883d4d519dL
        const val P5 = 0x27D4EB2F165667C5L
        const val STRIPE = 32
        const val LANES = 4
        const val BYTE = 0xFFL
        const val UINT = 0xFFFF_FFFFL

        fun longLe(data: ByteArray, at: Int): Long =
            (XxHash32.intLe(data, at).toLong() and UINT) or (XxHash32.intLe(data, at + Int.SIZE_BYTES).toLong() shl 32)
    }
}
