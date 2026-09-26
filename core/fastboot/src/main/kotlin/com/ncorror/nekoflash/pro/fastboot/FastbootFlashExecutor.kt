package com.ncorror.nekoflash.pro.fastboot

import com.ncorror.nekoflash.pro.transport.FastbootPacketTransport
import java.io.InputStream

sealed interface FastbootFlashResult {
    data class Finished(val message: String) : FastbootFlashResult
    data class DeviceRejected(val stage: Stage, val reason: String) : FastbootFlashResult
    data class TooLarge(val size: Long, val limit: Long) : FastbootFlashResult
    data object MissingDownloadLimit : FastbootFlashResult
    enum class Stage { DOWNLOAD, FLASH }
}

/** One raw image: check device limit, stream DATA, then issue flash in the same session.
 * This core operation is intentionally not connected to the app until file selection,
 * sparse handling and a user-reviewed preview are implemented. */
class FastbootFlashExecutor(private val openTransport: () -> FastbootPacketTransport) {
    fun flashRaw(
        partition: String,
        imageSize: Long,
        openImage: () -> InputStream,
        onProgress: (Long) -> Unit = {},
    ): FastbootFlashResult {
        require(partition.matches(PARTITION)) { "Invalid Fastboot partition name" }
        require(imageSize in 1..MAX_WIRE_SIZE) { "Image size requires sparse planning" }
        return FastbootWireSession(openTransport()).use { session ->
            val limitReply = session.execute("getvar:max-download-size")
            val limit = when (limitReply) {
                is FastbootWireResult.Okay -> parseLimit(limitReply.message)
                is FastbootWireResult.Fail -> null
                is FastbootWireResult.DataPhaseRequired -> null
            } ?: return@use FastbootFlashResult.MissingDownloadLimit
            if (imageSize > limit) return@use FastbootFlashResult.TooLarge(imageSize, limit)

            val download = openImage().use { source -> session.download(source, imageSize, onProgress) }
            when (download) {
                is FastbootWireResult.Fail -> return@use FastbootFlashResult.DeviceRejected(
                    FastbootFlashResult.Stage.DOWNLOAD, download.reason)
                is FastbootWireResult.DataPhaseRequired -> error("DATA cannot complete a download")
                is FastbootWireResult.Okay -> Unit
            }
            when (val flash = session.execute("flash:$partition")) {
                is FastbootWireResult.Okay -> FastbootFlashResult.Finished(flash.message)
                is FastbootWireResult.Fail -> FastbootFlashResult.DeviceRejected(
                    FastbootFlashResult.Stage.FLASH, flash.reason)
                is FastbootWireResult.DataPhaseRequired -> throw FastbootProtocolException(
                    "Unexpected Fastboot DATA during flash")
            }
        }
    }

    private fun parseLimit(value: String): Long? {
        val trimmed = value.trim()
        val parsed = if (trimmed.startsWith("0x", ignoreCase = true)) {
            trimmed.substring(2).toLongOrNull(16)
        } else trimmed.toLongOrNull()
        return parsed?.takeIf { it > 0 }?.coerceAtMost(MAX_WIRE_SIZE)
    }

    private companion object {
        const val MAX_WIRE_SIZE = 0xffff_ffffL
        val PARTITION = Regex("[A-Za-z0-9._-]{1,64}")
    }
}
