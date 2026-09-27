package io.github.ncorror.nekoflash.adb

import io.github.ncorror.nekoflash.artifact.ArtifactStagingOutcome
import io.github.ncorror.nekoflash.artifact.StagedArtifactSource
import io.github.ncorror.nekoflash.core.artifact.ArtifactRandomAccess
import io.github.ncorror.nekoflash.core.artifact.ArtifactSource
import io.github.ncorror.nekoflash.core.artifact.ArtifactStability
import io.github.ncorror.nekoflash.core.artifact.ArtifactStaging
import io.github.ncorror.nekoflash.core.artifact.ArtifactStagingDecision
import io.github.ncorror.nekoflash.core.artifact.ArtifactStamps
import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticSink
import io.github.ncorror.nekoflash.core.model.SessionGeneration
import io.github.ncorror.nekoflash.core.model.TargetId
import io.github.ncorror.nekoflash.core.operation.OperationIntent
import io.github.ncorror.nekoflash.core.operation.OperationKind
import io.github.ncorror.nekoflash.core.operation.OperationOutcome
import io.github.ncorror.nekoflash.operation.OperationEngine
import io.github.ncorror.nekoflash.operation.OperationHandle
import io.github.ncorror.nekoflash.payload.GeneratedPayload
import io.github.ncorror.nekoflash.protocol.adb.AdbConnection
import io.github.ncorror.nekoflash.protocol.adb.AdbPeerMode
import io.github.ncorror.nekoflash.protocol.adb.AdbSideloadContract
import io.github.ncorror.nekoflash.protocol.adb.AdbSideloadFailure
import io.github.ncorror.nekoflash.protocol.adb.AdbSideloadListener
import io.github.ncorror.nekoflash.protocol.adb.AdbSideloadOutcome
import io.github.ncorror.nekoflash.protocol.adb.AdbSideloadProgress
import io.github.ncorror.nekoflash.protocol.adb.AdbSideloadSource
import java.io.File
import java.time.Instant
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Что происходит с передачей пакета в Recovery. */
public sealed interface AdbSideloadState {
    /** Не начинали. */
    public data object None : AdbSideloadState

    /**
     * Передача идёт.
     *
     * [cancellable] гаснет не по прогрессу, а по **границе мутации**, и это
     * разные моменты: граница проходится, когда первый блок уходит на провод, а
     * прогресс приходит позже — на подтверждение от Recovery. Считать её по
     * прогрессу значило бы предлагать отмену там, где отменять уже нечего.
     */
    public data class Running(
        val progress: AdbSideloadProgress,
        val cancellable: Boolean,
    ) : AdbSideloadState

    /**
     * Пакет копируется в каталог приложения, прежде чем уйти на устройство.
     *
     * Отдельное состояние от передачи, а не её первые проценты: устройство в
     * это время не тронуто вовсе, и мешать эти две вещи значило бы показывать
     * прогресс мутации там, где мутации ещё нет.
     */
    public data class Staging(val bytes: Long, val name: String) : AdbSideloadState

    /**
     * Начать нельзя, и причина названа **до** первого байта.
     *
     * Сюда попадает всё, что выяснилось до передачи: подменённый источник,
     * нехватка места, источник, который нельзя ни читать по кусочкам, ни
     * скопировать.
     */
    public data class Refused(val detail: String) : AdbSideloadState

    /**
     * Передача кончилась.
     *
     * [verificationPending] — не украшение: `DONEDONE` кончает **передачу**, а
     * не установку (`03` §6, инвариант 1). Пока Recovery не сказало своего,
     * исход установки неизвестен, и показывать «готово» нельзя.
     */
    public data class Finished(
        val outcome: AdbSideloadOutcome,
        val verificationPending: Boolean,
    ) : AdbSideloadState
}

/**
 * Владелец одной передачи Sideload.
 *
 * Отдельный класс по той же причине, что оболочка и файловые операции: у
 * передачи своё время жизни, и мешать его с жизнью транспорта незачем.
 *
 * Содержимое пока порождается приложением: выбор пользовательского файла — это
 * artifact source из Phase 8, и делать его наспех значило бы прятать выбор в
 * приватный каталог. Протокольный путь от этого настоящий целиком — тот же
 * драйвер, тот же поток, те же подтверждения; меняется только, откуда байты.
 */
public class AdbSideloadController(
    private val executor: Executor,
    private val diagnostics: DiagnosticSink = DiagnosticSink { },
    /**
     * Владелец записей операций.
     *
     * Передача — длительная операция, и её след обязан пережить процесс: после
     * падения решает **граница мутации**, а не то, что помнил экран (`06` §3).
     */
    private val operations: OperationEngine? = null,
    /**
     * Поднять foreground service.
     *
     * Функция, а не `Context`: контроллер не знает про Android и знать не
     * должен — кто именно держит процесс, решает приложение.
     */
    private val holdProcess: () -> Unit = {},
) {
    private val mutableState = MutableStateFlow<AdbSideloadState>(AdbSideloadState.None)

    private val cancelling = AtomicBoolean(false)

    @Volatile
    private var running = false

    /** Состояние передачи. */
    public val state: StateFlow<AdbSideloadState> = mutableState.asStateFlow()

    /** Идёт ли передача прямо сейчас. */
    public val active: Boolean
        get() = running

    /**
     * Отдаёт пакет Recovery.
     *
     * Режим peer'а читается из баннера и сюда приходит готовым: догадываться о
     * нём по набору сервисов нельзя (`AdbConnectionBanner`). Отказ из-за режима
     * — это отказ **до единого байта**, и он называется, а не прячется.
     */
    public fun start(
        connection: AdbConnection,
        peerMode: AdbPeerMode,
        sizeBytes: Long,
        target: TargetId,
        generation: SessionGeneration,
    ) {
        if (running) return
        running = true
        cancelling.set(false)
        mutableState.value = AdbSideloadState.Running(nothingYet(sizeBytes), cancellable = true)
        val handle = record("пакет, порождённый приложением, $sizeBytes байт", target, generation, sizeBytes)
        executor.execute { transfer(connection, peerMode, sizeBytes, handle) }
    }

    /**
     * Заводит запись операции и держит процесс, пока она идёт.
     *
     * `null` означает, что владельца записей нет вовсе — так бывает в тестах;
     * передача от этого не меняется, меняется только то, останется ли от неё
     * след.
     */
    private fun record(
        summary: String,
        target: TargetId,
        generation: SessionGeneration,
        sizeBytes: Long?,
    ): OperationHandle? {
        holdProcess()
        return operations?.begin(
            intent = OperationIntent(OperationKind.ADB_SIDELOAD, summary),
            targetId = target,
            generation = generation,
            totalBytes = sizeBytes,
        )
    }

    /**
     * Просит отменить.
     *
     * Просьба, а не приказ: после границы мутации сессия откажет, и это
     * правильно — устройство уже могло начать меняться.
     */
    public fun cancel() {
        cancelling.set(true)
    }

    /**
     * Отдаёт Recovery пакет, **выбранный пользователем**.
     *
     * Три вещи выясняются до первого байта и в этом порядке: не подменили ли
     * файл, хватит ли места, и умеет ли источник отдавать блоки в том порядке,
     * в каком их просит Recovery. Последнее — не формальность: Sideload
     * управляется запросами, и источник, читаемый только подряд, на повторный
     * запрос отдал бы следующий кусок.
     */
    public fun startFrom(
        connection: AdbConnection,
        peerMode: AdbPeerMode,
        stagingDirectory: File,
        target: TargetId,
        generation: SessionGeneration,
        origin: () -> ArtifactSource,
    ) {
        if (running) return
        running = true
        cancelling.set(false)
        mutableState.value = AdbSideloadState.Staging(0L, "")
        val handle = record("выбранный пакет", target, generation, sizeBytes = null)
        executor.execute { prepare(connection, peerMode, stagingDirectory, origin, handle) }
    }

    private fun prepare(
        connection: AdbConnection,
        peerMode: AdbPeerMode,
        stagingDirectory: File,
        origin: () -> ArtifactSource,
        handle: OperationHandle?,
    ) {
        handle?.state(STAGING)
        val prepared = runCatching { ready(origin(), stagingDirectory) }.getOrElse { error ->
            Ready.No("источник не открылся: ${error.message ?: error.javaClass.simpleName}")
        }
        when (prepared) {
            is Ready.No -> {
                mutableState.value = AdbSideloadState.Refused(prepared.detail)
                // Отказ до передачи — это провал операции, а не неизвестность:
                // устройство не тронуто, и сказать это можно уверенно.
                handle?.finish(OperationOutcome.FAILED, REFUSED, Instant.now())
                running = false
            }

            is Ready.Yes -> try {
                transfer(connection, peerMode, prepared.size, prepared.access, handle)
            } finally {
                prepared.release()
            }
        }
    }

    /**
     * Готовит источник к передаче или объясняет, почему её не будет.
     *
     * Проверка на подмену идёт первой: если файл уже не тот, остальное неважно.
     */
    private fun ready(source: ArtifactSource, stagingDirectory: File): Ready {
        val stability = ArtifactStamps.compare(source.openedStamp, source.stamp())
        if (stability is ArtifactStability.Changed) {
            return Ready.No("выбранный файл изменился с момента выбора: ${stability.detail}")
        }
        val decision = ArtifactStaging.decide(
            access = source.identity.access,
            sizeBytes = source.identity.sizeBytes,
            randomAccessRequired = true,
            // Объём Sideload объявляет в самом имени сервиса, то есть обязан
            // знать его до первого байта.
            sizeRequiredUpFront = true,
            bytesAvailable = stagingDirectory.usableSpace,
        )
        return when (decision) {
            is ArtifactStagingDecision.NoRoom -> Ready.No(
                "для копии нужно ${decision.bytesNeeded} байт, свободно ${decision.bytesAvailable}",
            )

            is ArtifactStagingDecision.NotNeeded -> direct(source)
            is ArtifactStagingDecision.Required -> staged(source, stagingDirectory)
        }
    }

    private fun direct(source: ArtifactSource): Ready {
        val access = source.randomAccess()
        val size = source.identity.sizeBytes
        return if (access == null || size == null) {
            Ready.No("источник не отдаёт блоки в произвольном порядке, а скопировать его не удалось")
        } else {
            Ready.Yes(size, access) {}
        }
    }

    private fun staged(source: ArtifactSource, stagingDirectory: File): Ready {
        mutableState.value = AdbSideloadState.Staging(0L, source.identity.name)
        val outcome = StagedArtifactSource.stage(source, stagingDirectory) { bytes ->
            mutableState.value = AdbSideloadState.Staging(bytes, source.identity.name)
        }
        return when (outcome) {
            is ArtifactStagingOutcome.Failed -> Ready.No(outcome.detail)
            is ArtifactStagingOutcome.Staged -> Ready.Yes(
                size = outcome.source.identity.sizeBytes ?: 0L,
                access = outcome.source.randomAccess(),
                release = outcome.source::close,
            )
        }
    }

    private fun transfer(
        connection: AdbConnection,
        peerMode: AdbPeerMode,
        sizeBytes: Long,
        handle: OperationHandle?,
    ) {
        val payload = GeneratedPayload(sizeBytes)
        transfer(
            connection = connection,
            peerMode = peerMode,
            sizeBytes = sizeBytes,
            access = { offset, length -> payload.read(offset, length) },
            handle = handle,
        )
    }

    private fun transfer(
        connection: AdbConnection,
        peerMode: AdbPeerMode,
        sizeBytes: Long,
        access: ArtifactRandomAccess,
        handle: OperationHandle?,
    ) {
        handle?.state(SENDING)
        val outcome = runCatching {
            connection.sideloadDriver(diagnostics).send(
                source = AdbSideloadSource { offset, length -> access.read(offset, length) },
                totalBytes = sizeBytes,
                transportConnected = true,
                peerIsSideload = peerMode == AdbPeerMode.SIDELOAD,
                listener = Watcher(handle),
                cancelRequested = cancelling::get,
            )
        }.getOrElse { error ->
            AdbSideloadOutcome.Failed(
                AdbSideloadFailure.FILE,
                error.message ?: error.javaClass.simpleName,
            )
        }
        val pending = AdbSideloadContract.requiresVerification(outcome)
        mutableState.value = AdbSideloadState.Finished(outcome, pending)
        handle?.finish(outcomeOf(outcome, pending), outcome.javaClass.simpleName, Instant.now())
        running = false
    }

    /**
     * Исход операции по исходу передачи.
     *
     * `DONEDONE` — это **не** успех операции: он кончает передачу, а не
     * установку, и пока Recovery не сказало своего, исход неизвестен. Назвать
     * его успехом значило бы записать в историю то, чего никто не подтверждал.
     */
    private fun outcomeOf(outcome: AdbSideloadOutcome, verificationPending: Boolean): OperationOutcome = when {
        outcome is AdbSideloadOutcome.Cancelled -> OperationOutcome.CANCELLED
        verificationPending -> OperationOutcome.UNKNOWN
        else -> OperationOutcome.FAILED
    }

    /** Готов источник к передаче или нет. */
    private sealed interface Ready {
        data class Yes(
            val size: Long,
            val access: ArtifactRandomAccess,
            /** Убрать временную копию, если она заводилась. */
            val release: () -> Unit,
        ) : Ready

        data class No(val detail: String) : Ready
    }

    /** Переносит события передачи в состояние экрана. */
    private inner class Watcher(private val handle: OperationHandle?) : AdbSideloadListener {
        @Volatile
        private var cancellable = true

        override fun onProgress(progress: AdbSideloadProgress) {
            mutableState.value = AdbSideloadState.Running(progress, cancellable)
            handle?.progress(progress.servedBytes, progress.uniqueBytes, System.currentTimeMillis())
        }

        override fun onMutationBoundary() {
            cancellable = false
            // В хранилище это уходит немедленно: после падения именно граница
            // решает, можно ли сказать, что устройство не тронуто.
            handle?.crossedMutationBoundary("первый блок нагрузки ушёл на провод", Instant.now())
            // Кнопка гаснет сразу, а не со следующим подтверждением: между
            // границей и первым подтверждением проходит целый блок, и всё это
            // время отмена была бы обещанием, которого никто не сдержит.
            val shown = mutableState.value
            if (shown is AdbSideloadState.Running) {
                mutableState.value = shown.copy(cancellable = false)
            }
        }
    }

    private companion object {
        const val STAGING = "STAGING"
        const val SENDING = "SENDING"
        const val REFUSED = "REFUSED"
    }

    private fun nothingYet(sizeBytes: Long): AdbSideloadProgress {
        val block = AdbSideloadContract.BLOCK_SIZE_BYTES.toLong()
        return AdbSideloadProgress(
            servedBytes = 0L,
            uniqueBytes = 0L,
            totalBytes = sizeBytes,
            uniqueBlocks = 0,
            totalBlocks = if (sizeBytes <= 0L) 0 else ((sizeBytes + block - 1) / block).toInt(),
        )
    }
}
