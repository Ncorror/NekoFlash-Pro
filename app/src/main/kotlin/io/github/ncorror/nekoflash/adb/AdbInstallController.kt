package io.github.ncorror.nekoflash.adb

import io.github.ncorror.nekoflash.artifact.ArtifactStagingOutcome
import io.github.ncorror.nekoflash.artifact.StagedArtifactSource
import io.github.ncorror.nekoflash.core.artifact.ArtifactSource
import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticEvent
import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticSink
import io.github.ncorror.nekoflash.protocol.adb.AdbByteChannel
import io.github.ncorror.nekoflash.protocol.adb.AdbConnection
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.StateFlow

/** Что происходит с установкой пакетов. */
public sealed interface AdbInstallState {
    public data object None : AdbInstallState

    /** Идёт [stage]; байты — по всем файлам вместе. */
    public data class Running(val stage: String, val sentBytes: Long, val totalBytes: Long) : AdbInstallState

    /**
     * Устройство ответило. Успех — слово `Success` в ответе `package`, как его
     * читает настольный клиент; остальное показывается как есть.
     */
    public data class Finished(val success: Boolean, val answer: String, val files: Int, val bytes: Long) :
        AdbInstallState

    /** До ответа устройства дело не дошло; [detail] называет, где остановились. */
    public data class Failed(val detail: String) : AdbInstallState
}

/**
 * `adb install` и `adb install-multiple` так, как их делает настольный клиент.
 *
 * Одиночный пакет — поток `exec:cmd package install -S РАЗМЕР`: байты APK
 * уходят в поток, `package` отвечает `Success` или причиной отказа. Несколько
 * — сессия `install-create`, по `install-write` на файл и `install-commit`;
 * при сбое посередине сессия отменяется `install-abandon`, чтобы на устройстве
 * не осталось половины установки.
 *
 * Размер каждого файла обязан быть известен до первого байта: он уходит в
 * саму команду. Если SAF-провайдер его не сообщает, источник сначала одним
 * проходом стажируется во временный файл, где размер становится доказанным.
 */
public class AdbInstallController(
    private val executor: Executor,
    private val diagnostics: DiagnosticSink,
) {
    private val slot = AdbOperationSlot<AdbInstallState>(AdbInstallState.None)

    public val state: StateFlow<AdbInstallState> = slot.state

    public val active: Boolean
        get() = slot.active

    internal fun bind(connection: AdbConnection?, discard: Boolean = false) = slot.bind(connection, discard)

    /** Устанавливает [origins]; [several] — сессией `install-multiple`, даже для одного файла. */
    public fun install(
        connection: AdbConnection,
        stagingDirectory: File,
        origins: List<() -> ArtifactSource>,
        options: List<String>,
        several: Boolean,
    ) {
        if (origins.isEmpty()) return
        val request = slot.begin(connection, AdbInstallState.Running("open", 0, 0), retainOutcome = true) ?: return
        slot.submit(executor, request, failure = { AdbInstallState.Failed(it.javaClass.simpleName) }) {
            val staged = mutableListOf<StagedArtifactSource>()
            val outcome = try {
                val sources = origins.mapIndexed { index, origin ->
                    request.requireConnected()
                    prepare(request, origin(), stagingDirectory, index, origins.size, staged)
                }
                request.requireConnected()
                val sizes = sources.map { source -> checkNotNull(source.identity.sizeBytes) }
                Install(request, sources, sizes, options).run(several)
            } catch (failure: InstallStagingFailure) {
                AdbInstallState.Failed(failure.detail)
            } catch (failure: Throwable) {
                AdbInstallState.Failed(failure.javaClass.simpleName)
            } finally {
                staged.forEach(StagedArtifactSource::close)
            }
            if (request.current) emit(outcome)
            outcome
        }
    }

    private fun prepare(
        request: AdbOperationSlot.Request<AdbInstallState>,
        source: ArtifactSource,
        stagingDirectory: File,
        index: Int,
        count: Int,
        staged: MutableList<StagedArtifactSource>,
    ): ArtifactSource {
        if (source.identity.sizeBytes != null) return source
        val stage = "stage ${index + 1}/$count"
        return when (
            val outcome = StagedArtifactSource.stage(source, stagingDirectory) { bytes ->
                request.publish(AdbInstallState.Running(stage, bytes, 0))
            }
        ) {
            is ArtifactStagingOutcome.Staged -> outcome.source.also(staged::add)
            is ArtifactStagingOutcome.Failed -> throw InstallStagingFailure(outcome.detail)
        }.also { request.requireConnected() }
    }

    private fun emit(outcome: AdbInstallState) {
        val fields = when (outcome) {
            is AdbInstallState.Finished -> mapOf(
                "success" to outcome.success.toString(),
                "files" to outcome.files.toString(),
                "bytes" to outcome.bytes.toString(),
                "answer" to outcome.answer.lineSequence().firstOrNull().orEmpty(),
            )
            is AdbInstallState.Failed -> mapOf("detail" to outcome.detail)
            else -> emptyMap()
        }
        diagnostics.emit(DiagnosticEvent(Instant.now(), CATEGORY, "install_finished", fields = fields))
    }

    /** Одна установка: источники, их размеры и счёт отправленного. */
    private inner class Install(
        private val request: AdbOperationSlot.Request<AdbInstallState>,
        private val sources: List<ArtifactSource>,
        private val sizes: List<Long>,
        private val options: List<String>,
    ) {
        private val total = sizes.sum()
        private var sent = 0L

        fun run(several: Boolean): AdbInstallState =
            if (several) session() else single()

        private fun single(): AdbInstallState {
            request.requireConnected()
            val answer = sources[0].open().use { exchange("install", InstallCommands.install(options, sizes[0]), it) }
            return finished(answer)
        }

        private fun session(): AdbInstallState {
            val created = exchange("install-create", InstallCommands.create(options, total), null)
            val id = InstallCommands.session(created)
                ?: return AdbInstallState.Finished(false, created.ifBlank { "install-create: no answer" }, 0, 0)
            val failure = runCatching { sources.indices.firstNotNullOfOrNull { index -> write(id, index) } }
                .getOrElse { error ->
                    // A local provider/queue failure still owns this package session.
                    // Never abandon through a replacement connection.
                    if (request.connected) {
                        runCatching { exchange("install-abandon", InstallCommands.abandon(id), null) }
                    }
                    throw error
                }
            return if (failure != null) {
                exchange("install-abandon", InstallCommands.abandon(id), null)
                AdbInstallState.Finished(false, failure, sources.size, sent)
            } else {
                finished(exchange("install-commit", InstallCommands.commit(id), null))
            }
        }

        /** Пишет один файл в сессию; `null` — принят, иначе ответ отказа. */
        private fun write(id: String, index: Int): String? {
            request.requireConnected()
            val command = InstallCommands.write(id, index, sources[index].identity.name, sizes[index])
            val stage = "install-write ${index + 1}/${sources.size}"
            val answer = sources[index].open().use { exchange(stage, command, it) }
            return answer.takeUnless(InstallCommands::accepted)?.ifBlank { "install-write: no answer" }
        }

        private fun finished(answer: String): AdbInstallState =
            AdbInstallState.Finished(InstallCommands.accepted(answer), answer.trim(), sources.size, sent)

        /**
         * Открывает сервис, отдаёт [input] и собирает ответ до закрытия потока
         * устройством.
         *
         * Канал не сообщает конец ввода сразу: закрыть поток по нашей стороне
         * значило бы не дождаться `Success`, который `package` пишет **после**
         * того, как прочитал все байты. Поэтому чтение, исчерпав файл, ждёт,
         * пока поток закроет устройство.
         */
        private fun exchange(stage: String, service: String, input: InputStream?): String {
            request.requireConnected()
            request.publish(AdbInstallState.Running(stage, sent, total))
            val channel = InstallChannel(input) { bytes ->
                sent += bytes
                request.publish(AdbInstallState.Running(stage, sent, total))
            }
            val stream = request.connection.forwardStream(channel, diagnostics)
            try {
                val refused = stream.open(service)
                if (refused != null) return "${refused.end.name}: ${refused.detail}"
                executor.execute {
                    runCatching { stream.fromClient() }.onFailure { stream.cancel(it.javaClass.simpleName) }
                }
                val outcome = stream.fromDevice()
                return channel.answer().ifBlank { "${outcome.end.name}: ${outcome.detail}" }
            } finally {
                channel.close()
                stream.cancel("install exchange finished")
            }
        }
    }

    /** Канал установки: байты файла туда, ответ `package` обратно. */
    private class InstallChannel(
        private val input: InputStream?,
        private val onSent: (Int) -> Unit,
    ) : AdbByteChannel {
        private val closed = CountDownLatch(1)
        private val answer = StringBuilder()

        override fun read(destination: ByteArray): Int {
            val read = input?.read(destination) ?: -1
            return if (read > 0) {
                read.also(onSent)
            } else {
                closed.await()
                -1
            }
        }

        override fun write(source: ByteArray, length: Int) {
            synchronized(answer) {
                if (answer.length < MAX_ANSWER) answer.append(String(source, 0, length, Charsets.UTF_8))
            }
        }

        override fun close() = closed.countDown()

        fun answer(): String = synchronized(answer) { answer.toString() }
    }

    private class InstallStagingFailure(val detail: String) : RuntimeException()

    private companion object {
        const val CATEGORY = "adb"
        const val MAX_ANSWER = 16 * 1024
    }
}

/**
 * Сервисы `package`, которыми ставит пакеты настольный `adb`.
 *
 * Размер идёт в самой команде (`-S`), флаги — как есть: это флаги `package`.
 */
internal object InstallCommands {
    fun install(options: List<String>, size: Long): String = join("install", options + listOf("-S", size.toString()))

    fun create(options: List<String>, total: Long): String =
        join("install-create", options + listOf("-S", total.toString()))

    /** Имя файла внутри сессии: порядковый номер и безопасное имя, как у настольного клиента. */
    fun write(session: String, index: Int, name: String, size: Long): String =
        join("install-write", listOf("-S", size.toString(), session, "${index}_" + name.replace(UNSAFE, "_"), "-"))

    fun commit(session: String): String = join("install-commit", listOf(session))

    fun abandon(session: String): String = join("install-abandon", listOf(session))

    /** Номер сессии из ответа `Success: created install session [1234]`. */
    fun session(answer: String): String? = SESSION.find(answer)?.groupValues?.get(1)

    /** Принят ли шаг: `package` начинает ответ словом `Success`. */
    fun accepted(answer: String): Boolean = answer.trim().startsWith(SUCCESS)

    private fun join(verb: String, arguments: List<String>): String =
        (listOf("exec:cmd package $verb") + arguments).joinToString(" ")

    private const val SUCCESS = "Success"
    private val SESSION = Regex("""\[(\d+)]""")
    private val UNSAFE = Regex("""[^A-Za-z0-9._-]""")
}
