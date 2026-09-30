package io.github.ncorror.nekoflash.core.diagnostics

import java.io.BufferedOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Именованный раздел диагностического архива. */
public data class DiagnosticBundleSection(
    val name: String,
    val content: String,
) {
    init {
        require(name.isNotBlank()) { "Section name must not be blank" }
        require(name.none { it == '/' || it == '\\' }) { "Section name must not contain path separators" }
        require(name != DiagnosticBundle.MANIFEST_NAME) {
            "Section name is reserved for the bundle manifest"
        }
    }
}

/** Что попало в архив. Возвращается вызывающему, чтобы он мог сообщить это пользователю. */
public data class DiagnosticBundleResult(
    val sectionCount: Int,
    val uncompressedBytes: Long,
)

/**
 * Сборщик диагностического архива.
 *
 * Архив **детерминирован**: одинаковый набор разделов и одна и та же отметка
 * экспорта дают побайтово одинаковый результат. Это требование `07_TESTING_CI_HARDWARE_EVIDENCE_RU.md`
 * к evidence-пакету: два прогона можно сравнить, а расхождение будет означать
 * расхождение данных, а не времени файловой системы.
 *
 * Записывается только то, что передал вызывающий. Каталог целиком не
 * упаковывается: рядом могут оказаться посторонние файлы, а неожиданное
 * содержимое в evidence обесценивает его.
 *
 * Поток принадлежит вызывающему и не закрывается здесь: архив может быть
 * частью большего вывода.
 */
public object DiagnosticBundle {
    /** Версия формата. Меняется при несовместимом изменении состава архива. */
    public const val SCHEMA: String = "io.github.ncorror.nekoflash.diagnostics-bundle.v1"

    /** Имя файла с описью. Всегда первый в архиве. */
    public const val MANIFEST_NAME: String = "manifest.txt"

    private val FILE_NAME_FORMATTER: DateTimeFormatter = DateTimeFormatter
        .ofPattern("yyyyMMdd-HHmmss'Z'")
        .withZone(ZoneOffset.UTC)

    /**
     * Записывает архив в [output].
     *
     * Разделы сохраняются в переданном порядке. Порядок задаёт вызывающий,
     * потому что читать evidence удобнее в логическом порядке, а не по алфавиту.
     *
     * @throws IllegalArgumentException если имена разделов повторяются.
     */
    public fun write(
        output: OutputStream,
        sections: List<DiagnosticBundleSection>,
        exportedAt: Instant,
    ): DiagnosticBundleResult {
        val duplicates = sections.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate section names: ${duplicates.sorted()}" }

        val manifest = manifestText(sections, exportedAt)
        val payloadBytes = sections.sumOf { it.content.toByteArray(Charsets.UTF_8).size.toLong() }

        ZipOutputStream(BufferedOutputStream(NonClosingOutputStream(output))).use { zip ->
            writeEntry(zip, MANIFEST_NAME, manifest)
            sections.forEach { section -> writeEntry(zip, section.name, section.content) }
        }
        output.flush()

        return DiagnosticBundleResult(
            sectionCount = sections.size,
            uncompressedBytes = payloadBytes + manifest.toByteArray(Charsets.UTF_8).size,
        )
    }

    /** Имя файла для сохранения архива. */
    public fun suggestedFileName(exportedAt: Instant): String =
        "NekoFlash-diagnostics-${FILE_NAME_FORMATTER.format(exportedAt)}.zip"

    private fun manifestText(
        sections: List<DiagnosticBundleSection>,
        exportedAt: Instant,
    ): String = buildString {
        appendLine("schema=$SCHEMA")
        appendLine("exportedAt=$exportedAt")
        appendLine("sectionCount=${sections.size}")
        sections.forEach { section ->
            appendLine("section=${section.name} bytes=${section.content.toByteArray(Charsets.UTF_8).size}")
        }
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, content: String) {
        // Время записи обнуляется намеренно: байты архива не должны зависеть от
        // времени файловой системы, иначе два одинаковых прогона дадут разные файлы.
        zip.putNextEntry(ZipEntry(name).apply { time = 0L })
        try {
            zip.write(content.toByteArray(Charsets.UTF_8))
        } finally {
            zip.closeEntry()
        }
    }

    private class NonClosingOutputStream(output: OutputStream) : FilterOutputStream(output) {
        override fun write(source: ByteArray, offset: Int, length: Int) {
            out.write(source, offset, length)
        }

        override fun close() {
            flush()
        }
    }
}

/**
 * Строка evidence для одного события.
 *
 * Одна строка на событие: такой формат читается и человеком, и `grep`, а
 * переносы внутри значений заменяются пробелом, чтобы одно событие не
 * распалось на несколько строк.
 */
public fun DiagnosticEvent.toEvidenceLine(): String = buildString {
    append(timestamp)
    append(' ').append(category.singleLine())
    append(' ').append(message.singleLine())
    targetId?.let { append(" target=").append(it.value.singleLine()) }
    sessionGeneration?.let { append(" generation=").append(it.value) }
    fields.toSortedMap().forEach { (key, value) ->
        append(' ').append(key.singleLine()).append('=').append(value.singleLine())
    }
}

/** Раздел архива со всеми событиями в порядке их появления. */
public fun List<DiagnosticEvent>.toEvidenceSection(
    name: String = "events.txt",
): DiagnosticBundleSection = DiagnosticBundleSection(
    name = name,
    content = joinToString(separator = "\n", postfix = "\n") { it.toEvidenceLine() },
)

private fun String.singleLine(): String = replace('\n', ' ').replace('\r', ' ')
