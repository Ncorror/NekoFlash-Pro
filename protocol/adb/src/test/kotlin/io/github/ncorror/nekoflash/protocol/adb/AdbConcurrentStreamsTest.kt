package io.github.ncorror.nekoflash.protocol.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test

/**
 * Несколько логических потоков одновременно.
 *
 * Отдельно от `AdbStreamRouterTest`, где каждый случай проверяет одно правило:
 * здесь проверяется, что правила не мешают друг другу, когда потоков больше
 * одного. Именно этого не покрывали ни тесты, ни один прогон на устройстве —
 * до сих пор поток всегда был один.
 */
class AdbConcurrentStreamsTest {
    private val harnesses = AdbDispatchHarnesses()

    @After
    fun stopDispatchLoops() {
        harnesses.stopAll()
    }

    @Test
    fun outputOfTwoStreamsIsNotMixed() {
        val session = TwoStreams()

        val first = session.deliver(session.shellId, "from shell")
        val second = session.deliver(session.syncId, "from sync")

        assertEquals(session.shellId, (first.single() as AdbStreamEvent.Data).localId)
        assertArrayEquals("from shell".toByteArray(), (first.single() as AdbStreamEvent.Data).payload)
        assertEquals(session.syncId, (second.single() as AdbStreamEvent.Data).localId)
    }

    /** Подтверждение должно уходить тому потоку, чьи данные пришли. */
    @Test
    fun acknowledgementCarriesTheIdentifiersOfItsOwnStream() {
        val session = TwoStreams()

        val step = session.router.onPacket(
            packet(AdbCommand.WRTE, arg0 = session.syncRemote, arg1 = session.syncId, payload = "x".toByteArray()),
        )

        val ack = step.outbound.single()
        assertEquals(AdbCommand.OKAY, ack.command)
        assertEquals(session.syncId, ack.arg0)
        assertEquals(session.syncRemote, ack.arg1)
    }

    /** Закрытие одного потока не должно задевать соседний. */
    @Test
    fun closingOneStreamLeavesTheOtherUsable() {
        val session = TwoStreams()

        session.router.onPacket(
            packet(AdbCommand.CLSE, arg0 = session.shellRemote, arg1 = session.shellId),
        )

        assertEquals(setOf(session.syncId), session.router.activeStreamIds)
        assertNotNull(session.router.writeRequest(session.syncId, "still alive".toByteArray()))
        assertNull(session.router.writeRequest(session.shellId, "gone".toByteArray()))
    }

    /**
     * Пакет закрытого потока приходит с опозданием и не должен попасть в
     * живой: идентификаторы для того и не переиспользуются.
     */
    @Test
    fun latePacketOfAClosedStreamDoesNotReachTheLivingOne() {
        val session = TwoStreams()
        session.router.onPacket(packet(AdbCommand.CLSE, arg0 = session.shellRemote, arg1 = session.shellId))

        val step = session.router.onPacket(
            packet(AdbCommand.WRTE, arg0 = session.shellRemote, arg1 = session.shellId, payload = "late".toByteArray()),
        )

        assertTrue(step.events.none { it is AdbStreamEvent.Data })
        assertEquals(AdbCommand.CLSE, step.outbound.single().command)
        assertEquals(setOf(session.syncId), session.router.activeStreamIds)
    }

    /** Третий поток открывается поверх двух живых и получает свой номер. */
    @Test
    fun aThirdStreamOpensAlongsideTheLivingOnes() {
        val session = TwoStreams()

        val (thirdId, open) = session.router.openRequest("shell:third")

        assertEquals(AdbCommand.OPEN, open.command)
        assertEquals(setOf(session.shellId, session.syncId, thirdId), session.router.activeStreamIds)
        assertTrue(thirdId != session.shellId && thirdId != session.syncId)
    }

    /** Порядок разбора не должен зависеть от чередования пакетов. */
    @Test
    fun interleavedTrafficKeepsEachStreamInOrder() {
        val session = TwoStreams()
        val shell = mutableListOf<String>()
        val sync = mutableListOf<String>()

        listOf(
            session.shellId to "a",
            session.syncId to "1",
            session.shellId to "b",
            session.syncId to "2",
            session.shellId to "c",
        ).forEach { (id, text) ->
            val remote = if (id == session.shellId) session.shellRemote else session.syncRemote
            val step = session.router.onPacket(
                packet(AdbCommand.WRTE, arg0 = remote, arg1 = id, payload = text.toByteArray()),
            )
            val data = step.events.single() as AdbStreamEvent.Data
            if (data.localId == session.shellId) {
                shell += data.payload.decodeToString()
            } else {
                sync += data.payload.decodeToString()
            }
        }

        assertEquals(listOf("a", "b", "c"), shell)
        assertEquals(listOf("1", "2"), sync)
    }

    /** Обрыв транспорта закрывает все потоки, а не только последний. */
    @Test
    fun losingTheTransportClosesEveryStream() {
        val session = TwoStreams()

        val step = session.router.abandonAll()

        assertEquals(
            setOf(session.shellId, session.syncId),
            step.events.filterIsInstance<AdbStreamEvent.Closed>().map { it.localId }.toSet(),
        )
        assertTrue(session.router.activeStreamIds.isEmpty())
    }

    /**
     * Живая интерактивная сессия не должна замечать чужой поток.
     *
     * Это ближайшее к настоящей одновременности, что можно проверить без
     * устройства: пакеты соседа приходят в тот же читатель.
     */
    @Test
    fun interactiveShellIgnoresAnotherStreamsTraffic() {
        val foreign = AdbShellProtocol.encode(AdbShellProtocol.ID_STDOUT, "not mine".toByteArray())
        val mine = AdbShellProtocol.encode(AdbShellProtocol.ID_STDOUT, "mine".toByteArray())
        val inbound = mutableListOf<FakeUsbTransportHandle.Transfer>()
        inbound += transfers(AdbCommand.OKAY, arg0 = 42, arg1 = 1)
        inbound += transfers(AdbCommand.WRTE, arg0 = 99, arg1 = 77, payload = foreign)
        inbound += transfers(AdbCommand.WRTE, arg0 = 42, arg1 = 1, payload = mine)
        val handle = FakeUsbTransportHandle(inbound = inbound, answerOnlyAfterRequest = true)
        val harness = harnesses.start(handle)
        val shell = AdbInteractiveShell(
            writer = harness.writer,
            dispatcher = harness.dispatcher,
            useShellV2 = true,
        )
        shell.open()

        // Утверждение о **накопленном**, а не о шаге: приём крутит цикл
        // раскладки, и сколько событий успело лечь в ящик к моменту шага —
        // вопрос планировщика. Существенно здесь то, что вывода чужого потока
        // в этой последовательности нет вовсе.
        val events = mutableListOf<AdbShellEvent>()
        val deadline = System.nanoTime() + COLLECT_TIMEOUT_MS * NANOS_PER_MILLI
        while (events.size < EXPECTED_EVENTS && System.nanoTime() < deadline && shell.active) {
            events += shell.pump(STEP_MS)
        }

        assertEquals(
            listOf(AdbShellEvent.Opened, AdbShellEvent.Output("mine")),
            events,
        )
        assertTrue(shell.active)
    }

    /** Два потока, открытые и подтверждённые. */
    private class TwoStreams {
        val router = AdbStreamRouter()
        val shellId: Int
        val syncId: Int
        val shellRemote = 42
        val syncRemote = 43

        init {
            shellId = router.openRequest("shell:one").first
            syncId = router.openRequest("sync:").first
            router.onPacket(packet(AdbCommand.OKAY, arg0 = shellRemote, arg1 = shellId))
            router.onPacket(packet(AdbCommand.OKAY, arg0 = syncRemote, arg1 = syncId))
        }

        fun deliver(localId: Int, text: String): List<AdbStreamEvent> {
            val remote = if (localId == shellId) shellRemote else syncRemote
            return router.onPacket(
                packet(AdbCommand.WRTE, arg0 = remote, arg1 = localId, payload = text.toByteArray()),
            ).events
        }
    }

    private companion object {
        /** Подтверждение открытия и собственный вывод — больше ничему взяться неоткуда. */
        const val EXPECTED_EVENTS = 2

        const val COLLECT_TIMEOUT_MS = 5_000L
        const val STEP_MS = 25
        const val NANOS_PER_MILLI = 1_000_000L

        fun packet(command: Long, arg0: Int, arg1: Int, payload: ByteArray = ByteArray(0)) =
            AdbPacket(command, arg0, arg1, payload)

        /** Тот же пакет, но как передачи для подставного интерфейса. */
        fun transfers(
            command: Long,
            arg0: Int,
            arg1: Int,
            payload: ByteArray = ByteArray(0),
        ): List<FakeUsbTransportHandle.Transfer> {
            val frames = mutableListOf<FakeUsbTransportHandle.Transfer>(
                FakeUsbTransportHandle.Transfer.Completed(
                    AdbPacketHeader.SIZE_BYTES,
                    header(command, arg0, arg1, payload),
                ),
            )
            if (payload.isNotEmpty()) {
                frames += FakeUsbTransportHandle.Transfer.Completed(payload.size, payload)
            }
            return frames
        }
    }
}
