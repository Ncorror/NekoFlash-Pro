package io.github.ncorror.nekoflash.protocol.adb

import io.github.ncorror.nekoflash.core.diagnostics.InMemoryDiagnosticSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Потоки, заведённые устройством, и их получатель.
 *
 * Так приходит соединение при обратном пробросе, и проверяется здесь не
 * «данные дошли», а порядок: решение принимается **до** подтверждения, потому
 * что подтвердить и передумать значило бы пообещать и не сдержать.
 */
class AdbInboundStreamsTest {
    @Test
    fun withoutAReceiverTheStreamIsRefused() {
        val sink = InMemoryDiagnosticSink()
        val dispatcher = AdbStreamDispatcher(diagnostics = sink)

        val outbound = dispatcher.dispatch(inboundOpen())

        assertEquals(AdbCommand.CLSE, outbound.single().command)
        assertEquals("inbound_stream_refused", sink.snapshot().single().message)
    }

    @Test
    fun aReceiverThatDoesNotWantItGetsARefusalToo() {
        val receiver = RecordingReceiver(wants = false)
        val dispatcher = AdbStreamDispatcher()
        dispatcher.inboundStreams(receiver)

        val outbound = dispatcher.dispatch(inboundOpen())

        assertEquals(AdbCommand.CLSE, outbound.single().command)
        assertEquals(listOf(SERVICE), receiver.asked)
        assertTrue("отказ не должен отдавать ящик", receiver.taken.isEmpty())
    }

    @Test
    fun anAcceptedStreamIsConfirmedAndHandedOver() {
        val receiver = RecordingReceiver(wants = true)
        val sink = InMemoryDiagnosticSink()
        val dispatcher = AdbStreamDispatcher(diagnostics = sink)
        dispatcher.inboundStreams(receiver)

        val outbound = dispatcher.dispatch(inboundOpen())

        val reply = outbound.single()
        assertEquals(AdbCommand.OKAY, reply.command)
        assertEquals(REMOTE_ID, reply.arg1)
        assertEquals(1, receiver.taken.size)
        assertEquals(reply.arg0, receiver.taken.single().localId)
        assertEquals("inbound_stream_accepted", sink.snapshot().single().message)
    }

    /** Спросили раньше, чем подтвердили: иначе обещание нечем было бы взять назад. */
    @Test
    fun theReceiverIsAskedBeforeAnythingIsConfirmed() {
        val order = mutableListOf<String>()
        val dispatcher = AdbStreamDispatcher()
        dispatcher.inboundStreams(
            object : AdbInboundStreams {
                override fun wants(service: String): Boolean {
                    order += "asked"
                    return true
                }

                override fun accepted(service: String, mailbox: AdbStreamMailbox) {
                    order += "handed"
                }
            },
        )

        dispatcher.dispatch(inboundOpen())

        assertEquals(listOf("asked", "handed"), order)
    }

    /** Принятый поток живой: данные устройства попадают в его ящик. */
    @Test
    fun anAcceptedStreamReceivesWhatTheDeviceSends() {
        val receiver = RecordingReceiver(wants = true)
        val dispatcher = AdbStreamDispatcher()
        dispatcher.inboundStreams(receiver)
        val localId = dispatcher.dispatch(inboundOpen()).single().arg0

        dispatcher.dispatch(AdbPacket(AdbCommand.WRTE, REMOTE_ID, localId, "hello".toByteArray()))

        val item = receiver.taken.single().poll(0) as AdbMailboxItem.Data
        assertEquals("hello", item.payload.decodeToString())
    }

    /** Снятый получатель возвращает поведение по умолчанию — отказ. */
    @Test
    fun clearingTheReceiverBringsRefusalBack() {
        val dispatcher = AdbStreamDispatcher()
        dispatcher.inboundStreams(RecordingReceiver(wants = true))
        dispatcher.inboundStreams(null)

        assertEquals(AdbCommand.CLSE, dispatcher.dispatch(inboundOpen()).single().command)
    }

    /** Принятый поток закрывается, как всякий другой: устройство закрыло — ящик кончился. */
    @Test
    fun anAcceptedStreamEndsWhenTheDeviceClosesIt() {
        val receiver = RecordingReceiver(wants = true)
        val dispatcher = AdbStreamDispatcher()
        dispatcher.inboundStreams(receiver)
        val localId = dispatcher.dispatch(inboundOpen()).single().arg0

        dispatcher.dispatch(AdbPacket(AdbCommand.CLSE, REMOTE_ID, localId, ByteArray(0)))

        val ended = receiver.taken.single().poll(0) as AdbMailboxItem.Ended
        assertEquals(AdbMailboxEnd.COMPLETED, ended.reason)
    }

    /** Поток, заведённый устройством, не путается с нашими: идентификаторы разные. */
    @Test
    fun anAcceptedStreamDoesNotCollideWithOurOwn() {
        val receiver = RecordingReceiver(wants = true)
        val dispatcher = AdbStreamDispatcher()
        dispatcher.inboundStreams(receiver)
        val (ours, _) = dispatcher.open("shell:id")

        val theirs = dispatcher.dispatch(inboundOpen()).single().arg0

        assertTrue("идентификаторы не должны совпадать", ours.localId != theirs)
        assertNull("чужой поток не должен трогать наш ящик", ours.poll(0))
    }

    private class RecordingReceiver(private val wants: Boolean) : AdbInboundStreams {
        val asked: MutableList<String> = mutableListOf()
        val taken: MutableList<AdbStreamMailbox> = mutableListOf()

        override fun wants(service: String): Boolean {
            asked += service
            return wants
        }

        override fun accepted(service: String, mailbox: AdbStreamMailbox) {
            taken += mailbox
        }
    }

    private companion object {
        const val SERVICE = "tcp:8888"
        const val REMOTE_ID = 91

        fun inboundOpen() =
            AdbPacket(AdbCommand.OPEN, REMOTE_ID, 0, "$SERVICE\u0000".toByteArray())
    }
}
