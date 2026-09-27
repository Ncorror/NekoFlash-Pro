package io.github.ncorror.nekoflash.protocol.fastboot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Различение загрузчика и `fastbootd`.
 *
 * Признак взят из Legacy (`FastbootProtocol.logDiagnostics`), а не из общего
 * знания протокола. Главное, что здесь проверяется, — что «неизвестно»
 * остаётся отдельным состоянием и ни при каких условиях не превращается в
 * «загрузчик».
 */
class FastbootModeProbeTest {
    @Test
    fun yesMeansFastbootd() {
        val identity = FastbootModeProbe.of(FastbootVariable.Present("is-userspace", "yes"))

        assertEquals(FastbootMode.FASTBOOTD, identity.mode)
        assertTrue(identity.resolved)
    }

    @Test
    fun noMeansBootloader() {
        assertEquals(
            FastbootMode.BOOTLOADER,
            FastbootModeProbe.of(FastbootVariable.Present("is-userspace", "no")).mode,
        )
    }

    /** Регистр и пробелы устройства нас не касаются. */
    @Test
    fun theAnswerIsReadRegardlessOfCaseAndPadding() {
        assertEquals(
            FastbootMode.FASTBOOTD,
            FastbootModeProbe.of(FastbootVariable.Present("is-userspace", "  YES ")).mode,
        )
    }

    /**
     * Отказ устройства — это «неизвестно», а не «загрузчик».
     *
     * Старые загрузчики переменной не знают. Прочитать их `FAIL` как «значит,
     * не userspace» соблазнительно и почти всегда верно — но «почти» здесь
     * недостаточно: вывод из отсутствия ответа наблюдением не является.
     */
    @Test
    fun aRefusalIsUnknownRatherThanBootloader() {
        val identity = FastbootModeProbe.of(FastbootVariable.Unsupported("is-userspace", "unknown variable"))

        assertEquals(FastbootMode.UNKNOWN, identity.mode)
        assertFalse(identity.resolved)
        assertTrue("причина должна быть названа", identity.detail.contains("unknown variable"))
    }

    /** Незаданный вопрос — тем более «неизвестно», и причина другая. */
    @Test
    fun anUnaskedQuestionIsUnknownWithItsOwnReason() {
        val identity = FastbootModeProbe.of(FastbootVariable.Unavailable("is-userspace", "полоса занята: STALLED"))

        assertEquals(FastbootMode.UNKNOWN, identity.mode)
        assertTrue(identity.detail.contains("STALLED"))
    }

    /** Третий ответ сохраняется целиком: по нему видно, что наблюдение неполно. */
    @Test
    fun anAnswerThatIsNeitherYesNorNoKeepsItsText() {
        val identity = FastbootModeProbe.of(FastbootVariable.Present("is-userspace", "maybe"))

        assertEquals(FastbootMode.UNKNOWN, identity.mode)
        assertTrue(identity.detail.contains("maybe"))
    }

    @Test
    fun anEmptyAnswerIsUnknownAndSaysSo() {
        val identity = FastbootModeProbe.of(FastbootVariable.Present("is-userspace", ""))

        assertEquals(FastbootMode.UNKNOWN, identity.mode)
        assertTrue(identity.detail.contains("пусто"))
    }

    /** Полный путь через полосу: команда уходит той, какой её знает устройство. */
    @Test
    fun theProbeAsksTheDeviceForIsUserspace() {
        val transport = FakeFastbootTransport().willReply("OKAYyes")
        val identity = FastbootModeProbe.probe(FastbootGetVar(FastbootLane(transport)))

        assertEquals(listOf("getvar:is-userspace"), transport.sent)
        assertEquals(FastbootMode.FASTBOOTD, identity.mode)
    }

    /** Молчащее устройство не классифицируется, и полоса при этом теряет рамку. */
    @Test
    fun aSilentDeviceIsUnknownAndTheLaneStalls() {
        val lane = FastbootLane(FakeFastbootTransport().willBeSilent(100))

        val identity = FastbootModeProbe.of(FastbootGetVar(lane).read(FastbootModeProbe.VARIABLE, 100))

        assertEquals(FastbootMode.UNKNOWN, identity.mode)
        assertEquals(FastbootLaneState.STALLED, lane.state)
    }
}
