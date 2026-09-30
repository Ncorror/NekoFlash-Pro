package io.github.ncorror.nekoflash.core.operation

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

/** Что удалось прочитать из хранилища и чего не удалось. */
data class OperationHistory(
    /** Записи от новых к старым. */
    val records: List<OperationRecord>,
    /**
     * Сколько записей прочитать не вышло.
     *
     * Число называется, а не проглатывается: недописанная запись — обычное
     * следствие смерти процесса, но если их вдруг много, это уже находка, и
     * увидеть её можно только если её считают.
     */
    val unreadable: Int = 0,
)

/**
 * Где живут записи операций.
 *
 * Хранилище нужно для истории, evidence и честного восстановления после смерти
 * процесса — и **не** для продолжения оборванной транзакции (`06` §2).
 */
interface OperationJournal {
    /** Сохраняет запись. Повторный вызов с тем же [OperationId] заменяет прежнюю. */
    fun save(record: OperationRecord)

    /** Читает всё, что есть, от новых к старым. */
    fun history(): OperationHistory

    /** Забывает запись. */
    fun forget(id: OperationId)
}

/** Хранилище в памяти: для тестов и для случая, когда каталог недоступен. */
open class InMemoryOperationJournal : OperationJournal {
    private val records = LinkedHashMap<OperationId, OperationRecord>()

    override fun save(record: OperationRecord) {
        records[record.id] = record
    }

    override fun history(): OperationHistory =
        OperationHistory(records.values.sortedByDescending { it.createdAt })

    override fun forget(id: OperationId) {
        records.remove(id)
    }
}

/**
 * Хранилище в каталоге: одна запись — один файл.
 *
 * Один файл на операцию, а не общий журнал, по одной причине: **смерть процесса
 * посреди записи не должна портить чужие записи**. Общий файл, недописанный на
 * середине, уносит с собой всю историю; отдельный — только себя, и то не всегда,
 * потому что запись идёт во временный файл и переезжает на место переименованием.
 *
 * Это та же дисциплина, что у `ArtifactSink`, и по той же причине: на месте не
 * должно оказаться файла, про который никто не доказал, что он целый.
 *
 * Нечитаемая запись **пропускается и считается**, а не роняет чтение истории:
 * недописанный файл — это ровно тот случай, ради которого хранилище заведено.
 */
class FileOperationJournal(
    private val directory: Path,
    /** Сколько записей держать. Старые за пределом забываются. */
    private val limit: Int = DEFAULT_LIMIT,
) : OperationJournal {
    init {
        require(limit > 0) { "предел истории должен быть положительным: $limit" }
    }

    override fun save(record: OperationRecord) {
        Files.createDirectories(directory)
        val target = directory.resolve(fileName(record.id))
        val partial = directory.resolve(fileName(record.id) + PARTIAL)
        Files.newBufferedWriter(partial, StandardCharsets.UTF_8).use { writer ->
            // Комментарий с датой, который пишет `store`, здесь не нужен: он
            // делает одинаковые записи разными и мешает их сравнивать.
            OperationJournalCodec.encode(record).store(writer, null)
        }
        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING)
        prune()
    }

    override fun history(): OperationHistory {
        val files = listFiles()
        var unreadable = 0
        val records = files.mapNotNull { file ->
            read(file).also { record -> if (record == null) unreadable += 1 }
        }
        return OperationHistory(records.sortedByDescending { it.createdAt }, unreadable)
    }

    override fun forget(id: OperationId) {
        Files.deleteIfExists(directory.resolve(fileName(id)))
    }

    private fun read(file: Path): OperationRecord? = runCatching {
        Properties().apply {
            Files.newBufferedReader(file, StandardCharsets.UTF_8).use { reader -> load(reader) }
        }
    }.getOrNull()?.let(OperationJournalCodec::decode)

    private fun listFiles(): List<Path> = runCatching {
        Files.list(directory).use { stream ->
            stream.filter { path -> path.fileName.toString().endsWith(SUFFIX) }.toList()
        }
    }.getOrElse { emptyList() }

    /**
     * Забывает самые старые записи за пределом.
     *
     * Порядок — по времени создания записи, а не по времени файла: файл могли
     * переписать позже, а операция от этого раньше не стала.
     */
    private fun prune() {
        val known = listFiles().mapNotNull { file -> read(file)?.let { record -> record to file } }
        if (known.size <= limit) return
        known.sortedByDescending { (record, _) -> record.createdAt }
            .drop(limit)
            .forEach { (_, file) -> runCatching { Files.deleteIfExists(file) } }
    }

    /**
     * Имя файла из идентификатора.
     *
     * Идентификатор в имя файла напрямую не пускается: он приходит снаружи, а в
     * имени файла `/` и `..` значат не то, что в строке.
     */
    private fun fileName(id: OperationId): String =
        id.value.map { ch -> if (ch.isLetterOrDigit() || ch == '-' || ch == '_') ch else '_' }
            .joinToString(separator = "") + SUFFIX

    companion object {
        const val DEFAULT_LIMIT: Int = 200

        private const val SUFFIX = ".operation"
        private const val PARTIAL = ".partial"
    }
}
