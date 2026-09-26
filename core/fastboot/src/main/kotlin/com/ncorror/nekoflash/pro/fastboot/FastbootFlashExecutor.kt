package com.ncorror.nekoflash.pro.fastboot

import com.ncorror.nekoflash.pro.transport.FastbootPacketTransport
import java.io.InputStream
import java.io.EOFException
import java.io.PushbackInputStream

sealed interface FastbootFlashResult {
    data class Finished(val message: String) : FastbootFlashResult
    data class DeviceRejected(val stage: Stage, val reason: String) : FastbootFlashResult
    data class TooLarge(val size: Long, val limit: Long) : FastbootFlashResult
    data object MissingDownloadLimit : FastbootFlashResult
    data object SparseImageUnsupported : FastbootFlashResult
    enum class Stage { DOWNLOAD, FLASH }
}

/** Report which phase failed without logging file names, Uris, or image contents. */
enum class FastbootFlashStage { READ_LIMIT, OPEN_IMAGE, DOWNLOAD, FLASH }

/** One raw image: check device limit, reject sparse, stream DATA, then flash in the same session. */
class FastbootFlashExecutor(private val openTransport: () -> FastbootPacketTransport) {
    fun flashRaw(
        partition: String,
        imageSize: Long,
        openImage: () -> InputStream,
        onProgress: (Long) -> Unit = {},
        onStage: (FastbootFlashStage) -> Unit = {},
    ): FastbootFlashResult {
        require(partition.matches(PARTITION)) { "Invalid Fastboot partition name" }
        require(imageSize in 1..MAX_WIRE_SIZE) { "Image size requires sparse planning" }
        return FastbootWireSession(openTransport()).use { session ->
            onStage(FastbootFlashStage.READ_LIMIT)
            val limitReply = session.execute("getvar:max-download-size")
            val limit = when (limitReply) {
                is FastbootWireResult.Okay -> parseLimit(limitReply.message)
                is FastbootWireResult.Fail -> null
                is FastbootWireResult.DataPhaseRequired -> null
            } ?: return@use FastbootFlashResult.MissingDownloadLimit
            if (imageSize > limit) return@use FastbootFlashResult.TooLarge(imageSize, limit)

            onStage(FastbootFlashStage.OPEN_IMAGE)
            val download: FastbootWireResult? = PushbackInputStream(openImage(), 4).use { source ->
                val prefix = ByteArray(minOf(imageSize, 4).toInt())
                for (index in prefix.indices) {
                    val next = source.read()
                    if (next < 0) throw EOFException("Fastboot image ended before declared size")
                    prefix[index] = next.toByte()
                }
                source.unread(prefix)
                if (prefix.size == 4 && prefix.contentEquals(SPARSE_MAGIC)) {
                    return@use null
                }
                onStage(FastbootFlashStage.DOWNLOAD)
                session.download(source, imageSize, onProgress)
            }
            if (download == null) return@use FastbootFlashResult.SparseImageUnsupported
            when (download) {
                is FastbootWireResult.Fail -> return@use FastbootFlashResult.DeviceRejected(
                    FastbootFlashResult.Stage.DOWNLOAD, download.reason)
                is FastbootWireResult.DataPhaseRequired -> error("DATA cannot complete a download")
                is FastbootWireResult.Okay -> Unit
            }
            onStage(FastbootFlashStage.FLASH)
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
        val SPARSE_MAGIC = byteArrayOf(0x3a, 0xff.toByte(), 0x26, 0xed.toByte())
    }
}
