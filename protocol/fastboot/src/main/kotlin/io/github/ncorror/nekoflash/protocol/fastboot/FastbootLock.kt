package io.github.ncorror.nekoflash.protocol.fastboot

/**
 * Состояние замка загрузчика.
 *
 * **Это форма предупреждения, а не разрешение на команду** (`03` §5.1, D031).
 * Единственный product-level hard guard, блокировавший `flash:` при
 * подтверждённом `LOCKED`, отменён: отказ при `LOCKED` принадлежит классу
 * B (Device authority) — устройство отвечает `FAIL` само, — а guard подменял
 * его ответ решением хоста.
 */
public enum class FastbootLockState {
    /** Устройство сказало, что заперто. Единственное состояние, дающее typed confirmation. */
    LOCKED,

    /** Устройство сказало, что открыто. */
    UNLOCKED,

    /**
     * Не установлено — и это **не** `LOCKED`.
     *
     * Сюда попадают отказ, недоступность и ответ, который не читается ни как
     * «да», ни как «нет». Приравнять их к `LOCKED` значило бы вернуть запрет
     * через чёрный ход: `03` §5.1 прямо требует для них обычный advisory
     * **без** typed confirmation, иначе `UNKNOWN` снова становится `LOCKED`.
     */
    UNKNOWN,
}

/**
 * Замок и то, чем он установлен.
 *
 * [detail] хранится всегда, как и у роли: по нему видно, ответом устройства
 * состояние установлено или его молчанием.
 */
public data class FastbootLockStatus(
    val state: FastbootLockState,
    val detail: String,
) {
    /**
     * Требуется ли ввод слова подтверждения перед разрушающим действием.
     *
     * Только у подтверждённого `LOCKED`. Это не «можно/нельзя» — команда уйдёт
     * в обоих случаях, — а разница в том, насколько осознанным должно быть
     * подтверждение.
     */
    public val typedConfirmation: Boolean get() = state == FastbootLockState.LOCKED
}

/**
 * Чтение состояния замка.
 *
 * Имя переменной взято из Legacy `FastbootProtocol.refreshDiagnostics`, где
 * `unlocked` читается наравне с `secure` и `current-slot`. На железе оно
 * подтверждено прогонами `07` §6.72, §6.74 и §6.76: vayu отвечает
 * `unlocked = no`.
 *
 * Состояние принадлежит **текущей** `SessionGeneration` и переезду в новую не
 * подлежит: после перезагрузки старая generation инвалидируется, и замок
 * определяется заново (`03` §5.1). Поэтому здесь нет ни кэша, ни значения по
 * умолчанию — только перевод прочитанного ответа в состояние.
 */
public object FastbootLockProbe {
    /** Имя переменной, по которой читается замок. */
    public const val VARIABLE: String = "unlocked"

    /** Спрашивает устройство и переводит ответ в состояние замка. */
    public fun probe(getVar: FastbootGetVar): FastbootLockStatus = of(getVar.read(VARIABLE))

    /** Переводит уже прочитанную переменную. Выделено ради проверяемости без транспорта. */
    public fun of(variable: FastbootVariable): FastbootLockStatus = when (variable) {
        is FastbootVariable.Present -> fromValue(variable.value)

        // Устройство переменной не знает. Это ответ, но не про замок — и
        // «не знает» не равно «заперто».
        is FastbootVariable.Unsupported ->
            FastbootLockStatus(FastbootLockState.UNKNOWN, "устройство не знает $VARIABLE: ${variable.detail}")

        is FastbootVariable.Unavailable ->
            FastbootLockStatus(FastbootLockState.UNKNOWN, "спросить не удалось: ${variable.detail}")
    }

    private fun fromValue(value: String): FastbootLockStatus {
        val normalized = value.trim().lowercase()
        return when (normalized) {
            in YES -> FastbootLockStatus(FastbootLockState.UNLOCKED, "$VARIABLE=$normalized")
            in NO -> FastbootLockStatus(FastbootLockState.LOCKED, "$VARIABLE=$normalized")

            // Ответ есть, но он не читается ни как «да», ни как «нет».
            // Сохраняется целиком: по нему видно, что наблюдение неполное.
            else -> FastbootLockStatus(
                FastbootLockState.UNKNOWN,
                "$VARIABLE=${value.ifBlank { "<пусто>" }} — ни yes, ни no",
            )
        }
    }

    /** Написания «да» из Legacy `FastbootGetVarAllParser.parseBoolean`. */
    private val YES = setOf("yes", "true", "1")

    /** Написания «нет» оттуда же. */
    private val NO = setOf("no", "false", "0")
}
