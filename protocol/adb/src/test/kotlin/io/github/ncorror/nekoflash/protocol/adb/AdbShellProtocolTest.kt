package io.github.ncorror.nekoflash.protocol.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbShellProtocolTest {
    @Test
    fun frameIsIdThenLittleEndianLength() {
        val frame = AdbShellProtocol.encode(AdbShellProtocol.ID_STDIN, byteArrayOf(1, 2, 3))

        assertEquals(AdbShellProtocol.ID_STDIN, frame[0].toInt())
        assertArrayEquals(byteArrayOf(3, 0, 0, 0), frame.copyOfRange(1, 5))
        assertArrayEquals(byteArrayOf(1, 2, 3), frame.copyOfRange(5, 8))
    }

    /** Без закрытия ввода часть команд ждёт его конца вместо завершения. */
    @Test
    fun closeStdinFrameCarriesNoPayload() {
        val frame = AdbShellProtocol.closeStdinFrame()

        assertEquals(AdbShellProtocol.HEADER_SIZE_BYTES, frame.size)
        assertEquals(AdbShellProtocol.ID_CLOSE_STDIN, frame[0].toInt())
    }

    @Test
    fun stdoutAndStderrAreSeparated() {
        val stream = AdbShellProtocol.encode(AdbShellProtocol.ID_STDOUT, "out".toByteArray()) +
            AdbShellProtocol.encode(AdbShellProtocol.ID_STDERR, "err".toByteArray()) +
            AdbShellProtocol.encode(AdbShellProtocol.ID_EXIT, byteArrayOf(0))

        val output = AdbShellProtocol.decode(stream)

        assertEquals("out", output.stdout)
        assertEquals("err", output.stderr)
        assertEquals(0, output.exitCode)
        assertFalse(output.truncated)
    }

    @Test
    fun exitCodeIsTheFirstPayloadByteAsUnsigned() {
        val stream = AdbShellProtocol.encode(AdbShellProtocol.ID_EXIT, byteArrayOf(127.toByte()))

        assertEquals(127, AdbShellProtocol.decode(stream).exitCode)
    }

    @Test
    fun exitPacketWithoutPayloadLeavesTheCodeUnknown() {
        val stream = AdbShellProtocol.encode(AdbShellProtocol.ID_EXIT, ByteArray(0))

        assertNull(AdbShellProtocol.decode(stream).exitCode)
    }

    @Test
    fun outputSplitAcrossManyFramesIsJoined() {
        val stream = AdbShellProtocol.encode(AdbShellProtocol.ID_STDOUT, "one ".toByteArray()) +
            AdbShellProtocol.encode(AdbShellProtocol.ID_STDOUT, "two".toByteArray())

        assertEquals("one two", AdbShellProtocol.decode(stream).stdout)
    }

    /** Протокол расширяемый: новое поле не повод потерять уже полученный вывод. */
    @Test
    fun unknownFrameIsSkippedWithItsPayload() {
        val stream = AdbShellProtocol.encode(99, "ignored".toByteArray()) +
            AdbShellProtocol.encode(AdbShellProtocol.ID_STDOUT, "kept".toByteArray())

        val output = AdbShellProtocol.decode(stream)

        assertEquals("kept", output.stdout)
        assertFalse(output.truncated)
    }

    @Test
    fun frameCutInHalfIsReportedAsTruncated() {
        val full = AdbShellProtocol.encode(AdbShellProtocol.ID_STDOUT, "abcdef".toByteArray())

        val output = AdbShellProtocol.decode(full.copyOfRange(0, full.size - 2))

        assertTrue(output.truncated)
    }

    @Test
    fun impossibleLengthStopsTheParseInsteadOfAllocating() {
        val stream = byteArrayOf(
            AdbShellProtocol.ID_STDOUT.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F,
        )

        assertTrue(AdbShellProtocol.decode(stream).truncated)
    }

    @Test
    fun nulBytesAreStrippedFromText() {
        val stream = AdbShellProtocol.encode(
            AdbShellProtocol.ID_STDOUT,
            byteArrayOf('v'.code.toByte(), 0, 'a'.code.toByte()),
        )

        assertEquals("va", AdbShellProtocol.decode(stream).stdout)
    }

    @Test
    fun emptyStreamGivesEmptyOutput() {
        val output = AdbShellProtocol.decode(ByteArray(0))

        assertEquals("", output.stdout)
        assertNull(output.exitCode)
        assertFalse(output.truncated)
    }
}
