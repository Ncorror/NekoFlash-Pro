package io.github.ncorror.nekoflash.fastboot

import io.github.ncorror.nekoflash.core.artifact.ArtifactAccess
import io.github.ncorror.nekoflash.core.artifact.ArtifactIdentity
import io.github.ncorror.nekoflash.core.artifact.ArtifactRandomAccess
import io.github.ncorror.nekoflash.core.artifact.ArtifactSource
import io.github.ncorror.nekoflash.core.artifact.ArtifactStamp
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootFetch
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootFetchOutcome
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootGetVar
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootLane
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootSparse
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootVariable
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootVendorBoot
import io.github.ncorror.nekoflash.protocol.fastboot.VendorBootPiece
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.io.SequenceInputStream
import java.util.Collections

/**
 * `fastboot flash vendor_boot:ИМЯ РАМДИСК` — `repack_ramdisk` из AOSP
 * `fastboot/fastboot.cpp`: раздел `vendor_boot` под слот читается с
 * устройства (`fetch`), рамдиск в нём заменяется ([FastbootVendorBoot]), и
 * образ той же длины прошивается обратно. Раздел читается во временный файл в
 * [staging] и удаляется после; новый образ собирается из него потоком.
 */
internal fun flashVendorRamdisk(
    lane: FastbootLane,
    ramdisk: ArtifactSource,
    name: String,
    slot: String,
    sparseLimit: Long?,
    staging: File,
): FastbootConsoleState = try {
    val bytes = ramdisk.open().use { it.readBytes() }
    if (bytes.size >= Int.SIZE_BYTES && sparseMagic(bytes)) {
        throw FlashAllStop("Flashing sparse vendor ramdisk image is not supported.")
    }
    if (bytes.isEmpty()) throw FlashAllStop("repack_ramdisk() sees negative size: 0")
    val partitions = when (val resolved = slotCommands(listOf(FLASH + VENDOR_BOOT), slot, lane)) {
        is SlotResolution.Failed -> throw FlashAllStop(resolved.detail)
        is SlotResolution.Resolved -> resolved.commands.map { it.removePrefix(FLASH) }
    }
    val done = mutableListOf<FastbootConsoleState.Planned>()
    for (partition in partitions) {
        if (done.lastOrNull()?.stoppedAt == null) done += repackOne(lane, partition, name, bytes, sparseLimit, staging)
    }
    merged(done, lane)
} catch (stop: FlashAllStop) {
    FastbootConsoleState.Planned(emptyList(), 0, 0, stop.detail, lane.state)
}

/** Один раздел: `fetch` во временный файл, замена, прошивка; отказ до прошивки — [FlashAllStop]. */
private fun repackOne(
    lane: FastbootLane,
    partition: String,
    name: String,
    ramdisk: ByteArray,
    sparseLimit: Long?,
    staging: File,
): FastbootConsoleState.Planned {
    val file = File.createTempFile("vendor_boot", ".img", staging)
    try {
        fetchTo(lane, partition, file)
        val size = file.length()
        val pieces = RandomAccessFile(file, "r").use { image ->
            FastbootVendorBoot.replace(size, { offset, length -> image.readAt(offset, length) }, name, ramdisk)
        }.getOrElse { failure -> throw FlashAllStop(failure.message.orEmpty()) }
        val source = PiecesSource(file, pieces, "$partition.img")
        return flashImage(lane, source, size, sparseLimit, listOf(FLASH + partition)) as FastbootConsoleState.Planned
    } finally {
        file.delete()
    }
}

/** `fetch_partition`: без `max-fetch-size` — отказ до первого чтения; неполное чтение — отказ. */
private fun fetchTo(lane: FastbootLane, partition: String, file: File) {
    val variables = FastbootGetVar(lane)
    val fetchSize = (variables.read(MAX_FETCH_SIZE) as? FastbootVariable.Present)?.value?.let(::deviceNumber) ?: 0L
    if (fetchSize == 0L) throw FlashAllStop("Unable to get $MAX_FETCH_SIZE. Device does not support fetch command.")
    val fetched = file.outputStream().use { FastbootFetch(lane, variables).fetch(partition, it) }
    if (fetched !is FastbootFetchOutcome.Completed) {
        throw FlashAllStop("Unable to fetch $partition: ${describeFetch(fetched)}")
    }
}

private fun merged(parts: List<FastbootConsoleState.Planned>, lane: FastbootLane): FastbootConsoleState {
    val commands = parts.flatMap { it.commands }
    val last = parts.lastOrNull()
    val stoppedAt = last?.stoppedAt?.let { it + commands.size - last.commands.size }
    val applied = parts.sumOf { it.applied }
    return FastbootConsoleState.Planned(commands, applied, stoppedAt, last?.detail.orEmpty(), lane.state)
}

private fun describeFetch(outcome: FastbootFetchOutcome): String = when (outcome) {
    is FastbootFetchOutcome.Completed -> "completed"
    is FastbootFetchOutcome.Refused -> outcome.detail
    is FastbootFetchOutcome.Partial -> "partial: ${outcome.detail}"
    is FastbootFetchOutcome.NotStarted -> outcome.detail
}

private fun sparseMagic(bytes: ByteArray): Boolean =
    java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(0) == FastbootSparse.MAGIC

private fun deviceNumber(raw: String): Long? {
    val text = raw.trim()
    return if (text.startsWith("0x") || text.startsWith("0X")) text.drop(2).toLongOrNull(HEX) else text.toLongOrNull()
}

private fun RandomAccessFile.readAt(offset: Long, length: Int): ByteArray {
    val bytes = ByteArray(length)
    seek(offset)
    readFully(bytes)
    return bytes
}

/** Новый образ `vendor_boot` потоком: отрезки прочитанного раздела, новые байты и нули. */
private class PiecesSource(private val file: File, private val pieces: List<VendorBootPiece>, name: String) :
    ArtifactSource {
    private val size = pieces.sumOf { it.length }
    override val identity = ArtifactIdentity(name, size, ArtifactAccess.NON_SEEKABLE)
    override val openedStamp = ArtifactStamp(size)
    override fun stamp() = openedStamp
    override fun randomAccess(): ArtifactRandomAccess? = null

    override fun open(): InputStream {
        val image = RandomAccessFile(file, "r")
        val streams = pieces.map { piece ->
            when (piece) {
                is VendorBootPiece.Old -> RangeStream(image, piece.offset, piece.length)
                is VendorBootPiece.New -> piece.bytes.inputStream()
                is VendorBootPiece.Zero -> ZeroStream(piece.length)
            }
        }
        return object : SequenceInputStream(Collections.enumeration(streams)) {
            override fun close() {
                super.close()
                image.close()
            }
        }
    }
}

private class RangeStream(
    private val image: RandomAccessFile,
    private var at: Long,
    private var left: Long,
) : InputStream() {
    override fun read(): Int = ByteArray(1).let { if (read(it, 0, 1) < 0) -1 else it[0].toInt() and BYTE }

    override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
        if (left <= 0L) return -1
        val take = minOf(count.toLong(), left).toInt()
        image.seek(at)
        val read = image.read(buffer, offset, take)
        if (read > 0) {
            at += read
            left -= read
        }
        return read
    }
}

private class ZeroStream(private var left: Long) : InputStream() {
    override fun read(): Int = if (left-- > 0L) 0 else -1

    override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
        if (left <= 0L) return -1
        val take = minOf(count.toLong(), left).toInt()
        buffer.fill(0, offset, offset + take)
        left -= take
        return take
    }
}

private const val FLASH = "flash:"
private const val VENDOR_BOOT = "vendor_boot"
private const val MAX_FETCH_SIZE = "max-fetch-size"
private const val HEX = 16
private const val BYTE = 0xFF
