package io.github.ncorror.nekoflash.protocol.adb

/**
 * Единственный физический читатель транспорта.
 *
 * Шаг 5 плана `docs/adr/0004_CONCURRENT_ADB_DISPATCHER_RU.md`. До него цикл
 * `reader.read()` крутил сам потребитель, и потому потребитель мог быть только
 * один. Здесь цикл становится один на соединение, а ждать своего потока может
 * каждый — на своём ящике.
 *
 * Инвариант «один физический читатель» (`03` §4) от этого впервые становится
 * **структурным**: раньше его держало соглашение между четырьмя классами, а
 * теперь — то, что цикл ровно один и принадлежит соединению.
 *
 * Цикл вынесен из `AdbConnection` отдельным классом не ради слоёв: соединение
 * требует ключей и рукопожатия, а цикл проверяется поверх подставного
 * транспорта без того и другого.
 *
 * **Остановка обязана быть доказуемой** (`ADR-0004` §4). Цикл кончается по
 * одной из трёх наблюдаемых причин: его попросили ([stop]), транспорт закрылся
 * или потерян кадр. В любом случае ящики закрываются — иначе потребитель, уже
 * уснувший на своём ящике, ждал бы до таймаута конца, который никто не пришлёт.
 */
internal class AdbDispatchLoop(
    private val reader: AdbPacketReader,
    private val writer: AdbPacketWriter,
    private val dispatcher: AdbStreamDispatcher,
    private val readSliceMillis: Int = READ_SLICE_MS,
    /**
     * Сообщает владельцу, что транспорт кончился сам — обрывом или потерей
     * кадра, а не по [stop].
     *
     * Без этого соединение, потерявшее кадр, продолжало называться живым:
     * прогон этапа 2 показывал «Подключено», а каждая операция кончалась
     * `FRAMING_LOST`.
     */
    private val onTransportEnd: (AdbMailboxEnd, String) -> Unit = { _, _ -> },
) {
    private val lifecycleLock = Any()

    @Volatile
    private var running = true

    @Volatile
    private var transportEnd: AdbMailboxEnd? = null

    private var terminal: AdbMailboxItem.Ended? = null

    init {
        writer.observeFailure(::writeEnded)
    }

    /** Работает ли цикл. */
    val active: Boolean
        get() = running

    /**
     * Чем кончился **транспорт**. `null` — жив.
     *
     * Отличается от [active] намеренно: остановленный по просьбе цикл — это не
     * умерший транспорт, и путать их нельзя. Поле заполняется при отказе
     * приёма или записи и не заполняется при [stop].
     *
     * Нужно тому, кто уже получил конец своего потока и не может узнать о
     * судьбе транспорта из ящика: ящик хранит **первую** причину, и поздний
     * обрыв в нём не виден. Так устроен разбор ответа на `reboot:`
     * (`07` §6.47).
     */
    val transportEndedBy: AdbMailboxEnd?
        get() = transportEnd

    /**
     * Крутит приём, пока транспорт жив или пока не попросят остановиться.
     *
     * Блокирует вызвавший поток. Кто его выделяет — решает владелец соединения.
     */
    // Every exit from the physical reader must close the waiting streams.
    @Suppress("TooGenericExceptionCaught")
    fun run() {
        try {
            while (running) {
                step()
            }
        } catch (error: RuntimeException) {
            end(AdbMailboxEnd.FRAMING_LOST, "dispatch threw ${error.javaClass.simpleName}")
        } finally {
            stop()
        }
    }

    /**
     * Просит цикл закончиться.
     *
     * Mailboxes close immediately; an in-flight USB call may still be returning.
     * Header waits use [readSliceMillis], a started payload has its own timeout.
     */
    fun stop() {
        val end = synchronized(lifecycleLock) {
            terminate(AdbMailboxEnd.TRANSPORT_CLOSED, "dispatch loop stopped")
        }
        dispatcher.abandonAll(end.reason, end.detail)
    }

    private fun step() {
        when (val outcome = reader.read(readSliceMillis)) {
            // Тишина — обычное состояние: устройству нечего сказать.
            AdbReadOutcome.Idle -> Unit

            AdbReadOutcome.Closed -> end(AdbMailboxEnd.TRANSPORT_CLOSED, "transport closed")

            // Потеря кадра делает недостоверным весь поток протокола, а не один
            // логический поток: после неё неизвестно, где начинается следующий
            // пакет. Уцелевших потоков не бывает (`03` §3).
            is AdbReadOutcome.Failed -> end(AdbMailboxEnd.FRAMING_LOST, "${outcome.reason.name} ${outcome.detail}")

            is AdbReadOutcome.Received -> if (running) {
                sendResponses(dispatcher.dispatch(outcome.packet))
            }
        }
    }

    private fun sendResponses(packets: List<AdbOutboundPacket>) {
        for (packet in packets) {
            if (!running) break
            writer.write(packet.command, packet.arg0, packet.arg1, packet.payload)
        }
    }

    private fun end(reason: AdbMailboxEnd, detail: String) {
        val end = synchronized(lifecycleLock) {
            if (terminal != null) {
                null
            } else {
                transportEnd = reason
                terminate(reason, detail)
            }
        }
        if (end != null) {
            // Dispatcher callbacks may stop the loop. Never acquire its lock under lifecycleLock.
            dispatcher.abandonAll(end.reason, end.detail)
            onTransportEnd(end.reason, end.detail)
        }
    }

    /** Called under lifecycleLock; preserves the same reason for every concurrent close. */
    private fun terminate(reason: AdbMailboxEnd, detail: String): AdbMailboxItem.Ended {
        running = false
        reader.stop()
        writer.stop()
        val end = terminal ?: AdbMailboxItem.Ended(reason, detail)
        terminal = end
        return end
    }

    private fun writeEnded(outcome: AdbWriteOutcome) {
        when (outcome) {
            AdbWriteOutcome.Sent -> Unit
            AdbWriteOutcome.Closed -> end(AdbMailboxEnd.TRANSPORT_CLOSED, "write: transport closed")
            is AdbWriteOutcome.Interrupted -> end(
                AdbMailboxEnd.FRAMING_LOST,
                "write interrupted after ${outcome.sentBytes} bytes: ${outcome.detail}",
            )
        }
    }

    companion object {
        /**
         * Потолок одной операции приёма.
         *
         * Значение из `AdbInteractiveShell.PUMP_TIMEOUT_MS`, и по той же
         * причине: цикл обязан возвращать управление, даже когда устройство
         * молчит, иначе остановить его можно будет только закрытием транспорта.
         */
        const val READ_SLICE_MS = 250
    }
}
