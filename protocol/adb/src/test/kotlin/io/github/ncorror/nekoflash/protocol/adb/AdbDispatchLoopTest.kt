package io.github.ncorror.nekoflash.protocol.adb

import io.github.ncorror.nekoflash.usb.api.UsbTransferFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Конец транспорта доходит до владельца соединения.
 *
 * Прогон этапа 2: после потери кадра экран показывал «Подключено», потому что
 * о конце знали только ящики потоков, а не тот, кто показывает соединение.
 */
class AdbDispatchLoopTest {
    @Test
    fun framingLossIsReportedToTheOwner() {
        val payload = ByteArray(1_000)
        val handle = FakeUsbTransportHandle(
            inbound = mutableListOf(
                FakeUsbTransportHandle.Transfer.Completed(
                    AdbPacketHeader.SIZE_BYTES,
                    header(AdbCommand.WRTE, arg0 = 1, arg1 = 1, payload = payload),
                ),
                FakeUsbTransportHandle.Transfer.Failed(UsbTransferFailure.NOT_COMPLETED),
            ),
        )
        val ends = mutableListOf<Pair<AdbMailboxEnd, String>>()
        val loop = AdbDispatchLoop(
            reader = AdbPacketReader(handle, AdbInboundFraming.MODERN_MAX_PAYLOAD_BYTES),
            writer = AdbPacketWriter(handle),
            dispatcher = AdbStreamDispatcher(),
        ) { reason, detail -> ends += reason to detail }

        loop.run()

        assertEquals(AdbMailboxEnd.FRAMING_LOST, ends.single().first)
        assertTrue(ends.single().second.startsWith("PAYLOAD_TRANSFER_FAILED expected=1000"))
    }

    @Test
    fun aRequestedStopIsNotReportedAsAnEnd() {
        val handle = FakeUsbTransportHandle()
        val ends = mutableListOf<AdbMailboxEnd>()
        val loop = AdbDispatchLoop(
            reader = AdbPacketReader(handle, AdbInboundFraming.MODERN_MAX_PAYLOAD_BYTES),
            writer = AdbPacketWriter(handle),
            dispatcher = AdbStreamDispatcher(),
        ) { reason, _ -> ends += reason }

        loop.stop()
        loop.run()

        assertTrue(ends.isEmpty())
    }
}
