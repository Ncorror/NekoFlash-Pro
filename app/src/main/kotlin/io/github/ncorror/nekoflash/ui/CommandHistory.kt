package io.github.ncorror.nekoflash.ui

/**
 * Недавние команды — те, что оператор уже набирал.
 *
 * Нужна не ради удобства. Команды здесь длинные и набираются в спешке одной
 * рукой, вторая держит кабель; опечатка в `getvar:partition-size:system_a`
 * стоит ещё одного круга, а на некоторых путях — ещё одной перезагрузки
 * устройства.
 *
 * Три правила, и каждое выведено из того, как список ломается:
 *
 * 1. **Пустое не запоминается** — иначе первая же случайная отправка съедает
 *    строку истории.
 * 2. **Повтор переезжает наверх, а не задваивается** — иначе история из
 *    двадцати строк оказывается тремя командами, повторёнными по семь раз.
 * 3. **Порядок — от свежего к старому**: ищут почти всегда последнее.
 */
internal class CommandHistory(private val limit: Int = DEFAULT_LIMIT) {
    private val commands = ArrayDeque<String>()

    init {
        require(limit > 0) { "предел истории должен быть положительным: $limit" }
    }

    /** Недавние команды, от свежей к старой. */
    fun entries(): List<String> = commands.toList()

    /**
     * Запоминает отправленную команду.
     *
     * Называется `add`, а не `remember`: в коде на Compose `remember` значит
     * совсем другое, и одноимённый метод читается как вызов из композиции —
     * это заметил гейт `check_compose_wiring.py`, и он был прав.
     */
    fun add(command: String) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return
        commands.remove(trimmed)
        commands.addFirst(trimmed)
        while (commands.size > limit) {
            commands.removeLast()
        }
    }

    private companion object {
        /**
         * Сколько держать.
         *
         * Двадцати хватает на один сеанс работы с устройством; больше — это уже
         * список, по которому листают, то есть ровно то, от чего история должна
         * избавлять.
         */
        const val DEFAULT_LIMIT = 20
    }
}
