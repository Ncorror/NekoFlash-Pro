package ru.forum.nekoflash.pro

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
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

private const val ACTION_USB_PERMISSION = "ru.forum.nekoflash.pro.USB_PERMISSION"

/** USB inventory and permission only; this activity never opens a device or executes a command. */
class MainActivity : Activity() {
    private lateinit var usbManager: UsbManager
    private lateinit var deviceList: LinearLayout
    private lateinit var status: TextView
    private lateinit var feedback: TextView
    private val pendingDevices = mutableSetOf<String>()

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val requestedDevice = usbDeviceFromIntent(intent) ?: return
            if (!pendingDevices.remove(requestedDevice.deviceName)) return

            val connectedDevice = usbManager.deviceList[requestedDevice.deviceName]
                ?.takeIf { it.deviceId == requestedDevice.deviceId }
            val granted = connectedDevice != null &&
                intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) &&
                usbManager.hasPermission(connectedDevice)
            renderDevices()
            feedback.setText(
                when {
                    connectedDevice == null -> R.string.usb_device_disconnected
                    granted -> R.string.usb_permission_granted
                    else -> R.string.usb_permission_denied
                },
            )
        }
    }

    private val deviceEventsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED ||
                intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED
            ) {
                renderDevices()
                feedback.setText(R.string.usb_device_changed)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        deviceList = findViewById(R.id.usb_device_list)
        status = findViewById(R.id.usb_status)
        feedback = findViewById(R.id.usb_feedback)
        findViewById<Button>(R.id.usb_refresh).setOnClickListener {
            renderDevices()
            feedback.setText(R.string.usb_hint)
        }
    }

    override fun onStart() {
        super.onStart()
        val permissionFilter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(permissionReceiver, permissionFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(permissionReceiver, permissionFilter)
        }

        // This receiver listens exclusively for system USB broadcasts.
        val deviceFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        registerReceiver(deviceEventsReceiver, deviceFilter)
    }

    override fun onResume() {
        super.onResume()
        renderDevices()
    }

    override fun onStop() {
        unregisterReceiver(deviceEventsReceiver)
        unregisterReceiver(permissionReceiver)
        pendingDevices.clear()
        super.onStop()
    }

    private fun renderDevices() {
        deviceList.removeAllViews()
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
            status.setText(R.string.usb_host_unsupported)
            return
        }

        val devices = usbManager.deviceList.values.sortedWith(
            compareBy<UsbDevice> { it.vendorId }.thenBy { it.productId }.thenBy { it.deviceId },
        )
        pendingDevices.retainAll(devices.map { it.deviceName }.toSet())
        status.text = if (devices.isEmpty()) {
            getString(R.string.usb_no_devices)
        } else {
            getString(R.string.usb_device_count, devices.size)
        }

        devices.forEach { device ->
            val row = layoutInflater.inflate(R.layout.usb_device_row, deviceList, false)
            row.findViewById<TextView>(R.id.usb_device_title).text = getString(
                R.string.usb_device_label, device.vendorId, device.productId, device.deviceId,
            )
            val hasPermission = usbManager.hasPermission(device)
            row.findViewById<TextView>(R.id.usb_device_permission).setText(
                when {
                    hasPermission -> R.string.usb_access_granted
                    device.deviceName in pendingDevices -> R.string.usb_access_pending
                    else -> R.string.usb_access_missing
                },
            )
            row.findViewById<Button>(R.id.usb_permission_button).apply {
                visibility = if (hasPermission) View.GONE else View.VISIBLE
                isEnabled = device.deviceName !in pendingDevices
                setOnClickListener { requestAccess(device.deviceName) }
            }
            deviceList.addView(row, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun requestAccess(deviceName: String) {
        val device = usbManager.deviceList[deviceName] ?: run {
            renderDevices()
            feedback.setText(R.string.usb_device_disconnected)
            return
        }
        if (usbManager.hasPermission(device)) {
            renderDevices()
            feedback.setText(R.string.usb_permission_granted)
            return
        }
        if (!pendingDevices.add(deviceName)) return
        renderDevices()

        val resultIntent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
        val callback = PendingIntent.getBroadcast(
            this, device.deviceId, resultIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        try {
            usbManager.requestPermission(device, callback)
        } catch (_: SecurityException) {
            pendingDevices.remove(deviceName)
            renderDevices()
            feedback.setText(R.string.usb_permission_unavailable)
        }
    }

    @Suppress("DEPRECATION")
    private fun usbDeviceFromIntent(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
}
