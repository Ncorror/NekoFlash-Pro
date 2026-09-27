package io.github.ncorror.nekoflash.protocol.adb

/** Пакет, который маршрутизатор просит отправить. */
public data class AdbOutboundPacket(
    val command: Long,
    val arg0: Int,
    val arg1: Int,
    val payload: ByteArray = ByteArray(0),
) {
    /** Содержимое не печатается: через потоки идут чужие данные. */
    override fun toString(): String =
        "AdbOutboundPacket(command=0x${command.toString(16)}, arg0=$arg0, arg1=$arg1, payload=${payload.size} bytes)"
}

/** Почему поток закрылся. */
public enum class AdbStreamClosure {
    /** Устройство закрыло поток после того, как тот успел открыться. */
    COMPLETED,

    /**
     * Устройство закрыло поток, ни разу не подтвердив открытие.
     *
     * Обычно означает, что сервиса нет или он отказал. Это не то же самое, что
     * пустой ответ: пустой ответ приходит после `OKAY`.
     */
    REJECTED,

    /** Поток закрыт по нашей просьбе. */
    LOCAL,
}

/** Что произошло с потоками при разборе одного пакета. */
public sealed interface AdbStreamEvent {
    /** Устройство подтвердило открытие и назвало свой идентификатор. */
    public data class Opened(val localId: Int, val remoteId: Int) : AdbStreamEvent

    /** Данные потока. */
    public data class Data(val localId: Int, val payload: ByteArray) : AdbStreamEvent

    /**
     * Устройство подтвердило **нашу** запись и готово принимать дальше.
     *
     * Раньше это событие никуда не выходило: `OKAY` открытого потока — сугубо
     * управление потоком, и потребителю от него ничего не нужно. Оказалось,
     * что нужно ровно одному: Sideload считает покрытие по **подтверждённым**
     * блокам, а подтверждение — это оно и есть. Заменить его отметкой «байты
     * ушли» нельзя: она говорит про нас, а не про устройство.
     *
     * Событие рождается всегда, а до потребителя доходит только если тот его
     * просил ([AdbStreamDispatcher.open]). Остальные потоки не должны платить
     * местом в ящике за то, чего не заказывали.
     */
    public data class Acknowledged(val localId: Int) : AdbStreamEvent

    /** Поток закончился. */
    public data class Closed(val localId: Int, val reason: AdbStreamClosure) : AdbStreamEvent

    /**
     * Поток заводит **устройство**, а не мы.
     *
     * Так приходит соединение при обратном пробросе: `reverse` заставляет
     * слушать устройство, и клиента оно приводит к нам входящим `OPEN`
     * (`docs/adr/0005_LOCAL_SOCKET_FORWARDING_RU.md` §1).
     *
     * Маршрутизатор такой поток **не заводит сам и не подтверждает**. Ответить
     * `OKAY`, а потом передумать, значило бы сказать устройству «принял», не
     * приняв: на основании подтверждения оно вправе сразу слать данные, и они
     * ушли бы в никуда. Решение принимает владелец — [acceptInbound] или
     * [rejectInbound].
     */
    public data class Inbound(val remoteId: Int, val service: String) : AdbStreamEvent

    /**
     * Пакет адресован потоку, которого у нас нет.
     *
     * Не ошибка: устройство могло не успеть узнать о закрытии. Ответ на него
     * обязателен, иначе peer будет ждать вечно — см. [AdbStreamRouter].
     */
    public data class Stale(val detail: String) : AdbStreamEvent

    /** Пакет, которому в потоковом обмене места нет. */
    public data class Unexpected(val detail: String) : AdbStreamEvent
}

/** Итог разбора одного пакета: что отправить и что произошло. */
public data class AdbRouterStep(
    val outbound: List<AdbOutboundPacket> = emptyList(),
    val events: List<AdbStreamEvent> = emptyList(),
)

/**
 * Маршрутизатор логических потоков ADB.
 *
 * Чистое состояние без ввода-вывода: на входе принятый пакет, на выходе список
 * пакетов к отправке и список событий. Ввод-вывод и поток исполнения
 * принадлежат владельцу транспорта — так же, как разделены пакетный автомат и
 * транспорт в A2.
 *
 * Три правила перенесены из Legacy (`openAdbStream`) и A2
 * (`AdbReadOnlyStreamSession`), где они совпадают, и каждое неочевидно:
 *
 * 1. Пакет, адресованный неизвестному потоку, **нельзя молча пропустить**. На
 *    `WRTE` и `CLSE` отвечаем `CLSE` его же идентификаторами: иначе устройство
 *    останется ждать подтверждения от потока, которого нет. Чужой `OKAY`
 *    пропускается — отвечать на него нечем.
 * 2. Каждый `WRTE` подтверждается `OKAY` немедленно. Это backpressure ADB:
 *    без подтверждения peer перестанет присылать данные.
 * 3. `WRTE`, пришедший до `OKAY`, подтверждается, но данными потока не
 *    считается. Поток ещё не открыт, и отдавать вызывающему то, чего он не
 *    заказывал, нельзя.
 *
 * Идентификаторы выдаются по возрастанию и не переиспользуются: устройство
 * может прислать пакет закрытого потока, и совпадение с новым потоком выдало бы
 * чужие данные за свои.
 */
public class AdbStreamRouter {
    private val streams = LinkedHashMap<Int, StreamState>()
    private var nextLocalId = FIRST_LOCAL_ID

    /** Идентификаторы открытых и открывающихся потоков. */
    public val activeStreamIds: Set<Int>
        get() = streams.keys.toSet()

    /**
     * Готовит запрос на открытие сервиса.
     *
     * Возвращает пакет и идентификатор; отправка — забота вызывающего. Поток
     * считается открытым только после `OKAY` от устройства.
     */
    public fun openRequest(service: String): Pair<Int, AdbOutboundPacket> {
        require(service.isNotBlank()) { "ADB service name must not be blank" }
        val localId = nextLocalId++
        streams[localId] = StreamState()
        return localId to AdbOutboundPacket(
            command = AdbCommand.OPEN,
            arg0 = localId,
            arg1 = 0,
            payload = "$service\u0000".toByteArray(Charsets.UTF_8),
        )
    }

    /**
     * Готовит отправку данных в открытый поток.
     *
     * `null` означает, что потока нет или он ещё не открыт: писать в него
     * некуда, и притворяться, что запись состоялась, нельзя.
     */
    public fun writeRequest(localId: Int, payload: ByteArray): AdbOutboundPacket? {
        val stream = streams[localId] ?: return null
        if (!stream.opened) return null
        return AdbOutboundPacket(AdbCommand.WRTE, localId, stream.remoteId, payload)
    }

    /**
     * Готовит закрытие потока по нашей инициативе.
     *
     * Поток убирается из реестра сразу: ответное `CLSE` устройства придёт на
     * уже неизвестный идентификатор и будет обработано как чужой пакет — это
     * ожидаемый ход событий, а не ошибка.
     */
    public fun closeRequest(localId: Int): AdbOutboundPacket? {
        val stream = streams.remove(localId) ?: return null
        if (stream.remoteId <= 0) return null
        return AdbOutboundPacket(AdbCommand.CLSE, localId, stream.remoteId)
    }

    /**
     * Принимает поток, заведённый устройством, и даёт ему наш идентификатор.
     *
     * Поток сразу считается открытым: устройство свою сторону уже открыло, и
     * ждать подтверждения не от кого — подтверждение шлём мы.
     */
    public fun acceptInbound(remoteId: Int): Pair<Int, AdbOutboundPacket> {
        require(remoteId > 0) { "Inbound stream must carry a remote id: $remoteId" }
        val localId = nextLocalId++
        streams[localId] = StreamState().apply {
            opened = true
            this.remoteId = remoteId
        }
        return localId to AdbOutboundPacket(AdbCommand.OKAY, localId, remoteId)
    }

    /**
     * Отказывает потоку, заведённому устройством.
     *
     * Своего идентификатора у него не появляется: отказ означает, что потока не
     * было. `CLSE` с нулём в нашем поле — так же, как отвечает `adbd` на
     * сервис, которого у него нет.
     */
    public fun rejectInbound(remoteId: Int): AdbOutboundPacket {
        require(remoteId > 0) { "Inbound stream must carry a remote id: $remoteId" }
        return AdbOutboundPacket(AdbCommand.CLSE, 0, remoteId)
    }

    /** Разбирает один принятый пакет. */
    public fun onPacket(packet: AdbPacket): AdbRouterStep = when (packet.command) {
        AdbCommand.OPEN -> onOpen(packet)
        AdbCommand.OKAY -> onOkay(packet)
        AdbCommand.WRTE -> onWrite(packet)
        AdbCommand.CLSE -> onClose(packet)
        else -> AdbRouterStep(
            events = listOf(
                AdbStreamEvent.Unexpected("command=0x${packet.command.toString(16)}"),
            ),
        )
    }

    /**
     * Закрывает все потоки, когда транспорт больше не пригоден.
     *
     * Пакеты не формируются: если транспорт умер, отправлять их некуда, а
     * молчаливое исчезновение потоков оставило бы вызывающего ждать.
     */
    public fun abandonAll(): AdbRouterStep {
        val events = streams.keys.map { localId ->
            AdbStreamEvent.Closed(localId, AdbStreamClosure.LOCAL)
        }
        streams.clear()
        return AdbRouterStep(events = events)
    }

    /**
     * Устройство просит открыть поток к нам.
     *
     * Имя сервиса приходит с завершающим нулём, как и в нашем `OPEN`; нуль
     * снимается, остальное передаётся как есть — толковать имя не наше дело.
     */
    private fun onOpen(packet: AdbPacket): AdbRouterStep {
        val event = if (packet.arg0 > 0) {
            AdbStreamEvent.Inbound(
                remoteId = packet.arg0,
                service = packet.payload.toString(Charsets.UTF_8).trimEnd('\u0000'),
            )
        } else {
            // Без своего идентификатора поток не адресуем: ответить нечем, и
            // притворяться, что он есть, нельзя.
            AdbStreamEvent.Unexpected("OPEN without a remote id: payload=${packet.payload.size} bytes")
        }
        return AdbRouterStep(events = listOf(event))
    }

    private fun onOkay(packet: AdbPacket): AdbRouterStep {
        val stream = streams[packet.arg1]
            ?: return AdbRouterStep(events = listOf(staleEvent("OKAY", packet)))
        stream.remoteId = packet.arg0
        if (stream.opened) {
            // Подтверждение нашей записи: устройство готово принимать дальше.
            return AdbRouterStep(events = listOf(AdbStreamEvent.Acknowledged(packet.arg1)))
        }
        stream.opened = true
        return AdbRouterStep(
            events = listOf(AdbStreamEvent.Opened(packet.arg1, packet.arg0)),
        )
    }

    private fun onWrite(packet: AdbPacket): AdbRouterStep {
        val stream = streams[packet.arg1] ?: return staleStep("WRTE", packet)
        stream.remoteId = packet.arg0
        val acknowledgement = AdbOutboundPacket(AdbCommand.OKAY, packet.arg1, packet.arg0)
        if (!stream.opened) {
            return AdbRouterStep(
                outbound = listOf(acknowledgement),
                events = listOf(
                    AdbStreamEvent.Stale("early WRTE on stream ${packet.arg1}: ${packet.payload.size} bytes"),
                ),
            )
        }
        return AdbRouterStep(
            outbound = listOf(acknowledgement),
            events = listOf(AdbStreamEvent.Data(packet.arg1, packet.payload)),
        )
    }

    private fun onClose(packet: AdbPacket): AdbRouterStep {
        val stream = streams.remove(packet.arg1) ?: return staleStep("CLSE", packet)
        val remoteId = if (packet.arg0 > 0) packet.arg0 else stream.remoteId
        val reply = if (remoteId > 0) {
            listOf(AdbOutboundPacket(AdbCommand.CLSE, packet.arg1, remoteId))
        } else {
            emptyList()
        }
        val reason = if (stream.opened) AdbStreamClosure.COMPLETED else AdbStreamClosure.REJECTED
        return AdbRouterStep(
            outbound = reply,
            events = listOf(AdbStreamEvent.Closed(packet.arg1, reason)),
        )
    }

    /**
     * Ответ на пакет несуществующего потока.
     *
     * `CLSE` его же идентификаторами: устройство должно узнать, что потока нет.
     */
    private fun staleStep(command: String, packet: AdbPacket): AdbRouterStep {
        val reply = if (packet.arg0 > 0 && packet.arg1 > 0) {
            listOf(AdbOutboundPacket(AdbCommand.CLSE, packet.arg1, packet.arg0))
        } else {
            emptyList()
        }
        return AdbRouterStep(outbound = reply, events = listOf(staleEvent(command, packet)))
    }

    private fun staleEvent(command: String, packet: AdbPacket): AdbStreamEvent.Stale =
        AdbStreamEvent.Stale("$command for unknown stream local=${packet.arg1} remote=${packet.arg0}")

    private class StreamState {
        var opened: Boolean = false
        var remoteId: Int = 0
    }

    private companion object {
        /** Нулевой идентификатор в ADB означает «нет потока», поэтому счёт с единицы. */
        const val FIRST_LOCAL_ID = 1
    }
}
