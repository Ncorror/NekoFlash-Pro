package io.github.ncorror.nekoflash.protocol.adb

/** Что Recovery сказало об установке. */
public enum class AdbRecoveryVerdict {
    SUCCESS,
    FAILED,

    /**
     * Recovery не сказало ничего определённого.
     *
     * **Это полноценный исход, а не отсутствие исхода.** Выдать «нет записи» за
     * успех или за провал значило бы придумать вердикт, которого нет (`03` §3).
     */
    UNKNOWN,
}

/** Один прочитанный журнал Recovery. */
public data class AdbRecoveryLog(
    val path: String,
    val text: String,
)

/** Вердикт вместе с тем, чем он подтверждён. */
public data class AdbRecoveryResult(
    val verdict: AdbRecoveryVerdict,
    val detail: String,
    val source: String? = null,
    val evidence: String? = null,
)

/**
 * Чтение исхода установки из журналов Recovery.
 *
 * **Вердикт берётся только из того, что написало Recovery.** `DONEDONE`
 * намеренно не считается успехом установки: он кончает передачу на хосте
 * (`03` §6, инвариант 1). Это и есть смысл всего файла — отделить «мы отдали» от
 * «оно установило».
 *
 * Четыре правила взяты у A2 и по памяти не восстанавливаются:
 *
 * 1. **Источники упорядочены.** `/tmp/recovery.log` — журнал текущей сессии;
 *    `last_install`, `last_log`, `install.log` — исторические, и смотрятся
 *    только если в первом нет явного результата.
 * 2. **Берётся срез от последнего начала сессии.** Иначе установка, прошедшая
 *    неделю назад, будет прочитана как сегодняшняя.
 * 3. **Побеждает последнее событие по положению в тексте**, а не первое и не
 *    «любой успех». Журнал пишется по порядку, и последнее слово — итог.
 * 4. **Признаки TWRP/OrangeFox принимаются только внутри распознанной сессии
 *    Sideload.** Без этого чужое действие Recovery — например, ручная установка
 *    из меню — было бы прочитано как результат нашего.
 */
public object AdbRecoveryInstall {
    /** Журнал текущей сессии Recovery. */
    public const val PRIMARY_PATH: String = "/tmp/recovery.log"

    /** Читает вердикт из [sources]. */
    public fun evaluate(sources: List<AdbRecoveryLog>): AdbRecoveryResult {
        val ordered = sources.sortedBy { priority(it.path) }
        val found = ordered.firstNotNullOfOrNull { source -> evaluateSource(source) }
        return found ?: unresolved(ordered)
    }

    private fun unresolved(ordered: List<AdbRecoveryLog>): AdbRecoveryResult = when {
        ordered.isEmpty() -> AdbRecoveryResult(
            verdict = AdbRecoveryVerdict.UNKNOWN,
            detail = "журнал Recovery недоступен: исход установки не подтверждён",
        )

        else -> AdbRecoveryResult(
            verdict = AdbRecoveryVerdict.UNKNOWN,
            detail = "журнал получен, но явного результата последней установки в нём нет",
            source = ordered.first().path,
        )
    }

    private fun evaluateSource(source: AdbRecoveryLog): AdbRecoveryResult? {
        val text = latestSession(source.text)
        return if (text.isBlank()) {
            null
        } else {
            // Побеждает последнее событие по положению: журнал пишется по
            // порядку, и итог — то, что сказано последним, а не первым.
            events(text).maxByOrNull { it.at }?.let { event ->
                AdbRecoveryResult(event.verdict, event.detail, source.path, event.evidence)
            }
        }
    }

    private fun events(text: String): List<Event> {
        val found = mutableListOf<Event>()
        AdbRecoveryPatterns.STATUS.findAll(text).forEach { match ->
            match.groupValues[1].toIntOrNull()?.let { status ->
                found += Event(
                    at = match.range.first,
                    verdict = if (status == 0) AdbRecoveryVerdict.SUCCESS else AdbRecoveryVerdict.FAILED,
                    detail = if (status == 0) {
                        "Recovery подтвердило установку: status=0"
                    } else {
                        "Recovery закончило установку с ошибкой: status=$status"
                    },
                    evidence = lineAt(text, match.range.first),
                )
            }
        }

        AdbRecoveryPatterns.UPDATER_ERROR.findAll(text).forEach { match ->
            match.groupValues[1].toIntOrNull()?.takeIf { it != 0 }?.let { code ->
                found += Event(
                    at = match.range.first,
                    verdict = AdbRecoveryVerdict.FAILED,
                    detail = "Updater закончил с ошибкой: $code",
                    evidence = lineAt(text, match.range.first),
                )
            }
        }

        // Признаки TWRP/OrangeFox читаются только внутри распознанной сессии
        // Sideload: иначе чужое действие Recovery будет прочитано как результат
        // нашего.
        if (AdbRecoveryPatterns.SIDELOAD_SESSION.any { it.containsMatchIn(text) }) {
            found += sessionEvents(text)
        }

        found += simple(text, AdbRecoveryPatterns.FAILURE, AdbRecoveryVerdict.FAILED, "Recovery сообщает об ошибке")
        found += simple(text, AdbRecoveryPatterns.SUCCESS, AdbRecoveryVerdict.SUCCESS, "Recovery сообщает об успехе")
        return found
    }

    private fun sessionEvents(text: String): List<Event> {
        val found = mutableListOf<Event>()
        AdbRecoveryPatterns.OPERATION_END.findAll(text).forEach { match ->
            match.groupValues[1].toIntOrNull()?.let { status ->
                found += Event(
                    at = match.range.first,
                    verdict = if (status == 0) AdbRecoveryVerdict.SUCCESS else AdbRecoveryVerdict.FAILED,
                    detail = "operation_end status=$status",
                    evidence = lineAt(text, match.range.first),
                )
            }
        }
        found += simple(
            text,
            listOf(AdbRecoveryPatterns.ORANGEFOX),
            AdbRecoveryVerdict.SUCCESS,
            "Recovery сообщает об успешной установке OrangeFox",
        )
        return found
    }

    private fun simple(
        text: String,
        patterns: List<Regex>,
        verdict: AdbRecoveryVerdict,
        detail: String,
    ): List<Event> = patterns.flatMap { regex ->
        regex.findAll(text).map { match ->
            Event(match.range.first, verdict, detail, lineAt(text, match.range.first))
        }
    }

    /**
     * Срез от **последнего** начала сессии.
     *
     * Без него установка недельной давности будет прочитана как сегодняшняя, и
     * вердикт окажется про чужое событие.
     */
    private fun latestSession(text: String): String {
        val start = AdbRecoveryPatterns.SESSION_START
            .flatMap { regex -> regex.findAll(text).map { it.range.first } }
            .maxOrNull()
        return if (start == null) text else text.substring(start)
    }

    /** Журнал текущей сессии важнее исторических. */
    private fun priority(path: String): Int {
        val lower = path.lowercase()
        return when {
            lower.endsWith(PRIMARY_PATH) -> 0
            lower.endsWith("/last_install") -> 1
            lower.endsWith("/last_log") -> 2
            lower.endsWith("/install.log") -> 3
            else -> 4
        }
    }

    private fun lineAt(text: String, index: Int): String {
        val from = text.lastIndexOf('\n', (index - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val to = text.indexOf('\n', index).let { if (it < 0) text.length else it }
        return text.substring(from, to).trim().take(EVIDENCE_LIMIT)
    }

    private data class Event(
        val at: Int,
        val verdict: AdbRecoveryVerdict,
        val detail: String,
        val evidence: String,
    )

    private const val EVIDENCE_LIMIT = 500
}

/**
 * Признаки в журналах Recovery.
 *
 * Взяты у A2 дословно и не дополняются догадкой: строка, которую пишет
 * конкретная сборка Recovery, — наблюдение, а не то, что можно вывести. Чего
 * здесь нет, то остаётся `UNKNOWN`, и это честнее выдуманного совпадения.
 */
internal object AdbRecoveryPatterns {
    val STATUS: Regex = Regex("""(?i)Install from ADB complete \(status:\s*(-?\d+)\)""")
    val UPDATER_ERROR: Regex = Regex("""(?i)Updater process ended with ERROR:\s*(-?\d+)""")
    val OPERATION_END: Regex = Regex("""(?im)^\s*I:operation_end\s*-\s*status=(-?\d+)\s*$""")
    val ORANGEFOX: Regex = Regex("""(?i)Finished installing OrangeFox!""")

    val SIDELOAD_SESSION: List<Regex> = listOf(
        Regex("""(?im)^\s*Starting ADB sideload"""),
        Regex("""(?im)^\s*Starting sideload"""),
        Regex("""(?im)^\s*I:operation_start:\s*['"]Sideload['"]\s*$"""),
        Regex("""(?im)^\s*Installing zip file"""),
    )

    val SESSION_START: List<Regex> = SIDELOAD_SESSION + listOf(
        Regex("""(?im)^\s*Installing update"""),
        Regex("""(?im)^\s*Installing package"""),
    )

    val FAILURE: List<Regex> = listOf(
        Regex("""(?i)Error installing zip file"""),
        Regex("""(?i)Installation aborted"""),
        Regex("""(?i)E:Error in .*\(status\s+-?\d+\)"""),
        Regex("""(?i)script aborted[^\n]*"""),
    )

    val SUCCESS: List<Regex> = listOf(
        Regex("""(?i)Install completed successfully"""),
        Regex("""(?i)installation successful"""),
        Regex("""(?i)script succeeded[^\n]*"""),
    )
}
