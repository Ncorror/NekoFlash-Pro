package io.github.ncorror.nekoflash.artifact

import io.github.ncorror.nekoflash.core.artifact.ArtifactAccess
import io.github.ncorror.nekoflash.core.artifact.ArtifactDigest
import io.github.ncorror.nekoflash.core.artifact.ArtifactIdentity
import io.github.ncorror.nekoflash.core.artifact.ArtifactRandomAccess
import io.github.ncorror.nekoflash.core.artifact.ArtifactSource
import io.github.ncorror.nekoflash.core.artifact.ArtifactStamp
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

/** Чем кончилась стажировка. */
internal sealed interface ArtifactStagingOutcome {
    /** Источник лежит во временном файле и умеет читать с произвольного места. */
    data class Staged(val source: StagedArtifactSource) : ArtifactStagingOutcome

    /** Не вышло. Временный файл убран. */
    data class Failed(val detail: String) : ArtifactStagingOutcome
}

/**
 * Копия источника во временном файле приложения.
 *
 * Нужна там, где передача просит блоки в своём порядке, а источник читается
 * только подряд (`06` §6). Это не оптимизация, а единственный честный выход:
 * подделать произвольный доступ поверх трубы нельзя — на повторный запрос она
 * отдала бы следующий кусок.
 *
 * Побочно стажировка отвечает на второй вопрос — **какого размера артефакт**.
 * Провайдер `content://` вправе не сообщать длины, а Sideload объявляет объём в
 * самом имени сервиса, то есть обязан знать его до первого байта.
 *
 * Отпечаток считается по дороге: он и так нужен для evidence, а второй проход
 * по гигабайтам ради него был бы чистой тратой.
 */
internal class StagedArtifactSource private constructor(
    private val file: File,
    override val identity: ArtifactIdentity,
    /** SHA-256 того, что действительно скопировано. */
    val sha256: String,
) : ArtifactSource, Closeable {
    private val reads = RandomAccessFile(file, "r")

    override val openedStamp: ArtifactStamp = ArtifactStamp(
        sizeBytes = file.length(),
        modifiedAtMillis = file.lastModified(),
    )

    override fun stamp(): ArtifactStamp = ArtifactStamp(
        sizeBytes = file.length(),
        modifiedAtMillis = file.lastModified(),
    )

    override fun open(): InputStream = file.inputStream()

    override fun randomAccess(): ArtifactRandomAccess = ArtifactRandomAccess { offset, length ->
        ByteArray(length).also { buffer ->
            synchronized(reads) {
                reads.seek(offset)
                reads.readFully(buffer)
            }
        }
    }

    /** Убирает временный файл: он не переживает операцию намеренно. */
    override fun close() {
        runCatching { reads.close() }
        file.delete()
    }

    companion object {
        /**
         * Копирует [source] в каталог [directory].
         *
         * Место проверяется **до** первого байта там, где размер известен:
         * оборвавшаяся на середине стажировка оставит половину файла и
         * потраченное время оператора. Где размер неизвестен, проверить нечем,
         * и об этом говорится честно — исход тогда выяснится по ходу.
         */
        fun stage(
            source: ArtifactSource,
            directory: File,
            onProgress: (Long) -> Unit = {},
        ): ArtifactStagingOutcome {
            val file = File(directory, "staged-${System.nanoTime()}.artifact")
            val digest = ArtifactDigest()
            return runCatching {
                directory.mkdirs()
                source.open().use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(COPY_BLOCK_BYTES)
                        var read = input.read(buffer)
                        while (read > 0) {
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            onProgress(digest.bytes)
                            read = input.read(buffer)
                        }
                    }
                }
                staged(source, file, digest)
            }.getOrElse { error ->
                file.delete()
                ArtifactStagingOutcome.Failed(
                    "стажировка не удалась: ${error.message ?: error.javaClass.simpleName}",
                )
            }
        }

        private fun staged(source: ArtifactSource, file: File, digest: ArtifactDigest) =
            ArtifactStagingOutcome.Staged(
                StagedArtifactSource(
                    file = file,
                    identity = ArtifactIdentity(
                        name = source.identity.name,
                        // Размер берётся от скопированного, а не от объявленного:
                        // здесь он впервые становится известен наверняка.
                        sizeBytes = digest.bytes,
                        access = ArtifactAccess.SEEKABLE,
                        version = source.identity.version,
                    ),
                    sha256 = digest.hex(),
                ),
            )

        /** Кусок копирования. Тот же, что у Fastboot DATA OUT: 16 КиБ. */
        private const val COPY_BLOCK_BYTES = 16 * 1024
    }
}
