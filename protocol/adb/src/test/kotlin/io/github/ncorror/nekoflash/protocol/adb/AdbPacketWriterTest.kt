package io.github.ncorror.nekoflash.protocol.adb

import io.github.ncorror.nekoflash.usb.api.UsbTransferFailure
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbPacketWriterTest {
    @Test
    fun headerAndPayloadGoOutAsSeparateTransfers() {
        val handle = FakeUsbTransportHandle()
        val payload = "shell,v2:ls".toByteArray()

        val outcome = AdbPacketWriter(handle).write(AdbCommand.OPEN, arg0 = 1, arg1 = 0, payload = payload)

        assertEquals(AdbWriteOutcome.Sent, outcome)
        assertEquals(2, handle.sentBytes.size)
        assertEquals(AdbPacketHeader.SIZE_BYTES, handle.sentBytes[0].size)
        assertArrayEquals(payload, handle.sentBytes[1])
    }

    @Test
    fun encodedHeaderRoundTripsThroughTheDecoder() {
        val handle = FakeUsbTransportHandle()
        val payload = byteArrayOf(1, 2, 3, 4)

        AdbPacketWriter(handle).write(AdbCommand.WRTE, arg0 = 11, arg1 = 22, payload = payload)

        val decoded = AdbPacketHeader.decode(
            handle.sentBytes[0],
            AdbInboundFraming.MODERN_MAX_PAYLOAD_BYTES,
        ) as AdbHeaderDecoding.Decoded
        assertEquals(AdbCommand.WRTE, decoded.header.command)
        assertEquals(11, decoded.header.arg0)
        assertEquals(22, decoded.header.arg1)
        assertEquals(4, decoded.header.payloadLength)
        assertEquals(AdbChecksum.compute(payload), decoded.header.checksum)
    }

    @Test
    fun packetWithoutPayloadSendsOnlyTheHeader() {
        val handle = FakeUsbTransportHandle()

        AdbPacketWriter(handle).write(AdbCommand.CLSE, arg0 = 4, arg1 = 5)

        assertEquals(1, handle.sentBytes.size)
    }

    /** Запись в сторону устройства дробится и дописывается — так в обоих архивах. */
    @Test
    fun payloadLongerThanOneChunkIsSentInFullAcrossTransfers() {
        val handle = FakeUsbTransportHandle()
        val payload = ByteArray(40_000) { (it % 97).toByte() }

        val outcome = AdbPacketWriter(handle).write(AdbCommand.WRTE, 1, 1, payload)

        assertEquals(AdbWriteOutcome.Sent, outcome)
        val sentPayload = handle.sentBytes.drop(1).reduce { acc, part -> acc + part }
        assertArrayEquals(payload, sentPayload)
        assertTrue(
            "куски записи не должны превышать проверенный размер",
            handle.sentBytes.drop(1).all { it.size <= AdbPacketWriter.USB_BULK_CHUNK_BYTES },
        )
    }

    @Test
    fun partialTransferIsContinuedRatherThanRestarted() {
        val payload = ByteArray(100) { 7 }
        val handle = FakeUsbTransportHandle(
            outbound = mutableListOf(
                FakeUsbTransportHandle.Transfer.Completed(AdbPacketHeader.SIZE_BYTES),
                FakeUsbTransportHandle.Transfer.Completed(40),
                FakeUsbTransportHandle.Transfer.Completed(60),
            ),
        )

        val outcome = AdbPacketWriter(handle).write(AdbCommand.WRTE, 1, 1, payload)

        assertEquals(AdbWriteOutcome.Sent, outcome)
        assertEquals(listOf(24, 40, 60), handle.sentBytes.map { it.size })
    }

    /** По числу ушедших байт видно, увидел ли peer заголовок. */
    @Test
    fun interruptedWriteReportsHowMuchOfTheFrameLeft() {
        val handle = FakeUsbTransportHandle(
            outbound = mutableListOf(
                FakeUsbTransportHandle.Transfer.Completed(AdbPacketHeader.SIZE_BYTES),
                FakeUsbTransportHandle.Transfer.Failed(UsbTransferFailure.NOT_COMPLETED),
            ),
        )

        val outcome = AdbPacketWriter(handle).write(AdbCommand.WRTE, 1, 1, ByteArray(64))

        assertEquals(AdbPacketHeader.SIZE_BYTES, (outcome as AdbWriteOutcome.Interrupted).sentBytes)
    }

    @Test
    fun releasedInterfaceIsReportedAsClosed() {
        val handle = FakeUsbTransportHandle(
            outbound = mutableListOf(FakeUsbTransportHandle.Transfer.Failed(UsbTransferFailure.NOT_HELD)),
        )

        assertEquals(AdbWriteOutcome.Closed, AdbPacketWriter(handle).write(AdbCommand.CNXN, 0, 0))
    }

    @Test
    fun skipChecksumSessionWritesZeroInsteadOfASum() {
        val handle = FakeUsbTransportHandle()
        val writer = AdbPacketWriter(handle, localVersion = AdbChecksum.VERSION_SKIP_CHECKSUM)
        writer.negotiate(AdbChecksum.VERSION_SKIP_CHECKSUM)

        writer.write(AdbCommand.WRTE, 1, 1, byteArrayOf(9, 9, 9))

        val decoded = AdbPacketHeader.decode(
            handle.sentBytes[0],
            AdbInboundFraming.MODERN_MAX_PAYLOAD_BYTES,
        ) as AdbHeaderDecoding.Decoded
        assertEquals(0, decoded.header.checksum)
    }
}

class AdbInboundFramingTest {
    @Test
    fun hostsBelowAndroidPAdvertiseWhatTheyCanActuallyReceive() {
        assertEquals(AdbInboundFraming.PRE_P_MAX_PAYLOAD_BYTES, AdbInboundFraming.advertisedMaxPayload(26))
        assertEquals(AdbInboundFraming.PRE_P_MAX_PAYLOAD_BYTES, AdbInboundFraming.advertisedMaxPayload(27))
    }

    @Test
    fun modernHostsAdvertiseTheFullPayload() {
        assertEquals(AdbInboundFraming.MODERN_MAX_PAYLOAD_BYTES, AdbInboundFraming.advertisedMaxPayload(28))
        assertEquals(AdbInboundFraming.MODERN_MAX_PAYLOAD_BYTES, AdbInboundFraming.advertisedMaxPayload(36))
    }

    @Test
    fun onlyAnExactTransferCompletesADeclaredPayload() {
        assertTrue(AdbInboundFraming.payloadReadIsComplete(1_024, 1_024))
        assertTrue(AdbInboundFraming.payloadReadIsComplete(0, 0))
    }

    @Test
    fun shortOrOversizedTransferDoesNotCompleteADeclaredPayload() {
        assertFalse(AdbInboundFraming.payloadReadIsComplete(50_000, 32_768))
        assertFalse(AdbInboundFraming.payloadReadIsComplete(1_024, 1_025))
        assertFalse(AdbInboundFraming.payloadReadIsComplete(-1, -1))
    }
}

class AdbChecksumTest {
    @Test
    fun checksumSumsBytesAsUnsignedValues() {
        assertEquals(511, AdbChecksum.compute(byteArrayOf(0x00, 0x01, 0x7F, 0x80.toByte(), 0xFF.toByte())))
    }

    @Test
    fun checksumIsRequiredWhenEitherSideStillUsesTheClassicVersion() {
        assertTrue(AdbChecksum.isRequired(AdbChecksum.VERSION_WITH_CHECKSUM, AdbChecksum.VERSION_SKIP_CHECKSUM))
        assertTrue(AdbChecksum.isRequired(AdbChecksum.VERSION_SKIP_CHECKSUM, AdbChecksum.VERSION_WITH_CHECKSUM))
    }

    @Test
    fun checksumIsSkippedOnlyWhenBothSidesSupportIt() {
        assertFalse(AdbChecksum.isRequired(AdbChecksum.VERSION_SKIP_CHECKSUM, AdbChecksum.VERSION_SKIP_CHECKSUM))
    }

    @Test
    fun classicSessionRejectsAMismatchedChecksum() {
        val payload = byteArrayOf(1, 2, 3)
        assertFalse(
            AdbChecksum.matches(5, payload, AdbChecksum.VERSION_WITH_CHECKSUM, AdbChecksum.VERSION_WITH_CHECKSUM),
        )
        assertTrue(
            AdbChecksum.matches(6, payload, AdbChecksum.VERSION_WITH_CHECKSUM, AdbChecksum.VERSION_WITH_CHECKSUM),
        )
    }

    @Test
    fun skipChecksumSessionAcceptsAnyDeclaredValue() {
        assertTrue(
            AdbChecksum.matches(
                0,
                byteArrayOf(1, 2, 3),
                AdbChecksum.VERSION_SKIP_CHECKSUM,
                AdbChecksum.VERSION_SKIP_CHECKSUM,
            ),
        )
    }
}

class AdbPacketHeaderTest {
    @Test
    fun decodingNeedsTwentyFourBytes() {
        assertThrowsIllegalArgument {
            AdbPacketHeader.decode(ByteArray(23), AdbInboundFraming.MODERN_MAX_PAYLOAD_BYTES)
        }
    }

    @Test
    fun commandIsReadAsAnUnsignedThirtyTwoBitValue() {
        val bytes = header(UNSIGNED_HIGH_BIT_COMMAND)

        val decoded = AdbPacketHeader.decode(
            bytes,
            AdbInboundFraming.MODERN_MAX_PAYLOAD_BYTES,
        ) as AdbHeaderDecoding.Decoded

        assertEquals(UNSIGNED_HIGH_BIT_COMMAND, decoded.header.command)
        assertTrue(decoded.header.command > 0)
    }

    @Test
    fun zeroLengthIsInsideTheAcceptedRange() {
        val decoded = AdbPacketHeader.decode(
            header(AdbCommand.OKAY),
            AdbInboundFraming.MODERN_MAX_PAYLOAD_BYTES,
        )

        assertTrue(decoded is AdbHeaderDecoding.Decoded)
    }

    private companion object {
        /** Команда со старшим установленным битом: в `Int` она стала бы отрицательной. */
        const val UNSIGNED_HIGH_BIT_COMMAND = 0xF000_0001L

        fun assertThrowsIllegalArgument(block: () -> Unit) {
            try {
                block()
            } catch (expected: IllegalArgumentException) {
                return
            }
            throw AssertionError("expected IllegalArgumentException")
        }
    }
}
