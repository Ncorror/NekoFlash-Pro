package io.github.ncorror.nekoflash.protocol.adb

/** Почему Sideload не начался. Все три причины — до единого байта на проводе. */
public enum class AdbSideloadRejection {
    /** Транспорта нет. */
    TRANSPORT_UNAVAILABLE,

    /** Peer отвечает не из Sideload. Режим читается из баннера, а не предполагается. */
    NOT_IN_SIDELOAD_MODE,

    /** Пустая нагрузка: передавать нечего. */
    EMPTY_PAYLOAD,
}

/** Чем кончился хостовой поток Sideload. */
public sealed interface AdbSideloadOutcome {
    /**
     * Recovery прислал `DONEDONE`.
     *
     * **Это конец передачи, а не вердикт об установке.** Первый из одиннадцати
     * инвариантов `03` §6, доказанных A2 на железе: терминальный сигнал говорит
     * о том, что хост отдал всё, и ничего — о том, что Recovery приняло пакет и
     * установило его. Вердикт даёт только Recovery evidence.
     */
    public data object TransferComplete : AdbSideloadOutcome

    /**
     * Recovery закрыло поток, когда подтверждено **не меньше** 95% уникальных байт.
     *
     * Отдельный исход от обрыва: почти полное покрытие — наблюдение, которое
     * стоит сохранить. Но проверки Recovery он не отменяет, и выдавать его за
     * успех нельзя.
     */
    public data class ClosedBeforeDoneDone(
        override val servedBytes: Long,
        override val uniqueBytes: Long,
        override val totalBytes: Long,
        val detail: String,
    ) : AdbSideloadOutcome, AdbSideloadCoverage

    /**
     * Поток стал непригоден **после** границы мутации.
     *
     * Состояние устройства неизвестно, **каким бы ни выглядел процент**. Recovery
     * начинает менять устройство, как только у него появились данные, и
     * поэтому «передано мало» не означает «ничего не изменилось».
     */
    public data class InterruptedAfterPayload(
        override val servedBytes: Long,
        override val uniqueBytes: Long,
        override val totalBytes: Long,
        val detail: String,
    ) : AdbSideloadOutcome, AdbSideloadCoverage

    /**
     * Отмена оператором — законна **только до** первого блока нагрузки.
     *
     * После границы мутации отмены не существует: отменять нечего, потому что
     * устройство уже могло начать меняться.
     */
    public data object Cancelled : AdbSideloadOutcome

    /** Peer не в Sideload: обмен не начинался. */
    public data class NotInSideloadMode(val mode: String) : AdbSideloadOutcome

    /** Сбой до границы мутации: устройство не тронуто. */
    public data class Failed(val kind: AdbSideloadFailure, val detail: String) : AdbSideloadOutcome
}

/** Покрытие — там, где оно есть. */
public interface AdbSideloadCoverage {
    /** Сколько байт хост отдал всего, **включая повторы**. Диагностика, не прогресс. */
    public val servedBytes: Long

    /** Сколько **уникальных** байт подтверждено. Только это годится для прогресса. */
    public val uniqueBytes: Long

    public val totalBytes: Long

    /** Доля уникального покрытия, 0..100. */
    public val percent: Int
        get() = AdbSideloadContract.coveragePercent(uniqueBytes, totalBytes)
}

/** Чем вызван сбой. */
public enum class AdbSideloadFailure {
    /** Не прочиталась нагрузка на хосте. */
    FILE,

    /** Провод. */
    TRANSPORT,

    /** Peer сказал не то, о чём договаривались. */
    PROTOCOL,
}

/**
 * Правила Sideload, доказанные A2 на железе.
 *
 * Перенесены как **correctness evidence**, а не как ограничение возможностей —
 * `09` требует этого прямо для всей Phase 7. Одиннадцать инвариантов записаны
 * в `03` §6; здесь живут те из них, что выражаются числами и классификацией.
 *
 * Главное, чего не увидеть из общего знания протокола:
 *
 * 1. **Sideload управляется запросами Recovery**, и один и тот же блок оно
 *    вправе запросить несколько раз. Поэтому кумулятивный трафик и уникальное
 *    покрытие — разные величины, и мерить прогресс первым значит показывать
 *    больше ста процентов на исправной передаче.
 * 2. **Граница мутации — первая попытка отправить блок нагрузки.** Не
 *    `DONEDONE`, не половина файла: Recovery начинает менять устройство, как
 *    только у него появились данные.
 * 3. **95% уникального покрытия при закрытии** — отдельный исход. Это не
 *    «почти успех», а наблюдение, которое стоит сохранить отдельно от обрыва в
 *    начале.
 */
public object AdbSideloadContract {
    /** Размер блока, которым Recovery оперирует. */
    public const val BLOCK_SIZE_BYTES: Int = 65_536

    /** С какого уникального покрытия закрытие считается почти полным. */
    public const val CLOSE_VERIFY_PENDING_PERCENT: Int = 95

    /** Слово Recovery о конце передачи. Успехом установки оно не является. */
    public const val DONE_DONE: String = "DONEDONE"

    /** Номер блока, которым Recovery сообщает, что запросы кончились. */
    public const val END_OF_REQUESTS: Int = -1

    /** Имя сервиса: объём и размер блока объявляются в нём самом. */
    public fun service(totalBytes: Long): String = "sideload-host:$totalBytes:$BLOCK_SIZE_BYTES"

    /** Можно ли начинать. Все отказы — до единого байта на проводе. */
    public fun validateStart(
        transportConnected: Boolean,
        peerIsSideload: Boolean,
        payloadBytes: Long,
    ): AdbSideloadRejection? = when {
        !transportConnected -> AdbSideloadRejection.TRANSPORT_UNAVAILABLE
        !peerIsSideload -> AdbSideloadRejection.NOT_IN_SIDELOAD_MODE
        payloadBytes <= 0L -> AdbSideloadRejection.EMPTY_PAYLOAD
        else -> null
    }

    /**
     * Как читать закрытие потока до `DONEDONE`.
     *
     * **Классификация идёт по уникальному покрытию, а не по отданному трафику.**
     * Повторные запросы Recovery раздувают второе и не двигают первое, и спутать
     * их значит объявить почти полной передачу, где половина блоков ушла дважды.
     */
    public fun classifyClose(
        kind: AdbSideloadFailure,
        detail: String,
        payloadStarted: Boolean,
        servedBytes: Long,
        uniqueBytes: Long,
        totalBytes: Long,
    ): AdbSideloadOutcome = when {
        // До границы мутации это обычный сбой: устройство не тронуто.
        !payloadStarted -> AdbSideloadOutcome.Failed(kind, detail)

        coveragePercent(uniqueBytes, totalBytes) >= CLOSE_VERIFY_PENDING_PERCENT ->
            AdbSideloadOutcome.ClosedBeforeDoneDone(servedBytes, uniqueBytes, totalBytes, detail)

        else -> AdbSideloadOutcome.InterruptedAfterPayload(servedBytes, uniqueBytes, totalBytes, detail)
    }

    /**
     * Нужна ли проверка через Recovery evidence.
     *
     * Нужна везде, где нагрузка уже пошла, — включая `DONEDONE`. Не нужна там,
     * где устройство не тронуто: отмена до границы, чужой режим, сбой до данных.
     */
    public fun requiresVerification(outcome: AdbSideloadOutcome): Boolean = when (outcome) {
        is AdbSideloadOutcome.TransferComplete,
        is AdbSideloadOutcome.ClosedBeforeDoneDone,
        is AdbSideloadOutcome.InterruptedAfterPayload,
        -> true

        is AdbSideloadOutcome.Cancelled,
        is AdbSideloadOutcome.NotInSideloadMode,
        is AdbSideloadOutcome.Failed,
        -> false
    }

    /** Доля уникального покрытия, 0..100. */
    public fun coveragePercent(uniqueBytes: Long, totalBytes: Long): Int {
        if (totalBytes <= 0L) return 0
        val confirmed = uniqueBytes.coerceIn(0L, totalBytes)
        return ((confirmed * PERCENT) / totalBytes).toInt().coerceIn(0, PERCENT.toInt())
    }

    private const val PERCENT = 100L
}
