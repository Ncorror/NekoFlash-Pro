package io.github.ncorror.nekoflash.protocol.adb

import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticEvent
import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticSink
import java.time.Clock
import java.time.Instant

/**
 * Кому достаются потоки, заведённые **устройством**.
 *
 * Так приходит соединение при обратном пробросе: слушает устройство, а клиента
 * оно приводит к нам входящим `OPEN`
 * (`docs/adr/0005_LOCAL_SOCKET_FORWARDING_RU.md` §1).
 *
 * Два вызова, а не один, и порядок между ними существенный. Сперва [wants] —
 * решение принимается **до** подтверждения, потому что подтвердить и передумать
 * значило бы сказать устройству «принял», не приняв: на `OKAY` оно вправе сразу
 * слать данные. Потом [accepted] — с готовым ящиком.
 *
 * Оба вызова идут **под замком диспетчера**, то есть из потока цикла раскладки.
 * Значит оба обязаны возвращаться сразу: блокировать здесь — значит остановить
 * приём для всех потоков разом. Владелец кладёт работу в очередь и уходит.
 */
public interface AdbInboundStreams {
    /** Нужен ли поток с таким адресом. Отказ — не ошибка, а ответ. */
    public fun wants(service: String): Boolean

    /** Поток принят и открыт; вот его ящик. */
    public fun accepted(service: String, mailbox: AdbStreamMailbox)
}

/**
 * Раскладывает принятые пакеты по ящикам логических потоков.
 *
 * Первый шаг плана `docs/adr/0004_CONCURRENT_ADB_DISPATCHER_RU.md`. Сегодня
 * инвариант «один физический читатель» держится соглашением между четырьмя
 * классами: цикл `reader.read()` крутит сам потребитель, и поэтому потребитель
 * может быть только один. Диспетчер снимает именно это: цикл будет один, а
 * ждать своего потока сможет каждый — на своём ящике.
 *
 * Класс остаётся **без ввода-вывода и без потока исполнения**, как и
 * [AdbStreamRouter]: на входе принятый пакет, на выходе список пакетов к
 * отправке. Кто вертит цикл и на каком потоке — решает владелец соединения, и
 * это шаг 2 того же плана. Такое разделение позволяет проверить раскладку
 * тестами, не заводя ни одного потока.
 *
 * Правила маршрутизации не дублируются: их знает [AdbStreamRouter], а
 * диспетчер только доставляет его события адресатам.
 */
public class AdbStreamDispatcher(
    private val router: AdbStreamRouter = AdbStreamRouter(),
    private val mailboxCapacity: Int = AdbStreamMailbox.DEFAULT_CAPACITY,
    /**
     * Журнал для того, что происходит **не по просьбе потребителя**.
     *
     * Своё диспетчер не пишет: у каждого потребителя есть собственный журнал, и
     * дублировать его здесь значило бы писать одно и то же дважды. Но поток,
     * заведённый устройством, не принадлежит никакому потребителю, и кроме
     * диспетчера рассказать о нём некому.
     */
    private val diagnostics: DiagnosticSink = DiagnosticSink { },
    private val clock: () -> Instant = { Clock.systemUTC().instant() },
) {
    private val lock = Any()
    private val mailboxes = LinkedHashMap<Int, AdbStreamMailbox>()

    private var inbound: AdbInboundStreams? = null

    /** Ящики живых потоков в порядке открытия. */
    public val activeMailboxes: List<AdbStreamMailbox>
        get() = synchronized(lock) { mailboxes.values.toList() }

    /**
     * Назначает получателя потоков, заведённых устройством.
     *
     * `null` возвращает поведение по умолчанию: отказывать. Отказ здесь не
     * заглушка — без обратного проброса устройство таких потоков не заводит, а
     * если завело, оно должно узнать, что адресата нет, иначе будет ждать.
     */
    public fun inboundStreams(streams: AdbInboundStreams?): Unit = synchronized(lock) {
        inbound = streams
    }

    /**
     * Открывает поток и заводит ему ящик.
     *
     * Возвращает ящик и пакет, который надо отправить: отправка принадлежит
     * владельцу транспорта, как и в [AdbStreamRouter.openRequest].
     *
     * [acknowledgements] включает доставку [AdbMailboxItem.Acknowledged] — того
     * самого `OKAY`, которым устройство подтверждает **нашу** запись. По
     * умолчанию выключено, и это не осторожность ради осторожности: подтверждения
     * приходят по одному на каждую нашу запись, а ящик ограничен намеренно
     * (`03` §4). Потоку, который их не читает, они отъедали бы место наравне с
     * выводом — и `logcat`, и загрузка файла переполняли бы ящик вдвое быстрее
     * из-за того, чего никто не заказывал.
     *
     * Просит их ровно один потребитель — Sideload: он считает покрытие по
     * **подтверждённым** блокам, и заменить подтверждение отметкой «байты ушли»
     * значило бы считать своё обещание за ответ устройства.
     */
    public fun open(
        service: String,
        acknowledgements: Boolean = false,
    ): Pair<AdbStreamMailbox, AdbOutboundPacket> = synchronized(lock) {
        val (localId, packet) = router.openRequest(service)
        val mailbox = AdbStreamMailbox(localId, mailboxCapacity, acknowledgements)
        mailboxes[localId] = mailbox
        mailbox to packet
    }

    /** Готовит отправку данных в открытый поток; `null` — потока нет или он не открыт. */
    public fun write(localId: Int, payload: ByteArray): AdbOutboundPacket? = synchronized(lock) {
        router.writeRequest(localId, payload)
    }

    /**
     * Закрывает поток по нашей инициативе.
     *
     * Ящик получает конец сразу: потребитель, ждущий на нём, должен узнать о
     * закрытии, а не висеть до таймаута.
     */
    public fun close(localId: Int): AdbOutboundPacket? = synchronized(lock) {
        val packet = router.closeRequest(localId)
        mailboxes.remove(localId)?.end(AdbMailboxEnd.LOCAL, "closed by host")
        packet
    }

    /**
     * Разбирает один принятый пакет и раскладывает его по ящикам.
     *
     * Возвращает пакеты к отправке — как подтверждения от маршрутизатора, так и
     * `CLSE` для потока, чей ящик переполнился.
     */
    public fun dispatch(packet: AdbPacket): List<AdbOutboundPacket> = synchronized(lock) {
        val step = router.onPacket(packet)
        val outbound = step.outbound.toMutableList()
        step.events.forEach { event -> deliver(event, outbound) }
        outbound
    }

    /**
     * Закрывает **все** ящики одной причиной.
     *
     * Потеря кадра делает недостоверным весь поток протокола, а не отдельный
     * логический поток: после неё неизвестно, где начинается следующий пакет.
     * Делать вид, что какой-то из потоков уцелел, нельзя
     * (`03_PROTOCOL_AND_SAFETY_INVARIANTS_RU.md` §3).
     */
    public fun abandonAll(reason: AdbMailboxEnd, detail: String): Unit = synchronized(lock) {
        router.abandonAll()
        mailboxes.values.forEach { mailbox -> mailbox.end(reason, detail) }
        mailboxes.clear()
    }

    /**
     * Решает судьбу потока, заведённого устройством.
     *
     * Спрашивает получателя **до** подтверждения и только потом заводит ящик:
     * иначе пришлось бы подтвердить, а затем закрыть — то есть пообещать и не
     * сдержать.
     */
    private fun admit(event: AdbStreamEvent.Inbound, outbound: MutableList<AdbOutboundPacket>) {
        val handler = inbound
        if (handler == null || !handler.wants(event.service)) {
            outbound += router.rejectInbound(event.remoteId)
            emit(
                "inbound_stream_refused",
                mapOf("service" to event.service, "remote" to event.remoteId.toString()),
            )
            return
        }
        val (localId, reply) = router.acceptInbound(event.remoteId)
        val mailbox = AdbStreamMailbox(localId, mailboxCapacity)
        mailboxes[localId] = mailbox
        outbound += reply
        emit(
            "inbound_stream_accepted",
            mapOf("service" to event.service, "stream" to localId.toString()),
        )
        handler.accepted(event.service, mailbox)
    }

    private fun emit(message: String, fields: Map<String, String>) {
        diagnostics.emit(
            DiagnosticEvent(
                timestamp = clock(),
                category = AdbHandshake.DIAGNOSTIC_CATEGORY,
                message = message,
                fields = fields,
            ),
        )
    }

    private fun deliver(event: AdbStreamEvent, outbound: MutableList<AdbOutboundPacket>) {
        when (event) {
            is AdbStreamEvent.Opened ->
                put(event.localId, AdbMailboxItem.Opened(event.remoteId), outbound)

            is AdbStreamEvent.Data ->
                put(event.localId, AdbMailboxItem.Data(event.payload), outbound)

            /*
             * Подтверждение нашей записи доходит только до того, кто его
             * просил. Молча класть его всем значило бы тратить ограниченный
             * ящик на то, чего потребитель не читает.
             */
            is AdbStreamEvent.Acknowledged ->
                if (mailboxes[event.localId]?.acknowledgements == true) {
                    put(event.localId, AdbMailboxItem.Acknowledged, outbound)
                }

            is AdbStreamEvent.Closed -> {
                mailboxes.remove(event.localId)?.end(event.reason.toEnd(), "device closed the stream")
            }

            /*
             * Поток, заведённый устройством. Принимать его пока некому, и
             * отказ здесь — не заглушка, а правильный ответ: без обратного
             * проброса устройство таких потоков и не заводит, а если завело —
             * оно должно узнать, что адресата нет, иначе будет ждать.
             *
             * Приёмник появится шагом 7 плана
             * `docs/adr/0005_LOCAL_SOCKET_FORWARDING_RU.md`; отказ переедет в
             * ветку «приёмника нет», а не исчезнет.
             */
            is AdbStreamEvent.Inbound -> admit(event, outbound)

            // Чужой и неожиданный пакет адресату не принадлежат: маршрутизатор уже
            // ответил на них тем, чем следовало, а ящику сообщать нечего.
            is AdbStreamEvent.Stale -> Unit
            is AdbStreamEvent.Unexpected -> Unit
        }
    }

    /**
     * Кладёт событие в ящик, а при переполнении закрывает поток.
     *
     * `CLSE` отправляется устройству: оно должно узнать, что поток кончился,
     * иначе продолжит слать данные в никуда.
     */
    private fun put(
        localId: Int,
        item: AdbMailboxItem,
        outbound: MutableList<AdbOutboundPacket>,
    ) {
        val mailbox = mailboxes[localId] ?: return
        if (mailbox.offer(item)) return
        mailbox.end(
            AdbMailboxEnd.OVERFLOWED,
            "mailbox of stream $localId is full: ${mailbox.rateDetail()}",
        )
        mailboxes.remove(localId)
        router.closeRequest(localId)?.let { packet -> outbound += packet }
    }
}

private fun AdbStreamClosure.toEnd(): AdbMailboxEnd = when (this) {
    AdbStreamClosure.COMPLETED -> AdbMailboxEnd.COMPLETED
    AdbStreamClosure.REJECTED -> AdbMailboxEnd.REJECTED
    AdbStreamClosure.LOCAL -> AdbMailboxEnd.LOCAL
}
