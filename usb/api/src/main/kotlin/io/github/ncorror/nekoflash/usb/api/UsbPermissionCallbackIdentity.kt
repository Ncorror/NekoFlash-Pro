package io.github.ncorror.nekoflash.usb.api

/**
 * Уникальное имя действия для ответа системы на запрос USB-разрешения.
 *
 * Тонкое место, перенесённое из A2 (`UsbPermissionCallbackIdentity`) без
 * изменения смысла. Идентичность отложенного намерения на Android **не
 * учитывает дополнительные данные**, поэтому различать активации приходится
 * самим именем действия. Без этого ответ, созданный до перезапуска владельца
 * USB, совпал бы с приёмником новой активации.
 *
 * Токен процесса добавлен по той же причине уровнем выше: ответ, созданный до
 * гибели процесса, не должен совпасть с приёмником в новом процессе.
 *
 * Класс не потокобезопасен: активация происходит в одном месте владения USB.
 */
public class UsbPermissionCallbackIdentity(
    private val actionPrefix: String,
    private val processToken: String,
) {
    init {
        require(actionPrefix.isNotBlank()) { "Action prefix must not be blank" }
        require(processToken.isNotBlank()) { "Process token must not be blank" }
    }

    private var activation = 0L

    /** Номер последней выданной активации. Ноль означает, что ни одной ещё не было. */
    public val currentActivation: Long
        get() = activation

    /**
     * Имя действия для новой активации владельца USB.
     *
     * Каждый вызов даёт новое имя: две активации не могут разделить одно
     * действие, иначе приёмник прошлой активации перехватил бы ответ текущей.
     */
    public fun nextAction(): String {
        activation += 1L
        return actionFor(activation)
    }

    /**
     * Относится ли действие к текущей активации.
     *
     * Ответ, пришедший с чужим именем действия, игнорируется молча: это не
     * ошибка, а опоздавшее сообщение прошлой активации или другого процесса.
     */
    public fun matchesCurrent(action: String?): Boolean =
        activation > 0L && action == actionFor(activation)

    private fun actionFor(value: Long): String = "$actionPrefix.$processToken.$value"
}
