package io.github.ncorror.nekoflash.adb

import io.github.ncorror.nekoflash.artifact.LocalEntry
import io.github.ncorror.nekoflash.artifact.LocalFolder
import io.github.ncorror.nekoflash.core.artifact.ArtifactSource
import io.github.ncorror.nekoflash.protocol.adb.AdbSyncOutcome

/** Что отправляется: [name] — как назвал источник оператор, по нему строится путь на устройстве. */
internal sealed interface PushSource {
    val name: String

    data class File(
        override val name: String,
        val sizeBytes: Long,
        val modifiedAtMillis: Long?,
        val open: () -> ArtifactSource,
    ) : PushSource

    data class Folder(override val name: String, val folder: LocalFolder) : PushSource
}

/**
 * `adb push` — как `do_sync_push` и `copy_local_dir_remote` в AOSP
 * `adb/client/file_sync_client.cpp` (android11-release).
 *
 * Назначение-каталог получает источник под его именем; несколько источников
 * требуют каталога; каталог-источник уходит рекурсивно, и пустые каталоги
 * создаются только обходом `mkdir`, как у настольного клиента. `--sync`
 * пропускает файл, у которого на устройстве то же время (у каталога — и
 * тот же размер).
 */
internal class AdbTreePush(
    private val port: AdbTransferPort,
    private val sync: Boolean,
    private val listOnly: Boolean = false,
    private val nowSeconds: () -> Int = { (System.currentTimeMillis() / MILLIS).toInt() },
) {
    private val ledger = TransferLedger()

    fun push(sources: List<PushSource>, destination: String): AdbTransferReport {
        run(sources, destination)
        return ledger.report()
    }

    /**
     * `adb sync [-l] [РАЗДЕЛ]`: [productOut] — выбранная папка вместо
     * `$ANDROID_PRODUCT_OUT`. Каждый раздел списка, у которого там есть
     * папка, уходит в `/РАЗДЕЛ` с проверкой времени и размера; `-l` только
     * называет, что ушло бы.
     */
    fun syncBuild(productOut: LocalFolder, partition: String): AdbTransferReport {
        val folders = productOut.entries().filterIsInstance<LocalEntry.Folder>().associateBy { it.name }
        var found = false
        for (name in SYNC_PARTITIONS) {
            val local = folders[name]
            if ((partition == ALL || partition == name) && local != null) {
                found = true
                if (!copyFolder(local.folder, "/$name", "$name/")) break
            }
        }
        if (!found) ledger.error("don't know how to sync $partition partition")
        return ledger.report()
    }

    private fun run(sources: List<PushSource>, destination: String) {
        when (val answer = port.kindOf(destination)) {
            is AdbSyncOutcome.Failed ->
                ledger.error("stat failed when trying to push to $destination: ${answer.detail}")
            is AdbSyncOutcome.Done -> {
                val exists = answer.value != RemoteKind.Missing
                val isDirectory = answer.value == RemoteKind.Directory
                val problem = when {
                    isDirectory -> null
                    sources.size > 1 -> "target '$destination' is not a directory"
                    destination.endsWith('/') && exists -> "failed to access '$destination': Not a directory"
                    else -> null
                }
                if (problem != null) {
                    ledger.error(problem)
                } else {
                    sources.none { source -> pushOne(source, destination, exists, isDirectory) }
                }
            }
        }
    }

    /** `true` — дальше не идём. */
    private fun pushOne(source: PushSource, destination: String, exists: Boolean, isDirectory: Boolean): Boolean =
        when (source) {
            is PushSource.Folder -> folder(source, destination, exists, isDirectory)
            is PushSource.File -> {
                val path = if (isDirectory) withSlash(destination) + basename(source.name) else destination
                file(path, source.modifiedAtMillis, source.open)
                false
            }
        }

    /** `true` — дальше не идём: назначение не каталог. */
    private fun folder(source: PushSource.Folder, destination: String, exists: Boolean, isDirectory: Boolean): Boolean {
        if (exists && !isDirectory) {
            ledger.error("target '$destination' is not a directory")
            return true
        }
        val target = if (exists) withSlash(destination) + basename(source.name) else destination
        copyFolder(source.folder, target, withSlash(source.name))
        return false
    }

    /** `copy_local_dir_remote`; `false` — перенос сорвался. [shown] — как назвать папку в выводе `-l`. */
    private fun copyFolder(folder: LocalFolder, destination: String, shown: String): Boolean {
        val root = withSlash(destination)
        val directories = generateSequence(root) { path -> dirname(path).takeIf { it != path } }
            .takeWhile { path -> path.any { it != '/' } && path != "." }
            .toList()
            .reversed()
            .toMutableList()
        val files = mutableListOf<Pair<String, LocalEntry.File>>()
        collect(folder, root, files, directories)
        if (port.needsMkdir && directories.isNotEmpty()) port.mkdir(directories)
        val skip = if (sync) {
            files.filter { (path, file) -> unchanged(path, file) }.map { it.first }.toSet()
        } else {
            emptySet()
        }
        for ((path, file) in files) {
            when {
                path in skip -> ledger.skipped++
                listOnly -> ledger.note("would push: ${shown + path.removePrefix(root)} -> $path")
                !send(path, file.modifiedAtMillis, file.open) -> return false
            }
        }
        return true
    }

    /** `local_build_list`: файлы каталога, потом его подкаталоги. */
    private fun collect(
        folder: LocalFolder,
        remote: String,
        files: MutableList<Pair<String, LocalEntry.File>>,
        directories: MutableList<String>,
    ) {
        val subfolders = mutableListOf<LocalEntry.Folder>()
        for (entry in folder.entries()) {
            when (entry) {
                is LocalEntry.Folder -> subfolders += entry
                is LocalEntry.File -> files += remote + entry.name to entry
            }
        }
        for (subfolder in subfolders) {
            val path = remote + subfolder.name + "/"
            directories += path
            collect(subfolder.folder, path, files, directories)
        }
    }

    /** Размер и время на устройстве совпали — как проверка `check_timestamps`. */
    private fun unchanged(path: String, file: LocalEntry.File): Boolean {
        val stat = (port.stat(path) as? AdbSyncOutcome.Done)?.value ?: return false
        val time = file.modifiedAtMillis?.let { (it / MILLIS).toInt() } ?: return false
        return stat.exists && stat.size == file.sizeBytes && stat.modifiedAtSeconds == time
    }

    /** `sync_send`: с `--sync` пропускает файл с тем же временем на устройстве. */
    private fun file(path: String, modifiedAtMillis: Long?, open: () -> ArtifactSource) {
        val time = modifiedAtMillis?.let { (it / MILLIS).toInt() }
        val remote = if (sync && time != null) (port.stat(path) as? AdbSyncOutcome.Done)?.value else null
        if (remote != null && remote.exists && remote.modifiedAtSeconds == time) {
            ledger.skipped++
        } else {
            send(path, modifiedAtMillis, open)
        }
    }

    private fun send(path: String, modifiedAtMillis: Long?, open: () -> ArtifactSource): Boolean {
        val time = modifiedAtMillis?.let { (it / MILLIS).toInt() } ?: nowSeconds()
        return ledger.moved(port.send(path, time, open()), path)
    }

    private companion object {
        const val MILLIS = 1000L
        const val ALL = "all"

        /** Разделы `adb sync` в порядке настольного клиента. */
        val SYNC_PARTITIONS = listOf("data", "odm", "oem", "product", "system", "system_ext", "vendor")
    }
}
