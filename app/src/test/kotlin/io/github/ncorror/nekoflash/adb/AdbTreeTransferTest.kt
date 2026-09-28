package io.github.ncorror.nekoflash.adb

import io.github.ncorror.nekoflash.artifact.LocalEntry
import io.github.ncorror.nekoflash.artifact.LocalFolder
import io.github.ncorror.nekoflash.core.artifact.ArtifactAccess
import io.github.ncorror.nekoflash.core.artifact.ArtifactCommit
import io.github.ncorror.nekoflash.core.artifact.ArtifactIdentity
import io.github.ncorror.nekoflash.core.artifact.ArtifactRandomAccess
import io.github.ncorror.nekoflash.core.artifact.ArtifactSink
import io.github.ncorror.nekoflash.core.artifact.ArtifactSource
import io.github.ncorror.nekoflash.core.artifact.ArtifactStamp
import io.github.ncorror.nekoflash.protocol.adb.AdbSyncEntry
import io.github.ncorror.nekoflash.protocol.adb.AdbSyncOutcome
import io.github.ncorror.nekoflash.protocol.adb.AdbSyncProtocol
import io.github.ncorror.nekoflash.protocol.adb.AdbSyncStat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `adb push`/`pull` — как `do_sync_push` и `do_sync_pull` настольного клиента. */
class AdbTreeTransferTest {
    private val device = Device()

    @Test
    fun aFolderGoesUnderItsNameIntoAnExistingDirectory() {
        device.dirs += "/sdcard"
        val photos = Folder("photos", "a.jpg" to "AAA", "sub/b.jpg" to "BB")

        val report = AdbTreePush(device, sync = false).push(listOf(PushSource.Folder("photos", photos)), "/sdcard")

        assertTrue(report.success)
        assertEquals(mapOf("/sdcard/photos/a.jpg" to "AAA", "/sdcard/photos/sub/b.jpg" to "BB"), device.files)
    }

    @Test
    fun aFolderBecomesTheDestinationWhenItDoesNotExist() {
        AdbTreePush(device, sync = false).push(listOf(PushSource.Folder("photos", Folder("photos", "a" to "1"))), "/sdcard/new")

        assertEquals(mapOf("/sdcard/new/a" to "1"), device.files)
    }

    @Test
    fun severalSourcesNeedADirectory() {
        val report = AdbTreePush(device, sync = false).push(listOf(file("a", "1"), file("b", "2")), "/sdcard/x")

        assertEquals(listOf("error: target '/sdcard/x' is not a directory"), report.messages)
        assertEquals(emptyMap<String, String>(), device.files)
    }

    @Test
    fun aFileIntoADirectoryTakesItsTypedName() {
        device.dirs += "/sdcard"

        AdbTreePush(device, sync = false).push(listOf(file("notes/today.txt", "hi")), "/sdcard/")

        assertEquals(mapOf("/sdcard/today.txt" to "hi"), device.files)
    }

    @Test
    fun syncSkipsFilesWithTheSameSizeAndTime() {
        device.dirs += "/sdcard"
        device.files["/sdcard/photos/a"] = "111"
        device.times["/sdcard/photos/a"] = 5
        val folder = Folder("photos", "a" to "111", "b" to "2")

        val report = AdbTreePush(device, sync = true).push(listOf(PushSource.Folder("photos", folder)), "/sdcard")

        assertEquals(1, report.skipped)
        assertEquals(1, report.files)
    }

    @Test
    fun mkdirIsSentOnlyWhereTheDeviceNeedsIt() {
        device.needsMkdir = true
        AdbTreePush(device, sync = false).push(listOf(PushSource.Folder("p", Folder("p", "x/y/z" to "1"))), "/sdcard/p")

        assertEquals(listOf("/sdcard", "/sdcard/p/", "/sdcard/p/x/", "/sdcard/p/x/y/"), device.mkdirs)
        assertEquals("mkdir '/a b' 'it'\\''s'", AdbTransferController.mkdirCommands(listOf("/a b", "it's")).single())
    }

    @Test
    fun syncPushesEveryPartitionFolderOfTheBuildOutput() {
        device.files["/system/bin/same"] = "111"
        device.times["/system/bin/same"] = 5
        val out = Folder("out", "system/bin/same" to "111", "system/bin/new" to "2", "vendor/lib/x" to "3", "other/y" to "4")

        val report = AdbTreePush(device, sync = true).syncBuild(out, "all")

        assertEquals(1, report.skipped)
        assertEquals(setOf("/system/bin/same", "/system/bin/new", "/vendor/lib/x"), device.files.keys)
        assertEquals(
            listOf("error: don't know how to sync odm partition"),
            AdbTreePush(device, sync = true).syncBuild(out, "odm").messages,
        )
    }

    @Test
    fun syncListOnlyNamesWhatWouldGo() {
        val out = Folder("out", "vendor/lib/x" to "3")

        val report = AdbTreePush(device, sync = true, listOnly = true).syncBuild(out, "vendor")

        assertEquals(listOf("would push: vendor/lib/x -> /vendor/lib/x"), report.messages)
        assertEquals(emptyMap<String, String>(), device.files)
    }

    @Test
    fun dryRunSendsEveryFileAsAProbeAndWritesNothing() {
        device.dirs += "/sdcard"
        device.needsMkdir = true
        val photos = Folder("photos", "a.jpg" to "AAA", "sub/b.jpg" to "BB")

        val report = AdbTreePush(device, sync = false, dryRun = true)
            .push(listOf(PushSource.Folder("photos", photos)), "/sdcard")

        assertTrue(report.success)
        assertEquals(2, report.files)
        assertEquals(listOf("/sdcard/photos/a.jpg", "/sdcard/photos/sub/b.jpg"), device.rehearsed)
        assertEquals(emptyMap<String, String>(), device.files)
        // Обход `mkdir` в `copy_local_dir_remote` от `-n` не зависит.
        assertTrue(device.mkdirs.isNotEmpty())
    }

    @Test
    fun aRemoteDirectoryIsPulledRecursivelyUnderItsName() {
        device.dirs += listOf("/sdcard/DCIM", "/sdcard/DCIM/Camera", "/sdcard/DCIM/empty")
        device.files["/sdcard/DCIM/a.jpg"] = "A"
        device.files["/sdcard/DCIM/Camera/b.jpg"] = "B"
        val chosen = Folder("Download")

        val report = AdbTreePull(device).pull(listOf("/sdcard/DCIM"), PullTarget(chosen, null))

        assertTrue(report.messages.toString(), report.success)
        assertEquals(mapOf("DCIM/a.jpg" to "A", "DCIM/Camera/b.jpg" to "B"), chosen.written())
        assertTrue(chosen.hasFolder("DCIM/empty"))
    }

    @Test
    fun aRemoteFileTakesTheNamedDestination() {
        device.files["/sdcard/a.txt"] = "x"
        val chosen = Folder("Download")

        AdbTreePull(device).pull(listOf("/sdcard/a.txt"), PullTarget(chosen, "b.txt"))

        assertEquals(mapOf("b.txt" to "x"), chosen.written())
    }

    @Test
    fun aMissingRemoteObjectIsNamedAndTheRestContinues() {
        device.files["/sdcard/a"] = "1"
        val chosen = Folder("d")

        val report = AdbTreePull(device).pull(listOf("/sdcard/none", "/sdcard/a"), PullTarget(chosen, null))

        assertEquals(listOf("error: remote object '/sdcard/none' does not exist"), report.messages)
        assertEquals(mapOf("a" to "1"), chosen.written())
    }

    @Test
    fun aLinkToADirectoryIsFollowed() {
        device.dirs += listOf("/sdcard/real")
        device.files["/sdcard/real/f"] = "F"
        device.links["/sdcard/root/link"] = "/sdcard/real"
        device.dirs += "/sdcard/root"
        val chosen = Folder("d")

        AdbTreePull(device).pull(listOf("/sdcard/root"), PullTarget(chosen, null))

        assertEquals(mapOf("root/link/f" to "F"), chosen.written())
    }

    @Test
    fun remotePathHelpersFollowAndroidBase() {
        assertEquals("b", basename("/a/b/"))
        assertEquals("/a", dirname("/a/b/"))
        assertEquals("/", dirname("/a"))
    }

    private fun file(name: String, text: String) =
        PushSource.File(name, text.length.toLong(), 5_000L) { Source(text) }

    /** Устройство: каталоги, файлы, ссылки и время. */
    private class Device : AdbTransferPort {
        val dirs = mutableSetOf<String>()
        val files = mutableMapOf<String, String>()
        val times = mutableMapOf<String, Int>()
        val links = mutableMapOf<String, String>()
        val mkdirs = mutableListOf<String>()
        override var needsMkdir = false

        private fun resolve(path: String): String {
            val clean = path.trimEnd('/')
            return links[clean] ?: links.entries.firstOrNull { clean.startsWith(it.key + "/") }
                ?.let { it.value + clean.removePrefix(it.key) } ?: clean
        }

        override fun stat(path: String): AdbSyncOutcome<AdbSyncStat> {
            val clean = path.trimEnd('/')
            val target = if (path.endsWith('/')) resolve(path) else clean
            return AdbSyncOutcome.Done(
                when {
                    !path.endsWith('/') && clean in links -> AdbSyncStat(AdbSyncProtocol.MODE_SYMLINK or 0x1FF, 10, 0)
                    target in dirs -> AdbSyncStat(AdbSyncProtocol.MODE_DIRECTORY or 0x1ED, 0, 0)
                    target in files -> AdbSyncStat(AdbSyncProtocol.MODE_REGULAR_FILE or 0x1A4, files.getValue(target).length.toLong(), times[target] ?: 0)
                    else -> AdbSyncStat(0, 0, 0)
                },
            )
        }

        override fun list(path: String): AdbSyncOutcome<List<AdbSyncEntry>> {
            val base = resolve(path) + "/"
            val names = (dirs + files.keys + links.keys).filter { it.startsWith(base) && '/' !in it.removePrefix(base) }
            return AdbSyncOutcome.Done(
                listOf(AdbSyncEntry(".", AdbSyncStat(AdbSyncProtocol.MODE_DIRECTORY, 0, 0))) +
                    names.map { name ->
                        val stat = (stat(name) as AdbSyncOutcome.Done).value
                        AdbSyncEntry(name.removePrefix(base), stat)
                    },
            )
        }

        val rehearsed = mutableListOf<String>()

        override fun send(path: String, modifiedAtSeconds: Int, source: ArtifactSource, dryRun: Boolean): Moved {
            val text = source.open().readBytes().decodeToString()
            if (dryRun) {
                // Проба: устройство прочитало всё и ничего не записало.
                rehearsed += path
                return Moved.Done(text.length.toLong())
            }
            files[path] = text
            times[path] = modifiedAtSeconds
            return Moved.Done(text.length.toLong())
        }

        override fun receive(path: String, sink: ArtifactSink): Moved {
            val text = files[resolve(path)] ?: return Moved.Failed("no file")
            sink.open().use { it.write(text.toByteArray()) }
            sink.commit()
            return Moved.Done(text.length.toLong())
        }

        override fun mkdir(paths: List<String>) {
            mkdirs += paths
        }
    }

    /** Папка в памяти: «a/b» в описании — вложенные папки. */
    private class Folder(override val name: String, vararg content: Pair<String, String>) : LocalFolder {
        private val folders = mutableMapOf<String, Folder>()
        private val bytes = mutableMapOf<String, ByteArrayOutputStream>()

        init {
            content.forEach { (path, text) -> put(path.split('/'), text) }
        }

        private fun put(path: List<String>, text: String) {
            if (path.size == 1) {
                bytes[path[0]] = ByteArrayOutputStream().apply { write(text.toByteArray()) }
            } else {
                (folder(path[0]) as Folder).put(path.drop(1), text)
            }
        }

        override fun entries(): List<LocalEntry> =
            bytes.map { (name, data) -> LocalEntry.File(name, data.size().toLong(), 5_000L) { Source(data.toString()) } } +
                folders.map { (name, folder) -> LocalEntry.Folder(name, folder) }

        override fun folder(name: String): LocalFolder = folders.getOrPut(name) { Folder(name) }

        override fun file(name: String): ArtifactSink = object : ArtifactSink {
            override val destination = name
            override val atomicCommit = false
            override fun open(): OutputStream = ByteArrayOutputStream().also { bytes[name] = it }
            override fun commit(): ArtifactCommit = ArtifactCommit.Done(name, false)
            override fun abandon(reason: String) = Unit
        }

        fun written(prefix: String = ""): Map<String, String> =
            bytes.mapKeys { prefix + it.key }.mapValues { it.value.toString() } +
                folders.flatMap { (name, folder) -> folder.written("$prefix$name/").toList() }

        fun hasFolder(path: String): Boolean {
            val parts = path.split('/')
            val next = folders[parts[0]] ?: return false
            return parts.size == 1 || next.hasFolder(parts.drop(1).joinToString("/"))
        }
    }

    private class Source(private val text: String) : ArtifactSource {
        override val identity = ArtifactIdentity("f", text.length.toLong(), ArtifactAccess.SEEKABLE)
        override val openedStamp = ArtifactStamp(text.length.toLong(), null, null)
        override fun stamp() = openedStamp
        override fun open(): InputStream = ByteArrayInputStream(text.toByteArray())
        override fun randomAccess(): ArtifactRandomAccess? = null
    }
}
