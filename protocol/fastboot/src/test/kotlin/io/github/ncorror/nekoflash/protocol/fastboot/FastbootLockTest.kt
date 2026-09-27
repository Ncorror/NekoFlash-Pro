package io.github.ncorror.nekoflash.protocol.fastboot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Замок как форма предупреждения, а не как разрешение.
 *
 * Проверяется не «запретили ли», — запрещать нечего, — а что `UNKNOWN` не
 * превращается в `LOCKED` ни одним из путей. `03` §5.1 требует этого прямо:
 * иначе отменённый guard возвращается через чёрный ход.
 */
class FastbootLockTest {
    @Test
    fun theDeviceSaysItIsLocked() {
        val status = FastbootLockProbe.of(FastbootVariable.Present("unlocked", "no"))

        assertEquals(FastbootLockState.LOCKED, status.state)
        assertTrue("подтверждённый замок требует слова", status.typedConfirmation)
    }

    @Test
    fun theDeviceSaysItIsUnlocked() {
        val status = FastbootLockProbe.of(FastbootVariable.Present("unlocked", "yes"))

        assertEquals(FastbootLockState.UNLOCKED, status.state)
        assertFalse(status.typedConfirmation)
    }

    /** Написания взяты из Legacy: `true`/`false` и `1`/`0` встречаются наравне с `yes`/`no`. */
    @Test
    fun theOtherSpellingsFromTheArchiveAreUnderstood() {
        assertEquals(FastbootLockState.UNLOCKED, FastbootLockProbe.of(present("TRUE")).state)
        assertEquals(FastbootLockState.UNLOCKED, FastbootLockProbe.of(present("1")).state)
        assertEquals(FastbootLockState.LOCKED, FastbootLockProbe.of(present("False")).state)
        assertEquals(FastbootLockState.LOCKED, FastbootLockProbe.of(present(" 0 ")).state)
    }

    /**
     * Отказ устройства — не замок.
     *
     * Старые загрузчики переменной не знают. Прочитать это как «заперто»
     * значило бы вернуть запрет через догадку, и `03` §5.1 запрещает это
     * прямо: для `UNKNOWN` действует обычный advisory **без** typed
     * confirmation.
     */
    @Test
    fun aRefusalIsNotALock() {
        val status = FastbootLockProbe.of(FastbootVariable.Unsupported("unlocked", "not found"))

        assertEquals(FastbootLockState.UNKNOWN, status.state)
        assertFalse("слова подтверждения не требуем", status.typedConfirmation)
    }

    /** Молчание — тоже не замок, и причина названа. */
    @Test
    fun silenceIsNotALockEither() {
        val status = FastbootLockProbe.of(FastbootVariable.Unavailable("unlocked", "полоса занята: STALLED"))

        assertEquals(FastbootLockState.UNKNOWN, status.state)
        assertTrue(status.detail.contains("STALLED"))
    }

    /** Ответ, который не читается ни как «да», ни как «нет», сохраняется целиком. */
    @Test
    fun anAnswerThatIsNeitherYesNorNoIsKeptVerbatim() {
        val status = FastbootLockProbe.of(present("maybe"))

        assertEquals(FastbootLockState.UNKNOWN, status.state)
        assertTrue(status.detail.contains("maybe"))
        assertFalse(status.typedConfirmation)
    }

    /** Пустой ответ виден как пустой, а не как отсутствие ответа. */
    @Test
    fun anEmptyAnswerIsShownAsEmpty() {
        val status = FastbootLockProbe.of(present(""))

        assertEquals(FastbootLockState.UNKNOWN, status.state)
        assertTrue(status.detail.contains("<пусто>"))
    }

    @Test
    fun theVariableIsReadFromTheDevice() {
        val transport = FakeFastbootTransport().willReply("OKAYno")
        val lane = FastbootLane(transport)

        val status = FastbootLockProbe.probe(FastbootGetVar(lane))

        assertEquals(FastbootLockState.LOCKED, status.state)
        assertEquals(listOf("getvar:unlocked"), transport.sent)
    }

    private fun present(value: String) = FastbootVariable.Present("unlocked", value)
}
