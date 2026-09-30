package io.github.ncorror.nekoflash.adb

import io.github.ncorror.nekoflash.artifact.LocalEntry
import io.github.ncorror.nekoflash.artifact.LocalFolder
import io.github.ncorror.nekoflash.protocol.adb.AdbSyncOutcome
import io.github.ncorror.nekoflash.protocol.adb.AdbSyncProtocol
import io.github.ncorror.nekoflash.protocol.adb.AdbSyncStat

/**
 * Куда сохранять: папка, выбранная через Android, и имя в ней.
 *
 * [name] `null` — назначение и есть папка: `adb pull ПУТЬ` (назначение `.`)
 * или назначение с `/` на конце. Иначе папка — родитель, а [name] — то, что
 * настольный клиент создал бы по пути назначения.
 */
internal data class PullTarget(val folder: LocalFolder, val name: String?, val keepAttributes: Boolean = false)

/**
 * `adb pull` — как `do_sync_pull`, `copy_remote_dir_local` и
 * `remote_build_list` в AOSP `adb/client/file_sync_client.cpp`
 * (android11-release).
 *
 * Каталог устройства уходит рекурсивно: сначала весь список (`LIST` по
 * каждому уровню, ссылки уточняются `STAT`), потом каталоги создаются, а
 * файлы читаются. Особые файлы, кроме блочных и символьных устройств,
 * пропускаются с предупреждением. С `-a` у каждого файла — время изменения и
 * режим с устройства (`set_time_and_mode`); не вышло — перенос файла
 * неудачен, а каталог дальше не идёт, как `return false` у AOSP.
 */
internal class AdbTreePull(private val port: AdbTransferPort) {
    private val ledger = TransferLedger()

    private var keep = false

    fun pull(sources: List<String>, target: PullTarget): AdbTransferReport {
        keep = target.keepAttributes
        run(sources, target)
        return ledger.report()
    }

    private fun run(sources: List<String>, target: PullTarget) {
        val existing = target.name?.let { name -> target.folder.entries().firstOrNull { it.name == name } }
        val destination = Destination(target, existing)
        if (!destination.isDirectory && sources.size > 1) {
            ledger.error("target '${destination.shown}' is not a directory")
        } else {
            sources.all { source -> pullOne(source, destination) }
        }
    }

    /** Что лежит по пути назначения: как `stat(dst)` у настольного клиента. */
    private class Destination(val target: PullTarget, val existing: LocalEntry?) {
        val exists = target.name == null || existing != null
        val isDirectory = target.name == null || existing is LocalEntry.Folder
        val shown = target.name ?: target.folder.name

        /** Папка-назначение, когда назначение — каталог. */
        val directory: LocalFolder get() = (existing as? LocalEntry.Folder)?.folder ?: target.folder

        /** Новое имя в папке-родителе, когда назначения нет. */
        val named: String get() = checkNotNull(target.name)
    }

    /** Один источник; `false` — дальше не идём, как `return false` в AOSP. */
    private fun pullOne(source: String, destination: Destination): Boolean {
        when (val kind = port.kindOf(source)) {
            is AdbSyncOutcome.Failed -> ledger.error("failed to stat remote object '$source': ${kind.detail}")
            is AdbSyncOutcome.Done -> when (val found = kind.value) {
                RemoteKind.Missing -> ledger.error("remote object '$source' does not exist")
                RemoteKind.Directory -> return pullDirectory(source, destination)
                is RemoteKind.Other -> pullFile(source, found, destination)
            }
        }
        return true
    }

    private fun pullDirectory(source: String, destination: Destination): Boolean {
        if (destination.exists && !destination.isDirectory) {
            ledger.error("target '${destination.shown}' is not a directory")
            return false
        }
        val folder = if (destination.exists) {
            destination.directory.folder(basename(source))
        } else {
            destination.target.folder.folder(destination.named)
        }
        copyDirectory(source, folder)
        return true
    }

    private fun pullFile(source: String, found: RemoteKind.Other, destination: Destination) {
        if (pullable(found.stat.mode)) {
            val (folder, name) = if (destination.isDirectory) {
                destination.directory to basename(source)
            } else {
                destination.target.folder to destination.named
            }
            received(source, folder, name, found.stat)
        } else {
            ledger.warning(special(source, found.stat.mode))
        }
    }

    /** `copy_remote_dir_local`: сначала весь список, потом перенос. */
    private fun copyDirectory(source: String, folder: LocalFolder) {
        val items = mutableListOf<Item>()
        if (!collect(withSlash(source), emptyList(), items)) return
        val folders = mutableMapOf(emptyList<String>() to folder)
        fun folderAt(path: List<String>): LocalFolder =
            folders.getOrPut(path) { folderAt(path.dropLast(1)).folder(path.last()) }
        for (item in items) {
            when (item) {
                is Item.Directory -> folderAt(item.path)
                is Item.File -> if (!received(item.remote, folderAt(item.path), item.name, item.stat)) return
                is Item.Skipped -> ledger.skipped++
            }
        }
    }

    /** Файл в [folder] под именем [name]; с `-a` — ещё время и режим. */
    private fun received(remote: String, folder: LocalFolder, name: String, stat: AdbSyncStat): Boolean {
        if (!ledger.moved(port.receive(remote, folder.file(name)), remote)) return false
        if (!keep) return true
        val problem = folder.keepTimeAndMode(name, stat.modifiedAtSeconds.toLong() and UINT, stat.mode)
        problem?.let { ledger.error("failed to preserve time and mode of '$remote': $it") }
        return problem == null
    }

    /**
     * `remote_build_list`: запись самого каталога, его файлы, ссылки, затем
     * подкаталоги — с конца, как `dirlist.pop_back()`.
     */
    private fun collect(remote: String, local: List<String>, items: MutableList<Item>): Boolean {
        items += Item.Directory(local)
        val entries = when (val listed = port.list(remote)) {
            is AdbSyncOutcome.Failed -> {
                ledger.error("failed to list '$remote': ${listed.detail}")
                return false
            }
            is AdbSyncOutcome.Done -> listed.value.filter { it.name != "." && it.name != ".." }
        }
        val directories = mutableListOf<String>()
        for (entry in entries.filter { !it.symlink }) {
            when {
                entry.stat.directory -> directories += entry.name
                pullable(entry.stat.mode) -> items += Item.File(local, entry.name, remote + entry.name, entry.stat)
                else -> {
                    ledger.warning(special(remote + entry.name, entry.stat.mode))
                    items += Item.Skipped
                }
            }
        }
        for (link in entries.filter { it.symlink }) {
            when (val kind = port.kindOf(remote + link.name)) {
                is AdbSyncOutcome.Failed -> ledger.warning("stat failed for path ${remote + link.name}: ${kind.detail}")
                is AdbSyncOutcome.Done -> when (val target = kind.value) {
                    RemoteKind.Directory -> directories += link.name
                    is RemoteKind.Other -> items += Item.File(local, link.name, remote + link.name, target.stat)
                    RemoteKind.Missing -> items += Item.File(local, link.name, remote + link.name, link.stat)
                }
            }
        }
        return directories.reversed().all { name -> collect("$remote$name/", local + name, items) }
    }

    private sealed interface Item {
        data class Directory(val path: List<String>) : Item
        data class File(val path: List<String>, val name: String, val remote: String, val stat: AdbSyncStat) : Item
        data object Skipped : Item
    }

    private companion object {
        const val OCTAL = 8
        const val UINT = 0xFFFF_FFFFL
        const val BLOCK_DEVICE = 0x6000
        const val CHARACTER_DEVICE = 0x2000

        fun special(path: String, mode: Int): String =
            "skipping special file '$path' (mode = 0o${mode.toString(OCTAL)})"

        /** `should_pull_file`: обычный файл, блочное или символьное устройство. */
        fun pullable(mode: Int): Boolean = when (mode and AdbSyncProtocol.MODE_TYPE_MASK) {
            AdbSyncProtocol.MODE_REGULAR_FILE, BLOCK_DEVICE, CHARACTER_DEVICE -> true
            else -> false
        }
    }
}
