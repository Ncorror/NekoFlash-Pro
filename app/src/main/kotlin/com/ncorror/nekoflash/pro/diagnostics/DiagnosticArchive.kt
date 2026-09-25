package com.ncorror.nekoflash.pro.diagnostics

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import com.ncorror.nekoflash.pro.platform.usb.UsbInterfaceScanner
import java.io.IOException
import java.time.Instant
import java.util.Locale

/** Captures a read-only snapshot; never opens a USB connection or reads device contents. */
class DiagnosticArchive(context: Context, private val journal: DiagnosticJournal) {
    private val appContext = context.applicationContext

    /** Call from a background thread; the document provider may be slow. */
    fun export(uri: Uri) {
        val entries = listOf(
            DiagnosticEntry("README.txt", readme()),
            DiagnosticEntry("environment.txt", environment()),
            DiagnosticEntry("usb-descriptors.txt", usbDescriptors()),
            DiagnosticEntry("events.log", journal.snapshot()),
        )
        val stream = appContext.contentResolver.openOutputStream(uri)
            ?: throw IOException("Could not open the selected document")
        DiagnosticZipWriter.write(stream, entries)
    }

    private fun readme() = """
        NekoFlash Pro diagnostic archive

        environment.txt: app version, Android version, phone model, USB host feature and battery.
        usb-descriptors.txt: connected USB device VID/PID, permission, configuration,
                             interface class/subclass/protocol, endpoints and ADB/Fastboot signatures.
        events.log: recent app/USB events and explicit ADB/Fastboot probe results,
                    at most 256 KiB, timestamps in UTC.

        The archive does not list USB serial numbers, ADB keys/signatures/banners/tokens,
        device file paths or device contents.
        It does not include Android system logcat; review exception messages before sharing.
        A USB signature and bulk endpoints do not prove ADB authorization or connectivity;
        inspect events.log for the explicit ADB response check or authorization result.
        The first-reply check sends no keys. The separate authorization button sends a host key
        and waits for the connected device to confirm it. Neither mode runs ADB services.
    """.trimIndent() + "\n"

    private fun environment() = buildString {
        appendLine("Captured (UTC): ${Instant.now()}")
        appendLine("Package: ${appContext.packageName}")
        appendLine("App version: ${versionInfo()}")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Security patch: ${Build.VERSION.SECURITY_PATCH}")
        appendLine("Phone: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Supported ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("App language: ${Locale.getDefault().toLanguageTag()}")
        appendLine("USB host feature: ${appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)}")
        val capacity = (appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        appendLine("Battery level: ${capacity?.takeIf { it in 0..100 }?.let { "$it%" } ?: "unavailable"}")
    }

    @Suppress("DEPRECATION")
    private fun versionInfo(): String = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.packageManager.getPackageInfo(
                appContext.packageName, PackageManager.PackageInfoFlags.of(0L),
            )
        } else {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        }
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
        "${info.versionName ?: "unknown"} ($code)"
    } catch (error: PackageManager.NameNotFoundException) {
        "unavailable (${error.javaClass.simpleName})"
    }

    private fun usbDescriptors(): String = try {
        val manager = appContext.getSystemService(Context.USB_SERVICE) as? UsbManager
        if (manager == null) "USB manager unavailable\n" else {
            val devices = manager.deviceList.values.sortedWith(
                compareBy<UsbDevice> { it.vendorId }.thenBy { it.productId }.thenBy { it.deviceId },
            )
            buildString {
                appendLine("Connected USB devices: ${devices.size}")
                devices.take(MAX_DEVICES).forEachIndexed { index, device ->
                    appendLine()
                    appendLine("Device ${index + 1}:")
                    try {
                        appendDevice(device, manager)
                    } catch (error: RuntimeException) {
                        journal.recordFailure("USB descriptor inspection failed", error)
                        appendLine("  Descriptor inspection failed: ${error.javaClass.simpleName}")
                    }
                }
                if (devices.size > MAX_DEVICES) appendLine("Additional devices omitted")
            }
        }
    } catch (error: RuntimeException) {
        journal.recordFailure("USB enumeration failed during export", error)
        "USB enumeration failed: ${error.javaClass.simpleName}\n"
    }

    private fun StringBuilder.appendDevice(device: UsbDevice, manager: UsbManager) {
        appendLine("  VID:PID: ${hex(device.vendorId)}:${hex(device.productId)}")
        appendLine("  Android device ID: ${device.deviceId} (changes after reconnection)")
        appendLine("  USB version: ${device.version}")
        appendLine("  Device class/subclass/protocol: ${hex(device.deviceClass)}/" +
            "${hex(device.deviceSubclass)}/${hex(device.deviceProtocol)}")
        appendLine("  Permission granted: ${manager.hasPermission(device)}")
        appendLine("  Configurations: ${device.configurationCount}")
        for (configIndex in 0 until minOf(device.configurationCount, MAX_CONFIGURATIONS)) {
            val config = device.getConfiguration(configIndex)
            appendLine("    Configuration $configIndex: id=${config.id}, maxPower=${config.maxPower}mA, " +
                "selfPowered=${config.isSelfPowered}, remoteWakeup=${config.isRemoteWakeup}")
            appendLine("    Interfaces: ${config.interfaceCount}")
            for (interfaceIndex in 0 until minOf(config.interfaceCount, MAX_INTERFACES)) {
                val usbInterface = config.getInterface(interfaceIndex)
                appendLine("      Interface $interfaceIndex: id=${usbInterface.id}, alt=${usbInterface.alternateSetting}, " +
                    "class/subclass/protocol=${hex(usbInterface.interfaceClass)}/" +
                    "${hex(usbInterface.interfaceSubclass)}/${hex(usbInterface.interfaceProtocol)}")
                for (endpointIndex in 0 until minOf(usbInterface.endpointCount, MAX_ENDPOINTS)) {
                    val endpoint = usbInterface.getEndpoint(endpointIndex)
                    val direction = if (endpoint.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
                    val type = when (endpoint.type) {
                        UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "control"
                        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "isochronous"
                        UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"
                        UsbConstants.USB_ENDPOINT_XFER_INT -> "interrupt"
                        else -> "unknown(${endpoint.type})"
                    }
                    appendLine("        Endpoint $endpointIndex: address=0x${hex(endpoint.address)}, " +
                        "direction=$direction, type=$type, maxPacket=${endpoint.maxPacketSize}, " +
                        "interval=${endpoint.interval}")
                }
                if (usbInterface.endpointCount > MAX_ENDPOINTS) appendLine("        Remaining endpoints omitted")
            }
            if (config.interfaceCount > MAX_INTERFACES) appendLine("      Remaining interfaces omitted")
        }
        if (device.configurationCount > MAX_CONFIGURATIONS) appendLine("    Remaining configurations omitted")
        try {
            val interfaces = UsbInterfaceScanner.scan(device)
            appendLine("  Recognized USB interfaces: ${interfaces.size}")
            interfaces.forEach { appendLine("    ${UsbInterfaceScanner.describe(it)}") }
            appendLine("  ADB authorization: not established by this snapshot (see events.log for optional CNXN/AUTH probe)")
        } catch (error: RuntimeException) {
            journal.recordFailure("USB interface classification failed", error)
            appendLine("  USB interface classification failed: ${error.javaClass.simpleName}")
        }
    }

    private fun hex(value: Int): String = String.format(Locale.US, "%02X", value)

    private companion object {
        const val MAX_DEVICES = 32
        const val MAX_CONFIGURATIONS = 8
        const val MAX_INTERFACES = 32
        const val MAX_ENDPOINTS = 16
    }
}
