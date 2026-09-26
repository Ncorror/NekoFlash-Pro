package com.ncorror.nekoflash.pro.model

enum class FastbootFlashPhase {
    RUNNING, FINISHED, REJECTED, TOO_LARGE, LIMIT_UNKNOWN,
    PARTITION_TOO_SMALL, PARTITION_SIZE_UNKNOWN, SPARSE_UNSUPPORTED,
    INVALID_DATA_REPLY, FAILED, CANCELLED,
}

data class FastbootFlashState(
    val deviceKey: String,
    val deviceId: Int,
    val vendorId: Int,
    val productId: Int,
    val phase: FastbootFlashPhase,
    val partition: String,
    val totalBytes: Long,
    val sentBytes: Long = 0,
) {
    fun matches(device: UsbDeviceSummary): Boolean =
        deviceKey == device.key && deviceId == device.deviceId &&
            vendorId == device.vendorId && productId == device.productId
}
