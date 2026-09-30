package io.github.ncorror.nekoflash.artifact

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.system.Os
import io.github.ncorror.nekoflash.core.artifact.ArtifactSink
import java.io.IOException

/**
 * Папка SAF: дерево, выбранное через `OpenDocumentTree`, и документ в нём.
 *
 * Имя, которое уже занято, не получает от провайдера суффикса «(1)»:
 * существующий документ находится и переписывается, как файл настольного
 * клиента, а не кладётся рядом под другим именем.
 */
internal class SafFolder(
    private val resolver: ContentResolver,
    private val tree: Uri,
    private val documentId: String = DocumentsContract.getTreeDocumentId(tree),
    override val name: String = documentId.substringAfterLast('/').substringAfterLast(':'),
) : LocalFolder {
    private val document: Uri get() = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)

    override fun entries(): List<LocalEntry> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
        val cursor = resolver.query(children, COLUMNS, null, null, null)
            ?: throw IOException("провайдер не перечислил $name")
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    val id = it.getString(0)
                    val childName = it.getString(1) ?: id
                    add(
                        if (it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                            LocalEntry.Folder(childName, SafFolder(resolver, tree, id, childName))
                        } else {
                            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                            LocalEntry.File(
                                name = childName,
                                sizeBytes = if (it.isNull(SIZE)) 0L else it.getLong(SIZE),
                                modifiedAtMillis = if (it.isNull(MODIFIED)) null else it.getLong(MODIFIED),
                                open = { SafArtifactSource(resolver, uri) },
                            )
                        },
                    )
                }
            }
        }
    }

    override fun folder(name: String): LocalFolder =
        (entries().firstOrNull { it.name == name } as? LocalEntry.Folder)?.folder
            ?: SafFolder(resolver, tree, create(DocumentsContract.Document.MIME_TYPE_DIR, name), name)

    override fun file(name: String): ArtifactSink {
        val existing = entries().firstOrNull { it.name == name }
        val id = when (existing) {
            is LocalEntry.Folder -> throw IOException("$name — папка, а не файл")
            is LocalEntry.File -> childId(name)
            null -> create(BINARY, name)
        }
        return SafArtifactSink(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, id), name)
    }

    /**
     * `set_time_and_mode` по дескриптору документа: `utime` — через
     * `/proc/self/fd/N` (у Android нет `futimens` в открытом API), `chmod` —
     * `fchmod` с `umask` процесса. Провайдер вправе не позволить ни того, ни
     * другого, поэтому итог сверяется `fstat`, а не принимается на веру.
     */
    override fun keepTimeAndMode(name: String, modifiedAtSeconds: Long, mode: Int): String? = runCatching {
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, childId(name))
        val descriptor = resolver.openFileDescriptor(uri, "rw") ?: return "провайдер не открыл $name"
        descriptor.use { pfd ->
            val wanted = mode and PERMISSIONS and synchronized(UMASK_LOCK) { Os.umask(0).also { Os.umask(it) } }.inv()
            java.io.File("/proc/self/fd/${pfd.fd}").setLastModified(modifiedAtSeconds * MILLIS)
            val chmod = runCatching { Os.fchmod(pfd.fileDescriptor, wanted) }.exceptionOrNull()
            val stat = Os.fstat(pfd.fileDescriptor)
            when {
                stat.st_mtime != modifiedAtSeconds ->
                    "время изменения не выставилось: провайдер оставил ${stat.st_mtime}, нужно $modifiedAtSeconds"
                stat.st_mode and PERMISSIONS != wanted ->
                    "режим не выставился: 0${Integer.toOctalString(stat.st_mode and PERMISSIONS)} вместо " +
                        "0${Integer.toOctalString(wanted)}" + (chmod?.message?.let { " ($it)" } ?: "")
                else -> null
            }
        }
    }.getOrElse { failure -> failure.message ?: failure.javaClass.simpleName }

    private fun childId(name: String): String {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
        return resolver.query(children, COLUMNS, null, null, null)?.use { cursor ->
            generateSequence { if (cursor.moveToNext()) cursor.getString(0) to cursor.getString(1) else null }
                .firstOrNull { (_, childName) -> childName == name }?.first
        } ?: throw IOException("$name пропал из ${this.name}")
    }

    private fun create(mime: String, name: String): String {
        val created = DocumentsContract.createDocument(resolver, document, mime, name)
            ?: throw IOException("провайдер не создал $name в ${this.name}")
        return DocumentsContract.getDocumentId(created)
    }

    private companion object {
        const val PERMISSIONS = 0xFFF
        const val MILLIS = 1000L
        val UMASK_LOCK = Any()
        const val BINARY = "application/octet-stream"
        const val SIZE = 3
        const val MODIFIED = 4
        val COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}
