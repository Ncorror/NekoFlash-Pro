package com.ncorror.nekoflash.pro.model

import com.ncorror.nekoflash.pro.adb.AdbAuthenticationPath

enum class AdbProbePhase {
    RUNNING, AUTHORIZING, WAITING_CONFIRMATION, CONNECTED, VALUE, AUTH_REQUIRED, TLS_REQUIRED, FAILED,
}

enum class AdbProbeKind { CONNECTION, FIRST_REPLY, PRODUCT }

data class AdbProbeState(
    val deviceKey: String,
    val deviceId: Int,
    val vendorId: Int,
    val productId: Int,
    val phase: AdbProbePhase,
    val protocolVersion: Long? = null,
    val maxData: Long? = null,
    val kind: AdbProbeKind = AdbProbeKind.CONNECTION,
    val authentication: AdbAuthenticationPath? = null,
    val productCode: String? = null,
) {
    fun matches(device: UsbDeviceSummary): Boolean =
        deviceKey == device.key && deviceId == device.deviceId &&
            vendorId == device.vendorId && productId == device.productId
}
