package io.github.ncorror.nekoflash.command

import com.ncorror.nekoflash.pro.engine.LocalReportKind
import io.github.ncorror.nekoflash.adb.AdbLinkState
import io.github.ncorror.nekoflash.core.model.SessionGeneration
import io.github.ncorror.nekoflash.fastboot.FastbootLinkState
import io.github.ncorror.nekoflash.protocol.adb.AdbPeerMode
import io.github.ncorror.nekoflash.usb.api.UsbTargetIdentity

/**
 * Ответы `devices`, `get-state` и `status` из состояния приложения.
 *
 * Настольный клиент спрашивает свой сервер; здесь сервер — это приложение, и
 * отвечает оно тем, что знает о своей связи, не обращаясь к устройству.
 * Формат строк — как у Platform-Tools: вывод привычен и копируется в заметки
 * без перевода. Устройство, которое связи не держит, в списке не значится, как
 * и у настольного клиента.
 */
internal object LocalReports {
    fun adb(kind: LocalReportKind, state: AdbLinkState, identity: (SessionGeneration) -> UsbTargetIdentity?): String {
        val listed = adbListed(state)
        val word = listed?.let { adbStateWord(it) }
        val who = generationOf(state)?.let(identity)
        return when (kind) {
            LocalReportKind.DEVICES -> devices(listOfNotNull(word?.let { "${serialOf(who)}\t$it" }))
            LocalReportKind.DEVICES_LONG -> devices(listOfNotNull(word?.let { longLine(who, it, listed, state) }))
            LocalReportKind.GET_STATE -> word ?: NO_DEVICES
            LocalReportKind.STATUS -> "adb: " + adbStatus(state, who)
        }
    }

    fun fastboot(
        kind: LocalReportKind,
        state: FastbootLinkState,
        identity: (SessionGeneration) -> UsbTargetIdentity?,
    ): String {
        val generation = when (state) {
            is FastbootLinkState.Probing -> state.generation
            is FastbootLinkState.Connected -> state.generation
            else -> null
        }
        val who = generation?.let(identity)
        return when (kind) {
            LocalReportKind.DEVICES -> generation?.let { "${serialOf(who)}\t$FASTBOOT" }.orEmpty()
            LocalReportKind.DEVICES_LONG ->
                generation?.let { "${serialOf(who).padEnd(SERIAL_WIDTH)} $FASTBOOT${usbOf(who)}" }.orEmpty()
            LocalReportKind.GET_STATE -> generation?.let { FASTBOOT } ?: NO_DEVICES
            LocalReportKind.STATUS -> "fastboot: " + fastbootStatus(state, who)
        }
    }

    private fun devices(lines: List<String>): String = (listOf(DEVICES_HEADER) + lines).joinToString("\n")

    /** Связь, которую настольный клиент показал бы в списке. */
    private fun adbListed(state: AdbLinkState): AdbLinkState? = when (state) {
        is AdbLinkState.Connecting, is AdbLinkState.WaitingForAuthorization, is AdbLinkState.Connected -> state
        AdbLinkState.Idle, is AdbLinkState.Failed -> null
    }

    private fun adbStateWord(state: AdbLinkState): String = when (state) {
        is AdbLinkState.Connecting -> "connecting"
        is AdbLinkState.WaitingForAuthorization -> "unauthorized"
        is AdbLinkState.Connected -> when (state.peerMode) {
            AdbPeerMode.DEVICE -> "device"
            AdbPeerMode.RECOVERY -> "recovery"
            AdbPeerMode.SIDELOAD -> "sideload"
            // Незнакомый баннер называет себя сам: `rescue::`, `bootloader::`.
            AdbPeerMode.UNKNOWN -> state.banner.substringBefore("::").ifBlank { "unknown" }
        }
        AdbLinkState.Idle, is AdbLinkState.Failed -> "offline"
    }

    private fun generationOf(state: AdbLinkState): SessionGeneration? = when (state) {
        AdbLinkState.Idle -> null
        is AdbLinkState.Connecting -> state.generation
        is AdbLinkState.WaitingForAuthorization -> state.generation
        is AdbLinkState.Connected -> state.generation
        is AdbLinkState.Failed -> state.generation
    }

    /** `adb devices -l`: свойства из баннера `CNXN`, как их показывает настольный клиент. */
    private fun longLine(who: UsbTargetIdentity?, word: String, listed: AdbLinkState, state: AdbLinkState): String {
        val banner = (listed as? AdbLinkState.Connected)?.banner.orEmpty()
        val properties = BANNER_PROPERTIES
            .mapNotNull { (label, key) -> bannerProperty(banner, key)?.let { " $label:${it.replace(' ', '_')}" } }
            .joinToString("")
        val transport = generationOf(state)?.let { " transport_id:${it.value}" }.orEmpty()
        return "${serialOf(who).padEnd(SERIAL_WIDTH)} $word${usbOf(who)}$properties$transport"
    }

    private fun bannerProperty(banner: String, key: String): String? = banner.substringAfter("::", "")
        .split(';')
        .firstOrNull { it.startsWith("$key=") }
        ?.substringAfter('=')
        ?.takeIf { it.isNotEmpty() }

    private fun adbStatus(state: AdbLinkState, who: UsbTargetIdentity?): String = when (state) {
        AdbLinkState.Idle -> "no connection"
        is AdbLinkState.Connecting -> "connecting ${serialOf(who)}"
        is AdbLinkState.WaitingForAuthorization -> "waiting for authorization ${serialOf(who)}"
        is AdbLinkState.Connected -> "${adbStateWord(state)} ${serialOf(who)}, ${state.features.size} features"
        is AdbLinkState.Failed ->
            (if (state.afterConnect) "connection lost" else "failed ${state.reason}") + ": ${state.detail}"
    }

    private fun fastbootStatus(state: FastbootLinkState, who: UsbTargetIdentity?): String = when (state) {
        FastbootLinkState.Idle -> "no connection"
        is FastbootLinkState.Probing -> "probing ${serialOf(who)}"
        is FastbootLinkState.Connected ->
            "${state.identity.mode.name.lowercase()} ${serialOf(who)}, lock ${state.lock.state.name.lowercase()}"
        is FastbootLinkState.Failed -> "failed: ${state.detail}"
    }

    /** Серийный номер, который Android не отдал, настольный клиент пишет так же. */
    private fun serialOf(who: UsbTargetIdentity?): String =
        who?.serialNumber?.takeIf { it.isNotBlank() } ?: UNKNOWN_SERIAL

    private fun usbOf(who: UsbTargetIdentity?): String = who?.attachmentName?.let { " usb:$it" }.orEmpty()

    private const val DEVICES_HEADER = "List of devices attached"
    private const val NO_DEVICES = "error: no devices/emulators found"
    private const val FASTBOOT = "fastboot"
    private const val UNKNOWN_SERIAL = "????????????"
    private const val SERIAL_WIDTH = 22
    private val BANNER_PROPERTIES =
        listOf("product" to "ro.product.name", "model" to "ro.product.model", "device" to "ro.product.device")
}
