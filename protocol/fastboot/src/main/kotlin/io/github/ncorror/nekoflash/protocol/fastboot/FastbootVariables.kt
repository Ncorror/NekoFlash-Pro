package io.github.ncorror.nekoflash.protocol.fastboot

/** Переменная, названная устройством больше одного раза. */
public data class FastbootDuplicate(
    val name: String,
    val values: List<String>,
) {
    /**
     * Разошлись ли значения по существу.
     *
     * Повтор одного и того же значения — не конфликт, а особенность вывода.
     * Разные значения под одним именем — уже наблюдение, которое стоит видеть.
     */
    public val conflicting: Boolean
        get() = values.map { it.trim().lowercase() }.distinct().size > 1
}

/**
 * Всё, что устройство сказало на `getvar:all`.
 *
 * [ignored] хранится намеренно: строка, которую не удалось разобрать, — это
 * наблюдение о выводе устройства, а не мусор. Выбросить её значило бы выдать
 * неполный разбор за полный.
 */
public data class FastbootVariableSnapshot(
    val variables: Map<String, String>,
    val duplicates: List<FastbootDuplicate>,
    val ignored: List<String>,
    val complete: Boolean,
    val finalReply: FastbootReply,
    val finalPayload: String,
) {
    /** Значение по имени без учёта регистра. */
    public fun value(name: String): String? = variables[name.trim().lowercase()]
}

/**
 * Разбор вывода `getvar:all`.
 *
 * **Написан по обоим архивам, и они совпали.** Legacy
 * `FastbootGetVarAllParser.kt` и A2 `fastboot/codec/FastbootGetVarAllParser.kt`
 * устроены одинаково, вплоть до комментариев, и дают три вещи, которых по
 * памяти не восстановить:
 *
 * 1. **Строки несут транспортные префиксы, и снимать их надо в цикле.** Кроме
 *    `INFO` и `TEXT` встречается `(bootloader)` — тот самый, что показывает
 *    `fastboot` в консоли. Они комбинируются, поэтому снятие повторяется, пока
 *    строка меняется.
 * 2. **Последнее значение главное.** Так ведёт себя `fastboot` CLI, и
 *    расходиться с ним здесь незачем. Но все значения сохраняются в
 *    [FastbootVariableSnapshot.duplicates] — расхождение под одним именем это
 *    наблюдение об устройстве, и терять его нельзя.
 * 3. **Имя с разделом внутри себя содержит второе двоеточие.**
 *    `partition-size:boot: 65536` при наивном разрезании по первому двоеточию
 *    дало бы имя `partition-size` и значение `boot: 65536` — и все разделы
 *    слились бы в одну переменную. Семейства, у которых имя двухсоставное,
 *    перечислены и взяты из архивов, а не выведены.
 *
 * Нулевые байты здесь не снимаются: [FastbootPacketCodec] уже снял их, и делать
 * это второй раз значило бы заводить защиту от того, чего на входе не бывает.
 *
 * Сборка разделов в инвентарь здесь **намеренно отсутствует**: это пункт Phase 6
 * (`logical partitions / fastbootd operations`), а не этой фазы. Здесь только
 * то, что переменные разобраны верно.
 */
public object FastbootVariables {
    /**
     * Разбирает строки ответа.
     *
     * [lines] — то, что пришло кадрами `INFO`/`TEXT`; каждая может содержать
     * несколько строк текста. [complete] означает, что обмен дошёл до
     * терминального кадра, а не оборвался.
     */
    public fun parse(
        lines: Iterable<String>,
        complete: Boolean = true,
        finalReply: FastbootReply = FastbootReply.OKAY,
        finalPayload: String = "",
    ): FastbootVariableSnapshot {
        val variables = linkedMapOf<String, String>()
        val everyValue = linkedMapOf<String, MutableList<String>>()
        val ignored = mutableListOf<String>()

        lines.asSequence()
            .flatMap { block -> block.lineSequence() }
            .map(::normalize)
            .filter { it.isNotBlank() }
            .forEach { line ->
                val pair = split(line)
                when {
                    pair == null -> ignored += line

                    // `all: done!` — это конец вывода, а не переменная.
                    pair.first == ALL && pair.second.equals(DONE, ignoreCase = true) -> Unit

                    else -> {
                        everyValue.getOrPut(pair.first) { mutableListOf() } += pair.second
                        variables[pair.first] = pair.second
                    }
                }
            }

        return FastbootVariableSnapshot(
            variables = variables.toMap(),
            duplicates = everyValue
                .filterValues { it.size > 1 }
                .map { (name, values) -> FastbootDuplicate(name, values.toList()) },
            ignored = ignored.toList(),
            complete = complete,
            finalReply = finalReply,
            finalPayload = finalPayload,
        )
    }

    /** Снимает транспортные префиксы, пока строка меняется. */
    internal fun normalize(raw: String): String {
        var value = raw.trim()
        var previous = ""
        while (value != previous) {
            previous = value
            value = PREFIXES
                .firstOrNull { value.startsWith(it, ignoreCase = true) }
                ?.let { value.drop(it.length).trim() }
                ?: value
        }
        return value
    }

    /** Имя и значение, либо `null`, если строка не похожа на переменную. */
    private fun split(line: String): Pair<String, String>? {
        val scoped = PARTITION_SCOPED.matchEntire(line)
        return if (scoped != null) scopedPair(scoped) else plainPair(line)
    }

    private fun scopedPair(match: MatchResult): Pair<String, String>? {
        val family = match.groupValues[1].lowercase()
        val target = match.groupValues[2].trim().lowercase()
        val value = match.groupValues[3].trim()
        return if (target.isBlank() || value.isBlank()) null else "$family:$target" to value
    }

    private fun plainPair(line: String): Pair<String, String>? {
        val separator = line.indexOf(':')
        return if (separator <= 0 || separator == line.lastIndex) {
            null
        } else {
            val name = line.substring(0, separator).trim().lowercase()
            val value = line.substring(separator + 1).trim()
            if (name.isBlank() || value.isBlank()) null else name to value
        }
    }

    /**
     * Семейства переменных, у которых имя включает раздел или слот.
     *
     * Список взят из архивов и не расширяется догадкой: незнакомое
     * двухсоставное имя разберётся по первому двоеточию, и это будет видно в
     * значении, а не спрятано.
     */
    private val PARTITION_SCOPED = Regex(
        "^(partition-size|partition-type|is-logical|has-slot|slot-successful|" +
            "slot-unbootable|slot-retry-count):([^:]+):\\s*(.*)$",
        RegexOption.IGNORE_CASE,
    )

    /** `(bootloader)` — тот самый префикс, что показывает `fastboot` в консоли. */
    private val PREFIXES = listOf("INFO", "TEXT", "(bootloader)")

    private const val ALL = "all"
    private const val DONE = "done!"
}
