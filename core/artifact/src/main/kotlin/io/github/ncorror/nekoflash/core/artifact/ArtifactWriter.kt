package io.github.ncorror.nekoflash.core.artifact

import java.io.OutputStream

/** Чем кончилась запись артефакта в приёмник. */
sealed interface ArtifactWriteOutcome {
    /** Файл на месте, и счёт сошёлся. */
    data class Committed(
        val bytes: Long,
        val sha256: String,
        val destination: String,
        val atomic: Boolean,
    ) : ArtifactWriteOutcome

    /**
     * Пришло не столько, сколько обещали.
     *
     * Отдельный исход от обрыва: поток кончился нормально, а байт другое число.
     * Такой файл на настоящее место **не переезжает** — он выглядел бы целым.
     */
    data class CountMismatch(val expectedBytes: Long, val actualBytes: Long) : ArtifactWriteOutcome

    /** Поток оборвался или назначение не закрылось. Написанное убрано. */
    data class Interrupted(
        val bytes: Long,
        val expectedBytes: Long?,
        val detail: String,
    ) : ArtifactWriteOutcome

    /**
     * Байты записаны и посчитаны, а переезд на настоящее место не удался.
     *
     * Отпечаток при этом известен и сохраняется: он посчитан по тому, что
     * действительно прошло через запись, и от неудачи переезда не портится.
     */
    data class CommitFailed(
        val bytes: Long,
        val sha256: String,
        val detail: String,
    ) : ArtifactWriteOutcome
}

/**
 * Запись артефакта с точным счётом и отпечатком.
 *
 * Дисциплина `06_OPERATIONS_STORAGE_LIFECYCLE_RU.md` §7 целиком: пишем во
 * временное место, считаем байты и SHA-256 на лету, и переводим написанное на
 * настоящее место **только** после того, как счёт сошёлся и поток закрылся без
 * ошибки.
 *
 * Порядок здесь не формальность. Ошибка `close`/`flush` — это невынесенные на
 * диск байты, и проглотить её значило бы объявить целым файл, которого может не
 * быть. А `commit` до сверки счёта положил бы на место файл, выглядящий целым и
 * не являющийся им, — то есть худший из возможных исходов, потому что заметить
 * его будет уже нечем.
 *
 * Содержимое нигде не накапливается: через объект проходят куски, а остаются
 * счётчик и отпечаток (`06` §9).
 */
class ArtifactWriter(
    private val sink: ArtifactSink,
    /** Сколько байт обещано. `null` — объём заранее неизвестен, и сверять нечего. */
    private val expectedBytes: Long? = null,
    private val onProgress: (Long) -> Unit = {},
) {
    private val digest = ArtifactDigest()

    private var stream: OutputStream? = null
    private var finished = false

    init {
        require(expectedBytes == null || expectedBytes >= 0L) {
            "обещанный объём не может быть отрицательным: $expectedBytes"
        }
    }

    /** Сколько байт уже прошло через запись. */
    val bytes: Long
        get() = digest.bytes

    /** Принимает очередной кусок. */
    fun accept(chunk: ByteArray, offset: Int = 0, length: Int = chunk.size) {
        check(!finished) { "запись уже закончена" }
        ensureOpen().write(chunk, offset, length)
        digest.update(chunk, offset, length)
        onProgress(digest.bytes)
    }

    /**
     * Закрывает запись успехом.
     *
     * Успехом она станет только если счёт сойдётся: обещанный объём сверяется
     * здесь, а не вызывающим, потому что иначе сверку однажды забудут.
     */
    fun finish(): ArtifactWriteOutcome {
        check(!finished) { "запись уже закончена" }
        finished = true
        val failure = closeStream()
        return when {
            failure != null -> abandon(failure, ArtifactWriteOutcome.Interrupted(bytes, expectedBytes, failure))

            expectedBytes != null && expectedBytes != bytes -> abandon(
                "пришло $bytes байт вместо обещанных $expectedBytes",
                ArtifactWriteOutcome.CountMismatch(expectedBytes, bytes),
            )

            else -> commit()
        }
    }

    /** Закрывает запись обрывом: написанное убирается, причина называется. */
    fun interrupted(detail: String): ArtifactWriteOutcome {
        check(!finished) { "запись уже закончена" }
        finished = true
        closeStream()
        return abandon(detail, ArtifactWriteOutcome.Interrupted(bytes, expectedBytes, detail))
    }

    private fun commit(): ArtifactWriteOutcome = when (val done = sink.commit()) {
        is ArtifactCommit.Done ->
            ArtifactWriteOutcome.Committed(bytes, digest.hex(), done.destination, done.atomic)

        is ArtifactCommit.Failed ->
            ArtifactWriteOutcome.CommitFailed(bytes, digest.hex(), done.detail)
    }

    /**
     * Открывает назначение при первой надобности.
     *
     * Ленивое открытие нужно ради пустого артефакта: файл на ноль байт обязан
     * появиться так же, как любой другой, и [finish] открывает поток сам, если
     * ни одного куска не пришло.
     */
    private fun ensureOpen(): OutputStream = stream ?: sink.open().also { opened -> stream = opened }

    /** `null` — закрылось чисто; иначе описание того, что не вынеслось на диск. */
    private fun closeStream(): String? = runCatching {
        ensureOpen().apply {
            flush()
            close()
        }
    }.exceptionOrNull()?.let { error ->
        "назначение не закрылось: ${error.message ?: error.javaClass.simpleName}"
    }

    private fun <T : ArtifactWriteOutcome> abandon(reason: String, outcome: T): T {
        sink.abandon(reason)
        return outcome
    }
}
