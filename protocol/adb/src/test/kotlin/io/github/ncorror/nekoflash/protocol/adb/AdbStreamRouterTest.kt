package io.github.ncorror.nekoflash.protocol.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbStreamRouterTest {
    @Test
    fun openRequestCarriesTheServiceNameAsACString() {
        val (localId, packet) = AdbStreamRouter().openRequest("shell:getprop ro.product.device")

        assertEquals(AdbCommand.OPEN, packet.command)
        assertEquals(localId, packet.arg0)
        assertEquals(0, packet.arg1)
        assertEquals(0, packet.payload.last().toInt())
        assertEquals("shell:getprop ro.product.device", packet.payload.dropLast(1).toByteArray().decodeToString())
    }

    @Test
    fun localIdsStartAtOneBecauseZeroMeansNoStream() {
        assertEquals(1, AdbStreamRouter().openRequest("shell:id").first)
    }

    /** Переиспользование идентификатора выдало бы чужие данные за свои. */
    @Test
    fun localIdsAreNotReusedAfterAStreamClosed() {
        val router = AdbStreamRouter()
        val (first, _) = router.openRequest("shell:id")
        router.onPacket(packet(AdbCommand.CLSE, arg0 = 7, arg1 = first))

        val (second, _) = router.openRequest("shell:id")

        assertNotEquals(first, second)
    }

    @Test
    fun okayOpensTheStreamAndNamesTheRemoteId() {
        val router = AdbStreamRouter()
        val (localId, _) = router.openRequest("shell:id")

        val step = router.onPacket(packet(AdbCommand.OKAY, arg0 = 42, arg1 = localId))

        assertEquals(listOf(AdbStreamEvent.Opened(localId, 42)), step.events)
        assertTrue(step.outbound.isEmpty())
    }

    /** Каждый WRTE подтверждается немедленно: это backpressure ADB. */
    @Test
    fun everyWriteIsAcknowledgedAtOnce() {
        val router = openedStream()
        val payload = "hello".toByteArray()

        val step = router.router.onPacket(
            packet(AdbCommand.WRTE, arg0 = REMOTE_ID, arg1 = router.localId, payload = payload),
        )

        assertEquals(1, step.outbound.size)
        assertEquals(AdbCommand.OKAY, step.outbound[0].command)
        assertEquals(router.localId, step.outbound[0].arg0)
        assertEquals(REMOTE_ID, step.outbound[0].arg1)
        assertArrayEquals(payload, (step.events[0] as AdbStreamEvent.Data).payload)
    }

    /**
     * `WRTE` до `OKAY` подтверждается, но данными потока не считается: поток
     * ещё не открыт, и отдавать это вызывающему нельзя.
     */
    @Test
    fun writeBeforeOkayIsAcknowledgedButNotDelivered() {
        val router = AdbStreamRouter()
        val (localId, _) = router.openRequest("shell:id")

        val step = router.onPacket(
            packet(AdbCommand.WRTE, arg0 = REMOTE_ID, arg1 = localId, payload = "early".toByteArray()),
        )

        assertEquals(AdbCommand.OKAY, step.outbound.single().command)
        assertTrue(step.events.none { it is AdbStreamEvent.Data })
        assertTrue(step.events.single() is AdbStreamEvent.Stale)
    }

    /**
     * Пакет неизвестного потока нельзя молча пропустить: устройство останется
     * ждать подтверждения от потока, которого нет.
     */
    @Test
    fun writeForAnUnknownStreamIsAnsweredWithClose() {
        val step = AdbStreamRouter().onPacket(packet(AdbCommand.WRTE, arg0 = 9, arg1 = 77))

        val reply = step.outbound.single()
        assertEquals(AdbCommand.CLSE, reply.command)
        assertEquals(77, reply.arg0)
        assertEquals(9, reply.arg1)
    }

    @Test
    fun closeForAnUnknownStreamIsAnsweredWithClose() {
        val step = AdbStreamRouter().onPacket(packet(AdbCommand.CLSE, arg0 = 9, arg1 = 77))

        assertEquals(AdbCommand.CLSE, step.outbound.single().command)
    }

    /** На чужой OKAY отвечать нечем, и отвечать не надо. */
    @Test
    fun okayForAnUnknownStreamIsIgnoredWithoutAReply() {
        val step = AdbStreamRouter().onPacket(packet(AdbCommand.OKAY, arg0 = 9, arg1 = 77))

        assertTrue(step.outbound.isEmpty())
        assertTrue(step.events.single() is AdbStreamEvent.Stale)
    }

    @Test
    fun closeAfterOkayCompletesTheStreamAndIsAcknowledged() {
        val opened = openedStream()

        val step = opened.router.onPacket(packet(AdbCommand.CLSE, arg0 = REMOTE_ID, arg1 = opened.localId))

        assertEquals(
            listOf(AdbStreamEvent.Closed(opened.localId, AdbStreamClosure.COMPLETED)),
            step.events,
        )
        assertEquals(AdbCommand.CLSE, step.outbound.single().command)
    }

    /**
     * Поток, заведённый устройством, узнаётся, но сам собой не заводится.
     *
     * Так приходит соединение при обратном пробросе. Подтверждать его сразу
     * нельзя: на основании `OKAY` устройство вправе слать данные, а принимать
     * их, может быть, некому.
     */
    @Test
    fun anOpenFromTheDeviceIsRecognisedButNotAnswered() {
        val step = AdbStreamRouter().onPacket(
            packet(AdbCommand.OPEN, arg0 = REMOTE_ID, arg1 = 0, payload = "tcp:8080\u0000".toByteArray()),
        )

        assertTrue("маршрутизатор не должен отвечать сам", step.outbound.isEmpty())
        assertEquals(AdbStreamEvent.Inbound(REMOTE_ID, "tcp:8080"), step.events.single())
    }

    /** Имя сервиса передаётся как пришло, снимается только завершающий нуль. */
    @Test
    fun theInboundServiceNameIsPassedThroughUnchanged() {
        val step = AdbStreamRouter().onPacket(
            packet(AdbCommand.OPEN, arg0 = REMOTE_ID, payload = "что угодно\u0000".toByteArray()),
        )

        assertEquals("что угодно", (step.events.single() as AdbStreamEvent.Inbound).service)
    }

    /** Без своего идентификатора поток не адресуем: отвечать было бы нечем. */
    @Test
    fun anOpenWithoutARemoteIdIsUnexpected() {
        val step = AdbStreamRouter().onPacket(packet(AdbCommand.OPEN, arg0 = 0))

        assertTrue(step.events.single() is AdbStreamEvent.Unexpected)
        assertTrue(step.outbound.isEmpty())
    }

    /** Принятый входящий поток открыт сразу: устройство свою сторону уже открыло. */
    @Test
    fun anAcceptedInboundStreamIsOpenImmediately() {
        val router = AdbStreamRouter()

        val (localId, reply) = router.acceptInbound(REMOTE_ID)

        assertEquals(AdbCommand.OKAY, reply.command)
        assertEquals(localId, reply.arg0)
        assertEquals(REMOTE_ID, reply.arg1)
        assertTrue(router.activeStreamIds.contains(localId))
        // Открыт сразу — значит писать в него можно, не дожидаясь ничего.
        assertEquals(AdbCommand.WRTE, router.writeRequest(localId, byteArrayOf(1))?.command)
    }

    /** Принятые входящие получают такие же несовпадающие идентификаторы, как и наши. */
    @Test
    fun inboundStreamsShareTheOutboundIdentifierSequence() {
        val router = AdbStreamRouter()
        val (ours, _) = router.openRequest("shell:id")

        val (theirs, _) = router.acceptInbound(REMOTE_ID)

        assertTrue("идентификаторы не должны совпадать", ours != theirs)
    }

    /** Отказ не оставляет за собой потока: его и не было. */
    @Test
    fun aRejectedInboundStreamLeavesNothingBehind() {
        val router = AdbStreamRouter()

        val reply = router.rejectInbound(REMOTE_ID)

        assertEquals(AdbCommand.CLSE, reply.command)
        assertEquals(0, reply.arg0)
        assertEquals(REMOTE_ID, reply.arg1)
        assertTrue(router.activeStreamIds.isEmpty())
    }

    /** Закрытие без единого OKAY — это отказ сервиса, а не пустой ответ. */
    @Test
    fun closeBeforeOkayIsReportedAsRejection() {
        val router = AdbStreamRouter()
        val (localId, _) = router.openRequest("shell:nosuchservice")

        val step = router.onPacket(packet(AdbCommand.CLSE, arg0 = REMOTE_ID, arg1 = localId))

        assertEquals(
            listOf(AdbStreamEvent.Closed(localId, AdbStreamClosure.REJECTED)),
            step.events,
        )
    }

    /**
     * `OKAY` открытого потока — подтверждение **нашей** записи, и оно называется.
     *
     * Отвечать на него нечем, поэтому пакетов не появляется. А вот событие
     * появляется: Sideload считает покрытие по подтверждённым блокам, и заменить
     * подтверждение отметкой «байты ушли» значило бы выдать своё обещание за
     * ответ устройства. Открытым потоком это событие остаётся ровно потому, что
     * дальше диспетчер отдаёт его только тому, кто просил.
     */
    @Test
    fun okayOnAnAlreadyOpenStreamIsNamedAsAWriteAcknowledgement() {
        val opened = openedStream()

        val step = opened.router.onPacket(packet(AdbCommand.OKAY, arg0 = REMOTE_ID, arg1 = opened.localId))

        assertTrue("отвечать на подтверждение нечем", step.outbound.isEmpty())
        assertEquals(
            AdbStreamEvent.Acknowledged(opened.localId),
            step.events.single(),
        )
    }

    /** Повторное открытие подтверждением не притворяется: `Opened` ровно один. */
    @Test
    fun theOpeningConfirmationIsNotReportedAsAWriteAcknowledgement() {
        val opened = openedStream()

        val again = opened.router.onPacket(packet(AdbCommand.OKAY, arg0 = REMOTE_ID, arg1 = opened.localId))

        assertTrue(again.events.none { it is AdbStreamEvent.Opened })
    }

    @Test
    fun writingIsPossibleOnlyAfterTheStreamOpened() {
        val router = AdbStreamRouter()
        val (localId, _) = router.openRequest("shell:cat")

        assertNull(router.writeRequest(localId, "x".toByteArray()))

        router.onPacket(packet(AdbCommand.OKAY, arg0 = REMOTE_ID, arg1 = localId))
        val write = router.writeRequest(localId, "x".toByteArray())

        assertEquals(AdbCommand.WRTE, write?.command)
        assertEquals(REMOTE_ID, write?.arg1)
    }

    @Test
    fun writingToAnUnknownStreamIsRefusedRatherThanPretended() {
        assertNull(AdbStreamRouter().writeRequest(404, "x".toByteArray()))
    }

    @Test
    fun localCloseRemovesTheStreamAndAsksTheDeviceToClose() {
        val opened = openedStream()

        val close = opened.router.closeRequest(opened.localId)

        assertEquals(AdbCommand.CLSE, close?.command)
        assertEquals(REMOTE_ID, close?.arg1)
        assertTrue(opened.router.activeStreamIds.isEmpty())
    }

    /** Ответное CLSE устройства приходит уже на неизвестный поток — это норма. */
    @Test
    fun deviceReplyAfterLocalCloseIsTreatedAsStale() {
        val opened = openedStream()
        opened.router.closeRequest(opened.localId)

        val step = opened.router.onPacket(packet(AdbCommand.CLSE, arg0 = REMOTE_ID, arg1 = opened.localId))

        assertTrue(step.events.single() is AdbStreamEvent.Stale)
    }

    /**
     * Тест из A2 (`stale close is acknowledged without closing current stream`).
     * Ответ чужому потоку не должен задевать наш: устройство запаздывает с
     * закрытиями чаще, чем кажется.
     */
    @Test
    fun staleCloseDoesNotTouchTheCurrentStream() {
        val opened = openedStream()

        val step = opened.router.onPacket(packet(AdbCommand.CLSE, arg0 = 9, arg1 = 77))

        assertEquals(AdbCommand.CLSE, step.outbound.single().command)
        assertTrue(step.events.single() is AdbStreamEvent.Stale)
        assertEquals(setOf(opened.localId), opened.router.activeStreamIds)
        assertNotNull(opened.router.writeRequest(opened.localId, "still open".toByteArray()))
    }

    /**
     * Тест из A2 (`stale write is closed instead of contaminating current
     * stream`): чужие данные не должны попасть в наш поток.
     */
    @Test
    fun staleWriteDoesNotContaminateTheCurrentStream() {
        val opened = openedStream()

        val step = opened.router.onPacket(
            packet(AdbCommand.WRTE, arg0 = 9, arg1 = 77, payload = "not ours".toByteArray()),
        )

        assertTrue(step.events.none { it is AdbStreamEvent.Data })
        assertEquals(AdbCommand.CLSE, step.outbound.single().command)
        assertEquals(setOf(opened.localId), opened.router.activeStreamIds)
    }

    @Test
    fun packetsThatBelongToTheHandshakeAreUnexpectedHere() {
        val step = AdbStreamRouter().onPacket(packet(AdbCommand.CNXN))

        assertTrue(step.events.single() is AdbStreamEvent.Unexpected)
    }

    @Test
    fun twoStreamsAreRoutedIndependently() {
        val router = AdbStreamRouter()
        val (first, _) = router.openRequest("shell:one")
        val (second, _) = router.openRequest("shell:two")
        router.onPacket(packet(AdbCommand.OKAY, arg0 = 11, arg1 = first))
        router.onPacket(packet(AdbCommand.OKAY, arg0 = 22, arg1 = second))

        val step = router.onPacket(
            packet(AdbCommand.WRTE, arg0 = 22, arg1 = second, payload = "two".toByteArray()),
        )

        assertEquals(second, (step.events.single() as AdbStreamEvent.Data).localId)
        assertEquals(setOf(first, second), router.activeStreamIds)
    }

    @Test
    fun abandoningTheTransportClosesEveryStreamWithoutSendingAnything() {
        val router = AdbStreamRouter()
        val (first, _) = router.openRequest("shell:one")
        val (second, _) = router.openRequest("shell:two")

        val step = router.abandonAll()

        assertTrue(step.outbound.isEmpty())
        assertEquals(
            listOf(
                AdbStreamEvent.Closed(first, AdbStreamClosure.LOCAL),
                AdbStreamEvent.Closed(second, AdbStreamClosure.LOCAL),
            ),
            step.events,
        )
        assertTrue(router.activeStreamIds.isEmpty())
    }

    @Test
    fun blankServiceNameIsRejected() {
        var rejected = false
        try {
            AdbStreamRouter().openRequest("   ")
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    private class Opened(val router: AdbStreamRouter, val localId: Int)

    private companion object {
        const val REMOTE_ID = 42

        fun openedStream(): Opened {
            val router = AdbStreamRouter()
            val (localId, _) = router.openRequest("shell:id")
            router.onPacket(packet(AdbCommand.OKAY, arg0 = REMOTE_ID, arg1 = localId))
            return Opened(router, localId)
        }

        fun packet(
            command: Long,
            arg0: Int = 0,
            arg1: Int = 0,
            payload: ByteArray = ByteArray(0),
        ) = AdbPacket(command, arg0, arg1, payload)
    }
}
