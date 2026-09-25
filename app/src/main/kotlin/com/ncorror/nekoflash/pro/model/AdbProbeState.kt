package com.ncorror.nekoflash.pro.model

enum class AdbProbePhase {
    RUNNING, AUTHORIZING, WAITING_CONFIRMATION, CONNECTED, AUTH_REQUIRED, TLS_REQUIRED, FAILED,
}

data class AdbProbeState(
    val deviceKey: String,
    val deviceId: Int,
    val vendorId: Int,
    val productId: Int,
    val phase: AdbProbePhase,
    val protocolVersion: Long? = null,
    val maxData: Long? = null,
) {
    fun matches(device: UsbDeviceSummary): Boolean =
        deviceKey == device.key && deviceId == device.deviceId &&
            vendorId == device.vendorId && productId == device.productId
}
