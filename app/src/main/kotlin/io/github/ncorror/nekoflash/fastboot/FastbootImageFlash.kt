package io.github.ncorror.nekoflash.fastboot

import io.github.ncorror.nekoflash.core.artifact.ArtifactDescriptorSource
import io.github.ncorror.nekoflash.core.artifact.ArtifactSource
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootDownload
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootDownloadOutcome
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootGetVar
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootLane
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootMutation
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootPlan
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootPlanOutcome
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootReply
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootSparse
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootVariable
import io.github.ncorror.nekoflash.protocol.fastboot.SparseDataReader
import io.github.ncorror.nekoflash.protocol.fastboot.SparseImage
import java.io.InputStream

/**
 * Выбранный файл в буфер устройства и [commands] следом — целиком или кусками.
 *
 * Как `load_buf_fd` настольного `fastboot`: под `flash:` файл больше предела
 * режется на куски sparse, и каждый уходит своей парой `download` +
 * `flash:РАЗДЕЛ`. Предел — `-S` или `max-download-size`; устройство, которое
 * его не назвало, получает файл целиком и само отвечает, примет ли.
 */
internal fun flashImage(
    lane: FastbootLane,
    source: ArtifactSource,
    size: Long,
    sparseLimit: Long?,
    commands: List<String>,
    onProgress: (FastbootImageProgress) -> Unit = {},
): FastbootConsoleState {
    val flashes = commands.takeWhile { it.startsWith(FLASH) }
    return if (flashes.isEmpty()) {
        downloadWhole(lane, source, size, commands)
    } else {
        flashEach(
            lane,
            ImageLoad(source, size, sparseLimit),
            flashes,
            commands.drop(flashes.size),
            onProgress,
        )
    }
}

/** Файл одним `download:`; команды — только после `OKAY` на весь объём. */
private fun downloadWhole(
    lane: FastbootLane,
    source: ArtifactSource,
    size: Long,
    commands: List<String>,
): FastbootConsoleState {
    val outcome = source.open().use { stream -> downloadFile(lane, source, size, stream) }
    return when {
        commands.isEmpty() -> downloaded(size, outcome, lane.state)
        accepted(outcome, size) -> downloadedThen(size, commands, lane)
        else -> FastbootConsoleState.Planned(
            commands = listOf(downloadStep(size)) + commands,
            applied = 0,
            stoppedAt = 0,
            detail = describeDownload(outcome),
            lane = lane.state,
        )
    }
}

/**
 * Файл [source] целиком одним `download:`; [stream] — он же, открытый
 * вызывающим.
 *
 * Если источник отдаёт дескриптор, байты могут пойти нативным путём (D077):
 * решает предпроверка до `download:`, её отказ — тот же [stream]. Куски
 * sparse и правленый vbmeta дескриптора не отдают: их байты не равны файлу.
 */
internal fun downloadFile(
    lane: FastbootLane,
    source: ArtifactSource,
    size: Long,
    stream: InputStream,
    progress: (Long) -> Unit = {},
): FastbootDownloadOutcome = (source as? ArtifactDescriptorSource)?.openReadDescriptor().use { descriptor ->
    FastbootDownload(lane).send(stream, size, payloadFd = descriptor?.fd, progress = progress)
}

/** Что прошивается: файл, его размер и `-S`. */
private class ImageLoad(val source: ArtifactSource, val size: Long, val sparseLimit: Long?)

/**
 * Каждый `flash:` — своя загрузка файла, целиком или кусками sparse.
 *
 * Несколько `flash:` подряд даёт `--slot all`: под каждый слот файл грузится
 * заново, как в `do_for_partitions`, — буфер устройства после `flash:` мог
 * очиститься. Для кусков файл сначала читается целиком, чтобы разметить блоки,
 * как делает `libsparse`, потом по порядку кусков. План на экране — все шаги
 * сразу, и он останавливается на первом непринятом: кусок, не принятый
 * целиком, не прошивается, а после отказа `flash:` дальше ничего не идёт.
 */
private fun flashEach(
    lane: FastbootLane,
    load: ImageLoad,
    flashes: List<String>,
    rest: List<String>,
    onProgress: (FastbootImageProgress) -> Unit,
): FastbootConsoleState {
    val limit = pieceLimit(lane, load.size, load.sparseLimit)
    val pieces: List<SparseImage?> = if (limit > 0) {
        FastbootSparse.resparse(FastbootSparse.read(load.source::open, load.size), limit)
    } else {
        listOf(null)
    }
    val lengths = pieces.map { it?.sparseLength ?: load.size }
    val totalWireBytes = lengths.sum() * flashes.size
    val steps = flashes.flatMap { flash -> lengths.flatMap { listOf(downloadStep(it), flash) } } + rest
    var completedBytes = 0L
    val stopped = flashes.withIndex().firstNotNullOfOrNull { (target, flash) ->
        val partition = flash.removePrefix(FLASH)
        load.source.open().use { input ->
            val reader = SparseDataReader(input)
            pieces.withIndex().firstNotNullOfOrNull { (index, piece) ->
                val at = 2 * (target * pieces.size + index)
                val base = completedBytes
                onProgress(FastbootImageProgress.Transferring(partition, base, totalWireBytes))
                val report: (Long) -> Unit = { sent ->
                    onProgress(FastbootImageProgress.Transferring(partition, base + sent, totalWireBytes))
                }
                val outcome = if (piece == null) {
                    downloadFile(lane, load.source, load.size, input, report)
                } else {
                    FastbootDownload(lane).send(piece.stream(reader), lengths[index], progress = report)
                }
                if (accepted(outcome, lengths[index])) {
                    completedBytes += lengths[index]
                    onProgress(FastbootImageProgress.Writing(partition, completedBytes, totalWireBytes))
                    flashed(lane, flash)?.let { detail -> stoppedAt(steps, at + 1, detail, lane) }
                } else {
                    stoppedAt(steps, at, describeDownload(outcome), lane)
                }
            }
        }
    }
    return stopped ?: finished(lane, steps, rest)
}

/** Все загрузки приняты: остаток плана — тем же планом, что у кнопок. */
private fun finished(lane: FastbootLane, steps: List<String>, rest: List<String>): FastbootConsoleState {
    val done = steps.size - rest.size
    return if (rest.isEmpty()) {
        FastbootConsoleState.Planned(steps, applied = steps.size, stoppedAt = null, detail = "", lane = lane.state)
    } else {
        val tail = planned(rest, FastbootPlan(rest).run(mutation(lane)), lane.state) as FastbootConsoleState.Planned
        tail.copy(commands = steps, applied = tail.applied + done, stoppedAt = tail.stoppedAt?.plus(done))
    }
}

/** `flash:` одного куска; `null` — выполнено, иначе — почему нет. */
private fun flashed(lane: FastbootLane, flash: String): String? =
    when (val outcome = FastbootPlan(listOf(flash)).run(mutation(lane))) {
        is FastbootPlanOutcome.Completed -> null
        is FastbootPlanOutcome.Stopped -> "$flash: ${describe(outcome.outcome)}"
    }

private fun stoppedAt(steps: List<String>, index: Int, detail: String, lane: FastbootLane) =
    FastbootConsoleState.Planned(steps, applied = index, stoppedAt = index, detail = detail, lane = lane.state)

private fun mutation(lane: FastbootLane) = FastbootMutation(lane, FastbootGetVar(lane))

private fun accepted(outcome: FastbootDownloadOutcome, size: Long): Boolean =
    outcome is FastbootDownloadOutcome.Answered && outcome.reply == FastbootReply.OKAY && outcome.bytesSent == size

/**
 * Размер куска: `-S`, иначе `max-download-size` устройства.
 *
 * Значение устройства читается, как у `get_uint_var`: без пробелов по краям,
 * `0x` — шестнадцатеричное, иначе десятичное; не названо или не число — `0`,
 * и тогда файл идёт целиком.
 */
private fun pieceLimit(lane: FastbootLane, size: Long, requested: Long?): Long {
    val device = if (requested == null || requested == 0L) {
        (FastbootGetVar(lane).read(MAX_DOWNLOAD_SIZE) as? FastbootVariable.Present)?.value?.let(::deviceNumber)
    } else {
        null
    }
    return FastbootSparse.limit(size, requested, device)
}

private fun deviceNumber(raw: String): Long? {
    val text = raw.trim()
    return if (text.startsWith("0x") || text.startsWith("0X")) text.drop(2).toLongOrNull(HEX) else text.toLongOrNull()
}

private const val FLASH = "flash:"
private const val MAX_DOWNLOAD_SIZE = "max-download-size"
private const val HEX = 16
