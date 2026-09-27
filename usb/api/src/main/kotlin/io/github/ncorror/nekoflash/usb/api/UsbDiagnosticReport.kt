package io.github.ncorror.nekoflash.usb.api

import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticBundleSection
import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticEvent
import io.github.ncorror.nekoflash.core.diagnostics.toEvidenceLine

/**
 * Разделы диагностического отчёта.
 *
 * `07_TESTING_CI_HARDWARE_EVIDENCE_RU.md` требует от evidence идентичность
 * сборки и хоста, идентичность цели, режим и отметки времени. Здесь эти сведения
 * приводятся к текстовому виду; упаковкой занимается
 * `core:diagnostics.DiagnosticBundle`.
 *
 * Ничего не вырезается и не сокращается. Отчёт собирает оператор о собственном
 * устройстве и сам решает, что с ним делать; урезать его состав «на всякий
 * случай» означало бы ровно тот host-side запрет, который запрещён
 * `01_PRODUCT_CHARTER_RU.md`.
 *
 * Недоступное поле не пропускается молча, а записывается с причиной
 * недоступности: пустое место в отчёте читается как «этого не было», и это
 * ложь.
 */
public object UsbDiagnosticReport {
    /** Раздел с идентичностью сборки и устройства-хоста. */
    public const val HOST_SECTION: String = "host.txt"

    /** Раздел со снимком сессий на момент выгрузки. */
    public const val SESSIONS_SECTION: String = "sessions.txt"

    /** Раздел с журналом событий. */
    public const val EVENTS_SECTION: String = "events.txt"

    /**
     * Собирает разделы отчёта.
     *
     * @param host произвольные сведения о хосте и сборке, собранные платформой.
     *   Ключи упорядочиваются, чтобы два отчёта можно было сравнить построчно.
     * @param droppedEvents сколько событий вытеснено ограничением приёмника.
     *   Записывается всегда, в том числе нулём: отсутствие строки читатель не
     *   отличил бы от отсутствия потерь.
     */
    public fun sections(
        host: Map<String, String>,
        sessions: List<UsbSession>,
        events: List<DiagnosticEvent>,
        droppedEvents: Long,
    ): List<DiagnosticBundleSection> = listOf(
        DiagnosticBundleSection(HOST_SECTION, hostText(host)),
        DiagnosticBundleSection(SESSIONS_SECTION, sessionsText(sessions)),
        DiagnosticBundleSection(EVENTS_SECTION, eventsText(events, droppedEvents)),
    )

    private fun hostText(host: Map<String, String>): String =
        if (host.isEmpty()) {
            "# no host facts were provided\n"
        } else {
            host.toSortedMap()
                .entries
                .joinToString(separator = "\n", postfix = "\n") { (key, value) ->
                    "$key=${value.singleLine()}"
                }
        }

    private fun sessionsText(sessions: List<UsbSession>): String {
        if (sessions.isEmpty()) return "# no sessions at export time\n"
        return sessions.joinToString(separator = "\n", postfix = "\n") { session ->
            sessionBlock(session)
        }
    }

    private fun sessionBlock(session: UsbSession): String {
        val device = session.candidate.device
        val lines = listOf(
            "generation=${session.generation.value}",
            "targetId=${session.targetId.value.singleLine()}",
            "identitySource=${session.identity.source.name}",
            "survivesReattachment=${session.identity.survivesReattachment}",
            "serialNumber=${session.identity.serialNumber?.singleLine() ?: NOT_REPORTED}",
            "state=${session.state.name}",
            "closureReason=${session.closureReason?.name ?: NONE}",
            "connection=${device.deviceName.singleLine()}",
            "connectionId=${device.deviceId}",
            "vendorId=${device.vendorId.toHex()}",
            "productId=${device.productId.toHex()}",
            "productName=${device.productName?.singleLine() ?: NOT_REPORTED}",
            "interfaceKind=${session.candidate.kind.name}",
            "matchConfidence=${session.candidate.confidence.name}",
            "interfaceIndex=${session.candidate.interfaceIndex}",
            "interfaceId=${session.candidate.interfaceId}",
            "interfaceClass=${session.candidate.interfaceClass.toHex()}",
            "interfaceSubclass=${session.candidate.interfaceSubclass.toHex()}",
            "interfaceProtocol=${session.candidate.interfaceProtocol.toHex()}",
            "endpointIn=${session.candidate.endpointIn.address.toHex()}",
            "endpointOut=${session.candidate.endpointOut.address.toHex()}",
            "maxPacketSizeIn=${session.candidate.endpointIn.maxPacketSize}",
            "maxPacketSizeOut=${session.candidate.endpointOut.maxPacketSize}",
            "profileSignature=${session.candidate.profileSignature}",
            "protocolMode=$MODE_REQUIRES_HANDSHAKE",
        )
        return lines.joinToString(separator = "\n", postfix = "\n")
    }

    private fun eventsText(events: List<DiagnosticEvent>, droppedEvents: Long): String =
        buildString {
            append("# droppedEvents=").append(droppedEvents).append('\n')
            if (events.isEmpty()) {
                append("# no events were recorded\n")
            } else {
                events.forEach { event -> append(event.toEvidenceLine()).append('\n') }
            }
        }

    private const val NOT_REPORTED = "not_reported_by_device"
    private const val NONE = "none"

    /**
     * Протокольный режим в отчёт не подставляется.
     *
     * Дескриптор отличает интерфейс класса ADB от класса Fastboot, но не
     * обычный Android от Recovery или Sideload и не bootloader Fastboot от
     * fastbootd. До handshake это неизвестно, и evidence обязано так и говорить.
     */
    private const val MODE_REQUIRES_HANDSHAKE = "unknown_until_handshake"

    private fun Int.toHex(): String = "0x%04X".format(this)

    private fun String.singleLine(): String = replace('\n', ' ').replace('\r', ' ')
}
