package io.github.ncorror.nekoflash.core.diagnostics

import java.security.MessageDigest

/**
 * Обезличивание диагностического архива перед тем, как он покинет приложение.
 *
 * Серийный номер цели нужен внутри приложения — по нему личность устройства
 * переживает смену режима, — но в выгружаемый отчёт он попадать не должен:
 * отчёт пересылают, и номер связывает его с конкретным телефоном
 * (`docs/02_INVARIANTS.md` §3). Удалять номер совсем нельзя: тогда по двум
 * отчётам не понять, одно ли это устройство. Поэтому номер заменяется меткой,
 * одинаковой для одного и того же номера и не раскрывающей его.
 *
 * Замена идёт по тексту всех секций, а не по известным полям: номер приходит и
 * в личности цели, и в отчёте USB, и в ответах устройства (`getvar:serialno`),
 * и перечислить все места заранее нельзя.
 */
public object DiagnosticRedaction {
    /** Номера короче этого не заменяются: они совпали бы с обычными словами и числами отчёта. */
    public const val MIN_SECRET_LENGTH: Int = 4

    /** Метка вместо [secret]: стабильна для одного номера, по ней номер не восстановить. */
    public fun token(secret: String): String =
        "redacted-" + MessageDigest.getInstance("SHA-256")
            .digest(secret.toByteArray(Charsets.UTF_8))
            .take(TOKEN_BYTES)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    /** Заменяет каждое вхождение каждого из [secrets] во всех секциях. */
    public fun redact(
        sections: List<DiagnosticBundleSection>,
        secrets: Set<String>,
    ): List<DiagnosticBundleSection> {
        // Длинные раньше коротких: номер, входящий в другой номер, не должен
        // испортить замену более длинного.
        val ordered = secrets.map(String::trim)
            .filter { it.length >= MIN_SECRET_LENGTH }
            .distinct()
            .sortedByDescending(String::length)
        if (ordered.isEmpty()) return sections
        return sections.map { section ->
            val content = ordered.fold(section.content) { text, secret -> text.replace(secret, token(secret)) }
            section.copy(content = content)
        }
    }

    private const val TOKEN_BYTES = 4
}
