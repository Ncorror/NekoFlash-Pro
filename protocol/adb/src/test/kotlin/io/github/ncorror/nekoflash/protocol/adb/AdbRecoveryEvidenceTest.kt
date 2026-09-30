package io.github.ncorror.nekoflash.protocol.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Вердикт Recovery и его связывание с базой.
 *
 * Проверяется главное, ради чего всё это написано: **исход установки берётся
 * только из слов Recovery**, и только из тех, про которые доказано, что они
 * новые. `DONEDONE` сюда не входит вовсе — он кончает передачу (`03` §6).
 */
class AdbRecoveryEvidenceTest {
    @Test
    fun theDeviceConfirmsSuccessWithStatusZero() {
        val result = AdbRecoveryInstall.evaluate(
            listOf(log("Starting ADB sideload\nInstall from ADB complete (status: 0)\n")),
        )

        assertEquals(AdbRecoveryVerdict.SUCCESS, result.verdict)
        assertTrue(result.evidence!!.contains("status: 0"))
    }

    @Test
    fun aNonZeroStatusIsAFailure() {
        val result = AdbRecoveryInstall.evaluate(
            listOf(log("Starting ADB sideload\nInstall from ADB complete (status: 1)\n")),
        )

        assertEquals(AdbRecoveryVerdict.FAILED, result.verdict)
    }

    /**
     * Побеждает **последнее** событие, а не первое и не «любой успех».
     *
     * Журнал пишется по порядку, и итог — то, что сказано последним. Иначе
     * успешная первая попытка перекрыла бы провалившуюся вторую.
     */
    @Test
    fun theLastEventWinsRatherThanTheFirst() {
        val result = AdbRecoveryInstall.evaluate(
            listOf(
                log(
                    "Starting ADB sideload\n" +
                        "Install completed successfully\n" +
                        "Install from ADB complete (status: 1)\n",
                ),
            ),
        )

        assertEquals("последним сказано про ошибку", AdbRecoveryVerdict.FAILED, result.verdict)
    }

    /**
     * Читается срез от **последнего** начала сессии.
     *
     * Без этого установка недельной давности была бы прочитана как сегодняшняя.
     */
    @Test
    fun onlyTheLatestSessionIsRead() {
        val result = AdbRecoveryInstall.evaluate(
            listOf(
                log(
                    "Starting ADB sideload\n" +
                        "Install from ADB complete (status: 0)\n" +
                        "Starting ADB sideload\n" +
                        "Install from ADB complete (status: 7)\n",
                ),
            ),
        )

        assertEquals(AdbRecoveryVerdict.FAILED, result.verdict)
        assertTrue(result.detail.contains("status=7"))
    }

    /** Журнал текущей сессии важнее исторических. */
    @Test
    fun theCurrentSessionLogOutranksTheHistoricalOnes() {
        val result = AdbRecoveryInstall.evaluate(
            listOf(
                AdbRecoveryLog("/cache/recovery/last_install", "Starting ADB sideload\nInstallation aborted\n"),
                log("Starting ADB sideload\nInstall from ADB complete (status: 0)\n"),
            ),
        )

        assertEquals(AdbRecoveryVerdict.SUCCESS, result.verdict)
        assertEquals(AdbRecoveryInstall.PRIMARY_PATH, result.source)
    }

    /**
     * Признак TWRP принимается только внутри распознанной сессии Sideload.
     *
     * Без этого ручная установка из меню Recovery была бы прочитана как
     * результат нашей передачи.
     */
    @Test
    fun aTwrpMarkerOutsideASideloadSessionIsNotRead() {
        val outside = AdbRecoveryInstall.evaluate(listOf(log("I:operation_end - status=0\n")))
        assertEquals(AdbRecoveryVerdict.UNKNOWN, outside.verdict)

        val inside = AdbRecoveryInstall.evaluate(
            listOf(log("Starting ADB sideload\nI:operation_end - status=0\n")),
        )
        assertEquals(AdbRecoveryVerdict.SUCCESS, inside.verdict)
    }

    /** Нет журнала — нет вердикта, и это говорится прямо. */
    @Test
    fun withoutALogTheVerdictIsUnknown() {
        val result = AdbRecoveryInstall.evaluate(emptyList())

        assertEquals(AdbRecoveryVerdict.UNKNOWN, result.verdict)
        assertTrue(result.detail.contains("недоступен"))
    }

    /** Журнал есть, а результата в нём нет — тоже `UNKNOWN`, а не догадка. */
    @Test
    fun aLogWithoutAResultIsUnknownRatherThanAGuess() {
        val result = AdbRecoveryInstall.evaluate(listOf(log("Starting ADB sideload\nCopying zip\n")))

        assertEquals(AdbRecoveryVerdict.UNKNOWN, result.verdict)
        assertEquals(AdbRecoveryInstall.PRIMARY_PATH, result.source)
    }

    /** База хранит длину и отпечаток начала, но не сам журнал. */
    @Test
    fun theBaselineStoresAFingerprintAndNotTheLog() {
        val baseline = AdbRecoveryCorrelator.capture(log("прежние строки\n"))

        assertEquals(AdbRecoveryInstall.PRIMARY_PATH, baseline.path)
        assertEquals("прежние строки\n".length, baseline.prefixLength)
        assertEquals(64, baseline.prefixSha256.length)
    }

    /** Журнал, продолжающий базу, связывается, и видно, сколько дописано. */
    @Test
    fun aLogThatExtendsTheBaselineIsCorrelated() {
        val before = "Starting ADB sideload\n"
        val baseline = AdbRecoveryCorrelator.capture(log(before))

        val correlation = AdbRecoveryCorrelator.correlate(
            listOf(log(before + "Install from ADB complete (status: 0)\n")),
            baseline,
        )

        val correlated = correlation as AdbRecoveryCorrelation.Correlated
        assertEquals("Install from ADB complete (status: 0)\n".length, correlated.appendedCharacters)
    }

    /**
     * Переписанный журнал связи не даёт, и вердикт остаётся неизвестным.
     *
     * Это «fail closed»: без доказательства, что строки новые, исторический
     * успех был бы прочитан как сегодняшний.
     */
    @Test
    fun aRewrittenLogFailsClosed() {
        val baseline = AdbRecoveryCorrelator.capture(log("совсем другое начало\n"))

        val result = AdbRecoveryCorrelator.verdict(
            listOf(log("Starting ADB sideload\nInstall from ADB complete (status: 0)\n")),
            baseline,
        )

        assertEquals(AdbRecoveryVerdict.UNKNOWN, result.verdict)
        assertTrue(result.detail.contains("не продолжает"))
    }

    /** Обрезанный журнал — тоже отказ, а не повод читать что осталось. */
    @Test
    fun aTruncatedLogIsRefused() {
        val baseline = AdbRecoveryCorrelator.capture(log("длинное прежнее начало журнала\n"))

        val result = AdbRecoveryCorrelator.verdict(listOf(log("коротко\n")), baseline)

        assertEquals(AdbRecoveryVerdict.UNKNOWN, result.verdict)
        assertTrue(result.detail.contains("обрезан"))
    }

    /** Без базы вердикт не объявляется вовсе. */
    @Test
    fun withoutABaselineNoVerdictIsDeclared() {
        val result = AdbRecoveryCorrelator.verdict(
            listOf(log("Starting ADB sideload\nInstall from ADB complete (status: 0)\n")),
            baseline = null,
        )

        assertEquals(AdbRecoveryVerdict.UNKNOWN, result.verdict)
        assertTrue(result.detail.contains("базы"))
    }

    /** Связанный журнал даёт настоящий вердикт: механизм не мешает работе. */
    @Test
    fun aCorrelatedLogYieldsTheRealVerdict() {
        val before = "Starting ADB sideload\n"
        val baseline = AdbRecoveryCorrelator.capture(log(before))

        val result = AdbRecoveryCorrelator.verdict(
            listOf(log(before + "Install from ADB complete (status: 0)\n")),
            baseline,
        )

        assertEquals(AdbRecoveryVerdict.SUCCESS, result.verdict)
    }

    /** Переводы строк на отпечаток не влияют: иначе связь ломалась бы от них. */
    @Test
    fun lineEndingsDoNotBreakTheCorrelation() {
        val baseline = AdbRecoveryCorrelator.capture(log("Starting ADB sideload\r\n"))

        val result = AdbRecoveryCorrelator.verdict(
            listOf(log("Starting ADB sideload\nInstall from ADB complete (status: 0)\n")),
            baseline,
        )

        assertEquals(AdbRecoveryVerdict.SUCCESS, result.verdict)
    }

    private fun log(text: String) = AdbRecoveryLog(AdbRecoveryInstall.PRIMARY_PATH, text)
}
