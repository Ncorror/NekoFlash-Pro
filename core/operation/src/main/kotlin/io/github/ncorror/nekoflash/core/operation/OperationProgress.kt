package io.github.ncorror.nekoflash.core.operation

/**
 * Сколько сделано и как быстро.
 *
 * Две величины байт, а не одна, и это перенесено из Sideload, где разница
 * измерена: устройство вправе попросить один и тот же кусок не раз, и
 * кумулятивный трафик тогда растёт, а сделанного не прибавляется. Мерить
 * прогресс первым значит показывать больше ста процентов на исправной передаче
 * (`03` §6).
 *
 * Все счётчики 64-битные: 32-битная арифметика над байтами запрещена прямо
 * (`06` §9), и образ раздела в `Int` не помещается.
 */
data class OperationProgress(
    /** Кумулятивно переданное, **включая повторы**. Диагностика, не прогресс. */
    val servedBytes: Long,
    /** Уникально подтверждённое. Только это годится для процента. */
    val uniqueBytes: Long,
    /** Сколько всего ожидается. `null` — объём заранее неизвестен. */
    val totalBytes: Long? = null,
    /** Сколько времени операция идёт. Скорость меряется, а не назначается. */
    val elapsedMillis: Long = 0L,
) {
    init {
        require(servedBytes >= 0L) { "отданное не может быть отрицательным: $servedBytes" }
        require(uniqueBytes >= 0L) { "покрытое не может быть отрицательным: $uniqueBytes" }
        require(uniqueBytes <= servedBytes) { "покрытое не бывает больше отданного" }
        require(totalBytes == null || totalBytes >= 0L) { "объём не может быть отрицательным" }
        require(elapsedMillis >= 0L) { "время не может идти назад: $elapsedMillis" }
    }

    /**
     * Доля сделанного, 0..100. `null` — объём неизвестен, и процента нет.
     *
     * `null` здесь честнее нуля: ноль означал бы «ничего не сделано», а
     * неизвестен **объём**, а не сделанное.
     */
    val percent: Int?
        get() = totalBytes?.takeIf { it > 0L }?.let { total ->
            ((uniqueBytes.coerceAtMost(total) * PERCENT) / total).toInt()
        }

    /**
     * Измеренная скорость по проводу, байт в секунду.
     *
     * Считается по **отданному**, а не по покрытому: вопрос «сколько байт в
     * секунду идёт через провод» и вопрос «сколько из них полезны» — разные, и
     * ответ на второй завысил бы оставшееся время при повторах.
     *
     * `null`, пока прошло меньше [MIN_MEASURED_MILLIS]: скорость, посчитанная
     * по первым миллисекундам, это не измерение, а случайное число.
     */
    val bytesPerSecond: Long?
        get() = if (elapsedMillis < MIN_MEASURED_MILLIS) {
            null
        } else {
            servedBytes * MILLIS_PER_SECOND / elapsedMillis
        }

    companion object {
        /** Ничего не начато. */
        fun none(totalBytes: Long? = null): OperationProgress =
            OperationProgress(servedBytes = 0L, uniqueBytes = 0L, totalBytes = totalBytes)

        /**
         * Короче этого скорость не объявляется.
         *
         * Первые миллисекунды передачи говорят о планировщике, а не о проводе.
         */
        const val MIN_MEASURED_MILLIS: Long = 500L

        private const val PERCENT = 100L
        private const val MILLIS_PER_SECOND = 1_000L
    }
}
