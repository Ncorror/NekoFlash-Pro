package io.github.ncorror.nekoflash.artifact

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import io.github.ncorror.nekoflash.core.artifact.ArtifactIdentity
import io.github.ncorror.nekoflash.core.artifact.ArtifactRandomAccess
import io.github.ncorror.nekoflash.core.artifact.ArtifactSource
import io.github.ncorror.nekoflash.core.artifact.ArtifactStamp
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * Файл, выбранный пользователем через системный диалог.
 *
 * Класс тонкий намеренно: всё, что можно решить без `content://`, решает
 * [SafArtifactFacts], и оно проверено тестами. Здесь остаётся только разговор с
 * `ContentResolver`, который проверяется на устройстве и никак иначе.
 *
 * Умение читать с произвольного места **не обещается заранее**: оно
 * определяется по длине дескриптора и, если её нет, честно называется
 * отсутствующим. Труба, выданная за файл, отдала бы на повторный запрос блока
 * следующий кусок, и образ уехал бы перемешанным при исправной с виду передаче.
 */
internal class SafArtifactSource(
    private val resolver: ContentResolver,
    private val uri: Uri,
) : ArtifactSource {
    private val facts: SafDocumentFacts = read()

    override val identity: ArtifactIdentity = SafArtifactFacts.identity(facts, fallbackName())

    override val openedStamp: ArtifactStamp = SafArtifactFacts.stamp(facts)

    /**
     * Отпечаток **сейчас**, а не сохранённый при открытии.
     *
     * Смысл проверки в том и состоит, чтобы заметить подмену между выбором
     * файла и нажатием кнопки; сравнение сохранённого с самим собой ничего бы
     * не заметило.
     */
    override fun stamp(): ArtifactStamp = SafArtifactFacts.stamp(read())

    override fun open(): InputStream = resolver.openInputStream(uri)
        ?: throw IOException("провайдер не открыл ${identity.name} на чтение")

    override fun randomAccess(): ArtifactRandomAccess? {
        val descriptor = runCatching { resolver.openFileDescriptor(uri, "r") }.getOrNull()
        return if (descriptor == null || descriptor.statSize < 0L) {
            descriptor?.close()
            null
        } else {
            PositionedReads(descriptor)
        }
    }

    private fun fallbackName(): String = uri.lastPathSegment?.substringAfterLast('/') ?: "artifact"

    /**
     * Спрашивает провайдера о документе.
     *
     * Дескриптор открывается **только** ради длины: по ней и определяется,
     * файл это или труба. Никакого другого способа узнать это у SAF нет.
     */
    private fun read(): SafDocumentFacts {
        val statSize = runCatching {
            resolver.openFileDescriptor(uri, "r")?.use { descriptor -> descriptor.statSize }
        }.getOrNull()
        return runCatching { query(statSize) }.getOrElse {
            SafDocumentFacts(displayName = null, columnSize = null, statSize = statSize)
        }
    }

    private fun query(statSize: Long?): SafDocumentFacts = resolver.query(uri, COLUMNS, null, null, null)
        ?.use { cursor ->
            if (!cursor.moveToFirst()) {
                SafDocumentFacts(null, null, statSize)
            } else {
                SafDocumentFacts(
                    displayName = cursor.stringOrNull(OpenableColumns.DISPLAY_NAME),
                    columnSize = cursor.longOrNull(OpenableColumns.SIZE),
                    statSize = statSize,
                    lastModifiedMillis = cursor.longOrNull(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                )
            }
        }
        ?: SafDocumentFacts(null, null, statSize)

    private fun android.database.Cursor.stringOrNull(column: String): String? =
        getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let { getString(it) }

    private fun android.database.Cursor.longOrNull(column: String): Long? =
        getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let { getLong(it) }

    /**
     * Чтение с произвольного места поверх канала дескриптора.
     *
     * Позиционное чтение, а не `seek` плюс `read`: позиция канала общая, и два
     * чтения подряд с разных мест иначе мешали бы друг другу.
     */
    private class PositionedReads(
        private val descriptor: android.os.ParcelFileDescriptor,
    ) : ArtifactRandomAccess {
        private val channel = FileInputStream(descriptor.fileDescriptor).channel

        override fun read(offset: Long, length: Int): ByteArray {
            val buffer = ByteBuffer.allocate(length)
            var position = offset
            while (buffer.hasRemaining()) {
                val read = channel.read(buffer, position)
                if (read < 0) {
                    throw IOException("источник кончился на ${position} до запрошенных $length байт")
                }
                position += read.toLong()
            }
            return buffer.array()
        }
    }

    private companion object {
        val COLUMNS = arrayOf(
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}
