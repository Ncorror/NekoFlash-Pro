package io.github.ncorror.nekoflash.protocol.adb

import io.github.ncorror.nekoflash.usb.api.UsbTransferFailure
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbPacketReaderTest {
    @Test
    fun completePacketIsReceived() {
        val payload = "host::features=shell_v2".toByteArray()
        val handle = handleWith(
            completed(header(AdbCommand.CNXN, arg0 = 0x0100_0000, arg1 = 256 * 1024, payload = payload)),
            completed(payload),
        )

        val outcome = AdbPacketReader(handle, MAX_PAYLOAD).read()

        val packet = (outcome as AdbReadOutcome.Received).packet
        assertEquals(AdbCommand.CNXN, packet.command)
        assertEquals(0x0100_0000, packet.arg0)
        assertEquals(256 * 1024, packet.arg1)
        assertArrayEquals(payload, packet.payload)
    }

    @Test
    fun packetWithoutPayloadNeedsNoSecondReceive() {
        val handle = handleWith(completed(header(AdbCommand.OKAY, arg0 = 7, arg1 = 3)))

        val outcome = AdbPacketReader(handle, MAX_PAYLOAD).read()

        assertEquals(0, (outcome as AdbReadOutcome.Received).packet.payload.size)
        assertEquals(listOf(AdbPacketHeader.SIZE_BYTES), handle.receiveWindows)
    }

    /**
     * Тот самый доказанный на `vayu` отказ: устройство завершило передачу
     * короче объявленного. Дочитывать остаток нельзя, кадр потерян.
     */
    @Test
    fun shortPayloadTransferIsNotContinued() {
        val payload = ByteArray(50_000) { (it % 251).toByte() }
        val handle = handleWith(
            completed(header(AdbCommand.WRTE, arg0 = 1, arg1 = 1, payload = payload)),
            FakeUsbTransportHandle.Transfer.Completed(bytes = 32_768, source = payload),
        )

        val outcome = AdbPacketReader(handle, MAX_PAYLOAD).read()

        val failure = outcome as AdbReadOutcome.Failed
        assertEquals(AdbReadFailure.SHORT_PAYLOAD, failure.reason)
        assertTrue(failure.detail.contains("expected=50000"))
        assertTrue(failure.detail.contains("actual=32768"))
        assertEquals(
            "после короткой передачи не должно быть второй попытки приёма",
            listOf(AdbPacketHeader.SIZE_BYTES, 50_000),
            handle.receiveWindows,
        )
    }

    @Test
    fun declaredPayloadIsArmedAsOneReceive() {
        val payload = ByteArray(65_000)
        val handle = handleWith(
            completed(header(AdbCommand.WRTE, payload = payload)),
            completed(payload),
        )

        AdbPacketReader(handle, MAX_PAYLOAD).read()

        assertEquals(listOf(AdbPacketHeader.SIZE_BYTES, 65_000), handle.receiveWindows)
    }

    @Test
    fun silenceBeforeAnyByteIsIdleRatherThanFailure() {
        val handle = handleWith(FakeUsbTransportHandle.Transfer.Failed(UsbTransferFailure.NOT_COMPLETED))

        assertEquals(AdbReadOutcome.Idle, AdbPacketReader(handle, MAX_PAYLOAD).read())
    }

    @Test
    fun headerInterruptedAfterSomeBytesIsLostFraming() {
        val complete = header(AdbCommand.OKAY)
        val handle = handleWith(
            FakeUsbTransportHandle.Transfer.Completed(bytes = 8, source = complete),
            FakeUsbTransportHandle.Transfer.Failed(UsbTransferFailure.NOT_COMPLETED),
        )

        val failure = AdbPacketReader(handle, MAX_PAYLOAD).read() as AdbReadOutcome.Failed

        assertEquals(AdbReadFailure.PARTIAL_HEADER, failure.reason)
        assertTrue(failure.detail.contains("received=8/24"))
    }

    @Test
    fun headerSplitAcrossTransfersIsAssembled() {
        val complete = header(AdbCommand.OKAY, arg0 = 5, arg1 = 9)
        val handle = handleWith(
            FakeUsbTransportHandle.Transfer.Completed(bytes = 10, source = complete),
            FakeUsbTransportHandle.Transfer.Completed(bytes = 14, source = complete.copyOfRange(10, 24)),
        )

        val outcome = AdbPacketReader(handle, MAX_PAYLOAD).read()

        assertEquals(AdbCommand.OKAY, (outcome as AdbReadOutcome.Received).packet.command)
    }

    @Test
    fun releasedInterfaceIsReportedAsClosed() {
        val handle = handleWith(FakeUsbTransportHandle.Transfer.Failed(UsbTransferFailure.NOT_HELD))

        assertEquals(AdbReadOutcome.Closed, AdbPacketReader(handle, MAX_PAYLOAD).read())
    }

    @Test
    fun magicMismatchIsRejected() {
        val handle = handleWith(completed(header(AdbCommand.CNXN, magicOverride = 0)))

        val failure = AdbPacketReader(handle, MAX_PAYLOAD).read() as AdbReadOutcome.Failed

        assertEquals(AdbReadFailure.INVALID_HEADER, failure.reason)
        assertTrue(failure.detail.contains(AdbHeaderRejection.MAGIC_MISMATCH.name))
    }

    /**
     * Кадр длиннее объявленного нами `maxdata` не принимается: приняв его,
     * получатель нарушил бы собственное обещание из `CNXN`.
     */
    @Test
    fun payloadLongerThanAdvertisedMaxIsRejected() {
        val handle = handleWith(
            completed(header(AdbCommand.WRTE, declaredLength = AdbInboundFraming.PRE_P_MAX_PAYLOAD_BYTES + 1)),
        )

        val failure = AdbPacketReader(
            handle,
            AdbInboundFraming.PRE_P_MAX_PAYLOAD_BYTES,
        ).read() as AdbReadOutcome.Failed

        assertEquals(AdbReadFailure.INVALID_HEADER, failure.reason)
        assertTrue(failure.detail.contains(AdbHeaderRejection.PAYLOAD_LENGTH_OUT_OF_RANGE.name))
    }

    @Test
    fun negativeDeclaredLengthIsRejected() {
        val handle = handleWith(completed(header(AdbCommand.WRTE, declaredLength = -1)))

        val failure = AdbPacketReader(handle, MAX_PAYLOAD).read() as AdbReadOutcome.Failed

        assertEquals(AdbReadFailure.INVALID_HEADER, failure.reason)
    }

    @Test
    fun payloadThatFailsItsChecksumIsRejected() {
        val payload = byteArrayOf(1, 2, 3)
        val handle = handleWith(
            completed(header(AdbCommand.WRTE, payload = payload, checksum = 999)),
            completed(payload),
        )

        val failure = AdbPacketReader(handle, MAX_PAYLOAD).read() as AdbReadOutcome.Failed

        assertEquals(AdbReadFailure.CHECKSUM_MISMATCH, failure.reason)
    }

    @Test
    fun emptyPayloadChecksumIsAlsoVerified() {
        val handle = handleWith(completed(header(AdbCommand.OKAY, checksum = 42)))

        val failure = AdbPacketReader(handle, MAX_PAYLOAD).read() as AdbReadOutcome.Failed

        assertEquals(AdbReadFailure.CHECKSUM_MISMATCH, failure.reason)
    }

    /** После `CNXN` современного peer'а поле перестаёт быть суммой. */
    @Test
    fun negotiatedSkipVersionStopsCheckingTheChecksum() {
        val payload = byteArrayOf(1, 2, 3)
        val handle = handleWith(
            completed(header(AdbCommand.WRTE, payload = payload, checksum = 0)),
            completed(payload),
        )
        val reader = AdbPacketReader(
            handle,
            MAX_PAYLOAD,
            localVersion = AdbChecksum.VERSION_SKIP_CHECKSUM,
        )
        reader.negotiate(AdbChecksum.VERSION_SKIP_CHECKSUM)

        assertTrue(reader.read() is AdbReadOutcome.Received)
    }

    @Test
    fun peerVersionStartsEqualToLocalSoChecksumIsRequiredBeforeCnxn() {
        val handle = handleWith(completed(header(AdbCommand.CNXN)))

        assertEquals(
            AdbChecksum.VERSION_SKIP_CHECKSUM,
            AdbPacketReader(
                handle,
                MAX_PAYLOAD,
                localVersion = AdbChecksum.VERSION_SKIP_CHECKSUM,
            ).peerVersion,
        )
    }

    @Test
    fun payloadTransferThatMovedNothingIsAFailure() {
        val payload = byteArrayOf(9, 9)
        val handle = handleWith(
            completed(header(AdbCommand.WRTE, payload = payload)),
            FakeUsbTransportHandle.Transfer.Failed(UsbTransferFailure.NOT_COMPLETED),
        )

        val failure = AdbPacketReader(handle, MAX_PAYLOAD).read() as AdbReadOutcome.Failed

        assertEquals(AdbReadFailure.PAYLOAD_TRANSFER_FAILED, failure.reason)
    }

    private companion object {
        const val MAX_PAYLOAD = AdbInboundFraming.MODERN_MAX_PAYLOAD_BYTES

        fun completed(bytes: ByteArray) =
            FakeUsbTransportHandle.Transfer.Completed(bytes.size, bytes)

        fun handleWith(vararg transfers: FakeUsbTransportHandle.Transfer) =
            FakeUsbTransportHandle(inbound = transfers.toMutableList())
    }
}
