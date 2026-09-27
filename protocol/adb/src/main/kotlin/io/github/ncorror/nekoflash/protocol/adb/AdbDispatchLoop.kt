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
) {
    @Volatile
    private var running = true

    @Volatile
    private var transportEnd: AdbMailboxEnd? = null

    /** Работает ли цикл. */
    val active: Boolean
        get() = running

    /**
     * Чем кончился **транспорт**. `null` — жив.
     *
     * Отличается от [active] намеренно: остановленный по просьбе цикл — это не
     * умерший транспорт, и путать их нельзя. Поле заполняется только там, где
     * читатель принёс беду, и не заполняется при [stop].
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
    fun run() {
        while (running) {
            step()
        }
        // Причина, записанная первой, побеждает: если цикл кончился обрывом,
        // ящики уже несут его, а не «нас попросили остановиться».
        dispatcher.abandonAll(AdbMailboxEnd.TRANSPORT_CLOSED, "dispatch loop stopped")
    }

    /**
     * Просит цикл закончиться.
     *
     * Ответ приходит не позже [readSliceMillis]: ожидание нарезано на куски
     * именно затем, чтобы остановка не ждала молчащего устройства.
     */
    fun stop() {
        running = false
    }

    private fun step() {
        when (val outcome = reader.read(readSliceMillis)) {
            // Тишина — обычное состояние: устройству нечего сказать.
            AdbReadOutcome.Idle -> Unit

            AdbReadOutcome.Closed -> {
                transportEnd = AdbMailboxEnd.TRANSPORT_CLOSED
                dispatcher.abandonAll(AdbMailboxEnd.TRANSPORT_CLOSED, "transport closed")
                running = false
            }

            // Потеря кадра делает недостоверным весь поток протокола, а не один
            // логический поток: после неё неизвестно, где начинается следующий
            // пакет. Уцелевших потоков не бывает (`03` §3).
            is AdbReadOutcome.Failed -> {
                transportEnd = AdbMailboxEnd.FRAMING_LOST
                dispatcher.abandonAll(
                    AdbMailboxEnd.FRAMING_LOST,
                    "${outcome.reason.name} ${outcome.detail}",
                )
                running = false
            }

            is AdbReadOutcome.Received -> dispatcher.dispatch(outcome.packet).forEach { packet ->
                writer.write(packet.command, packet.arg0, packet.arg1, packet.payload)
            }
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
