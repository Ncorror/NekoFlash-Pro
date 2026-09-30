package io.github.ncorror.nekoflash.fastboot

import io.github.ncorror.nekoflash.protocol.fastboot.LpMetadata

/** Задача `flashall` — классы `task.cpp` AOSP. */
internal sealed interface FlashAllTask {
    /** `FlashTask`: [slot] — слот задачи, пусто — текущий. */
    data class Flash(val partition: String, val imageName: String, val slot: String, val applyVbmeta: Boolean) :
        FlashAllTask

    /** `UpdateSuperTask`: `super_empty.img` в `update-super`. */
    data object UpdateSuper : FlashAllTask

    /** `ResizeTask` с размером `0`. */
    data class Resize(val partition: String, val slot: String) : FlashAllTask

    /** `RebootTask`: пусто — `reboot`, иначе `fastboot`, `bootloader`, `recovery`. */
    data class Reboot(val target: String) : FlashAllTask

    /** `WipeTask`: `erase` и создание файловой системы на хосте. */
    data class Wipe(val partition: String) : FlashAllTask
}

/** `fastboot-info.txt` разобран или отвергнут — словами AOSP. */
internal sealed interface FastbootInfoRead {
    data class Tasks(val tasks: List<FlashAllTask>) : FastbootInfoRead
    data class Invalid(val reason: String) : FastbootInfoRead
}

/**
 * `ParseFastbootInfo` из AOSP `fastboot/fastboot.cpp`: `version`, `flash`
 * (`--apply-vbmeta`, `--slot-other`), `reboot [ЦЕЛЬ]`, `update-super`,
 * `erase РАЗДЕЛ`; строки с `#` и пустые пропускаются, `if-wipe` — только с `-w`.
 */
internal object FastbootInfoFile {
    private const val VERSION = 1

    fun parse(text: String, slot: String, secondary: String, wantsWipe: Boolean = false): FastbootInfoRead {
        val lines = text.split("\n").map { line(it, slot, secondary, wantsWipe) }
        val invalid = lines.firstNotNullOfOrNull { it as? FastbootInfoRead.Invalid }
        return invalid ?: FastbootInfoRead.Tasks(lines.filterIsInstance<Line>().mapNotNull { it.task })
    }

    /** Строка файла: задача, пропуск (`task` пуст) или отказ всему файлу. */
    private class Line(val task: FlashAllTask?)

    private fun line(text: String, slot: String, secondary: String, wantsWipe: Boolean): Any {
        val words = text.split(" ").filter { it.isNotEmpty() }
        return when {
            words.isEmpty() || words[0].startsWith("#") -> Line(null)
            words.size > 1 && words[0] == "version" -> version(words, text)
            words.size >= 2 && words[0] == "if-wipe" ->
                if (wantsWipe) command(words.drop(1), slot, secondary) else Line(null)
            else -> command(words, slot, secondary)
        }
    }

    /** `CheckFastbootInfoRequirements`. */
    private fun version(words: List<String>, text: String): Any {
        val version = words.takeIf { it.size == 2 }?.get(1)?.toUIntOrNull()
        return when {
            version == null ->
                FastbootInfoRead.Invalid("unknown characters in version info in fastboot-info.txt -> $text")
            version > VERSION.toUInt() -> FastbootInfoRead.Invalid(
                "fasboot-info.txt version: ${words[1]} not compatible with host tool version --> $VERSION",
            )
            else -> Line(null)
        }
    }

    private fun command(words: List<String>, slot: String, secondary: String): Any =
        task(words, slot, secondary)?.let(::Line)
            ?: FastbootInfoRead.Invalid("unknown command parsing fastboot-info.txt line: ${words.joinToString(" ")}")

    private fun task(command: List<String>, slot: String, secondary: String): FlashAllTask? {
        val arguments = command.drop(1)
        return when {
            command[0] == "flash" -> flash(arguments, slot, secondary)
            command[0] == "reboot" && arguments.size <= 1 -> FlashAllTask.Reboot(arguments.firstOrNull().orEmpty())
            command[0] == "update-super" && command.size == 1 -> FlashAllTask.UpdateSuper
            command[0] == "erase" && command.size == 2 -> FlashAllTask.Wipe(arguments[0])
            else -> null
        }
    }

    /** `ParseFlashCommand`: лишнее слово или нет раздела — отказ. */
    private fun flash(parts: List<String>, slot: String, secondary: String): FlashAllTask? {
        val names = parts.filterNot { it == APPLY_VBMETA || it == SLOT_OTHER }
        return names.takeIf { it.size in 1..2 }?.let {
            FlashAllTask.Flash(
                partition = names[0],
                imageName = names.getOrElse(1) { "${names[0]}.img" },
                slot = if (SLOT_OTHER in parts) secondary else slot,
                applyVbmeta = APPLY_VBMETA in parts,
            )
        }
    }

    private const val APPLY_VBMETA = "--apply-vbmeta"
    private const val SLOT_OTHER = "--slot-other"
}

/**
 * `AddResizeTasks`: перед первой прошивкой логического раздела — сжатие до
 * нуля каждого такого раздела плана, чтобы место в `super` раздавалось заново.
 * Слот сжатия — `--slot`, как `ResizeTask(…, fp->slot_override)`.
 */
internal fun withResizeTasks(
    tasks: List<FlashAllTask>,
    metadata: LpMetadata?,
    slotOverride: String,
    currentSlot: () -> String,
): List<FlashAllTask> {
    if (metadata == null) return tasks
    val dynamic = tasks.filterIsInstance<FlashAllTask.Flash>().filter { task ->
        val target = task.slot.ifEmpty { currentSlot() }
        metadata.flashesInUserspace(if (target.isEmpty()) task.partition else "${task.partition}_$target")
    }
    val first = tasks.indexOfFirst { it in dynamic }
    if (first < 0) return tasks
    return tasks.take(first) + dynamic.map { FlashAllTask.Resize(it.partition, slotOverride) } + tasks.drop(first)
}
