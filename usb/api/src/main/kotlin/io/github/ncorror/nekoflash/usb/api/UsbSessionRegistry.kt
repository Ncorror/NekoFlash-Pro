package io.github.ncorror.nekoflash.usb.api

import io.github.ncorror.nekoflash.core.model.SessionGeneration
import io.github.ncorror.nekoflash.core.model.TargetId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Владение USB-сессиями на уровне приложения.
 *
 * Реестр решает три задачи и только их: выдаёт generation, хранит текущее
 * состояние каждой сессии и не даёт завершённой сессии ожить. Он ничего не
 * знает ни об Android, ни об операциях, ни о протоколах.
 *
 * Диагностику реестр не пишет сам: он возвращает достаточно подробные
 * результаты, чтобы вызывающий слой построил событие с нужным контекстом.
 * Событие принадлежит тому, кто знает повод, — реестр знает только переход.
 * Сам модуль на `core:diagnostics` зависит: события пишет `UsbSessionCoordinator`,
 * а разделы evidence собирает `UsbDiagnosticReport`.
 *
 * Реализация потокобезопасна: переходы состояний — это чтение и запись,
 * которые обязаны быть атомарными относительно друг друга.
 */
public class UsbSessionRegistry(
    /**
     * Сколько завершённых сессий помнить.
     *
     * Память о недавно завершённых сессиях нужна, чтобы отличить «такой сессии
     * никогда не было» от «сессия завершилась вот по этой причине». Без неё
     * обращение по устаревшей generation после отключения устройства выглядело
     * бы как неизвестная ошибка вместо честного «устройство отключено».
     */
    private val closedHistoryLimit: Int = DEFAULT_CLOSED_HISTORY_LIMIT,
) {
    init {
        require(closedHistoryLimit >= 0) { "closedHistoryLimit must not be negative" }
    }

    private val lock = Any()
    private val generations = AtomicLong(0L)
    private val active = LinkedHashMap<Long, UsbSession>()
    private val closed = LinkedHashMap<Long, UsbSession>()
    private val activeState = MutableStateFlow<List<UsbSession>>(emptyList())

    /**
     * Незавершённые сессии в порядке открытия.
     *
     * Наблюдаемое состояние, а не снимок по запросу: слой выше подписывается
     * один раз и видит каждое изменение, вместо того чтобы опрашивать реестр и
     * рисковать пропустить короткоживущую сессию. Текущее значение доступно как
     * `activeSessions.value`.
     */
    public val activeSessions: StateFlow<List<UsbSession>> = activeState.asStateFlow()

    /**
     * Открывает новую сессию для target и выдаёт ей новую generation.
     *
     * Если для этого target уже была незавершённая сессия, она закрывается с
     * причиной [UsbSessionClosureReason.SUPERSEDED]: одно физическое
     * подключение к одному target одновременно — это владение ресурсом, а не
     * ограничение возможностей пользователя.
     *
     * Generation монотонна в пределах процесса и общая для всех target, поэтому
     * её значение однозначно указывает на конкретное событие подключения в
     * журнале диагностики.
     */
    public fun open(
        identity: UsbTargetIdentity,
        candidate: UsbInterfaceCandidate,
    ): UsbSession = synchronized(lock) {
        activeFor(identity.id)?.let { previous ->
            closeLocked(previous, UsbSessionClosureReason.SUPERSEDED)
        }
        val session = UsbSession(
            generation = SessionGeneration(generations.incrementAndGet()),
            targetId = identity.id,
            identity = identity,
            candidate = candidate,
            state = UsbSessionState.DISCOVERED,
        )
        active[session.generation.value] = session
        publishLocked()
        session
    }

    /**
     * Обновляет сведения о подключении, не меняя состояние сессии.
     *
     * Нужен после выдачи разрешения: платформа отдаёт новый объект дескриптора,
     * а серийный номер до этого момента мог быть недоступен. Идентичность при
     * этом уточняется, но не подменяется — если серийный номер уже был известен
     * и новый ему противоречит, прежнее значение сохраняется, потому что
     * расхождение означает другое устройство, а решать это должен слой выше.
     *
     * Generation не меняется: подключение то же самое.
     */
    public fun refresh(
        generation: SessionGeneration,
        candidate: UsbInterfaceCandidate,
    ): UsbSessionTransition = synchronized(lock) {
        val session = active[generation.value]
            ?: return@synchronized rejectionFor(generation)
        val serial = candidate.device.normalizedSerialNumber
        val updated = session.copy(
            candidate = candidate,
            identity = if (serial != null) session.identity.refinedWithSerial(serial) else session.identity,
        ).let { it.copy(targetId = it.identity.id) }
        active[generation.value] = updated
        publishLocked()
        UsbSessionTransition.Applied(updated)
    }

    /** Отмечает, что разрешение запрошено и ответ ещё не получен. */
    public fun markPermissionPending(generation: SessionGeneration): UsbSessionTransition =
        transition(generation, UsbSessionState.PERMISSION_PENDING)

    /**
     * Отмечает, что разрешение получено.
     *
     * Допустимо и без предшествующего [markPermissionPending]: разрешение на
     * устройство могло быть выдано ранее, и запрашивать его повторно незачем.
     */
    public fun markReady(generation: SessionGeneration): UsbSessionTransition =
        transition(generation, UsbSessionState.READY)

    /** Отмечает, что интерфейс захвачен и транспорт пригоден к использованию. */
    public fun markClaimed(generation: SessionGeneration): UsbSessionTransition =
        transition(generation, UsbSessionState.CLAIMED)

    /**
     * Завершает сессию.
     *
     * Повторное закрытие уже завершённой сессии отклоняется с
     * [UsbSessionRejection.ALREADY_CLOSED], а исходная причина завершения не
     * подменяется: первая причина точнее описывает, что произошло на самом деле.
     */
    public fun close(
        generation: SessionGeneration,
        reason: UsbSessionClosureReason,
    ): UsbSessionTransition = synchronized(lock) {
        val session = active[generation.value]
            ?: return@synchronized UsbSessionTransition.Rejected(
                session = closed[generation.value],
                reason = if (closed.containsKey(generation.value)) {
                    UsbSessionRejection.ALREADY_CLOSED
                } else {
                    UsbSessionRejection.UNKNOWN_GENERATION
                },
            )
        UsbSessionTransition.Applied(closeLocked(session, reason))
    }

    /**
     * Завершает все сессии, относящиеся к отключённому подключению.
     *
     * Сопоставление ведётся по дескриптору подключения, а не по идентичности
     * target: отключиться может устройство, чей серийный номер так и не стал
     * известен.
     */
    public fun closeDetached(detached: UsbDeviceDescriptor): List<UsbSession> =
        synchronized(lock) {
            active.values
                .filter { isSameAttachment(it.candidate.device, detached) }
                .map { closeLocked(it, UsbSessionClosureReason.DETACHED) }
        }

    /**
     * Недавно завершённые сессии, от самой старой к самой новой.
     *
     * Нужны отчёту: оператор обычно отключает устройство прежде, чем выгружать
     * диагностику, и без этого списка снимок сессий оказывался бы пустым именно
     * тогда, когда он интереснее всего.
     */
    public fun recentlyClosedSessions(): List<UsbSession> = synchronized(lock) {
        closed.values.toList()
    }

    /** Текущее состояние сессии, включая недавно завершённые. */
    public fun session(generation: SessionGeneration): UsbSession? = synchronized(lock) {
        active[generation.value] ?: closed[generation.value]
    }

    /** Незавершённая сессия для target, если она есть. */
    public fun activeSession(targetId: TargetId): UsbSession? = synchronized(lock) {
        activeFor(targetId)
    }

    private fun rejectionFor(generation: SessionGeneration): UsbSessionTransition.Rejected {
        val remembered = closed[generation.value]
        return UsbSessionTransition.Rejected(
            session = remembered,
            reason = if (remembered == null) {
                UsbSessionRejection.UNKNOWN_GENERATION
            } else {
                UsbSessionRejection.ALREADY_CLOSED
            },
        )
    }

    private fun transition(
        generation: SessionGeneration,
        target: UsbSessionState,
    ): UsbSessionTransition = synchronized(lock) {
        val session = active[generation.value]
            ?: return@synchronized rejectionFor(generation)
        if (!UsbSessionStateMachine.allows(session.state, target)) {
            return@synchronized UsbSessionTransition.Rejected(
                session = session,
                reason = UsbSessionRejection.ILLEGAL_TRANSITION,
            )
        }
        val updated = session.copy(state = target)
        active[generation.value] = updated
        publishLocked()
        UsbSessionTransition.Applied(updated)
    }

    private fun publishLocked() {
        activeState.value = active.values.toList()
    }

    private fun activeFor(targetId: TargetId): UsbSession? =
        active.values.firstOrNull { it.targetId == targetId }

    private fun closeLocked(
        session: UsbSession,
        reason: UsbSessionClosureReason,
    ): UsbSession {
        val closedSession = session.copy(
            state = UsbSessionState.CLOSED,
            closureReason = reason,
        )
        active.remove(session.generation.value)
        publishLocked()
        if (closedHistoryLimit > 0) {
            closed[session.generation.value] = closedSession
            while (closed.size > closedHistoryLimit) {
                val oldest = closed.keys.first()
                closed.remove(oldest)
            }
        }
        return closedSession
    }

    public companion object {
        /** Сколько завершённых сессий помнится по умолчанию. */
        public const val DEFAULT_CLOSED_HISTORY_LIMIT: Int = 32
    }
}
