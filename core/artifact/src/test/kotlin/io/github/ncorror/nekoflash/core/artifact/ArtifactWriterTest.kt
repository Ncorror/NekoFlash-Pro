package io.github.ncorror.nekoflash.core.artifact

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Дисциплина записи артефакта.
 *
 * Проверяется не «байты записались», а то, чего нельзя допустить: **на
 * настоящем месте не должно оказаться файла, про который никто не доказал, что
 * он целый**. Такой файл выглядит рабочим, и заметить подмену будет уже нечем.
 */
class ArtifactWriterTest {
    @Test
    fun aCompleteWriteCommitsWithTheCountAndTheDigest() {
        val sink = RecordingSink()
        val writer = ArtifactWriter(sink, expectedBytes = 5L)

        writer.accept(byteArrayOf(1, 2, 3))
        writer.accept(byteArrayOf(4, 5))
        val outcome = writer.finish() as ArtifactWriteOutcome.Committed

        assertEquals(5L, outcome.bytes)
        assertEquals(64, outcome.sha256.length)
        assertEquals(listOf<Byte>(1, 2, 3, 4, 5), sink.written())
        assertTrue(sink.committed)
    }

    /**
     * Пришло меньше обещанного — и файл **не** переезжает на место.
     *
     * Это главный тест файла: усечённый образ, положенный на место как целый,
     * ведёт себя как исправный ровно до попытки им воспользоваться.
     */
    @Test
    fun aShortWriteNeverReachesTheDestination() {
        val sink = RecordingSink()
        val writer = ArtifactWriter(sink, expectedBytes = 10L)
        writer.accept(byteArrayOf(1, 2, 3))

        val outcome = writer.finish() as ArtifactWriteOutcome.CountMismatch

        assertEquals(10L, outcome.expectedBytes)
        assertEquals(3L, outcome.actualBytes)
        assertFalse("усечённое не переезжает на место", sink.committed)
        assertTrue(sink.abandonedBecause!!.contains("вместо обещанных 10"))
    }

    /** Пришло больше обещанного — та же история и тот же отказ. */
    @Test
    fun writingMoreThanPromisedIsAlsoRefused() {
        val sink = RecordingSink()
        val writer = ArtifactWriter(sink, expectedBytes = 2L)
        writer.accept(byteArrayOf(1, 2, 3))

        assertTrue(writer.finish() is ArtifactWriteOutcome.CountMismatch)
        assertFalse(sink.committed)
    }

    /** Обрыв убирает написанное и называет причину. */
    @Test
    fun anInterruptionRemovesWhatWasWritten() {
        val sink = RecordingSink()
        val writer = ArtifactWriter(sink, expectedBytes = 10L)
        writer.accept(byteArrayOf(1, 2))

        val outcome = writer.interrupted("кабель выдернут") as ArtifactWriteOutcome.Interrupted

        assertEquals(2L, outcome.bytes)
        assertEquals(10L, outcome.expectedBytes)
        assertFalse(sink.committed)
        assertEquals("кабель выдернут", sink.abandonedBecause)
    }

    /**
     * Ошибка закрытия — это ошибка записи, а не мелочь.
     *
     * `close`/`flush` сообщают о байтах, не вынесенных на диск. Проглотить их
     * значило бы объявить целым файл, которого может не быть.
     */
    @Test
    fun aFailureToCloseIsAWriteFailureAndNotANit() {
        val sink = RecordingSink(failOnClose = true)
        val writer = ArtifactWriter(sink, expectedBytes = 2L)
        writer.accept(byteArrayOf(1, 2))

        val outcome = writer.finish() as ArtifactWriteOutcome.Interrupted

        assertTrue(outcome.detail.contains("не закрылось"))
        assertFalse("незакрытое не переезжает на место", sink.committed)
    }

    /**
     * Неудачный переезд сохраняет отпечаток.
     *
     * Байты прошли через запись и посчитаны; от того, что их не удалось
     * положить на место, отпечаток не портится, а оператору он нужен.
     */
    @Test
    fun aFailedCommitKeepsTheDigestThatWasAlreadyEarned() {
        val sink = RecordingSink(commitFailure = "провайдер отказал")
        val writer = ArtifactWriter(sink, expectedBytes = 2L)
        writer.accept(byteArrayOf(1, 2))

        val outcome = writer.finish() as ArtifactWriteOutcome.CommitFailed

        assertEquals(2L, outcome.bytes)
        assertEquals(64, outcome.sha256.length)
        assertTrue(outcome.detail.contains("провайдер отказал"))
    }

    /** Объём заранее неизвестен — сверять нечего, и запись не отказывает на пустом месте. */
    @Test
    fun withoutAPromisedSizeThereIsNothingToReconcile() {
        val sink = RecordingSink()
        val writer = ArtifactWriter(sink)
        writer.accept(byteArrayOf(1, 2, 3))

        assertTrue(writer.finish() is ArtifactWriteOutcome.Committed)
    }

    /** Пустой артефакт — это файл на ноль байт, а не отсутствие файла. */
    @Test
    fun anEmptyArtifactStillBecomesAFile() {
        val sink = RecordingSink()

        val outcome = ArtifactWriter(sink, expectedBytes = 0L).finish() as ArtifactWriteOutcome.Committed

        assertEquals(0L, outcome.bytes)
        assertTrue(sink.committed)
    }

    /** Отсутствие атомарной замены доносится до исхода, а не теряется в приёмнике. */
    @Test
    fun theAbsenceOfAnAtomicRenameReachesTheOutcome() {
        val sink = RecordingSink(atomic = false)
        val writer = ArtifactWriter(sink)

        val outcome = writer.finish() as ArtifactWriteOutcome.Committed

        assertFalse(outcome.atomic)
    }

    /** Прогресс идёт по мере приёма и меряется в байтах, а не в кусках. */
    @Test
    fun progressCountsBytesAndNotChunks() {
        val seen = mutableListOf<Long>()
        val writer = ArtifactWriter(RecordingSink(), onProgress = { bytes -> seen += bytes })

        writer.accept(ByteArray(3))
        writer.accept(ByteArray(4))

        assertEquals(listOf(3L, 7L), seen)
    }

    /** Один и тот же приёмник закрывается один раз: повтор — ошибка вызывающего. */
    @Test
    fun finishingTwiceIsARefusal() {
        val writer = ArtifactWriter(RecordingSink())
        writer.finish()

        assertTrue(runCatching { writer.finish() }.exceptionOrNull() is IllegalStateException)
    }

    /** Приёмник, который всё записывает и рассказывает, что с ним делали. */
    private class RecordingSink(
        private val failOnClose: Boolean = false,
        private val commitFailure: String? = null,
        private val atomic: Boolean = true,
    ) : ArtifactSink {
        private val buffer = ByteArrayOutputStream()

        var committed = false
            private set

        var abandonedBecause: String? = null
            private set

        override val destination: String = "/tmp/artifact"

        override val atomicCommit: Boolean
            get() = atomic

        fun written(): List<Byte> = buffer.toByteArray().toList()

        override fun open(): OutputStream = object : OutputStream() {
            override fun write(byte: Int) = buffer.write(byte)

            override fun write(source: ByteArray, offset: Int, length: Int) =
                buffer.write(source, offset, length)

            override fun close() {
                if (failOnClose) throw IOException("диск отвалился")
            }
        }

        override fun commit(): ArtifactCommit = if (commitFailure == null) {
            committed = true
            ArtifactCommit.Done(destination, atomic)
        } else {
            ArtifactCommit.Failed(commitFailure)
        }

        override fun abandon(reason: String) {
            abandonedBecause = reason
        }
    }

    /** Отпечаток считается потоком и не зависит от того, какими кусками пришло. */
    @Test
    fun theDigestDoesNotDependOnChunking() {
        val whole = ArtifactDigest().apply { update(ByteArray(100) { it.toByte() }) }
        val pieces = ArtifactDigest().apply {
            val bytes = ByteArray(100) { it.toByte() }
            update(bytes, 0, 30)
            update(bytes, 30, 70)
        }

        assertEquals(whole.hex(), pieces.hex())
        assertEquals(100L, pieces.bytes)
    }

    /** Отпечаток можно спросить не закрывая счёт: состояние от этого не рушится. */
    @Test
    fun askingForTheDigestDoesNotEndTheCount() {
        val digest = ArtifactDigest()
        digest.update(byteArrayOf(1))
        val first = digest.hex()
        digest.update(byteArrayOf(2))

        assertEquals(2L, digest.bytes)
        assertTrue("продолженный счёт даёт другой отпечаток", first != digest.hex())
    }
}
