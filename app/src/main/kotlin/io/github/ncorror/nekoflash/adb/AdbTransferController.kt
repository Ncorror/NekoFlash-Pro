package io.github.ncorror.nekoflash.adb

import io.github.ncorror.nekoflash.artifact.LocalFolder
import io.github.ncorror.nekoflash.artifact.SafArtifactSink
import io.github.ncorror.nekoflash.core.artifact.ArtifactSink
import io.github.ncorror.nekoflash.core.artifact.ArtifactSource
import io.github.ncorror.nekoflash.core.artifact.ArtifactWriteOutcome
import io.github.ncorror.nekoflash.core.artifact.ArtifactWriter
import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticSink
import io.github.ncorror.nekoflash.protocol.adb.AdbConnection
import io.github.ncorror.nekoflash.protocol.adb.AdbSyncEntry
import io.github.ncorror.nekoflash.protocol.adb.AdbSyncOutcome
import io.github.ncorror.nekoflash.protocol.adb.AdbSyncSendOutcome
import io.github.ncorror.nekoflash.protocol.adb.AdbSyncSession
import io.github.ncorror.nekoflash.protocol.adb.AdbSyncStat
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Что происходит с последним `adb push`/`pull`. */
public sealed interface AdbTransferState {
    public data object None : AdbTransferState

    public data class Running(val what: String) : AdbTransferState

    public data class Finished(val what: String, val report: AdbTransferReport) : AdbTransferState

    /** Сессия `sync:` не открылась или перенос сорвался исключением. */
    public data class Failed(val what: String, val detail: String) : AdbTransferState
}

/**
 * `adb push`/`pull` настольного клиента: несколько файлов и каталоги.
 *
 * Одна сессия `sync:` на весь перенос, как `SyncConnection` у настольного
 * клиента. Правила — в [AdbTreePush] и [AdbTreePull].
 */
public class AdbTransferController(
    private val executor: Executor,
    private val diagnostics: DiagnosticSink,
) {
    private val mutableState = MutableStateFlow<AdbTransferState>(AdbTransferState.None)

    public val state: StateFlow<AdbTransferState> = mutableState.asStateFlow()

    public val active: Boolean
        get() = mutableState.value is AdbTransferState.Running

    internal fun push(connection: AdbConnection, sources: () -> List<PushSource>, destination: String, sync: Boolean) {
        run(connection, "push → $destination") { port -> AdbTreePush(port, sync).push(sources(), destination) }
    }

    internal fun syncBuild(
        connection: AdbConnection,
        productOut: () -> LocalFolder,
        partition: String,
        listOnly: Boolean,
    ) {
        run(connection, "sync $partition") { port ->
            AdbTreePush(port, sync = true, listOnly = listOnly).syncBuild(productOut(), partition)
        }
    }

    internal fun pull(connection: AdbConnection, sources: List<String>, target: () -> PullTarget) {
        run(connection, "pull ${sources.joinToString(" ")}") { port -> AdbTreePull(port).pull(sources, target()) }
    }

    private fun run(connection: AdbConnection, what: String, work: (AdbTransferPort) -> AdbTransferReport) {
        if (active) return
        mutableState.value = AdbTransferState.Running(what)
        executor.execute {
            val session = connection.syncSession(diagnostics)
            mutableState.value = when (val opened = session.open()) {
                is AdbSyncOutcome.Failed -> AdbTransferState.Failed(what, "${opened.reason.name}: ${opened.detail}")
                is AdbSyncOutcome.Done -> runCatching { work(SessionPort(session, connection)) }.fold(
                    onSuccess = { AdbTransferState.Finished(what, it) },
                    onFailure = { AdbTransferState.Failed(what, it.message ?: it.javaClass.simpleName) },
                )
            }
            session.close()
        }
    }

    /** Порт поверх открытой сессии и того же соединения. */
    private class SessionPort(private val session: AdbSyncSession, private val connection: AdbConnection) :
        AdbTransferPort {
        override fun stat(path: String): AdbSyncOutcome<AdbSyncStat> = session.stat(path)

        override fun list(path: String): AdbSyncOutcome<List<AdbSyncEntry>> = session.list(path)

        override fun send(path: String, modifiedAtSeconds: Int, source: ArtifactSource): Moved =
            source.open().use { input ->
                val sent = session.send(path, modifiedAtSeconds) { buffer -> input.read(buffer).coerceAtLeast(0) }
                when (sent) {
                    is AdbSyncSendOutcome.Committed -> Moved.Done(sent.bytesSent)
                    is AdbSyncSendOutcome.Failed -> Moved.Failed("${sent.reason.name}: ${sent.detail}")
                }
            }

        override fun receive(path: String, sink: ArtifactSink): Moved {
            val writer = ArtifactWriter(sink)
            return when (val received = session.receive(path) { chunk -> writer.accept(chunk) }) {
                is AdbSyncOutcome.Failed -> {
                    writer.interrupted(received.detail)
                    (sink as? SafArtifactSink)?.removed()
                    Moved.Failed("${received.reason.name}: ${received.detail}")
                }
                is AdbSyncOutcome.Done -> when (val saved = writer.finish()) {
                    is ArtifactWriteOutcome.Committed -> Moved.Done(saved.bytes)
                    else -> Moved.Failed(saved.toString()).also { (sink as? SafArtifactSink)?.removed() }
                }
            }
        }

        override val needsMkdir: Boolean
            get() = FIXED_PUSH_MKDIR !in connection.peerFeatures && connection.supportsShellV2

        override fun mkdir(paths: List<String>) {
            mkdirCommands(paths).forEach { command -> connection.shell(command) }
        }
    }

    internal companion object {
        const val FIXED_PUSH_MKDIR = "fixed_push_mkdir"
        private const val MKDIR = "mkdir"
        private const val MAX_COMMAND = 32_768

        /**
         * `mkdir` одной или несколькими командами, как в `copy_local_dir_remote`:
         * пути — в одинарных кавычках (`escape_arg`), команда не длиннее 32 КиБ.
         */
        fun mkdirCommands(paths: List<String>): List<String> {
            val commands = mutableListOf<String>()
            var command = MKDIR
            for (path in paths.map(::escapeArg)) {
                if (command.length + path.length > MAX_COMMAND) {
                    commands += command
                    command = MKDIR
                }
                command += " $path"
            }
            if (command != MKDIR) commands += command
            return commands
        }

        /** `escape_arg`: одинарные кавычки, `'` внутри — как `'\''`. */
        fun escapeArg(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    }
}
