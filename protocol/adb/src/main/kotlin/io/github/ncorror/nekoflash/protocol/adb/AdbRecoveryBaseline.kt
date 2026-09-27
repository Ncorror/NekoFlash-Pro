package io.github.ncorror.nekoflash.protocol.adb

import java.security.MessageDigest

/**
 * Отпечаток журнала Recovery **до** Sideload.
 *
 * Хранится только длина нормализованного начала и его SHA-256 — **сам журнал не
 * хранится**. Этого достаточно, чтобы потом доказать, что новый журнал
 * продолжает прежний, и недостаточно, чтобы утащить в отчёт чужие строки.
 */
public data class AdbRecoveryBaseline(
    val path: String,
    val prefixLength: Int,
    val prefixSha256: String,
) {
    init {
        require(path == AdbRecoveryInstall.PRIMARY_PATH) {
            "базой может быть только ${AdbRecoveryInstall.PRIMARY_PATH}"
        }
        require(prefixLength >= 0) { "длина начала не может быть отрицательной" }
        require(SHA256.matches(prefixSha256)) { "SHA-256 записывается строчными шестнадцатеричными" }
    }

    private companion object {
        val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}

/** Удалось ли связать журнал после Sideload с базой до него. */
public sealed interface AdbRecoveryCorrelation {
    /** Журнал продолжает базу: новые строки принадлежат этой установке. */
    public data class Correlated(
        val log: AdbRecoveryLog,
        val appendedCharacters: Int,
    ) : AdbRecoveryCorrelation

    /**
     * Связать не удалось, и вердикт по такому журналу не читается.
     *
     * **Отказ закрытый, а не мягкий**: без доказательства, что строки новые,
     * исход установки остаётся `UNKNOWN`. Прочитать исторический журнал как
     * сегодняшний значило бы выдать чужое событие за наше.
     */
    public data class Unavailable(
        val reason: String,
        val path: String? = null,
    ) : AdbRecoveryCorrelation
}

/**
 * Связывание журнала Recovery с базой, снятой **до** Sideload.
 *
 * Инвариант 10 из `03` §6: корреляция строится относительно baseline до
 * Sideload и нового evidence сессии. Смысл такой:
 *
 * - **до** передачи снимается отпечаток `/tmp/recovery.log`;
 * - **после** вердикт читается только из журнала, который начинается **тем же**
 *   началом. Если журнал ротировали, обрезали или переписали — связи нет, и
 *   исход остаётся неизвестным.
 *
 * Без этого любая установка читалась бы по журналу, в котором мог остаться
 * результат прошлой: успех недельной давности выглядел бы успехом сегодняшним.
 *
 * Базой может быть **только** журнал текущей сессии. Исторические файлы
 * продолжением базы не бывают по определению — они пишутся целиком и заново.
 */
public object AdbRecoveryCorrelator {
    /** Снимает базу до Sideload. */
    public fun capture(log: AdbRecoveryLog): AdbRecoveryBaseline {
        require(log.path == AdbRecoveryInstall.PRIMARY_PATH) {
            "снимать базу можно только с ${AdbRecoveryInstall.PRIMARY_PATH}"
        }
        val normalized = normalize(log.text)
        return AdbRecoveryBaseline(
            path = AdbRecoveryInstall.PRIMARY_PATH,
            prefixLength = normalized.length,
            prefixSha256 = sha256(normalized),
        )
    }

    /** Проверяет, что журнал после Sideload продолжает базу. */
    public fun correlate(
        sources: List<AdbRecoveryLog>,
        baseline: AdbRecoveryBaseline?,
    ): AdbRecoveryCorrelation {
        val current = sources.firstOrNull { it.path == AdbRecoveryInstall.PRIMARY_PATH }
        return when {
            baseline == null -> AdbRecoveryCorrelation.Unavailable(
                "базы до Sideload нет: связать исход установки не с чем",
                current?.path,
            )

            current == null -> AdbRecoveryCorrelation.Unavailable(
                "${AdbRecoveryInstall.PRIMARY_PATH} недоступен после Sideload: исход остаётся неизвестным",
                AdbRecoveryInstall.PRIMARY_PATH,
            )

            else -> compare(current, baseline)
        }
    }

    private fun compare(current: AdbRecoveryLog, baseline: AdbRecoveryBaseline): AdbRecoveryCorrelation {
        val normalized = normalize(current.text)
        return when {
            // Журнал короче базы — его обрезали или начали заново. Читать из
            // него нечего: прежних строк там уже нет, а новые непонятно чьи.
            normalized.length < baseline.prefixLength -> AdbRecoveryCorrelation.Unavailable(
                "журнал обрезан или начат заново после снятия базы: исторические строки не принимаем",
                AdbRecoveryInstall.PRIMARY_PATH,
            )

            sha256(normalized.substring(0, baseline.prefixLength)) != baseline.prefixSha256 ->
                AdbRecoveryCorrelation.Unavailable(
                    "журнал больше не продолжает снятую базу: несвязанные строки не принимаем",
                    AdbRecoveryInstall.PRIMARY_PATH,
                )

            else -> AdbRecoveryCorrelation.Correlated(
                log = AdbRecoveryLog(current.path, normalized),
                appendedCharacters = normalized.length - baseline.prefixLength,
            )
        }
    }

    /**
     * Вердикт, который **разрешено** объявлять.
     *
     * Без корреляции — всегда `UNKNOWN` с названной причиной. Это и есть
     * «fail closed»: отсутствие доказательства не превращается в отсутствие
     * события, но и в событие не превращается тоже.
     */
    public fun verdict(
        sources: List<AdbRecoveryLog>,
        baseline: AdbRecoveryBaseline?,
    ): AdbRecoveryResult = when (val correlation = correlate(sources, baseline)) {
        is AdbRecoveryCorrelation.Correlated -> AdbRecoveryInstall.evaluate(listOf(correlation.log))

        is AdbRecoveryCorrelation.Unavailable -> AdbRecoveryResult(
            verdict = AdbRecoveryVerdict.UNKNOWN,
            detail = correlation.reason,
            source = correlation.path,
        )
    }

    /** Переводы строк приводятся к одному виду: иначе отпечаток зависел бы от них. */
    private fun normalize(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
