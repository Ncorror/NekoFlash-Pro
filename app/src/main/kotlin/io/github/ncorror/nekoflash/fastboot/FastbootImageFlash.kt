package io.github.ncorror.nekoflash.fastboot

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
): FastbootConsoleState {
    val limit = if (commands.firstOrNull()?.startsWith(FLASH) == true) pieceLimit(lane, size, sparseLimit) else 0L
    return if (limit > 0) {
        flashInPieces(lane, source, size, limit, commands)
    } else {
        downloadWhole(lane, source, size, commands)
    }
}

/** Файл одним `download:`; команды — только после `OKAY` на весь объём. */
private fun downloadWhole(
    lane: FastbootLane,
    source: ArtifactSource,
    size: Long,
    commands: List<String>,
): FastbootConsoleState {
    val outcome = source.open().use { stream -> FastbootDownload(lane).send(stream, size) }
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
 * Куски по очереди: `download` куска, `flash:` того же раздела, следующий.
 *
 * Файл читается дважды: сначала целиком — чтобы разметить блоки, как делает
 * `libsparse`, потом по порядку кусков. План на экране — все шаги сразу, и
 * он останавливается на первом непринятом: кусок, не принятый целиком, не
 * прошивается, а после отказа `flash:` следующие куски не идут.
 */
private fun flashInPieces(
    lane: FastbootLane,
    source: ArtifactSource,
    size: Long,
    limit: Long,
    commands: List<String>,
): FastbootConsoleState {
    val flash = commands.first()
    val rest = commands.drop(1)
    val pieces = FastbootSparse.resparse(FastbootSparse.read(source::open, size), limit)
    val steps = pieces.flatMap { listOf(downloadStep(it.sparseLength), flash) } + rest
    val stopped = source.open().use { input ->
        val reader = SparseDataReader(input)
        pieces.withIndex().firstNotNullOfOrNull { (index, piece) ->
            val outcome = FastbootDownload(lane).send(piece.stream(reader), piece.sparseLength)
            if (accepted(outcome, piece.sparseLength)) {
                flashed(lane, flash)?.let { detail -> stoppedAt(steps, 2 * index + 1, detail, lane) }
            } else {
                stoppedAt(steps, 2 * index, describeDownload(outcome), lane)
            }
        }
    }
    return stopped ?: if (rest.isEmpty()) {
        FastbootConsoleState.Planned(steps, applied = steps.size, stoppedAt = null, detail = "", lane = lane.state)
    } else {
        val tail = planned(rest, FastbootPlan(rest).run(mutation(lane)), lane.state) as FastbootConsoleState.Planned
        tail.copy(
            commands = steps,
            applied = tail.applied + 2 * pieces.size,
            stoppedAt = tail.stoppedAt?.plus(2 * pieces.size),
        )
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
