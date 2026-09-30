package io.github.ncorror.nekoflash.protocol.adb

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbConnectionTest {
    @Test
    fun hostBelowAndroidPAdvertisesTheSmallerPayload() = withKeyStore { keyStore ->
        val connection = AdbConnection(FakeUsbTransportHandle(), keyStore, apiLevel = 26)

        assertEquals(AdbInboundFraming.PRE_P_MAX_PAYLOAD_BYTES, connection.advertisedMaxPayload)
    }

    @Test
    fun modernHostAdvertisesTheFullPayload() = withKeyStore { keyStore ->
        val connection = AdbConnection(FakeUsbTransportHandle(), keyStore, apiLevel = 36)

        assertEquals(AdbInboundFraming.MODERN_MAX_PAYLOAD_BYTES, connection.advertisedMaxPayload)
    }

    /**
     * Объявленное в `CNXN` и проверяемое читателем — одно число. Разъезд между
     * ними и был исходной причиной inbound framing invariant.
     */
    @Test
    fun cnxnAdvertisesExactlyWhatTheReaderWillAccept() = withKeyStore { keyStore ->
        val handle = FakeUsbTransportHandle(
            inbound = mutableListOf(
                FakeUsbTransportHandle.Transfer.Completed(
                    AdbPacketHeader.SIZE_BYTES,
                    header(
                        AdbCommand.CNXN,
                        arg0 = AdbChecksum.VERSION_WITH_CHECKSUM,
                        arg1 = AdbInboundFraming.MODERN_MAX_PAYLOAD_BYTES,
                        payload = BANNER,
                    ),
                ),
                FakeUsbTransportHandle.Transfer.Completed(BANNER.size, BANNER),
            ),
        )
        val connection = AdbConnection(handle, keyStore, apiLevel = 26)

        connection.connect()

        assertEquals(connection.advertisedMaxPayload, handle.sentFrames()[0].arg1)
    }

    @Test
    fun connectionCarriesTheHandshakeOutcomeThrough() = withKeyStore { keyStore ->
        val handle = FakeUsbTransportHandle(
            inbound = mutableListOf(
                FakeUsbTransportHandle.Transfer.Completed(
                    AdbPacketHeader.SIZE_BYTES,
                    header(
                        AdbCommand.CNXN,
                        arg0 = AdbChecksum.VERSION_WITH_CHECKSUM,
                        arg1 = AdbInboundFraming.MODERN_MAX_PAYLOAD_BYTES,
                        payload = BANNER,
                    ),
                ),
                FakeUsbTransportHandle.Transfer.Completed(BANNER.size, BANNER),
            ),
        )

        val outcome = AdbConnection(handle, keyStore, apiLevel = 36).connect()

        assertTrue(outcome is AdbHandshakeOutcome.Connected)
        assertEquals(
            AdbPeerMode.RECOVERY,
            (outcome as AdbHandshakeOutcome.Connected).banner.peerMode,
        )
    }

    /**
     * Маршрутизатор один на соединение: второй начал бы выдавать идентификаторы
     * заново, и устройство получило бы два потока под одним номером.
     */
    @Test
    fun consecutiveCallsUseDistinctStreamIds() = withKeyStore { keyStore ->
        val handle = FakeUsbTransportHandle(
            inbound = mutableListOf(
                *serviceExchange(localId = 1, output = "first"),
                // Ответы второго вызова придерживаются до его запроса: иначе
                // цикл раскладки вычитал бы их, пока второго потока ещё нет.
                FakeUsbTransportHandle.Transfer.Gate(minimumFrames = OPENS_AND_ACKS_BEFORE_SECOND),
                *serviceExchange(localId = 2, output = "second"),
            ),
            answerOnlyAfterRequest = true,
        )
        val connection = AdbConnection(handle, keyStore, apiLevel = 36)

        try {
            val first = connection.call("shell:one") as AdbServiceOutcome.Completed
            val second = connection.call("shell:two") as AdbServiceOutcome.Completed

            assertEquals("first", first.text())
            assertEquals("second", second.text())
            val opens = handle.sentFrames().filter { it.command == AdbCommand.OPEN }
            assertEquals(listOf(1, 2), opens.map { it.arg0 })
        } finally {
            // Цикл раскладки принадлежит соединению: не закрыв его, тест
            // оставил бы за собой крутящийся поток.
            connection.close()
        }
    }

    private companion object {
        /**
         * Сколько кадров уходит до запроса второго вызова.
         *
         * `OPEN` первого, подтверждение его вывода, `CLSE` при закрытии и
         * `OPEN` второго — четвёртый кадр и есть тот запрос, после которого
         * второму вызову можно отвечать.
         */
        const val OPENS_AND_ACKS_BEFORE_SECOND = 4

        val BANNER = "recovery::ro.product.name=vayu\u0000".toByteArray()

        /** Полный обмен одного сервиса: подтверждение, вывод, закрытие. */
        fun serviceExchange(localId: Int, output: String): Array<FakeUsbTransportHandle.Transfer> {
            val payload = output.toByteArray()
            return arrayOf(
                FakeUsbTransportHandle.Transfer.Completed(
                    AdbPacketHeader.SIZE_BYTES,
                    header(AdbCommand.OKAY, arg0 = 100 + localId, arg1 = localId),
                ),
                FakeUsbTransportHandle.Transfer.Completed(
                    AdbPacketHeader.SIZE_BYTES,
                    header(AdbCommand.WRTE, arg0 = 100 + localId, arg1 = localId, payload = payload),
                ),
                FakeUsbTransportHandle.Transfer.Completed(payload.size, payload),
                FakeUsbTransportHandle.Transfer.Completed(
                    AdbPacketHeader.SIZE_BYTES,
                    header(AdbCommand.CLSE, arg0 = 100 + localId, arg1 = localId),
                ),
            )
        }

        fun withKeyStore(block: (AdbKeyStore) -> Unit) {
            val directory: File = Files.createTempDirectory("nekoflash-connection").toFile()
            try {
                block(AdbKeyStore(directory))
            } finally {
                directory.deleteRecursively()
            }
        }
    }
}
