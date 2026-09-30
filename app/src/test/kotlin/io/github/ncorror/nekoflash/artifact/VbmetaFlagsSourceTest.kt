package io.github.ncorror.nekoflash.artifact

import io.github.ncorror.nekoflash.core.artifact.ArtifactAccess
import io.github.ncorror.nekoflash.core.artifact.ArtifactDescriptor
import io.github.ncorror.nekoflash.core.artifact.ArtifactDescriptorSource
import io.github.ncorror.nekoflash.core.artifact.ArtifactIdentity
import io.github.ncorror.nekoflash.core.artifact.ArtifactRandomAccess
import io.github.ncorror.nekoflash.core.artifact.ArtifactSource
import io.github.ncorror.nekoflash.core.artifact.ArtifactStamp
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

/** Флаги vbmeta выставляются так же, как у настольного `fastboot`. */
class VbmetaFlagsSourceTest {
    private fun image(flags: Int = 0): ByteArray = ByteArray(4096) { (it % 7).toByte() }.also { bytes ->
        "AVB0".toByteArray().copyInto(bytes)
        bytes[120] = 0; bytes[121] = 0; bytes[122] = 0; bytes[123] = flags.toByte()
    }

    @Test
    fun bothFlagsAreOredIntoTheBigEndianField() {
        val original = image(flags = 0)
        val patched = VbmetaFlagsSource(
            BytesSource(original),
            VbmetaFlagsSource.DISABLE_VERITY or VbmetaFlagsSource.DISABLE_VERIFICATION,
        ).open().readBytes()

        assertEquals(original.size, patched.size)
        assertEquals(3, patched[123].toInt())
        assertArrayEquals(original.copyOfRange(124, original.size), patched.copyOfRange(124, patched.size))
        assertArrayEquals(original.copyOfRange(0, 120), patched.copyOfRange(0, 120))
    }

    @Test
    fun existingFlagsAreKept() {
        val patched = VbmetaFlagsSource(BytesSource(image(flags = 2)), VbmetaFlagsSource.DISABLE_VERITY).open().readBytes()
        assertEquals(3, patched[123].toInt())
    }

    @Test
    fun aForeignImageIsRefusedBeforeAnyByte() {
        val boot = ByteArray(4096).also { "ANDROID!".toByteArray().copyInto(it) }
        assertThrows(IOException::class.java) { VbmetaFlagsSource(BytesSource(boot), 1).open() }
    }

    /**
     * Дескриптор исходного файла не проходит сквозь обёртку: нативный путь
     * отправил бы файл без флагов (D077). Поэтому он отдельный интерфейс, а не
     * метод [ArtifactSource], который `by inner` унаследовал бы.
     */
    @Test
    fun thePatchedSourceHidesTheOriginalDescriptor() {
        val inner = object : ArtifactSource by BytesSource(image()), ArtifactDescriptorSource {
            override fun openReadDescriptor(): ArtifactDescriptor? = null
        }

        val patched: Any = VbmetaFlagsSource(inner, VbmetaFlagsSource.DISABLE_VERITY)

        assertFalse(patched is ArtifactDescriptorSource)
    }

    private class BytesSource(private val bytes: ByteArray) : ArtifactSource {
        override val identity = ArtifactIdentity("vbmeta.img", bytes.size.toLong(), ArtifactAccess.SEEKABLE)
        override val openedStamp = ArtifactStamp(bytes.size.toLong(), null, null)
        override fun stamp(): ArtifactStamp = openedStamp
        override fun open(): InputStream = ByteArrayInputStream(bytes)
        override fun randomAccess(): ArtifactRandomAccess? = null
    }
}
