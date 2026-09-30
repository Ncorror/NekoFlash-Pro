package io.github.ncorror.nekoflash.protocol.fastboot

/**
 * Тексты команд Fastboot, которые меняют устройство.
 *
 * Собраны в одном месте не ради «списка разрешённого», а наоборот: типизованный
 * экран и произвольная консоль должны строить **одну и ту же строку**, иначе
 * «один движок» перестанет быть правдой при первом расхождении в написании.
 * Набрать руками можно что угодно, включая то, чего здесь нет (`01` §3).
 *
 * Формы взяты из Legacy `FastbootProtocol`, где мутирующие команды уходят
 * обычным `sendCommand`: `flash:$partition` (строка 972) после
 * `download:%08x` + фаза данных (строки 921–944), `set_active:$slot` (строка
 * 687). Ни Legacy, ни A2 не заводят под них отдельного транспорта, и мы не
 * заводим: это ordinary commands на той же единственной полосе (ADR-0006 §1).
 */
public object FastbootCommands {
    /** Записать содержимое буфера загрузки в раздел. */
    public fun flash(partition: String): String = "flash:${partition.trim()}"

    /** Стереть раздел. */
    public fun erase(partition: String): String = "erase:${partition.trim()}"

    /** Стереть раздел и создать на нём файловую систему. */
    public fun format(partition: String): String = "format:${partition.trim()}"

    /** Загрузиться из буфера загрузки, ничего не записывая. */
    public fun boot(): String = BOOT

    /** Переключить слот, с которого устройство загрузится. */
    public fun setActive(slot: String): String = "set_active:${FastbootSlots.normalize(slot) ?: slot.trim()}"

    /** Перезагрузка: пустая цель — обычная загрузка, иначе `reboot-<цель>`. */
    public fun reboot(target: String = ""): String = target.trim().lowercase().let { clean ->
        if (clean.isEmpty()) REBOOT else "$REBOOT-$clean"
    }

    private const val BOOT = "boot"
    private const val REBOOT = "reboot"
}

/**
 * Слоты A/B.
 *
 * Правила взяты из обоих архивов и совпали: A2
 * `FastbootFlashTargetPolicy.normalizeSlot` снимает ведущее подчёркивание и
 * ждёт одну строчную букву, Legacy при сверке `set_active` делает то же самое
 * (`removePrefix("_")`, `lowercase`) с обеих сторон сравнения. То есть `a`,
 * `_a` и `A` — один и тот же слот, и сравнивать их как строки нельзя.
 *
 * Суффикс приписывается к имени раздела **перед** двоеточной частью, если она
 * есть: A2 `applySlot` разрезает имя по первому `:` и собирает `boot_a:foo`.
 * Наивное `"$partition_$slot"` дало бы `boot:foo_a` — имя, которого у
 * устройства нет.
 */
public object FastbootSlots {
    /** Слот в каноничном виде, либо `null`, если это не слот. */
    public fun normalize(raw: String?): String? = raw
        ?.trim()
        ?.removePrefix("_")
        ?.lowercase()
        ?.takeIf { SLOT.matches(it) }

    /** Тот же слот? Сравнение по каноничному виду, а не по строке. */
    public fun same(left: String?, right: String?): Boolean {
        val first = normalize(left)
        return first != null && first == normalize(right)
    }

    /**
     * Имя раздела со слотом.
     *
     * Неразобранный слот возвращает имя нетронутым: подставлять догадку в имя
     * раздела нельзя, а отказываться от команды — не наше дело.
     */
    public fun apply(partition: String, slot: String?): String {
        val suffix = normalize(slot)
        val name = partition.trim()
        return if (suffix == null) {
            name
        } else {
            val pieces = name.split(':', limit = 2)
            val target = "${pieces[0]}_$suffix"
            if (pieces.size == 2) "$target:${pieces[1]}" else target
        }
    }

    private val SLOT = Regex("^[a-z]$")
}
