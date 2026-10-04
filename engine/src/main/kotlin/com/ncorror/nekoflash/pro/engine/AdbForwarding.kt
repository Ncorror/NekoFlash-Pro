package com.ncorror.nekoflash.pro.engine

/** Host-side ADB forward request parsed from the desktop-client syntax. */
sealed interface AdbForwardRequest {
    data class Add(val local: String, val remote: String, val noRebind: Boolean = false) : AdbForwardRequest
    data class Remove(val local: String) : AdbForwardRequest
    data object List : AdbForwardRequest
    data object RemoveAll : AdbForwardRequest
}

/** Device-side ADB reverse request parsed from the desktop-client syntax. */
sealed interface AdbReverseRequest {
    data class Add(val remote: String, val local: String, val noRebind: Boolean = false) : AdbReverseRequest
    data class Remove(val remote: String) : AdbReverseRequest
    data object List : AdbReverseRequest
    data object RemoveAll : AdbReverseRequest
}

/** Typed host command. Parsing it must never turn it into a remote shell/service call. */
data class AdbForwardCommand(val request: AdbForwardRequest) : AdbCommand

/** Typed reverse command. The device remains the authority for remote endpoint names. */
data class AdbReverseCommand(val request: AdbReverseRequest) : AdbCommand

/** Existing host listener implementation: loopback TCP port to an arbitrary device ADB service endpoint. */
data class AdbForwardAddStep(val localPort: Int, val remote: String) : TerminalPlanStep

/** Remove one existing host listener by the actual local TCP port. */
data class AdbForwardRemoveStep(val localPort: Int) : TerminalPlanStep

/** Report the listeners owned by this app without touching the device. */
data object AdbForwardListStep : TerminalPlanStep

/** Existing reverse implementation: device endpoint routes incoming streams to the named host endpoint. */
data class AdbReverseAddStep(val remote: String, val local: String) : TerminalPlanStep

/** Ask the device for its reverse forwarding table. */
data object AdbReverseListStep : TerminalPlanStep

/** Ask the device to remove all reverse mappings. */
data object AdbReverseRemoveAllStep : TerminalPlanStep
