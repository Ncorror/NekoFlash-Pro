package io.github.ncorror.nekoflash.fastboot

import io.github.ncorror.nekoflash.core.artifact.ArtifactWriteOutcome
import io.github.ncorror.nekoflash.core.artifact.ArtifactWriter
import io.github.ncorror.nekoflash.payload.DigestingSink
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootDownloadOutcome
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootFetchOutcome
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootLaneState
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootReply
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootGetVar
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootLane
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootMutation
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootMutationOutcome
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootPlan
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootPlanOutcome

/*
 * Перевод исходов передач Fastboot в то, что увидит оператор.
 *
 * Вынесено из FastbootLinkController не ради слоёв: класс перешёл порог detekt
 * в 20 функций, а поднимать пороги запрещено (`15` §4.1). Граница при этом
 * получилась осмысленная — здесь с устройством ничего не происходит, тут только
 * перевод уже случившегося.
 */

/**
 * Исход чтения в файл.
 *
 * Файл переезжает на выбранное место **только** при полном чтении. Частично
 * прочитанный раздел, положенный туда как целый, выглядел бы рабочим
 * образом — и это опаснее самой неудачи, потому что заметить подмену будет
 * уже нечем (`06` §7).
 */
internal fun saved(
    partition: String,
    writer: ArtifactWriter,
    outcome: FastbootFetchOutcome,
    lane: FastbootLaneState,
): FastbootConsoleState {
    val complete = outcome is FastbootFetchOutcome.Completed
    val written = if (complete) {
        writer.finish()
    } else {
        writer.interrupted("чтение раздела не закончилось")
    }
    return FastbootConsoleState.Fetched(
        partition = partition,
        bytes = writer.bytes,
        sha256 = (written as? ArtifactWriteOutcome.Committed)?.sha256
            ?: (written as? ArtifactWriteOutcome.CommitFailed)?.sha256
            ?: "",
        complete = complete,
        detail = savedDetail(written, outcome),
        lane = lane,
    )
}

internal fun savedDetail(written: ArtifactWriteOutcome, outcome: FastbootFetchOutcome): String =
    when (written) {
        is ArtifactWriteOutcome.Committed -> "сохранено в ${written.destination}"
        is ArtifactWriteOutcome.CommitFailed -> "сохранить не вышло: ${written.detail}"
        is ArtifactWriteOutcome.CountMismatch -> "счёт байт не сошёлся, файл не сохранён"
        is ArtifactWriteOutcome.Interrupted -> when (outcome) {
            is FastbootFetchOutcome.Refused -> outcome.detail
            is FastbootFetchOutcome.Partial -> outcome.detail
            is FastbootFetchOutcome.NotStarted -> outcome.detail
            is FastbootFetchOutcome.Completed -> written.detail
        }
    }

internal class WriterStream(private val writer: ArtifactWriter) : java.io.OutputStream() {
    override fun write(value: Int) {
        writer.accept(byteArrayOf(value.toByte()))
    }

    override fun write(source: ByteArray, offset: Int, length: Int) {
        writer.accept(source, offset, length)
    }
}

internal fun fetched(
    partition: String,
    sink: DigestingSink,
    outcome: FastbootFetchOutcome,
    lane: FastbootLaneState,
): FastbootConsoleState {
    val complete = outcome is FastbootFetchOutcome.Completed
    val detail = when (outcome) {
        is FastbootFetchOutcome.Completed -> "кусков: ${outcome.chunks}"
        is FastbootFetchOutcome.Refused -> outcome.detail
        is FastbootFetchOutcome.Partial -> outcome.detail
        is FastbootFetchOutcome.NotStarted -> outcome.detail
    }
    return FastbootConsoleState.Fetched(
        partition = partition,
        bytes = sink.bytes,
        sha256 = if (sink.bytes > 0L) sink.sha256() else "",
        complete = complete,
        detail = detail,
        lane = lane,
    )
}

internal fun downloaded(
    declared: Long,
    outcome: FastbootDownloadOutcome,
    lane: FastbootLaneState,
): FastbootConsoleState = when (outcome) {
    is FastbootDownloadOutcome.Answered -> FastbootConsoleState.Downloaded(
        declaredBytes = declared,
        sentBytes = outcome.bytesSent,
        reply = outcome.reply,
        detail = outcome.payload,
        // Байты дошли все, но принял ли их приёмник — сказало устройство.
        // Про «не изменилось» речи нет: буфер наполнен.
        untouched = false,
        lane = lane,
        route = outcome.route,
    )

    // Единственный исход, про который можно честно сказать, что состояние
    // устройства не тронуто: ни одного байта не ушло.
    is FastbootDownloadOutcome.Refused -> FastbootConsoleState.Downloaded(
        declaredBytes = declared,
        sentBytes = 0L,
        reply = FastbootReply.FAIL,
        detail = outcome.detail,
        untouched = true,
        lane = lane,
    )

    is FastbootDownloadOutcome.Unknown -> FastbootConsoleState.Downloaded(
        declaredBytes = outcome.expectedBytes,
        sentBytes = outcome.bytesSent,
        reply = null,
        detail = outcome.detail,
        untouched = false,
        lane = lane,
        route = outcome.route,
    )

    // Обмен не начался: команда не ушла, устройство её не видело.
    is FastbootDownloadOutcome.NotStarted -> FastbootConsoleState.Downloaded(
        declaredBytes = declared,
        sentBytes = 0L,
        reply = null,
        detail = outcome.detail,
        untouched = true,
        lane = lane,
    )
}

internal fun planned(
    commands: List<String>,
    outcome: FastbootPlanOutcome,
    lane: FastbootLaneState,
): FastbootConsoleState = when (outcome) {
    is FastbootPlanOutcome.Completed -> FastbootConsoleState.Planned(
        commands = commands,
        applied = outcome.applied.size,
        stoppedAt = null,
        detail = "",
        lane = lane,
    )

    is FastbootPlanOutcome.Stopped -> FastbootConsoleState.Planned(
        commands = commands,
        applied = outcome.applied.size,
        stoppedAt = outcome.index,
        detail = "${outcome.command}: ${describe(outcome.outcome)}",
        lane = lane,
    )
}

/** Короткое слово об исходе шага — то же, что пишется в `claim`. */
internal fun describe(outcome: FastbootMutationOutcome): String = when (outcome) {
    is FastbootMutationOutcome.Applied -> "выполнено"
    is FastbootMutationOutcome.Refused -> "устройство отказало: ${outcome.detail}"
    is FastbootMutationOutcome.Unconfirmed -> "OKAY без подтверждения: ${outcome.detail}"
    is FastbootMutationOutcome.Departed -> "устройство ушло, не ответив"
    is FastbootMutationOutcome.Unknown -> "неизвестно: ${outcome.detail}"
    is FastbootMutationOutcome.NotStarted -> "не отправлено: ${outcome.detail}"
}

/** Команды после принятой загрузки — тем же планом, что и у кнопок. */
internal fun downloadedThen(size: Long, then: List<String>, lane: FastbootLane): FastbootConsoleState {
    val rest = planned(then, FastbootPlan(then).run(FastbootMutation(lane, FastbootGetVar(lane))), lane.state)
            as FastbootConsoleState.Planned
    return rest.copy(
        commands = listOf(downloadStep(size)) + then,
        applied = rest.applied + 1,
        stoppedAt = rest.stoppedAt?.plus(1),
    )
}

/** Как шаг загрузки выглядит в плане: так, как его увидит устройство. */
internal fun downloadStep(size: Long): String = "download:%08x".format(size)

/** Почему после загрузки команды не пошли: слово об исходе самой загрузки. */
internal fun describeDownload(outcome: FastbootDownloadOutcome): String = when (outcome) {
    is FastbootDownloadOutcome.Answered ->
        "download: ${outcome.reply.name} ${outcome.payload}, передано ${outcome.bytesSent} байт".trim()
    is FastbootDownloadOutcome.Refused -> "download: устройство отказало до передачи: ${outcome.detail}"
    is FastbootDownloadOutcome.Unknown ->
        "download: передача оборвана на ${outcome.bytesSent} из ${outcome.expectedBytes} байт " +
            "(${outcome.route.path}), буфер неизвестен: ${outcome.detail}"
    is FastbootDownloadOutcome.NotStarted -> "download: не начат: ${outcome.detail}"
}
