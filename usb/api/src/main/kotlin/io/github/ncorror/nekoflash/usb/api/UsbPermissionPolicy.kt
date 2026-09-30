package io.github.ncorror.nekoflash.usb.api

/**
 * Чистые решения по USB-разрешению, без единого обращения к платформе.
 *
 * Запрос разрешения, его ожидание и таймер принадлежат владельцу USB на стороне
 * Android. Здесь определяется только то, к какой сессии относится пришедший
 * ответ и что из этого следует.
 *
 * Логика сопоставления перенесена из проверенного A2 (`UsbPermissionPolicy`),
 * но опирается на сессии, а не на отдельный реестр ожидающих запросов: два
 * источника истины о том же самом рано или поздно разойдутся.
 */
public object UsbPermissionPolicy {
    /**
     * Сколько ждать ответа на запрос разрешения.
     *
     * Значение унаследовано от Legacy через A2 и подтверждено на реальных
     * устройствах. Это не ограничение возможностей, а защита от бесконечно
     * висящего запроса, на который система уже не ответит.
     */
    public const val RESPONSE_TIMEOUT_MS: Long = 30_000L

    /** Что делать с ответом системы на запрос разрешения. */
    public sealed interface Decision {
        /**
         * Разрешение получено, можно продолжать.
         *
         * [session] — сессия, к которой относится ответ, либо `null`, если
         * ожидающей сессии не нашлось: система могла выдать разрешение помимо
         * нашего запроса, и в этом случае сессию нужно открыть заново.
         *
         * [candidate] всегда получен из свежего дескриптора: платформа отдаёт
         * новый объект вместе с ответом, и прежний выбор интерфейса надо
         * привязать к нему заново.
         */
        public data class Proceed(
            val session: UsbSession?,
            val candidate: UsbInterfaceCandidate,
        ) : Decision

        /** Пользователь отклонил запрос для этой сессии. */
        public data class Denied(val session: UsbSession) : Decision

        /** Ответ пришёл без дескриптора устройства: сопоставить его не с чем. */
        public data object MissingDevice : Decision

        /** Устройство есть, но пригодного интерфейса у него не нашлось. */
        public data object NoCandidate : Decision

        /** Отказ пришёл, но ожидающей сессии для него нет. */
        public data object UnmatchedDenial : Decision
    }

    /**
     * Сопоставляет ответ системы с ожидающей сессией и решает, что дальше.
     *
     * @param pending сессии в состоянии [UsbSessionState.PERMISSION_PENDING],
     *   **в порядке их открытия**. Порядок значим: при сопоставлении по имени
     *   подключения берётся первая подходящая, и это поведение проверено в A2.
     * @param device дескриптор из ответа системы. Может отсутствовать.
     * @param granted выдано ли разрешение.
     */
    public fun resolve(
        pending: List<UsbSession>,
        device: UsbDeviceDescriptor?,
        granted: Boolean,
    ): Decision {
        if (device == null) {
            return if (granted) Decision.MissingDevice else Decision.UnmatchedDenial
        }

        val matched = matchPending(pending, device)
        if (!granted) {
            return matched?.let(Decision::Denied) ?: Decision.UnmatchedDenial
        }

        val candidate = matched
            ?.let { UsbInterfaceClassifier.rebind(device, it.candidate) }
            ?: UsbInterfaceClassifier.primaryCandidate(device, allowGenericVendor = true)

        return candidate?.let { Decision.Proceed(matched, it) } ?: Decision.NoCandidate
    }

    /**
     * Ожидающая сессия, к которой относится ответ.
     *
     * Точное совпадение идентификатора подключения предпочитается. Если его
     * нет, берётся первая ожидающая сессия с тем же именем подключения: система
     * может ответить дескриптором, созданным под другим идентификатором, и
     * потерять из-за этого запрос нельзя.
     */
    public fun matchPending(
        pending: List<UsbSession>,
        device: UsbDeviceDescriptor,
    ): UsbSession? =
        pending.firstOrNull { it.candidate.device.deviceId == device.deviceId }
            ?: pending.firstOrNull { it.candidate.device.deviceName == device.deviceName }

    /** Что делать по истечении таймаута ожидания разрешения. */
    public enum class TimeoutOutcome {
        /** Ответа не было и разрешения нет: закрыть сессию и сообщить пользователю. */
        CLOSE_AND_REPORT,

        /**
         * Ответа не было, но разрешение к этому моменту уже есть.
         *
         * Сессия закрывается без сообщения об ошибке, и подключение **не**
         * происходит неявно. Поведение унаследовано от Legacy через A2 без
         * изменений: подключаться по факту, о котором система не сообщила, —
         * значит действовать по недоказанному состоянию.
         */
        CLOSE_SILENTLY,

        /** Ответ уже обработан: таймаут опоздал и ничего не меняет. */
        IGNORE,
    }

    /**
     * Решение по таймауту для конкретной сессии.
     *
     * @param session сессия, для которой сработал таймер, либо `null`, если
     *   такой сессии больше нет.
     * @param permissionGrantedNow есть ли разрешение на момент срабатывания.
     */
    public fun onTimeout(
        session: UsbSession?,
        permissionGrantedNow: Boolean,
    ): TimeoutOutcome = when {
        session == null || session.state != UsbSessionState.PERMISSION_PENDING -> TimeoutOutcome.IGNORE
        permissionGrantedNow -> TimeoutOutcome.CLOSE_SILENTLY
        else -> TimeoutOutcome.CLOSE_AND_REPORT
    }
}
