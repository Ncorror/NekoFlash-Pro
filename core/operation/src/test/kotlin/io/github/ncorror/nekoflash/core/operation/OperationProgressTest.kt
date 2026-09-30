package io.github.ncorror.nekoflash.core.operation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Прогресс и скорость.
 *
 * Разделение отданного и покрытого перенесено из Sideload, где разница
 * измерена: устройство вправе попросить один кусок не раз, и спутать их значит
 * показывать больше ста процентов на исправной передаче.
 */
class OperationProgressTest {
    /** Процент считается по покрытому, а скорость — по отданному. */
    @Test
    fun coverageDrivesThePercentAndTrafficDrivesTheRate() {
        val progress = OperationProgress(
            servedBytes = 2_000L,
            uniqueBytes = 1_000L,
            totalBytes = 2_000L,
            elapsedMillis = 1_000L,
        )

        assertEquals(50, progress.percent)
        assertEquals(2_000L, progress.bytesPerSecond)
    }

    /**
     * Неизвестный объём даёт `null`, а не ноль.
     *
     * Ноль означал бы «ничего не сделано», а неизвестен объём, а не сделанное.
     */
    @Test
    fun anUnknownTotalGivesNoPercentRatherThanZero() {
        assertNull(OperationProgress(servedBytes = 10L, uniqueBytes = 10L).percent)
    }

    /**
     * Скорость по первым миллисекундам не объявляется.
     *
     * Она говорит о планировщике, а не о проводе, и показанное оператору число
     * было бы случайным.
     */
    @Test
    fun theRateIsNotDeclaredFromTheFirstMilliseconds() {
        val early = OperationProgress(servedBytes = 4_096L, uniqueBytes = 4_096L, elapsedMillis = 10L)

        assertNull(early.bytesPerSecond)
    }

    /** Покрытое больше отданного невозможно, и это ловится сразу. */
    @Test
    fun coverageCannotExceedTraffic() {
        val error = runCatching { OperationProgress(servedBytes = 1L, uniqueBytes = 2L) }.exceptionOrNull()

        assertEquals(IllegalArgumentException::class.java, error?.javaClass)
    }

    /** Счётчики 64-битные: образ раздела в `Int` не помещается. */
    @Test
    fun theCountersHoldMoreThanAnIntCan() {
        val huge = 8L * 1024L * 1024L * 1024L
        val progress = OperationProgress(
            servedBytes = huge,
            uniqueBytes = huge,
            totalBytes = huge,
            elapsedMillis = 1_000L,
        )

        assertEquals(100, progress.percent)
        assertEquals(huge, progress.bytesPerSecond)
    }

    /** Ничего не начато — тоже состояние, и оно выражается прямо. */
    @Test
    fun nothingStartedIsAStateOfItsOwn() {
        val progress = OperationProgress.none(totalBytes = 100L)

        assertEquals(0, progress.percent)
        assertNull(progress.bytesPerSecond)
    }
}
