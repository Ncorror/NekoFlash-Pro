package io.github.ncorror.nekoflash.artifact

import io.github.ncorror.nekoflash.core.artifact.ArtifactRandomAccess
import io.github.ncorror.nekoflash.core.artifact.ArtifactSource
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream

/**
 * Образ vbmeta с флагами `--disable-verity` / `--disable-verification`.
 *
 * Настольный `fastboot` не шлёт устройству отдельной команды: он выставляет
 * биты в поле `flags` заголовка AVB (big-endian `uint32` по смещению 120) и
 * прошивает изменённый образ. Здесь то же самое — потоком, без копии файла:
 * заголовок читается и правится до первого байта передачи, остальное идёт как
 * есть. Не образ AVB (нет `AVB0` в начале) — отказ **до** отправки: менять
 * байты в чужом формате значило бы испортить его молча.
 */
internal class VbmetaFlagsSource(private val inner: ArtifactSource, private val flags: Int) : ArtifactSource by inner {
    override fun open(): InputStream {
        val stream = inner.open()
        val header = ByteArray(HEADER_BYTES)
        var read = 0
        while (read < HEADER_BYTES) {
            val chunk = stream.read(header, read, HEADER_BYTES - read)
            if (chunk < 0) break
            read += chunk
        }
        if (read < HEADER_BYTES || !header.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            stream.close()
            throw IOException("not an AVB vbmeta image: no AVB0 header, flags were not applied and nothing was sent")
        }
        for (index in 0 until FLAG_BYTES) {
            val shift = (FLAG_BYTES - 1 - index) * Byte.SIZE_BITS
            val bits = flags ushr shift and BYTE
            header[FLAGS_OFFSET + index] = (header[FLAGS_OFFSET + index].toInt() or bits).toByte()
        }
        return SequenceInputStream(ByteArrayInputStream(header), stream)
    }

    /** Произвольное чтение отдало бы исходные байты, а не правленые. */
    override fun randomAccess(): ArtifactRandomAccess? = null

    companion object {
        /** `AVB_VBMETA_IMAGE_FLAGS_HASHTREE_DISABLED`. */
        const val DISABLE_VERITY: Int = 1

        /** `AVB_VBMETA_IMAGE_FLAGS_VERIFICATION_DISABLED`. */
        const val DISABLE_VERIFICATION: Int = 2

        private val MAGIC = "AVB0".toByteArray(Charsets.US_ASCII)
        private const val FLAGS_OFFSET = 120
        private const val FLAG_BYTES = 4
        private const val HEADER_BYTES = FLAGS_OFFSET + FLAG_BYTES
        private const val BYTE = 0xFF
    }
}
