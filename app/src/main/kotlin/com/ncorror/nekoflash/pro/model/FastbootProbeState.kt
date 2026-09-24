package com.ncorror.nekoflash.pro.model

import com.ncorror.nekoflash.pro.fastboot.FastbootVariable

enum class FastbootProbePhase { RUNNING, VALUE, REJECTED, FAILED }

data class FastbootProbeState(
    val deviceKey: String,
    val deviceId: Int,
    val vendorId: Int,
    val productId: Int,
    val variable: FastbootVariable,
    val phase: FastbootProbePhase,
    val detail: String? = null,
) {
    fun matches(device: UsbDeviceSummary): Boolean =
        deviceKey == device.key && deviceId == device.deviceId &&
            vendorId == device.vendorId && productId == device.productId
}
