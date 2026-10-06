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
import io.github.ncorror.nekoflash.protocol.adb.AdbSyncProtocol
import io.github.ncorror.nekoflash.protocol.adb.AdbSyncSendMode
import io.github.ncorror.nekoflash.protocol.adb.AdbSyncSendOutcome
import io.github.ncorror.nekoflash.protocol.adb.AdbSyncSession
import io.github.ncorror.nekoflash.protocol.adb.AdbSyncStat
import io.github.ncorror.nekoflash.protocol.adb.SyncCompression
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.StateFlow

/** Что происходит с последним `adb push`/`pull`. */
public sealed interface AdbTransferState {
    public data object None : AdbTransferState

    public data class Running(val what: String) : AdbTransferState

    public data class Finished(val what: String, val report: AdbTransferReport) : AdbTransferState

    /** Сессия `sync:` не открылась или перенос сорвался исключением. */
    public data class Failed(val what: String, val detail: String) : AdbTransferState
}

/** `-n` и сжатие записи: имя из `-z` (`any` — лучшее, что объявило устройство; `none` — без сжатия). */
internal data class AdbPushMode(val dryRun: Boolean = false, val compression: String = SyncCompression.ANY)

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
    private val slot = AdbOperationSlot<AdbTransferState>(AdbTransferState.None)

    public val state: StateFlow<AdbTransferState> = slot.state

    public val active: Boolean
        get() = slot.active

    internal fun bind(connection: AdbConnection?, discard: Boolean = false) = slot.bind(connection, discard)

    internal fun push(
        connection: AdbConnection,
        sources: () -> List<PushSource>,
        destination: String,
        sync: Boolean,
        mode: AdbPushMode,
    ) {
        run(connection, "push → $destination", mode.compression) { port ->
            AdbTreePush(port, sync, dryRun = mode.dryRun).push(sources(), destination)
        }
    }

    internal fun syncBuild(
        connection: AdbConnection,
        productOut: () -> LocalFolder,
        partition: String,
        listOnly: Boolean,
        mode: AdbPushMode,
    ) {
        run(connection, "sync $partition", mode.compression) { port ->
            AdbTreePush(port, sync = true, listOnly = listOnly, dryRun = mode.dryRun).syncBuild(productOut(), partition)
        }
    }

    internal fun pull(connection: AdbConnection, sources: List<String>, compression: String, target: () -> PullTarget) {
        run(connection, "pull ${sources.joinToString(" ")}", compression) { port ->
            AdbTreePull(port).pull(sources, target())
        }
    }

    private fun run(
        connection: AdbConnection,
        what: String,
        compression: String,
        work: (AdbTransferPort) -> AdbTransferReport,
    ) {
        val request = slot.begin(connection, AdbTransferState.Running(what)) ?: return
        slot.submit(executor, request, failure = { AdbTransferState.Failed(what, it.javaClass.simpleName) }) {
            val session = connection.syncSession(diagnostics)
            try {
                when (val opened = session.open()) {
                    is AdbSyncOutcome.Failed -> AdbTransferState.Failed(what, "${opened.reason.name}: ${opened.detail}")
                    is AdbSyncOutcome.Done -> {
                        request.requireConnected()
                        AdbTransferState.Finished(what, work(SessionPort(session, request, compression)))
                    }
                }
            } finally {
                session.close()
            }
        }
    }

    /**
     * Порт поверх открытой сессии и того же соединения.
     *
     * Версию и сжатие выбирает как `SyncConnection` Android 14: при
     * `sendrecv_v2` `pull` идёт через `RCV2`, а `push` файла от 64 КиБ — через
     * `SND2`, оба со сжатием [compressionName] (`ResolveCompressionType`).
     * Файл меньше 64 КиБ уходит `SEND` без сжатия (`SendSmallFile`), кроме
     * пробы `-n`: она — `SND2` без сжатия.
     */
    private class SessionPort(
        private val session: AdbSyncSession,
        private val request: AdbOperationSlot.Request<AdbTransferState>,
        compressionName: String,
    ) : AdbTransferPort {
        private val connection = request.connection
        private val v2 = SEND_RECV_V2 in connection.peerFeatures
        private val compression = SyncCompression.named(compressionName, connection.peerFeatures)

        override fun stat(path: String): AdbSyncOutcome<AdbSyncStat> {
            request.requireConnected()
            return session.stat(path)
        }

        override fun list(path: String): AdbSyncOutcome<List<AdbSyncEntry>> {
            request.requireConnected()
            return session.list(path)
        }

        override fun send(path: String, modifiedAtSeconds: Int, source: ArtifactSource, dryRun: Boolean): Moved =
            if (dryRun && DRY_RUN_SEND !in connection.peerFeatures) {
                // Как `SendLargeFile` настольного клиента: без возможности у
                // устройства проба не отправляется вовсе.
                Moved.Failed("dry-run not supported by the device")
            } else {
                request.requireConnected()
                val small = (source.identity.sizeBytes ?: Long.MAX_VALUE) < AdbSyncProtocol.DATA_CHUNK_BYTES
                val large = v2 && !small
                source.open().use { input ->
                    request.requireConnected()
                    sent(path, modifiedAtSeconds, dryRun, input, large)
                }
            }

        private fun sent(
            path: String,
            modifiedAtSeconds: Int,
            dryRun: Boolean,
            input: java.io.InputStream,
            large: Boolean,
        ): Moved {
            val sendMode = AdbSyncSendMode(dryRun = dryRun, v2 = large, compression = compression.takeIf { large })
            val sent = session.send(path, modifiedAtSeconds, sendMode = sendMode) { buffer ->
                input.read(buffer).coerceAtLeast(0)
            }
            return when (sent) {
                is AdbSyncSendOutcome.Committed -> Moved.Done(sent.bytesSent)
                is AdbSyncSendOutcome.Rehearsed -> Moved.Done(sent.bytesSent)
                is AdbSyncSendOutcome.Failed -> Moved.Failed("${sent.reason.name}: ${sent.detail}")
            }
        }

        override fun receive(path: String, sink: ArtifactSink): Moved {
            val writer = ArtifactWriter(sink)
            val received = runCatching {
                request.requireConnected()
                session.receive(path, v2 = v2, compression = compression.takeIf { v2 }) { chunk ->
                    request.requireConnected()
                    writer.accept(chunk)
                }.also { request.requireConnected() }
            }.getOrElse { error ->
                writer.interrupted(error.javaClass.simpleName)
                (sink as? SafArtifactSink)?.removed()
                return Moved.Failed(error.javaClass.simpleName)
            }
            return when (received) {
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
            mkdirCommands(paths).forEach { command -> request.requireConnected(); connection.shell(command) }
        }
    }

    internal companion object {
        const val FIXED_PUSH_MKDIR = "fixed_push_mkdir"

        /** `kFeatureSendRecv2DryRunSend`: устройство понимает `SEND_V2` с `kSyncFlagDryRun`. */
        const val DRY_RUN_SEND = "sendrecv_v2_dry_run_send"

        /** `kFeatureSendRecv2`: устройство понимает `SND2` и `RCV2`. */
        const val SEND_RECV_V2 = "sendrecv_v2"
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
