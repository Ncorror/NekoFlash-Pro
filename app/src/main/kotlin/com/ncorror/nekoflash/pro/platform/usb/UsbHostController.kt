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

/** Enumerates USB devices and asks Android for access; never opens an interface. */
class UsbHostController(
    private val activity: Activity,
    private val publish: (UsbHostSnapshot) -> Unit,
) {
    private val manager = activity.getSystemService(Context.USB_SERVICE) as UsbManager
    private val permissionAction = "${activity.packageName}.USB_PERMISSION"
    private val pending = mutableSetOf<String>()
    private var started = false

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != permissionAction) return
            val requested = deviceFromIntent(intent) ?: return
            if (!pending.remove(requested.deviceName)) return

            val connected = manager.deviceList[requested.deviceName]
                ?.takeIf { it.deviceId == requested.deviceId }
            val granted = connected != null &&
                intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) &&
                manager.hasPermission(connected)
            refresh(
                when {
                    connected == null -> UsbNotice.DEVICE_DISCONNECTED
                    granted -> UsbNotice.ACCESS_GRANTED
                    else -> UsbNotice.ACCESS_DENIED
                },
            )
        }
    }

    private val deviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED ||
                intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED
            ) {
                refresh(UsbNotice.DEVICE_CHANGED)
            }
        }
    }

    fun start() {
        if (started) return
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
            publish(UsbHostSnapshot(supported = false, notice = notice))
            return
        }

        val devices = manager.deviceList.values.sortedWith(
            compareBy<UsbDevice> { it.vendorId }.thenBy { it.productId }.thenBy { it.deviceId },
        )
        pending.retainAll(devices.map { it.deviceName }.toSet())
        publish(UsbHostSnapshot(devices = devices.map { device ->
            UsbDeviceSummary(
                key = device.deviceName,
                vendorId = device.vendorId,
                productId = device.productId,
                deviceId = device.deviceId,
                accessGranted = manager.hasPermission(device),
                accessPending = device.deviceName in pending,
            )
        }, notice = notice))
    }

    fun requestAccess(deviceName: String) {
        val device = manager.deviceList[deviceName] ?: run {
            refresh(UsbNotice.DEVICE_DISCONNECTED)
            return
        }
        if (manager.hasPermission(device)) {
            refresh(UsbNotice.ACCESS_GRANTED)
            return
        }
        if (!pending.add(deviceName)) return
        refresh()

        val callback = PendingIntent.getBroadcast(
            activity,
            device.deviceId,
            Intent(permissionAction).setPackage(activity.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        try {
            manager.requestPermission(device, callback)
        } catch (_: SecurityException) {
            pending.remove(deviceName)
            refresh(UsbNotice.REQUEST_UNAVAILABLE)
        }
    }

    @Suppress("DEPRECATION")
    private fun deviceFromIntent(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
}
