package io.github.ncorror.nekoflash.core.operation

import io.github.ncorror.nekoflash.core.model.SessionGeneration
import io.github.ncorror.nekoflash.core.model.TargetId
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Хранилище записей операций.
 *
 * Проверяется не «сохранилось и прочиталось», а то, ради чего хранилище
 * заведено: **оно обязано пережить смерть процесса посреди записи**. Недописанный
 * файл — это обычный случай, а не авария, и он не должен уносить с собой ни
 * чужие записи, ни чтение истории целиком (`06` §3).
 */
class OperationJournalTest {
    private lateinit var directory: Path

    @Before
    fun makeDirectory() {
        directory = Files.createTempDirectory("nekoflash-operations")
    }

    @Test
    fun aSavedRecordComesBackWholeIncludingTheThingsThatAreEasyToLose() {
        val journal = FileOperationJournal(directory)
        val saved = record(
            boundary = MutationBoundary.Crossed(Instant.parse("2026-09-14T00:00:00Z"), "первый блок ушёл"),
            progress = OperationProgress(1_000L, 900L, 2_000L, 1_500L),
            artifacts = listOf(OperationArtifact("payload.zip", 2_000L, "abc")),
            peerResponses = listOf("FAIL Flashing is not allowed in Lock State"),
            evidenceRefs = listOf("07 §6.83"),
        )

        journal.save(saved)

        assertEquals(saved, journal.history().records.single())
    }

    /** Русский текст остаётся русским: запись попадает в evidence и читается глазами. */
    @Test
    fun russianTextSurvivesAsRussianText() {
        val journal = FileOperationJournal(directory)
        journal.save(record(summary = "отдать пакет в Recovery"))

        val stored = Files.list(directory).use { it.toList() }.single()
        val text = Files.readString(stored, StandardCharsets.UTF_8)

        assertTrue("отчёт должен читаться без программы", text.contains("отдать пакет в Recovery"))
    }

    /**
     * Недописанный файл пропускается и **считается**, а не роняет историю.
     *
     * Это и есть тот случай, ради которого хранилище существует: процесс умер
     * посреди записи. Соседние записи обязаны уцелеть.
     */
    @Test
    fun aTornRecordIsSkippedAndCountedRatherThanFatal() {
        val journal = FileOperationJournal(directory)
        journal.save(record(id = "op-1"))
        journal.save(record(id = "op-2"))
        Files.writeString(directory.resolve("op-1.operation"), "id=op-1\ncreatedAt=огрыз")

        val history = journal.history()

        assertEquals(1, history.records.size)
        assertEquals("op-2", history.records.single().id.value)
        assertEquals(1, history.unreadable)
    }

    /**
     * Временный файл на месте настоящего не появляется.
     *
     * Запись идёт в `.partial` и переезжает переименованием — та же дисциплина,
     * что у `ArtifactSink`, и по той же причине.
     */
    @Test
    fun nothingHalfWrittenIsLeftBehind() {
        FileOperationJournal(directory).save(record())

        val names = Files.list(directory).use { stream -> stream.map { it.fileName.toString() }.toList() }

        assertEquals(listOf("op-1.operation"), names)
    }

    /** Та же операция заменяет себя, а не плодит копии. */
    @Test
    fun savingTheSameOperationTwiceReplacesIt() {
        val journal = FileOperationJournal(directory)
        journal.save(record(state = "RUNNING"))
        journal.save(record(state = "DONE"))

        assertEquals("DONE", journal.history().records.single().state.name)
    }

    /** История идёт от новых к старым: оператор смотрит на последнее. */
    @Test
    fun historyRunsFromNewestToOldest() {
        val journal = FileOperationJournal(directory)
        journal.save(record(id = "old", createdAt = Instant.parse("2026-01-01T00:00:00Z")))
        journal.save(record(id = "new", createdAt = Instant.parse("2026-09-01T00:00:00Z")))

        assertEquals(listOf("new", "old"), journal.history().records.map { it.id.value })
    }

    /**
     * Предел истории считается по времени **операции**, а не файла.
     *
     * Файл могли переписать позже, а операция от этого раньше не стала.
     */
    @Test
    fun theOldestOperationsAreForgottenFirst() {
        val journal = FileOperationJournal(directory, limit = 2)
        journal.save(record(id = "a", createdAt = Instant.parse("2026-01-01T00:00:00Z")))
        journal.save(record(id = "b", createdAt = Instant.parse("2026-02-01T00:00:00Z")))
        journal.save(record(id = "c", createdAt = Instant.parse("2026-03-01T00:00:00Z")))

        assertEquals(listOf("c", "b"), journal.history().records.map { it.id.value })
    }

    /**
     * Идентификатор в имя файла напрямую не пускается.
     *
     * Он приходит снаружи, а в имени файла `/` и `..` значат не то, что в
     * строке.
     */
    @Test
    fun anIdentifierNeverBecomesAPathOfItsOwn() {
        FileOperationJournal(directory).save(record(id = "../../etc/passwd"))

        val names = Files.list(directory).use { stream -> stream.map { it.fileName.toString() }.toList() }

        assertEquals(1, names.size)
        assertTrue(names.single().none { it == '/' })
    }

    /** Пустого каталога достаточно: истории просто нет. */
    @Test
    fun anEmptyDirectoryIsEmptyHistoryAndNotAFailure() {
        // Имя каталога латиницей намеренно: кодировка имён файлов в сборочной
        // среде CI — ASCII, и кириллица в **пути** там не создаётся вовсе. К
        // продукту это отношения не имеет (на Android пути в UTF-8), но тест,
        // падающий от среды, проверял бы среду.
        val history = FileOperationJournal(directory.resolve("no-such-directory")).history()

        assertTrue(history.records.isEmpty())
        assertEquals(0, history.unreadable)
    }

    /**
     * Непрочитанная граница мутации читается как **пересечённая**.
     *
     * Единственная несимметричность в чтении, и она намеренная: не узнать про
     * пересечённую границу значит объявить нетронутым устройство, которое могло
     * измениться.
     */
    @Test
    fun anUnreadableBoundaryFallsToCrossedRatherThanToSafe() {
        val properties = java.util.Properties().apply {
            setProperty("id", "op-1")
            setProperty("createdAt", Instant.EPOCH.toString())
            setProperty("kind", OperationKind.ADB_SIDELOAD.name)
            setProperty("summary", "отдать пакет")
            setProperty("target", "serial:1")
            setProperty("generation", "1")
            setProperty("state", "RUNNING")
            setProperty("boundary", "ЧТО-ТО НЕПОНЯТНОЕ")
        }

        val decoded = OperationJournalCodec.decode(properties)

        assertTrue(decoded!!.mutationBoundary is MutationBoundary.Crossed)
    }

    /** Без обязательного поля запись не восстанавливается, и это честный `null`. */
    @Test
    fun aRecordWithoutItsIdentityIsNotGuessedAt() {
        assertNull(OperationJournalCodec.decode(java.util.Properties()))
    }

    /** Хранилище в памяти отвечает так же: тесты и хранилище не расходятся. */
    @Test
    fun theInMemoryJournalAnswersTheSameWay() {
        val journal = InMemoryOperationJournal()
        journal.save(record(id = "a", createdAt = Instant.parse("2026-01-01T00:00:00Z")))
        journal.save(record(id = "b", createdAt = Instant.parse("2026-02-01T00:00:00Z")))
        journal.forget(OperationId("a"))

        assertEquals(listOf("b"), journal.history().records.map { it.id.value })
    }

    private fun record(
        id: String = "op-1",
        createdAt: Instant = Instant.EPOCH,
        summary: String = "отдать пакет",
        state: String = "RUNNING",
        boundary: MutationBoundary = MutationBoundary.NotCrossed,
        progress: OperationProgress? = null,
        artifacts: List<OperationArtifact> = emptyList(),
        peerResponses: List<String> = emptyList(),
        evidenceRefs: List<String> = emptyList(),
    ) = OperationRecord(
        id = OperationId(id),
        createdAt = createdAt,
        intent = OperationIntent(OperationKind.ADB_SIDELOAD, summary),
        targetId = TargetId("serial:eff4927c"),
        startedSessionGeneration = SessionGeneration(3L),
        state = OperationState(state),
        mutationBoundary = boundary,
        progress = progress,
        artifacts = artifacts,
        peerResponses = peerResponses,
        evidenceRefs = evidenceRefs,
    )
}
