package io.github.ncorror.nekoflash.protocol.fastboot

/**
 * В какой роли отвечает устройство по Fastboot.
 *
 * Дескриптор этого не знает и знать не может: и загрузчик, и `fastbootd`
 * перечисляются одинаковым интерфейсом Fastboot-класса (`02` §11). Роль
 * устанавливает только протокольный обмен — отсюда и весь этот файл.
 */
public enum class FastbootMode {
    /** Загрузчик устройства. */
    BOOTLOADER,

    /** `fastbootd` — тот же протокол, но из userspace, уже после загрузки ядра. */
    FASTBOOTD,

    /**
     * Роль не установлена.
     *
     * **Отдельное состояние, а не «значит загрузчик».** Разница не
     * косметическая: в `fastbootd` работают операции с динамическими разделами,
     * которых в загрузчике нет, и наоборот. Догадка здесь выйдет боком ровно
     * тогда, когда оператор на неё положится. Legacy пришёл к тому же:
     * `ConnectionModeUiPolicy` принимает `Boolean?` и на `null` показывает
     * `FASTBOOT_UNKNOWN`, а не подставляет загрузчик.
     */
    UNKNOWN,
}

/**
 * Роль устройства и то, чем она установлена.
 *
 * [detail] хранится всегда, включая успех: по нему видно, на чём основан
 * вывод — на ответе устройства или на его отсутствии.
 */
public data class FastbootIdentity(
    val mode: FastbootMode,
    val detail: String,
) {
    /** Установлена ли роль. Удобнее, чем сравнивать с [FastbootMode.UNKNOWN] на месте. */
    public val resolved: Boolean get() = mode != FastbootMode.UNKNOWN
}

/**
 * Различение загрузчика и `fastbootd`.
 *
 * **Признак — `getvar:is-userspace`, и он взят из архива, а не из общего
 * знания протокола.** Legacy различает роли ровно по нему
 * (`FastbootProtocol.logDiagnostics`: `yes` → «fastbootd / userspace`,
 * `no` → «bootloader fastboot»), и по нему же строит режим для экрана
 * (`DeviceViewModel` → `ConnectionModeUiPolicy.resolve`).
 *
 * **Отказ загрузчика — это тоже «неизвестно», а не «загрузчик».** Старые
 * загрузчики переменной не знают и отвечают `FAIL`. Соблазн прочитать это как
 * «значит, не userspace» силён и почти всегда верен — но «почти» здесь
 * недостаточно: вывод из отсутствия ответа не является наблюдением. Поэтому
 * `FAIL` даёт [FastbootMode.UNKNOWN] с названной причиной, а не догадку.
 */
public object FastbootModeProbe {
    /** Имя переменной, по которой различаются роли. */
    public const val VARIABLE: String = "is-userspace"

    /** Спрашивает устройство и переводит ответ в роль. */
    public fun probe(getVar: FastbootGetVar): FastbootIdentity = of(getVar.read(VARIABLE))

    /** Переводит уже прочитанную переменную в роль. Выделено ради проверяемости без транспорта. */
    public fun of(variable: FastbootVariable): FastbootIdentity = when (variable) {
        is FastbootVariable.Present -> fromValue(variable.value)

        is FastbootVariable.Unsupported ->
            FastbootIdentity(FastbootMode.UNKNOWN, "устройство не знает $VARIABLE: ${variable.detail}")

        is FastbootVariable.Unavailable ->
            FastbootIdentity(FastbootMode.UNKNOWN, "спросить не удалось: ${variable.detail}")
    }

    private fun fromValue(value: String): FastbootIdentity {
        val normalized = value.trim().lowercase()
        return when (normalized) {
            "yes" -> FastbootIdentity(FastbootMode.FASTBOOTD, "$VARIABLE=yes")
            "no" -> FastbootIdentity(FastbootMode.BOOTLOADER, "$VARIABLE=no")

            // Ни то, ни другое: устройство ответило чем-то третьим. Текст
            // сохраняется целиком — по нему будет видно, что наблюдение было
            // неполным, а по подогнанному ответу не было бы видно ничего.
            else -> FastbootIdentity(
                FastbootMode.UNKNOWN,
                "$VARIABLE=${value.ifBlank { "<пусто>" }} — ни yes, ни no",
            )
        }
    }
}
