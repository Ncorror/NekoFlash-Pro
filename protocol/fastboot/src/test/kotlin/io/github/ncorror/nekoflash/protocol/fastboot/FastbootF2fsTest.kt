package io.github.ncorror.nekoflash.protocol.fastboot

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Образы сверены с `mkfs.f2fs` f2fs-tools 1.16.0, собранным как в AOSP
 * (`-DWITH_ANDROID`, `libsparse`), теми же ключами, что у
 * `generate_f2fs_image`:
 *
 * ```
 * make_f2fs -S РАЗМЕР -g android [-O project_quota,extra_attr | -O casefold -C utf8 |
 *   -O compression -O extra_attr] -r -T 1700000000 -U 11111111-2222-3333-4444-555555555555 FILE
 * ```
 *
 * `-r` — `srand(0)`, после него `rand()` = 1804289383; версия ядра —
 * `uname -r` машины, где собирались эталоны. Эталон — SHA-256 всего
 * sparse-файла: совпадает каждый байт, включая разбиение на куски.
 */
class FastbootF2fsTest {
    private val uuid = ByteArray(16) { "11111111222233334444555555555555".substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun sha(size: Long, projid: Boolean = false, casefold: Boolean = false, compression: Boolean = false): String {
        val image = FastbootF2fs.image(
            FastbootF2fs.Request(size, projid, casefold, compression, uuid, NOW, RAND, KERNEL),
        ).getOrThrow()
        return MessageDigest.getInstance("SHA-256").digest(image).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun theSmallestVolumeMatchesMakeF2fs() =
        assertEquals("66c75dd40d5070f3997be551b3b51b9282b1ad0eb4ac7ddfa491be2c3d7b76bb", sha(67108864))

    @Test
    fun smallVolumesPickTheOverprovisionInFivePercentSteps() =
        assertEquals("8ec0b7d55aeda34c7db2db368b46124ed94102aff1818b8c68b717c30b3aeb65", sha(268435456))

    @Test
    fun anUnalignedSizeMatchesMakeF2fs() =
        assertEquals("c45f999ecd0c5975b12da1318498631cf0a778394c61dddf7cd612cd06a4954e", sha(1000000000))

    @Test
    fun largeVolumesPickTheOverprovisionInHundredthsOfAPercent() {
        assertEquals("2fd337273e65ac98bfcf6f0d20d51a3933e7d7edda08af3ccd5b1219c08e085d", sha(8589934592))
        assertEquals("2260cf212d242aa2bdcc7b61ab5f763c0b59638b9d4740424014de2f062e81f5", sha(68719476736))
        assertEquals("28cf6d2c309e0612b6480eab11358c144601a5970432787426097bd5751d8b56", sha(241591910400))
    }

    /** `-O project_quota,extra_attr` уже входит в `-g android`: образ тот же. */
    @Test
    fun projectIdsAreAlreadyPartOfTheAndroidPreset() =
        assertEquals("8ec0b7d55aeda34c7db2db368b46124ed94102aff1818b8c68b717c30b3aeb65", sha(268435456, projid = true))

    @Test
    fun casefoldAndCompressionMatchMakeF2fs() {
        assertEquals("78cde5732dbcb7802d0a576abf5bb5392240cd81b0eaf265ba796c66a444ad9c", sha(268435456, casefold = true))
        assertEquals("57d784ad16a06cbd17e64b1be58981545ae6f4a4a7a3c844b7fe00db8dd0528e", sha(268435456, compression = true))
    }

    @Test
    fun aVolumeTooSmallIsRefusedInMakeF2fsWords() {
        val failure = FastbootF2fs.image(FastbootF2fs.Request(33554432, false, false, false, uuid, NOW, RAND, KERNEL))
        assertEquals("Device size is not sufficient for F2FS volume", failure.exceptionOrNull()?.message)
    }

    private companion object {
        const val NOW = 1700000000L
        const val RAND = 1804289383L
        const val KERNEL = "6.18.44-fc-v37"
    }
}
