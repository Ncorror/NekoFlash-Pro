package io.github.ncorror.nekoflash.usb.api

import io.github.ncorror.nekoflash.core.model.SessionGeneration
import io.github.ncorror.nekoflash.core.model.TargetId

/**
 * Состояние одного физического подключения к target.
 *
 * Это состояние **одной сессии USB**, а не общая стадия приложения. Единый
 * глобальный `Stage`, одинаковый для всех операций, запрещён продуктовыми
 * документами: он связывает несвязанные вещи и блокирует одну операцию из-за
 * другой. Здесь у каждой сессии своё состояние, и оно ничего не знает об
 * операциях, которые поверх неё выполняются.
 */
public enum class UsbSessionState(
    /**
     * Терминальное состояние необратимо. Сессия из него не возвращается ни при
     * каких условиях: устройство, появившееся снова, получает новую generation.
     */
    public val terminal: Boolean,
) {
    /** Дескриптор виден, разрешение ещё не запрашивалось. */
    DISCOVERED(terminal = false),

    /** Разрешение запрошено, ответа нет. */
    PERMISSION_PENDING(terminal = false),

    /** Разрешение есть, интерфейс ещё не захвачен. */
    READY(terminal = false),

    /** Интерфейс захвачен, транспорт пригоден к использованию. */
    CLAIMED(terminal = false),

    /** Сессия завершена. Причина — в [UsbSession.closureReason]. */
    CLOSED(terminal = true),
}

/** Почему сессия завершилась. Хранится ради диагностики и честного отчёта пользователю. */
public enum class UsbSessionClosureReason {
    /** Пользователь отклонил запрос разрешения. */
    PERMISSION_DENIED,

    /** Ответа на запрос разрешения не последовало. */
    PERMISSION_TIMED_OUT,

    /** Штатное освобождение интерфейса. */
    RELEASED,

    /** Устройство физически отключено или переподключено. */
    DETACHED,

    /** Для того же target открыта новая сессия. */
    SUPERSEDED,

    /** Транспорт отказал: сессия непригодна, продолжать по ней нельзя. */
    TRANSPORT_FAILURE,
}

/**
 * Одно физическое подключение к одному target.
 *
 * [generation] — идентичность самой сессии. Каждое attach, detach и
 * re-enumeration порождает новую generation, поэтому handle, полученный в
 * прошлой generation, не может быть применён в текущей.
 */
public data class UsbSession(
    val generation: SessionGeneration,
    val targetId: TargetId,
    val identity: UsbTargetIdentity,
    val candidate: UsbInterfaceCandidate,
    val state: UsbSessionState,
    val closureReason: UsbSessionClosureReason? = null,
) {
    init {
        require(state == UsbSessionState.CLOSED || closureReason == null) {
            "Only a closed session may carry a closure reason"
        }
        require(state != UsbSessionState.CLOSED || closureReason != null) {
            "A closed session must state why it closed"
        }
    }

    /** Сессия завершена и не оживёт. */
    public val closed: Boolean
        get() = state.terminal

    /** По сессии можно передавать данные прямо сейчас. */
    public val usable: Boolean
        get() = state == UsbSessionState.CLAIMED
}

/** Почему переход состояния не был применён. */
public enum class UsbSessionRejection {
    /** Такой generation нет: она никогда не существовала либо уже забыта. */
    UNKNOWN_GENERATION,

    /**
     * Сессия уже завершена.
     *
     * Это ожидаемый исход гонки с отключением устройства, а не ошибка
     * программиста: запрос мог быть отправлен до detach, а обработан после.
     */
    ALREADY_CLOSED,

    /** Переход не предусмотрен для текущего состояния сессии. */
    ILLEGAL_TRANSITION,
}

/** Результат попытки перевести сессию в другое состояние. */
public sealed interface UsbSessionTransition {
    /** Переход применён; [session] — новое состояние. */
    public data class Applied(val session: UsbSession) : UsbSessionTransition

    /**
     * Переход отклонён.
     *
     * [session] содержит текущее состояние, если сессия известна. Отклонение
     * никогда не «оживляет» завершённую сессию и не подменяет её новой: решение
     * о повторном подключении принимает слой выше.
     */
    public data class Rejected(
        val session: UsbSession?,
        val reason: UsbSessionRejection,
    ) : UsbSessionTransition
}

/**
 * Допустимые переходы состояний сессии.
 *
 * Вынесено отдельно от реестра, чтобы правила можно было проверять без
 * какого-либо владения ресурсами.
 */
public object UsbSessionStateMachine {
    private val allowed: Map<UsbSessionState, Set<UsbSessionState>> = mapOf(
        UsbSessionState.DISCOVERED to setOf(
            UsbSessionState.PERMISSION_PENDING,
            UsbSessionState.READY,
            UsbSessionState.CLOSED,
        ),
        UsbSessionState.PERMISSION_PENDING to setOf(
            UsbSessionState.READY,
            UsbSessionState.CLOSED,
        ),
        UsbSessionState.READY to setOf(
            UsbSessionState.CLAIMED,
            UsbSessionState.CLOSED,
        ),
        UsbSessionState.CLAIMED to setOf(
            UsbSessionState.READY,
            UsbSessionState.CLAIMED,
            UsbSessionState.CLOSED,
        ),
        UsbSessionState.CLOSED to emptySet(),
    )

    /**
     * Разрешён ли переход.
     *
     * Переход из [UsbSessionState.DISCOVERED] сразу в [UsbSessionState.READY]
     * допустим: разрешение на устройство может быть выдано заранее, и повторно
     * запрашивать его незачем.
     *
     * Возврат из [UsbSessionState.CLAIMED] в [UsbSessionState.READY] допустим:
     * освобождение интерфейса не заканчивает физическую сессию. Устройство
     * остаётся подключённым, разрешение остаётся выданным, generation та же —
     * меняется лишь то, держим мы интерфейс или нет. Терминальным по-прежнему
     * является только [UsbSessionState.CLOSED].
     *
     * Повторный захват уже захваченной сессии тоже допустим: он ничего не
     * меняет, но и не является ошибкой вызывающего.
     */
    public fun allows(from: UsbSessionState, to: UsbSessionState): Boolean =
        to in allowed.getValue(from)
}
