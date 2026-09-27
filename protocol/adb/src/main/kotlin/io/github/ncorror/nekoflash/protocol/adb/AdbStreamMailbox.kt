package io.github.ncorror.nekoflash.protocol.adb

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** Почему поток больше ничего не принесёт. */
public enum class AdbMailboxEnd {
    /** Устройство закрыло поток после того, как подтвердило его открытие. */
    COMPLETED,

    /** Устройство закрыло поток, ни разу не подтвердив открытие: сервиса нет или он отказал. */
    REJECTED,

    /** Поток закрыт по нашей просьбе. */
    LOCAL,

    /**
     * Потребитель не успевал, и ящик переполнился.
     *
     * Поток закрывается, а не теряет часть содержимого молча: отдать вызывающему
     * вывод с дырой значило бы солгать о результате
     * (`03_PROTOCOL_AND_SAFETY_INVARIANTS_RU.md` §8).
     */
    OVERFLOWED,

    /** Кадр протокола потерян: соединение недостоверно целиком, а не только этот поток. */
    FRAMING_LOST,

    /** Интерфейс больше не удерживается. */
    TRANSPORT_CLOSED,
}

/** Что достали из ящика. */
public sealed interface AdbMailboxItem {
    /** Устройство подтвердило открытие и назвало свой идентификатор. */
    public data class Opened(val remoteId: Int) : AdbMailboxItem

    /** Данные потока. */
    public data class Data(val payload: ByteArray) : AdbMailboxItem

    /**
     * Устройство подтвердило **нашу** запись.
     *
     * Приходит только в ящик, который об этом просил: см. `acknowledgements` у
     * [AdbStreamDispatcher.open]. Остальным потокам подтверждения не нужны, а
     * место в ящике они занимали бы наравне с выводом — и ящик `logcat`
     * переполнялся бы вдвое быстрее из-за того, чего никто не читает.
     */
    public data object Acknowledged : AdbMailboxItem

    /** Поток кончился. После этого ящик не принесёт больше ничего. */
    public data class Ended(val reason: AdbMailboxEnd, val detail: String) : AdbMailboxItem
}

/**
 * Ящик одного логического потока ADB.
 *
 * Читающий цикл кладёт сюда события, потребитель забирает. Ящик существует
 * ровно для того, чтобы потребитель ждал **свой** поток, а не транспорт: пока
 * цикл читает транспорт один, параллельных потребителей может быть сколько
 * угодно (`docs/adr/0004_CONCURRENT_ADB_DISPATCHER_RU.md`).
 *
 * **Объём ограничен намеренно.** `03_PROTOCOL_AND_SAFETY_INVARIANTS_RU.md` §4
 * требует ограниченного и наблюдаемого backpressure. Ящик без предела означал
 * бы, что медленный потребитель распухает в памяти, пока `logcat` льёт вывод.
 *
 * Переполнение **закрывает поток**, а не отбрасывает содержимое. Молча
 * потерянный кусок вывода превратил бы результат в правдоподобную ложь, а
 * `03` §8 это запрещает прямо.
 *
 * **Конец доставляется всегда.** [AdbMailboxItem.Ended] хранится отдельно от
 * очереди и не соперничает с ней за место: иначе переполненный ящик не смог бы
 * сообщить о собственном переполнении, и потребитель ждал бы вечно.
 */
public class AdbStreamMailbox internal constructor(
    /** Идентификатор потока, которому принадлежит ящик. */
    public val localId: Int,
    private val capacity: Int = DEFAULT_CAPACITY,
    /**
     * Просил ли потребитель подтверждения наших записей.
     *
     * Признак живёт здесь, а не отдельным списком у диспетчера, намеренно: два
     * хранилища пришлось бы согласовывать при каждом закрытии, и рассинхрон
     * означал бы подтверждения в ящике, который их не ждёт.
     */
    internal val acknowledgements: Boolean = false,
    private val elapsedNanos: () -> Long = { System.nanoTime() },
) {
    init {
        require(capacity > 0) { "Mailbox capacity must be positive: $capacity" }
    }

    private val items = ArrayBlockingQueue<AdbMailboxItem>(capacity)

    private val openedAtNanos = elapsedNanos()

    /**
     * Сколько событий ящик принял.
     *
     * Меняется только в [offer], а его зовёт цикл раскладки — он один.
     */
    private var delivered = 0L

    @Volatile
    private var terminal: AdbMailboxItem.Ended? = null

    /** Кончился ли поток. Содержимое, принятое до конца, при этом ещё можно забрать. */
    public val ended: Boolean
        get() = terminal != null

    /**
     * Забирает следующее, ожидая не дольше указанного.
     *
     * `null` означает, что за отведённое время ничего не пришло, а поток жив.
     * Принятое до конца отдаётся раньше самого конца: обрыв не должен съедать
     * уже полученный вывод.
     */
    public fun poll(timeoutMillis: Long): AdbMailboxItem? {
        require(timeoutMillis >= 0) { "Mailbox timeout must not be negative: $timeoutMillis" }
        // Принятое отдаётся раньше конца, а после конца ждать уже нечего.
        val ready = items.poll() ?: terminal
        return ready ?: items.poll(timeoutMillis, TimeUnit.MILLISECONDS) ?: terminal
    }

    /**
     * Кладёт событие потока.
     *
     * Возвращает `false`, если места нет: решение о судьбе потока принимает
     * вызывающий, потому что оно требует отправить `CLSE` устройству, а ящик
     * ничего не отправляет.
     */
    internal fun offer(item: AdbMailboxItem): Boolean {
        if (terminal != null) return true
        val accepted = items.offer(item)
        if (accepted) delivered += 1
        return accepted
    }

    /**
     * Измеренный темп: сколько принято и за какое время.
     *
     * Нужен там, где ящик переполнился. Гейт `07` §6.39 требует выбирать объём
     * **по измеренному темпу**, а не подбором, — а измерить его больше негде:
     * события на каждый пакет никто не пишет, да и писать их при `logcat`
     * значило бы утопить журнал ровно тем, что мы изучаем.
     */
    internal fun rateDetail(): String {
        val elapsedMillis = (elapsedNanos() - openedAtNanos) / NANOS_PER_MILLI
        val perSecond = if (elapsedMillis > 0) delivered * MILLIS_PER_SECOND / elapsedMillis else 0
        return "delivered=$delivered in ${elapsedMillis}ms (~$perSecond/s) capacity=$capacity"
    }

    /**
     * Отмечает конец потока.
     *
     * Первая причина побеждает: она точнее описывает, что произошло на самом
     * деле, — так же, как в `UsbSessionRegistry` при повторном закрытии сессии.
     *
     * Конец кладётся **и** в очередь. Одной записи поля мало: потребитель,
     * уснувший на пустой очереди, полем не будится и досидел бы до таймаута —
     * ровно то, ради чего ящик и заведён. Если места нет, потребитель и не
     * спит: в очереди есть что забрать, а конец дождётся его снаружи очереди.
     */
    internal fun end(reason: AdbMailboxEnd, detail: String) {
        if (terminal != null) return
        val ended = AdbMailboxItem.Ended(reason, detail)
        terminal = ended
        items.offer(ended)
    }

    public companion object {
        /**
         * Сколько событий помещается в ящик по умолчанию.
         *
         * Значение выбрано от размера кадра: при `maxdata` в 1 МиБ и блоках
         * `WRTE` обычного размера этого хватает на заметную паузу потребителя,
         * не давая памяти расти без предела.
         */
        public const val DEFAULT_CAPACITY: Int = 64

        private const val NANOS_PER_MILLI = 1_000_000L
        private const val MILLIS_PER_SECOND = 1_000L
    }
}
