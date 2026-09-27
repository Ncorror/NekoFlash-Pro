package io.github.ncorror.nekoflash.protocol.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

/**
 * Ожидание на ящике.
 *
 * Раскладку проверяет [AdbStreamDispatcherTest] без единого потока. Здесь
 * проверяется то, ради чего ящик и заведён: потребитель спит на **своём**
 * потоке и просыпается, когда для него что-то появилось. Обещание «не висеть
 * до таймаута» ничем, кроме настоящего ожидания, не доказывается.
 *
 * Голый `Thread` здесь не спорит с ADR-0003 §2: правило описывает конкурентную
 * модель продукта, а `poll` блокирующий по замыслу (ADR-0004 §2 — «потребитель
 * блокируется на своём ящике»). Разбудить спящего может только кто-то, кто
 * работает **не** на его потоке, и корутина была бы тем же потоком под другим
 * именем.
 */
class AdbStreamMailboxTest {
    /** Живой поток без событий отдаёт `null`, а не выдумывает конец. */
    @Test
    fun waitingOnAnEmptyMailboxYieldsNothing() {
        val mailbox = AdbStreamMailbox(localId = 1)

        assertNull(mailbox.poll(SHORT_WAIT_MILLIS))
    }

    /**
     * Пришедшее во время ожидания будит потребителя.
     *
     * Иначе ящик был бы просто буфером, а ждать пришлось бы всё равно на
     * транспорте — то есть в одиночку.
     */
    @Test
    fun aWaitingConsumerIsWokenByData() {
        val mailbox = AdbStreamMailbox(localId = 1)
        thread {
            Thread.sleep(HANDOFF_MILLIS)
            mailbox.offer(AdbMailboxItem.Data(byteArrayOf(7)))
        }

        val item = mailbox.poll(LONG_WAIT_MILLIS)

        assertEquals(listOf<Byte>(7), (item as AdbMailboxItem.Data).payload.toList())
    }

    /**
     * Конец во время ожидания будит потребителя.
     *
     * Записи поля для этого мало: уснувший на пустой очереди её не видит.
     * Проверяется именно пробуждение, поэтому ожидание засекается: вернуть
     * конец через полный таймаут ящик мог бы и без исправления.
     */
    @Test
    fun aWaitingConsumerIsWokenByTheEnd() {
        val mailbox = AdbStreamMailbox(localId = 1)
        thread {
            Thread.sleep(HANDOFF_MILLIS)
            mailbox.end(AdbMailboxEnd.LOCAL, "closed by host")
        }

        val started = System.nanoTime()
        val item = mailbox.poll(LONG_WAIT_MILLIS)
        val elapsedMillis = (System.nanoTime() - started) / NANOS_PER_MILLI

        assertEquals(AdbMailboxEnd.LOCAL, (item as AdbMailboxItem.Ended).reason)
        assertTrue("ожидание заняло $elapsedMillis мс", elapsedMillis < LONG_WAIT_MILLIS / 2)
    }

    /** После конца ящик отвечает сразу и тем же самым: конец не одноразовый. */
    @Test
    fun theEndIsAnsweredAgainWithoutWaiting() {
        val mailbox = AdbStreamMailbox(localId = 1)
        mailbox.end(AdbMailboxEnd.COMPLETED, "device closed the stream")
        mailbox.poll(0)

        val started = System.nanoTime()
        val item = mailbox.poll(LONG_WAIT_MILLIS)
        val elapsedMillis = (System.nanoTime() - started) / NANOS_PER_MILLI

        assertEquals(AdbMailboxEnd.COMPLETED, (item as AdbMailboxItem.Ended).reason)
        assertTrue("ожидание заняло $elapsedMillis мс", elapsedMillis < LONG_WAIT_MILLIS / 2)
    }

    /** Принятое до конца отдаётся раньше конца даже при ожидании. */
    @Test
    fun contentIsHandedOutBeforeTheEnd() {
        val mailbox = AdbStreamMailbox(localId = 1)
        mailbox.offer(AdbMailboxItem.Data(byteArrayOf(7)))
        mailbox.end(AdbMailboxEnd.TRANSPORT_CLOSED, "released")

        assertTrue(mailbox.poll(LONG_WAIT_MILLIS) is AdbMailboxItem.Data)
        assertTrue(mailbox.poll(LONG_WAIT_MILLIS) is AdbMailboxItem.Ended)
    }

    /** После конца новых событий ящик не принимает: поток кончился. */
    @Test
    fun nothingIsAcceptedAfterTheEnd() {
        val mailbox = AdbStreamMailbox(localId = 1)
        mailbox.end(AdbMailboxEnd.COMPLETED, "device closed the stream")

        mailbox.offer(AdbMailboxItem.Data(byteArrayOf(7)))

        assertTrue(mailbox.poll(0) is AdbMailboxItem.Ended)
        assertTrue(mailbox.poll(0) is AdbMailboxItem.Ended)
    }

    /** Ящик нулевого объёма не ящик: в него ничего нельзя положить. */
    @Test(expected = IllegalArgumentException::class)
    fun capacityMustBePositive() {
        AdbStreamMailbox(localId = 1, capacity = 0)
    }

    /** Отрицательное ожидание — ошибка вызывающего, а не «не ждать». */
    @Test(expected = IllegalArgumentException::class)
    fun negativeTimeoutIsRejected() {
        AdbStreamMailbox(localId = 1).poll(-1)
    }

    private companion object {
        const val SHORT_WAIT_MILLIS = 30L
        const val LONG_WAIT_MILLIS = 5_000L
        const val HANDOFF_MILLIS = 50L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
