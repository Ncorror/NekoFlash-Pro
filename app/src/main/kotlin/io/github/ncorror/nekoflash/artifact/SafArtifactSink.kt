package io.github.ncorror.nekoflash.artifact

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import io.github.ncorror.nekoflash.core.artifact.ArtifactCommit
import io.github.ncorror.nekoflash.core.artifact.ArtifactSink
import java.io.IOException
import java.io.OutputStream

/**
 * Место, выбранное пользователем через системный диалог сохранения.
 *
 * **Атомарной замены здесь нет, и это сказано прямо.** Системный диалог создаёт
 * документ до того, как мы начнём писать, и пишем мы прямо в него: положить
 * рядом `.partial` и переименовать нельзя — у SAF нет переименования, которое
 * было бы атомарным у произвольного провайдера. `06` §7 требует в таком случае
 * честно отражать ограничение, а не изображать гарантию.
 *
 * Из этого следует единственная важная обязанность: **неудачная запись убирает
 * за собой документ**. Усечённый файл на месте, выбранном пользователем, — это
 * файл, который выглядит целым; заметить подмену будет уже нечем.
 *
 * Стажировать через свой каталог, чтобы получить атомарность, здесь
 * намеренно не делается: это удвоило бы занятое место на образе в несколько
 * гигабайт, а защищает от случая, который и так виден (пустой или короткий файл
 * после явно сообщённой ошибки).
 */
internal class SafArtifactSink(
    private val resolver: ContentResolver,
    private val uri: Uri,
    override val destination: String,
) : ArtifactSink {
    /** У SAF его нет: см. описание класса. */
    override val atomicCommit: Boolean = false

    override fun open(): OutputStream = resolver.openOutputStream(uri, "wt")
        ?: throw IOException("провайдер не открыл $destination на запись")

    override fun commit(): ArtifactCommit = ArtifactCommit.Done(destination, atomicCommit)

    /**
     * Убирает недописанный документ.
     *
     * Удаление делается по возможности: провайдер вправе не разрешить его. Если
     * не вышло — документ остаётся, и это должно быть видно вызывающему, а не
     * проглочено здесь; поэтому исход возвращается, а не теряется.
     */
    override fun abandon(reason: String) {
        removed()
    }

    /** `true`, если недописанный документ действительно убран. */
    fun removed(): Boolean = runCatching {
        DocumentsContract.deleteDocument(resolver, uri)
    }.getOrDefault(false)
}
