package com.ncorror.nekoflash.pro.engine

/** Parsing is deliberately separate from execution: a host operation cannot
 * accidentally be sent to a bootloader as a raw command. */
sealed interface TerminalParseResult {
    data class Parsed(val invocation: TerminalInvocation) : TerminalParseResult
    data class Invalid(val reason: String) : TerminalParseResult
    data class Unsupported(val tool: String, val command: String) : TerminalParseResult
}

sealed interface TerminalInvocation {
    data class Fastboot(val options: FastbootOptions, val command: FastbootCommand) : TerminalInvocation
    data class Adb(val command: AdbCommand) : TerminalInvocation
}

data class FastbootOptions(
    val slot: String? = null,
    val setActiveRequested: Boolean = false,
    val setActiveSlot: String? = null,
    val wipe: Boolean = false,
    val sparseLimit: String? = null,
    val force: Boolean = false,
    val skipReboot: Boolean = false,
    val skipSecondary: Boolean = false,
    val disableVerity: Boolean = false,
    val disableVerification: Boolean = false,
    val serial: String? = null,
    val verbose: Boolean = false,
)

sealed interface FastbootCommand {
    data class Flash(val partition: String, val image: String?) : FastbootCommand
    /** Desktop fastboot fetches and repacks vendor_boot for this form. */
    data class FlashVendorBootRamdisk(val ramdisk: String, val image: String?) : FastbootCommand
    data class Format(val partition: String, val fileSystem: String?, val size: String?) : FastbootCommand
    data class Erase(val partition: String) : FastbootCommand
    data object Wipe : FastbootCommand
    data class WipeSuper(val emptySuperImage: String?) : FastbootCommand
    data class Boot(val kernel: String, val ramdisk: String?, val second: String?) : FastbootCommand
    data class FlashRaw(val partition: String, val kernel: String, val ramdisk: String?, val second: String?) : FastbootCommand
    data class Update(val archive: String) : FastbootCommand
    data object FlashAll : FastbootCommand
    data class UpdateSuper(val image: String, val wipe: Boolean) : FastbootCommand
    data class Fetch(val partition: String, val destination: String) : FastbootCommand
    data class SetActive(val slot: String?) : FastbootCommand
    data class DeviceQuery(val query: String, val argument: String?) : FastbootCommand
    data class Wire(val payload: String) : FastbootCommand
    data object LocalStatus : FastbootCommand
    data class Devices(val longFormat: Boolean) : FastbootCommand
}

data class FileTransferOptions(
    val syncOnly: Boolean = false,
    val preserveAttributes: Boolean = false,
    val dryRun: Boolean = false,
    val quiet: Boolean = false,
    val compression: String? = null,
    val noCompression: Boolean = false,
)

sealed interface AdbCommand {
    data class Shell(val command: String, val tty: String? = null, val noExitCode: Boolean = false) : AdbCommand
    data class Exec(val command: String) : AdbCommand
    data class Logcat(val arguments: List<String>) : AdbCommand
    data class Service(val name: String) : AdbCommand
    data class Push(val sources: List<String>, val destination: String, val options: FileTransferOptions) : AdbCommand
    data class Pull(val sources: List<String>, val destination: String, val options: FileTransferOptions) : AdbCommand
    data class Sync(val partition: String?, val options: FileTransferOptions, val listOnly: Boolean) : AdbCommand
    data class Install(val packagePath: String, val options: List<String>) : AdbCommand
    data class InstallMultiple(val packages: List<String>, val options: List<String>) : AdbCommand
    data class Sideload(val archive: String) : AdbCommand
    data class Uninstall(val packageName: String, val keepData: Boolean) : AdbCommand
    data class Remount(val rebootIfNeeded: Boolean) : AdbCommand
    data object LocalStatus : AdbCommand
    data class Devices(val longFormat: Boolean) : AdbCommand
    data object GetState : AdbCommand
}
