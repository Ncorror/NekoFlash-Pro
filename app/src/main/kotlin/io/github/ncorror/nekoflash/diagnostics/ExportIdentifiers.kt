package io.github.ncorror.nekoflash.diagnostics

import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticEvent

/**
 * Значения, которые в выгружаемом отчёте заменяются меткой (D050, D073).
 *
 * Отчёт пересылают, и эти значения связывают его с конкретными устройствами:
 * серийный номер цели — по USB-сессии, по личности в событиях и по ответу
 * `getvar:serialno` (он может не совпасть с номером USB); Android ID хоста;
 * отпечаток ключа ADB хоста из события `host_key` — он одинаков для всех
 * устройств, которым хост разрешён. Метка одна для одного значения, поэтому
 * отчёты по-прежнему можно сопоставить между собой.
 */
internal object ExportIdentifiers {
    fun of(serials: List<String>, events: List<DiagnosticEvent>, host: Map<String, String>): Set<String> =
        (
            serials +
                events.mapNotNull { it.targetId?.value }
                    .filter { it.startsWith(SERIAL_TARGET_PREFIX) }
                    .map { it.removePrefix(SERIAL_TARGET_PREFIX) } +
                events.filter { it.fields[COMMAND]?.trim().equals(GETVAR_SERIALNO, ignoreCase = true) }
                    .mapNotNull { it.fields[PAYLOAD] } +
                events.filter { it.message == HOST_KEY_EVENT }.mapNotNull { it.fields[FINGERPRINT] } +
                // Недоступный ID записан словом, а не значением: его заменять нельзя.
                listOfNotNull(host[HOST_ANDROID_ID]?.takeUnless { it.startsWith(UNAVAILABLE_PREFIX) })
            ).map(String::trim).toSet()

    /** Префикс личности цели, построенной по серийному номеру (`UsbTargetIdentity`). */
    private const val SERIAL_TARGET_PREFIX = "serial:"

    /** Ключ Android ID хоста в [HostFacts]. */
    private const val HOST_ANDROID_ID = "host.androidId"

    /** Так [HostFacts] пишет значение, которого нет. */
    private const val UNAVAILABLE_PREFIX = "unavailable"

    private const val COMMAND = "command"
    private const val PAYLOAD = "payload"
    private const val GETVAR_SERIALNO = "getvar:serialno"
    private const val HOST_KEY_EVENT = "host_key"
    private const val FINGERPRINT = "fingerprint"
}
