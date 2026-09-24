package com.ncorror.nekoflash.pro.model

/** State the UI can render without depending on Android USB APIs. */
data class UsbHostSnapshot(
    val supported: Boolean = true,
    val devices: List<UsbDeviceSummary> = emptyList(),
    val notice: UsbNotice? = null,
)

data class UsbDeviceSummary(
    val key: String,
    val vendorId: Int,
    val productId: Int,
    val deviceId: Int,
    val accessGranted: Boolean,
    val accessPending: Boolean,
)

enum class UsbNotice {
    DEVICE_CHANGED,
    DEVICE_DISCONNECTED,
    ACCESS_GRANTED,
    ACCESS_DENIED,
    REQUEST_UNAVAILABLE,
}
