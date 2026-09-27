package io.github.ncorror.nekoflash.protocol.fastboot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбор кадра Fastboot.
 *
 * Случаи взяты из архивов, а не придуманы: набор типов и обращение с нулевыми
 * байтами сверены с Legacy `FastbootProtocol.kt` и A2 `FastbootTransaction.kt`
 * до написания кода (`16` §3).
 */
class FastbootPacketCodecTest {
    @Test
    fun anOkayWithoutPayloadIsTerminal() {
        val packet = parse("OKAY")

        assertEquals(FastbootReply.OKAY, packet.reply)
        assertEquals("", packet.payload)
        assertTrue("OKAY кончает обмен", packet.terminal)
    }

    @Test
    fun aFailCarriesItsReasonAndIsTerminal() {
        val packet = parse("FAILunknown command")

        assertEquals(FastbootReply.FAIL, packet.reply)
        assertEquals("unknown command", packet.payload)
        assertTrue("FAIL тоже кончает обмен", packet.terminal)
    }

    /** Отказ — это ответ устройства, а не поломка обмена (`03` §2, Device authority). */
    @Test
    fun aFailIsAnAnswerNotABrokenExchange() {
        assertTrue(parse("FAILnot supported").terminal)
    }

    @Test
    fun infoAndTextAreNotTerminal() {
        assertFalse("INFO не кончает обмен", parse("INFOerasing").terminal)
        assertFalse("TEXT не кончает обмен", parse("TEXThello").terminal)
    }

    @Test
    fun theDeclaredSizeIsReadAsHexadecimal() {
        assertEquals(0x1000L, parse("DATA00001000").declaredSize())
    }

    /** Префикс `0x` наблюдался и снимается — так же делает A2. */
    @Test
    fun theDeclaredSizeToleratesAnOxPrefix() {
        assertEquals(0x20L, parse("DATA0x00000020").declaredSize())
    }

    /**
     * Неразбираемый размер даёт `null`, а не ноль.
     *
     * «Не поняли, сколько» и «нисколько» — разные вещи, и подменять первое
     * вторым значило бы придумать устройству ответ, которого оно не давало.
     */
    @Test
    fun anUnreadableSizeIsNullRatherThanZero() {
        assertNull(parse("DATAzzzz").declaredSize())
    }

    @Test
    fun onlyDataDeclaresASize() {
        assertNull("у OKAY размера нет", parse("OKAY00001000").declaredSize())
    }

    /** Устройства дополняют ответ нулевыми байтами до длины пакета. */
    @Test
    fun trailingNulBytesAreStripped() {
        val bytes = "OKAY".toByteArray(Charsets.US_ASCII) + ByteArray(12)

        assertEquals(FastbootReply.OKAY, FastbootPacketCodec.parse(bytes).reply)
    }

    /** Читается ровно объявленная часть буфера, а не весь буфер. */
    @Test
    fun onlyTheDeclaredPrefixOfTheBufferIsRead() {
        val bytes = "OKAYхвост".toByteArray(Charsets.US_ASCII)

        assertEquals("", FastbootPacketCodec.parse(bytes, length = 4).payload)
    }

    /**
     * Слишком короткий кадр не опознаётся, но и не теряется.
     *
     * Legacy на неопознанном кадре пишет предупреждение и продолжает читать, а
     * не рвёт обмен. Текст сохраняется целиком — по нему видно, что пришло.
     */
    @Test
    fun aFrameShorterThanItsTypeIsKeptWhole() {
        val packet = parse("OK")

        assertEquals(FastbootReply.UNKNOWN, packet.reply)
        assertEquals("OK", packet.payload)
        assertEquals("OK", packet.raw)
        assertFalse("непонятое не считается концом обмена", packet.terminal)
    }

    @Test
    fun anUnrecognisedTypeKeepsItsRawText() {
        val packet = parse("WHATever")

        assertEquals(FastbootReply.UNKNOWN, packet.reply)
        assertEquals("WHATever", packet.raw)
    }

    @Test
    fun anEmptyFrameIsUnknownRatherThanAnError() {
        assertEquals(FastbootReply.UNKNOWN, parse("").reply)
    }

    @Test(expected = IllegalArgumentException::class)
    fun alengthBeyondTheBufferIsAProgrammerError() {
        FastbootPacketCodec.parse(ByteArray(2), length = 5)
    }

    private fun parse(text: String): FastbootPacket =
        FastbootPacketCodec.parse(text.toByteArray(Charsets.US_ASCII))
}
