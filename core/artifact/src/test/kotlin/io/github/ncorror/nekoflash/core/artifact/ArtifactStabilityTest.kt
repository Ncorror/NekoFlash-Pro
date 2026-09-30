package io.github.ncorror.nekoflash.core.artifact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тот же ли источник, что был выбран.
 *
 * Проверяется главное: **«сравнить нечем» не превращается в «не изменился»**.
 * Совпадение размера доказательством неизменности не является, и мутирующая
 * передача обязана узнать об этом до первого байта (`06` §6).
 */
class ArtifactStabilityTest {
    @Test
    fun aDifferentSizeIsAChange() {
        val outcome = ArtifactStamps.compare(stamp(sizeBytes = 10), stamp(sizeBytes = 11))

        assertTrue((outcome as ArtifactStability.Changed).detail.contains("размер"))
    }

    @Test
    fun aDifferentModificationTimeIsAChangeEvenAtTheSameSize() {
        val outcome = ArtifactStamps.compare(
            stamp(sizeBytes = 10, modifiedAtMillis = 1),
            stamp(sizeBytes = 10, modifiedAtMillis = 2),
        )

        assertTrue((outcome as ArtifactStability.Changed).detail.contains("время"))
    }

    @Test
    fun aDifferentVersionIsAChange() {
        val outcome = ArtifactStamps.compare(
            stamp(sizeBytes = 10, version = "1"),
            stamp(sizeBytes = 10, version = "2"),
        )

        assertTrue((outcome as ArtifactStability.Changed).detail.contains("версия"))
    }

    /**
     * Провайдер, не сказавший о себе ничего, не получает кредита доверия.
     *
     * Это и есть весь смысл файла: одинаковый размер — не доказательство, и
     * выдать его за доказательство значило бы отдать в необратимую передачу
     * файл, про который никто ничего не знает.
     */
    @Test
    fun withoutTimeOrVersionTheAnswerIsUnverifiableRatherThanUnchanged() {
        val outcome = ArtifactStamps.compare(stamp(sizeBytes = 10), stamp(sizeBytes = 10))

        val unverifiable = outcome as ArtifactStability.Unverifiable
        assertTrue(unverifiable.detail.contains("доказательством неизменности не является"))
    }

    /** Есть чем подтвердить — подтверждается, и механизм работе не мешает. */
    @Test
    fun aMatchingTimeIsEnoughToCallItUnchanged() {
        val outcome = ArtifactStamps.compare(
            stamp(sizeBytes = 10, modifiedAtMillis = 7),
            stamp(sizeBytes = 10, modifiedAtMillis = 7),
        )

        assertEquals(ArtifactStability.Unchanged, outcome)
    }

    /** Появившаяся версия — тоже изменение: раньше её не было, теперь есть. */
    @Test
    fun aVersionAppearingOutOfNowhereIsAChange() {
        val outcome = ArtifactStamps.compare(
            stamp(sizeBytes = 10, modifiedAtMillis = 7),
            stamp(sizeBytes = 10, modifiedAtMillis = 7, version = "2"),
        )

        assertTrue(outcome is ArtifactStability.Changed)
    }

    private fun stamp(sizeBytes: Long, modifiedAtMillis: Long? = null, version: String? = null) =
        ArtifactStamp(sizeBytes, modifiedAtMillis, version)
}
