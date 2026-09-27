package io.github.ncorror.nekoflash.protocol.adb

import io.github.ncorror.nekoflash.core.diagnostics.InMemoryDiagnosticSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Раскладка пакетов по ящикам логических потоков.
 *
 * Главное здесь — не «данные дошли», а что при нехватке места и при потере
 * кадра ящик говорит правду вместо того, чтобы молча потерять содержимое или
 * оставить потребителя ждать.
 */
class AdbStreamDispatcherTest {
    @Test
    fun openGivesAMailboxAndTheRequestToSend() {
        val dispatcher = AdbStreamDispatcher()

        val (mailbox, packet) = dispatcher.open("shell:id")

        assertEquals(AdbCommand.OPEN, packet.command)
        assertEquals("shell:id\u0000", packet.payload.decodeToString())
        assertEquals(packet.arg0, mailbox.localId)
    }

    @Test
    fun confirmationReachesTheMailboxOfThatStream() {
        val dispatcher = AdbStreamDispatcher()
        val (mailbox, open) = dispatcher.open("shell:id")

        dispatcher.dispatch(okay(remote = 7, local = open.arg0))

        val item = mailbox.poll(0) as AdbMailboxItem.Opened
        assertEquals(7, item.remoteId)
    }

    @Test
    fun dataReachesTheMailboxOfThatStream() {
        val dispatcher = AdbStreamDispatcher()
        val (mailbox, open) = dispatcher.open("shell:id")
        dispatcher.dispatch(okay(remote = 7, local = open.arg0))
        mailbox.poll(0)

        dispatcher.dispatch(write(remote = 7, local = open.arg0, payload = byteArrayOf(1, 2, 3)))

        val item = mailbox.poll(0) as AdbMailboxItem.Data
        assertEquals(listOf<Byte>(1, 2, 3), item.payload.toList())
    }

    /** Два потока живут рядом, и вывод одного не попадает в ящик другого. */
    @Test
    fun twoStreamsDoNotMix() {
        val dispatcher = AdbStreamDispatcher()
        val (first, firstOpen) = dispatcher.open("shell:one")
        val (second, secondOpen) = dispatcher.open("shell:two")
        dispatcher.dispatch(okay(remote = 11, local = firstOpen.arg0))
        dispatcher.dispatch(okay(remote = 22, local = secondOpen.arg0))
        first.poll(0)
        second.poll(0)

        dispatcher.dispatch(write(remote = 11, local = firstOpen.arg0, payload = byteArrayOf(1)))
        dispatcher.dispatch(write(remote = 22, local = secondOpen.arg0, payload = byteArrayOf(2)))

        assertEquals(listOf<Byte>(1), (first.poll(0) as AdbMailboxItem.Data).payload.toList())
        assertEquals(listOf<Byte>(2), (second.poll(0) as AdbMailboxItem.Data).payload.toList())
    }

    @Test
    fun everyWriteIsAcknowledged() {
        val dispatcher = AdbStreamDispatcher()
        val (_, open) = dispatcher.open("shell:id")
        dispatcher.dispatch(okay(remote = 7, local = open.arg0))

        val outbound = dispatcher.dispatch(write(remote = 7, local = open.arg0, payload = byteArrayOf(1)))

        assertEquals(1, outbound.size)
        assertEquals(AdbCommand.OKAY, outbound.single().command)
    }

    @Test
    fun deviceClosureEndsTheMailbox() {
        val dispatcher = AdbStreamDispatcher()
        val (mailbox, open) = dispatcher.open("shell:id")
        dispatcher.dispatch(okay(remote = 7, local = open.arg0))
        mailbox.poll(0)

        dispatcher.dispatch(close(remote = 7, local = open.arg0))

        val ended = mailbox.poll(0) as AdbMailboxItem.Ended
        assertEquals(AdbMailboxEnd.COMPLETED, ended.reason)
    }

    /** Закрытие без подтверждения открытия — это отказ сервиса, а не пустой ответ. */
    @Test
    fun closureBeforeOpeningIsARejection() {
        val dispatcher = AdbStreamDispatcher()
        val (mailbox, open) = dispatcher.open("nope:")

        dispatcher.dispatch(close(remote = 7, local = open.arg0))

        val ended = mailbox.poll(0) as AdbMailboxItem.Ended
        assertEquals(AdbMailboxEnd.REJECTED, ended.reason)
    }

    @Test
    fun closingLocallyEndsTheMailboxAtOnce() {
        val dispatcher = AdbStreamDispatcher()
        val (mailbox, open) = dispatcher.open("shell:id")
        dispatcher.dispatch(okay(remote = 7, local = open.arg0))
        mailbox.poll(0)

        dispatcher.close(open.arg0)

        val ended = mailbox.poll(0) as AdbMailboxItem.Ended
        assertEquals(AdbMailboxEnd.LOCAL, ended.reason)
    }

    /**
     * Переполнение закрывает поток, а не теряет содержимое молча.
     *
     * Отдать вызывающему вывод с дырой значило бы солгать о результате.
     */
    @Test
    fun overflowEndsTheStreamInsteadOfDroppingData() {
        val dispatcher = AdbStreamDispatcher(mailboxCapacity = 2)
        val (mailbox, open) = dispatcher.open("shell:logcat")
        dispatcher.dispatch(okay(remote = 7, local = open.arg0))

        // Подтверждение открытия занимает первое место, дальше два блока данных.
        dispatcher.dispatch(write(remote = 7, local = open.arg0, payload = byteArrayOf(1)))
        val outbound = dispatcher.dispatch(write(remote = 7, local = open.arg0, payload = byteArrayOf(2)))

        assertTrue(outbound.any { it.command == AdbCommand.CLSE })
        assertTrue(mailbox.ended)
    }

    /**
     * Переполнение приносит измеренный темп, а не только факт.
     *
     * Гейт `07` §6.39 требует выбирать объём ящика **по измеренному темпу**, а
     * не подбором. Прогон §6.41 переполнил ящик на `logcat` и не смог назвать
     * темп: в записи был один факт «полон». Мерить больше негде — событий на
     * каждый пакет никто не пишет, и писать их при `logcat` значило бы утопить
     * журнал ровно тем, что изучается.
     */
    @Test
    fun overflowReportsTheMeasuredRate() {
        val dispatcher = AdbStreamDispatcher(mailboxCapacity = 1)
        val (mailbox, open) = dispatcher.open("shell:logcat")
        dispatcher.dispatch(okay(remote = 7, local = open.arg0))

        dispatcher.dispatch(write(remote = 7, local = open.arg0, payload = byteArrayOf(1)))

        mailbox.poll(0)
        val ended = mailbox.poll(0) as AdbMailboxItem.Ended
        assertEquals(AdbMailboxEnd.OVERFLOWED, ended.reason)
        assertTrue(ended.detail, ended.detail.contains("delivered="))
        assertTrue(ended.detail, ended.detail.contains("capacity=1"))
    }

    /**
     * Конец доставляется даже из переполненного ящика.
     *
     * Иначе потребитель, из-за которого ящик и переполнился, никогда не узнал
     * бы, что поток кончился, и ждал бы до таймаута.
     */
    @Test
    fun theEndIsDeliverableFromAFullMailbox() {
        val dispatcher = AdbStreamDispatcher(mailboxCapacity = 1)
        val (mailbox, open) = dispatcher.open("shell:logcat")
        dispatcher.dispatch(okay(remote = 7, local = open.arg0))
        dispatcher.dispatch(write(remote = 7, local = open.arg0, payload = byteArrayOf(1)))

        // Забираем всё, что влезло, и следом обязан прийти конец.
        assertNotNull(mailbox.poll(0))
        val ended = mailbox.poll(0) as AdbMailboxItem.Ended
        assertEquals(AdbMailboxEnd.OVERFLOWED, ended.reason)
    }

    /** Принятое до конца отдаётся раньше самого конца: обрыв не съедает вывод. */
    @Test
    fun contentReceivedBeforeTheEndIsStillDelivered() {
        val dispatcher = AdbStreamDispatcher()
        val (mailbox, open) = dispatcher.open("shell:id")
        dispatcher.dispatch(okay(remote = 7, local = open.arg0))
        dispatcher.dispatch(write(remote = 7, local = open.arg0, payload = byteArrayOf(9)))

        dispatcher.abandonAll(AdbMailboxEnd.FRAMING_LOST, "PARTIAL_HEADER")

        assertTrue(mailbox.poll(0) is AdbMailboxItem.Opened)
        assertEquals(listOf<Byte>(9), (mailbox.poll(0) as AdbMailboxItem.Data).payload.toList())
        assertEquals(AdbMailboxEnd.FRAMING_LOST, (mailbox.poll(0) as AdbMailboxItem.Ended).reason)
    }

    /**
     * Потеря кадра закрывает все потоки одной причиной.
     *
     * После неё неизвестно, где начинается следующий пакет, поэтому уцелевших
     * потоков не бывает.
     */
    @Test
    fun framingLossEndsEveryMailbox() {
        val dispatcher = AdbStreamDispatcher()
        val (first, _) = dispatcher.open("shell:one")
        val (second, _) = dispatcher.open("shell:two")

        dispatcher.abandonAll(AdbMailboxEnd.FRAMING_LOST, "SHORT_PAYLOAD expected=10 actual=4")

        listOf(first, second).forEach { mailbox ->
            val ended = mailbox.poll(0) as AdbMailboxItem.Ended
            assertEquals(AdbMailboxEnd.FRAMING_LOST, ended.reason)
            assertTrue(ended.detail.contains("SHORT_PAYLOAD"))
        }
    }

    /** Первая причина конца побеждает: она точнее описывает случившееся. */
    @Test
    fun theFirstEndWins() {
        val dispatcher = AdbStreamDispatcher()
        val (mailbox, open) = dispatcher.open("shell:id")
        dispatcher.dispatch(okay(remote = 7, local = open.arg0))
        mailbox.poll(0)
        dispatcher.close(open.arg0)

        dispatcher.abandonAll(AdbMailboxEnd.TRANSPORT_CLOSED, "released")

        assertEquals(AdbMailboxEnd.LOCAL, (mailbox.poll(0) as AdbMailboxItem.Ended).reason)
    }

    /** Живой поток без событий ничего не отдаёт и не выдумывает конец. */
    @Test
    fun anIdleStreamYieldsNothing() {
        val dispatcher = AdbStreamDispatcher()
        val (mailbox, _) = dispatcher.open("shell:id")

        assertNull(mailbox.poll(0))
    }

    /** Чужой пакет отвечается маршрутизатором и ни в один ящик не попадает. */
    @Test
    fun aPacketForAnUnknownStreamReachesNoMailbox() {
        val dispatcher = AdbStreamDispatcher()
        val (mailbox, _) = dispatcher.open("shell:id")

        val outbound = dispatcher.dispatch(write(remote = 99, local = 404, payload = byteArrayOf(1)))

        assertTrue(outbound.any { it.command == AdbCommand.CLSE })
        assertNull(mailbox.poll(0))
    }

    @Test
    fun identifiersAreNotReused() {
        val dispatcher = AdbStreamDispatcher()
        val (first, _) = dispatcher.open("shell:one")
        dispatcher.close(first.localId)

        val (second, _) = dispatcher.open("shell:two")

        assertTrue(second.localId != first.localId)
    }

    /**
     * Поток, заведённый устройством, получает отказ, а не тишину.
     *
     * Принимать его пока некому (шаг 7 плана `ADR-0005`), но молчание оставило
     * бы устройство ждать: оно должно узнать, что адресата нет.
     */
    @Test
    fun anInboundStreamIsRefusedRatherThanIgnored() {
        val sink = InMemoryDiagnosticSink()
        val dispatcher = AdbStreamDispatcher(diagnostics = sink)

        val outbound = dispatcher.dispatch(
            AdbPacket(AdbCommand.OPEN, 77, 0, "tcp:8080\u0000".toByteArray()),
        )

        val reply = outbound.single()
        assertEquals(AdbCommand.CLSE, reply.command)
        assertEquals(77, reply.arg1)
        assertTrue("отказ не должен заводить ящик", dispatcher.activeMailboxes.isEmpty())
        // Отказ обязан быть виден: иначе на прогоне не отличить «поток пришёл и
        // мы отказали» от «поток не приходил вовсе».
        assertEquals("inbound_stream_refused", sink.snapshot().single().message)
        assertEquals("tcp:8080", sink.snapshot().single().fields["service"])
    }

    /**
     * Подтверждение нашей записи доходит до того, кто его просил.
     *
     * Sideload считает покрытие по **подтверждённым** блокам, и без этого
     * события посчитать их нечем: отметка «байты ушли» говорит про нас, а не
     * про устройство.
     */
    @Test
    fun anAcknowledgementReachesTheStreamThatAskedForIt() {
        val dispatcher = AdbStreamDispatcher()
        val (mailbox, open) = dispatcher.open("sideload-host:1:65536", acknowledgements = true)
        dispatcher.dispatch(okay(remote = 7, local = open.arg0))
        mailbox.poll(0)

        dispatcher.dispatch(okay(remote = 7, local = open.arg0))

        assertEquals(AdbMailboxItem.Acknowledged, mailbox.poll(0))
    }

    /**
     * Тот, кто не просил, подтверждений не получает.
     *
     * Это не вежливость: ящик ограничен намеренно (`03` §4), и подтверждения
     * занимали бы в нём место наравне с выводом. Поток `logcat` переполнялся бы
     * вдвое быстрее из-за того, чего никто не читает.
     */
    @Test
    fun aStreamThatDidNotAskGetsNoAcknowledgements() {
        val dispatcher = AdbStreamDispatcher()
        val (mailbox, open) = dispatcher.open("shell:logcat")
        dispatcher.dispatch(okay(remote = 7, local = open.arg0))
        mailbox.poll(0)

        dispatcher.dispatch(okay(remote = 7, local = open.arg0))

        assertNull("подтверждения приходят только по просьбе", mailbox.poll(0))
    }

    /** Подтверждение не путается с открытием: первый `OKAY` остаётся открытием. */
    @Test
    fun theOpeningConfirmationStaysAnOpening() {
        val dispatcher = AdbStreamDispatcher()
        val (mailbox, open) = dispatcher.open("sideload-host:1:65536", acknowledgements = true)

        dispatcher.dispatch(okay(remote = 7, local = open.arg0))

        assertEquals(AdbMailboxItem.Opened(7), mailbox.poll(0))
        assertNull(mailbox.poll(0))
    }

    private companion object {
        fun okay(remote: Int, local: Int) = AdbPacket(AdbCommand.OKAY, remote, local, ByteArray(0))

        fun write(remote: Int, local: Int, payload: ByteArray) =
            AdbPacket(AdbCommand.WRTE, remote, local, payload)

        fun close(remote: Int, local: Int) = AdbPacket(AdbCommand.CLSE, remote, local, ByteArray(0))
    }
}
