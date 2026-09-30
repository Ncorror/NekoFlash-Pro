package io.github.ncorror.nekoflash.protocol.fastboot

/** Какое поле про раздел устройство назвало. */
public enum class FastbootPartitionField {
    /** `partition-size:<имя>` */
    SIZE,

    /** `partition-type:<имя>` */
    TYPE,

    /** `is-logical:<имя>` */
    LOGICAL,

    /** `has-slot:<имя>` */
    HAS_SLOT,
}

/**
 * Что известно про один раздел.
 *
 * [concrete] отвечает на вопрос, который легко проглядеть: **является ли
 * упоминание раздела доказательством его существования**. Взято из A2
 * (`FastbootPartitionInventory.hasConcreteEvidence`): доказательством считаются
 * размер, тип и признак логического, но **не** `has-slot`. Причина в том, что
 * `has-slot` устройства отвечают и про имена, которых у них нет, — и собрать
 * инвентарь по нему значило бы выдумать разделы, а потом показать их оператору
 * как настоящие.
 */
public data class FastbootPartition(
    val name: String,
    val sizeBytes: Long? = null,
    val type: String? = null,
    val logical: Boolean? = null,
    val hasSlot: Boolean? = null,
    val fields: Set<FastbootPartitionField> = emptySet(),
) {
    /** Есть ли существенное свидетельство, что раздел существует. */
    public val concrete: Boolean
        get() = fields.any { it != FastbootPartitionField.HAS_SLOT }
}

/**
 * Разделы, о которых устройство рассказало само.
 *
 * **Инвентарь — это пересказ ответа устройства, а не карта хранилища.** Два
 * свойства обязаны быть видны снаружи, иначе он будет врать молча:
 *
 * - [complete] — был ли полон сам ответ. Инвентарь, собранный из оборванного
 *   `getvar:all`, неполон по построению, и выдать его за полный значило бы
 *   соврать о том, чего у устройства нет;
 * - отсутствие раздела в списке **не означает**, что раздела нет. Устройство
 *   перечисляет то, что считает нужным, и `03` §3 запрещает выводить небытие
 *   из молчания.
 */
public data class FastbootPartitionInventory(
    val partitions: List<FastbootPartition>,
    val complete: Boolean,
) {
    /** Разделы, существование которых подтверждено чем-то кроме `has-slot`. */
    public val concrete: List<FastbootPartition> get() = partitions.filter { it.concrete }

    /** Раздел по имени без учёта регистра, либо `null`. */
    public fun partition(name: String): FastbootPartition? =
        partitions.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
}

/**
 * Сборка инвентаря разделов из ответа `getvar:all`.
 *
 * Пункт намеренно **не** делался в Phase 5: там разбор `getvar:all` отвечал за
 * то, что переменные прочитаны верно, и приписать ему ещё и толкование смысла
 * значило бы смешать два разных обязательства. Здесь толкование и живёт.
 *
 * Семейства имён взяты из архивов и совпали дословно — A2
 * `FastbootGetVarAllParser.metadataFieldForKey` и Legacy
 * `FastbootPartitionProbePlanner`: `partition-size:`, `partition-type:`,
 * `is-logical:`, `has-slot:`. Незнакомое двухсоставное имя в инвентарь не
 * попадает и остаётся переменной — догадываться, что оно про раздел, нечем.
 */
public object FastbootPartitionIndex {
    /** Собирает инвентарь из [snapshot]. Полнота наследуется от самого ответа. */
    public fun of(snapshot: FastbootVariableSnapshot): FastbootPartitionInventory {
        val collected = linkedMapOf<String, FastbootPartition>()

        snapshot.variables.forEach { (key, value) ->
            val field = fieldOf(key) ?: return@forEach
            val name = key.substringAfter(':').trim().lowercase()
            if (name.isNotBlank()) {
                collected[name] = merge(collected[name] ?: FastbootPartition(name), field, value)
            }
        }

        return FastbootPartitionInventory(
            partitions = collected.values.sortedBy { it.name },
            complete = snapshot.complete,
        )
    }

    private fun merge(
        partition: FastbootPartition,
        field: FastbootPartitionField,
        value: String,
    ): FastbootPartition {
        val fields = partition.fields + field
        return when (field) {
            FastbootPartitionField.SIZE -> partition.copy(sizeBytes = FastbootSize.of(value), fields = fields)
            FastbootPartitionField.TYPE ->
                partition.copy(type = value.takeIf { it.isNotBlank() }, fields = fields)

            FastbootPartitionField.LOGICAL -> partition.copy(logical = booleanOf(value), fields = fields)
            FastbootPartitionField.HAS_SLOT -> partition.copy(hasSlot = booleanOf(value), fields = fields)
        }
    }

    private fun fieldOf(key: String): FastbootPartitionField? = when {
        key.startsWith("partition-size:") -> FastbootPartitionField.SIZE
        key.startsWith("partition-type:") -> FastbootPartitionField.TYPE
        key.startsWith("is-logical:") -> FastbootPartitionField.LOGICAL
        key.startsWith("has-slot:") -> FastbootPartitionField.HAS_SLOT
        else -> null
    }

    /** Написания «да» и «нет» из Legacy `FastbootGetVarAllParser.parseBoolean`. */
    private fun booleanOf(raw: String): Boolean? = when (raw.trim().lowercase()) {
        in setOf("yes", "true", "1") -> true
        in setOf("no", "false", "0") -> false
        else -> null
    }
}

/**
 * Команды управления динамическими разделами.
 *
 * Четыре имени взяты из Legacy `isLogicalPartitionManagementCommand` — других
 * там нет, и добавлять по догадке нечего.
 *
 * **Legacy предупреждает, что они обычно требуют `fastbootd`, и не запрещает**
 * («Dynamic partitions usually require userspace fastbootd. Run: fastboot reboot
 * fastboot»). Мы поступаем так же: роль показывается рядом, решение принимает
 * устройство.
 *
 * Чего из Legacy **не** берём: там `runLogicalPartitionCommand` отказывается
 * выполнять команду, не входящую в эти четыре. Для собственного помощника это
 * законно — он о другом, — но на общий путь такую проверку переносить нельзя:
 * набранное в консоли уходит как набрано (`01` §3).
 */
public object FastbootLogicalPartitions {
    /** Меняет ли команда разметку super. */
    public fun manages(command: String): Boolean {
        val clean = command.trim().lowercase()
        return PREFIXES.any { clean.startsWith(it) }
    }

    /** Имена переменных, которыми устройство описывает один раздел. */
    public fun describe(partition: String): List<String> {
        val name = partition.trim()
        return listOf("partition-size:$name", "partition-type:$name", "is-logical:$name", "has-slot:$name")
    }

    private val PREFIXES = listOf(
        "create-logical-partition:",
        "delete-logical-partition:",
        "resize-logical-partition:",
        "update-super:",
    )
}
