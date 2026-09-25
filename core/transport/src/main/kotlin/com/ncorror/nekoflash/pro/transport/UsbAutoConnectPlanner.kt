package com.ncorror.nekoflash.pro.transport

/** Only one unambiguous, usable ADB or Fastboot device is handled automatically. */
data class UsbAutoCandidate(
    val key: String,
    val deviceId: Int,
    val protocol: UsbWireProtocol?,
    val accessGranted: Boolean,
    val accessPending: Boolean,
)

sealed interface UsbAutoAction {
    data class RequestAccess(val key: String) : UsbAutoAction
    data class AuthorizeAdb(val key: String, val deviceId: Int) : UsbAutoAction
}

/** Tracks attempts per attachment, including denials; the user can always retry manually. */
class UsbAutoConnectPlanner {
    private data class Identity(val key: String, val deviceId: Int)
    private val requested = mutableSetOf<Identity>()
    private val authorized = mutableSetOf<Identity>()

    fun observe(devices: List<UsbAutoCandidate>): UsbAutoAction? {
        val attached = devices.map { Identity(it.key, it.deviceId) }.toSet()
        requested.retainAll(attached)
        authorized.retainAll(attached)
        if (devices.size != 1) return null
        val device = devices.single()
        val identity = Identity(device.key, device.deviceId)
        if (device.protocol == null) return null
        if (!device.accessGranted) {
            if (device.accessPending) {
                requested.add(identity) // A manually started request should not cause a second dialog.
                return null
            }
            if (!requested.add(identity)) return null
            return UsbAutoAction.RequestAccess(device.key)
        }
        if (device.protocol == UsbWireProtocol.ADB && authorized.add(identity)) {
            return UsbAutoAction.AuthorizeAdb(device.key, device.deviceId)
        }
        return null // Fastboot access is ready, but no command is run automatically.
    }

    /** Detach can be immediately followed by a new device with the same USB path and ID. */
    fun forget(key: String, deviceId: Int) {
        val identity = Identity(key, deviceId)
        requested.remove(identity)
        authorized.remove(identity)
    }
}
