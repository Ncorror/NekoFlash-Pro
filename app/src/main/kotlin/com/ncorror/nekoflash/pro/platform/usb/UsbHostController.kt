package com.ncorror.nekoflash.pro.platform.usb

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.ncorror.nekoflash.pro.model.UsbDeviceSummary
import com.ncorror.nekoflash.pro.model.UsbHostSnapshot
import com.ncorror.nekoflash.pro.model.UsbNotice
import java.util.Locale

/** Enumerates USB devices and asks Android for access; never opens an interface. */
class UsbHostController(
    private val activity: Activity,
    private val publish: (UsbHostSnapshot) -> Unit,
    private val record: (String) -> Unit,
    private val onDetached: (String, Int) -> Unit,
) {
    private val manager = activity.getSystemService(Context.USB_SERVICE) as UsbManager
    private val permissionAction = "${activity.packageName}.USB_PERMISSION"
    private val pending = mutableSetOf<String>()
    private var started = false

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != permissionAction) return
            val requested = deviceFromIntent(intent) ?: run {
                record("USB permission callback without a device")
                return
            }
            if (!pending.remove(requested.deviceName)) {
                record("Unexpected USB permission callback for ${deviceLabel(requested)}")
                return
            }

            val connected = manager.deviceList[requested.deviceName]
                ?.takeIf { it.deviceId == requested.deviceId }
            val granted = connected != null &&
                intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) &&
                manager.hasPermission(connected)
            val notice = when {
                connected == null -> UsbNotice.DEVICE_DISCONNECTED
                granted -> UsbNotice.ACCESS_GRANTED
                else -> UsbNotice.ACCESS_DENIED
            }
            record("USB permission result for ${deviceLabel(requested)}: $notice")
            refresh(notice)
        }
    }

    private val deviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED ||
                intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED
            ) {
                val action = if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED)
                    "attached" else "detached"
                val device = deviceFromIntent(intent)
                record("USB $action${device?.let { " ${deviceLabel(it)}" } ?: ""}")
                if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED && device != null) {
                    onDetached(device.deviceName, device.deviceId)
                }
                refresh(UsbNotice.DEVICE_CHANGED)
            }
        }
    }

    fun start() {
        if (started) return
        record("USB observer starting")
        val filter = IntentFilter(permissionAction)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerPermissionReceiverBeforeApi33(filter)
        }

        // This receiver listens exclusively for broadcasts from the Android USB system.
        activity.registerReceiver(deviceReceiver, IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        })
        started = true
        refresh()
    }

    fun stop() {
        if (!started) return
        record("USB observer stopped")
        activity.unregisterReceiver(deviceReceiver)
        activity.unregisterReceiver(permissionReceiver)
        pending.clear()
        started = false
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerPermissionReceiverBeforeApi33(filter: IntentFilter) {
        // API 26–32 have no export flag. Matching a pending device and checking
        // UsbManager.hasPermission keeps a forged broadcast from granting access.
        activity.registerReceiver(permissionReceiver, filter)
    }

    fun refresh() = refresh(null)

    private fun refresh(notice: UsbNotice?) {
        if (!activity.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
            record("USB scan: host mode unavailable")
            publish(UsbHostSnapshot(supported = false, notice = notice))
            return
        }

        val devices = manager.deviceList.values.sortedWith(
            compareBy<UsbDevice> { it.vendorId }.thenBy { it.productId }.thenBy { it.deviceId },
        )
        pending.retainAll(devices.map { it.deviceName }.toSet())
        record("USB scan: ${devices.size} device(s); " + devices.joinToString { device ->
            "${deviceLabel(device)} access=${manager.hasPermission(device)}"
        })
        publish(UsbHostSnapshot(devices = devices.map { device ->
            val interfaces = try {
                UsbInterfaceScanner.scan(device).also { detections ->
                    detections.forEach {
                        record("USB signature for ${deviceLabel(device)}: ${UsbInterfaceScanner.describe(it)}")
                    }
                }
            } catch (error: RuntimeException) {
                record("USB interface inspection failed for ${deviceLabel(device)}: ${error.javaClass.simpleName}")
                null
            }
            UsbDeviceSummary(
                key = device.deviceName,
                vendorId = device.vendorId,
                productId = device.productId,
                deviceId = device.deviceId,
                accessGranted = manager.hasPermission(device),
                accessPending = device.deviceName in pending,
                interfaces = interfaces.orEmpty(),
                interfaceInspectionFailed = interfaces == null,
            )
        }, notice = notice))
    }

    fun requestAccess(deviceName: String) {
        val device = manager.deviceList[deviceName] ?: run {
            record("USB access request: device already disconnected")
            refresh(UsbNotice.DEVICE_DISCONNECTED)
            return
        }
        if (manager.hasPermission(device)) {
            record("USB access already allowed for ${deviceLabel(device)}")
            refresh(UsbNotice.ACCESS_GRANTED)
            return
        }
        if (!pending.add(deviceName)) return
        record("USB access requested for ${deviceLabel(device)}")
        refresh()

        // UsbManager adds the device and permission result to the callback intent.
        // Mutable is required for those extras; the intent is restricted to our package.
        val callback = PendingIntent.getBroadcast(
            activity,
            device.deviceId,
            Intent(permissionAction).setPackage(activity.packageName),
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0) or
                PendingIntent.FLAG_UPDATE_CURRENT,
        )
        try {
            manager.requestPermission(device, callback)
        } catch (error: SecurityException) {
            pending.remove(deviceName)
            record("USB access request failed for ${deviceLabel(device)}: ${error.javaClass.simpleName}: ${error.message}")
            refresh(UsbNotice.REQUEST_UNAVAILABLE)
        } catch (error: IllegalArgumentException) {
            pending.remove(deviceName)
            record("USB access request failed for ${deviceLabel(device)}: ${error.javaClass.simpleName}: ${error.message}")
            refresh(UsbNotice.REQUEST_UNAVAILABLE)
        }
    }

    private fun deviceLabel(device: UsbDevice): String = String.format(
        Locale.US, "VID=%04X PID=%04X id=%d", device.vendorId, device.productId, device.deviceId,
    )

    @Suppress("DEPRECATION")
    private fun deviceFromIntent(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
}
