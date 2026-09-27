package io.github.ncorror.nekoflash.artifact

import io.github.ncorror.nekoflash.core.artifact.ArtifactAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Выводы о документе SAF.
 *
 * Проверяется то, что нельзя увидеть на устройстве без провайдера нужного
 * сорта: что труба не выдаётся за файл и что молчание провайдера не
 * превращается в обещание.
 */
class SafArtifactFactsTest {
    /** У файла длина дескриптора есть — он seekable. */
    @Test
    fun aDescriptorWithALengthIsAFile() {
        assertEquals(ArtifactAccess.SEEKABLE, SafArtifactFacts.access(facts(statSize = 100L)))
    }

    /**
     * У трубы длины нет, и трубой она и остаётся.
     *
     * Выдать её за файл значило бы отдать в Sideload источник, который на
     * повторный запрос блока вернёт следующий кусок.
     */
    @Test
    fun aDescriptorWithoutALengthIsAPipeAndStaysOne() {
        assertEquals(ArtifactAccess.NON_SEEKABLE, SafArtifactFacts.access(facts(statSize = -1L)))
    }

    /** Дескриптор не открылся — это `UNKNOWN`, а не догадка в удобную сторону. */
    @Test
    fun aDescriptorThatDidNotOpenSaysNothingEitherWay() {
        assertEquals(ArtifactAccess.UNKNOWN, SafArtifactFacts.access(facts(statSize = null)))
    }

    /** Колонка провайдера важнее длины дескриптора: он знает про документ больше. */
    @Test
    fun theProviderColumnOutranksTheDescriptor() {
        assertEquals(10L, SafArtifactFacts.size(facts(columnSize = 10L, statSize = 999L)))
    }

    /** Нет ни колонки, ни длины — размера нет, и это `null`, а не ноль. */
    @Test
    fun withoutAColumnOrALengthThereIsNoSizeAtAll() {
        assertNull(SafArtifactFacts.size(facts(statSize = -1L)))
    }

    /** Безымянный документ получает запасное имя, а не пустую строку. */
    @Test
    fun anUnnamedDocumentGetsAFallbackRatherThanNothing() {
        assertEquals("artifact.bin", SafArtifactFacts.name(facts(displayName = "  "), "artifact.bin"))
    }

    private fun facts(
        displayName: String? = "payload.zip",
        columnSize: Long? = null,
        statSize: Long? = null,
        lastModifiedMillis: Long? = null,
    ) = SafDocumentFacts(displayName, columnSize, statSize, lastModifiedMillis)
}
