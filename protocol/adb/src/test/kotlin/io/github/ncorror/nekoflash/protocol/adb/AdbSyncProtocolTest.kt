package io.github.ncorror.nekoflash.protocol.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbSyncProtocolTest {
    @Test
    fun headerIsFourAsciiCharactersAndLittleEndianValue() {
        val header = AdbSyncProtocol.header(AdbSyncProtocol.ID_DONE, 258)

        assertEquals(AdbSyncProtocol.HEADER_SIZE_BYTES, header.size)
        assertEquals("DONE", String(header, 0, 4, Charsets.US_ASCII))
        assertArrayEquals(byteArrayOf(2, 1, 0, 0), header.copyOfRange(4, 8))
    }

    @Test
    fun messageValueIsThePayloadLength() {
        val message = AdbSyncProtocol.message(AdbSyncProtocol.ID_DATA, ByteArray(300))

        assertEquals(AdbSyncProtocol.HEADER_SIZE_BYTES + 300, message.size)
        assertEquals(300, AdbSyncProtocol.decodeHeader(message).value)
    }

    /** Имена файлов бывают любые, поэтому путь идёт в UTF-8. */
    @Test
    fun pathIsEncodedAsUtf8() {
        val request = AdbSyncProtocol.request(AdbSyncProtocol.ID_STAT, "/sdcard/файл.txt")

        val expected = "/sdcard/файл.txt".toByteArray(Charsets.UTF_8)
        assertEquals(expected.size, AdbSyncProtocol.decodeHeader(request).value)
        assertArrayEquals(expected, request.copyOfRange(8, request.size))
    }

    @Test
    fun headerRoundTrips() {
        val header = AdbSyncProtocol.decodeHeader(AdbSyncProtocol.header(AdbSyncProtocol.ID_FAIL, 42))

        assertEquals(AdbSyncProtocol.ID_FAIL, header.id)
        assertEquals(42, header.value)
    }

    @Test
    fun identifierOfTheWrongLengthIsRejected() {
        var rejected = 0
        for (id in listOf("ST", "STATS")) {
            try {
                AdbSyncProtocol.header(id, 0)
            } catch (_: IllegalArgumentException) {
                rejected++
            }
        }
        assertEquals(2, rejected)
    }

    @Test
    fun truncatedHeaderIsRejected() {
        var rejected = false
        try {
            AdbSyncProtocol.decodeHeader(ByteArray(7))
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    /** Нулевой режим означает, что пути нет: `adbd` отвечает так вместо отказа. */
    @Test
    fun zeroModeMeansTheatPathDoesNotExist() {
        val stat = AdbSyncStat(mode = 0, size = 0, modifiedAtSeconds = 0)

        assertFalse(stat.exists)
        assertFalse(stat.directory)
        assertFalse(stat.regularFile)
    }

    @Test
    fun directoryAndFileAreDistinguishedByTheTypeBits() {
        val directory = AdbSyncStat(mode = AdbSyncProtocol.MODE_DIRECTORY or 0x1ED, size = 0, modifiedAtSeconds = 0)
        val file = AdbSyncStat(mode = AdbSyncProtocol.MODE_REGULAR_FILE or 0x1A4, size = 10, modifiedAtSeconds = 0)

        assertTrue(directory.directory)
        assertFalse(directory.regularFile)
        assertTrue(file.regularFile)
        assertFalse(file.directory)
    }
}

class AdbStreamBufferTest {
    @Test
    fun exactCountIsTaken() {
        val buffer = AdbStreamBuffer()
        buffer.append(byteArrayOf(1, 2, 3, 4, 5))

        assertArrayEquals(byteArrayOf(1, 2, 3), buffer.take(3))
        assertEquals(2, buffer.available)
    }

    /** Частичная выдача разрушила бы разбор следующей порции. */
    @Test
    fun nothingIsTakenWhenThereIsNotEnough() {
        val buffer = AdbStreamBuffer()
        buffer.append(byteArrayOf(1, 2))

        assertNull(buffer.take(3))
        assertEquals(2, buffer.available)
        buffer.append(byteArrayOf(3))
        assertArrayEquals(byteArrayOf(1, 2, 3), buffer.take(3))
    }

    /** Заголовок может прийти двумя пакетами, а один пакет — принести полтора. */
    @Test
    fun portionsAreIndependentOfArrivalBoundaries() {
        val buffer = AdbStreamBuffer()
        buffer.append(AdbSyncProtocol.header(AdbSyncProtocol.ID_DATA, 4).copyOfRange(0, 5))
        assertNull(buffer.take(8))

        buffer.append(AdbSyncProtocol.header(AdbSyncProtocol.ID_DATA, 4).copyOfRange(5, 8) + byteArrayOf(9, 9))

        val header = AdbSyncProtocol.decodeHeader(buffer.take(8)!!)
        assertEquals(AdbSyncProtocol.ID_DATA, header.id)
        assertEquals(4, header.value)
        assertEquals(2, buffer.available)
    }

    @Test
    fun takingZeroGivesAnEmptyArray() {
        assertArrayEquals(ByteArray(0), AdbStreamBuffer().take(0))
    }

    @Test
    fun negativeCountIsRejected() {
        var rejected = false
        try {
            AdbStreamBuffer().take(-1)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }
}
