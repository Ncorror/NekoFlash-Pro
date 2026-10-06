package io.github.ncorror.nekoflash.protocol.fastboot

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Пустой образ физического устройства: повторяемый поток sparse, без копии всего файла в памяти. */
public class SuperDeviceImage(
    public val partition: String,
    public val slotSuffixed: Boolean,
    public val sizeBytes: Long,
    private val openImage: () -> InputStream,
) {
    public fun open(): InputStream = openImage()
}

/**
 * Пустой `super` из метаданных — `ImageBuilder` из AOSP `fs_mgr/liblp/images.cpp`
 * без образов разделов, как его зовёт `wipe_super` настольного `fastboot`:
 * 4096 нулевых байт, две копии геометрии, по две копии метаданных на каждый
 * слот метаданных, остальное — пропуск sparse до размера устройства. Метаданные
 * сериализуются, как `SerializeGeometry` и `SerializeMetadata` (`writer.cpp`):
 * смещения таблиц и суммы SHA-256 считаются заново.
 */
public object FastbootSuperImage {
    /** Образы по устройствам; отказ — словами `ImageBuilder`. */
    public fun empty(metadata: LpMetadata): Result<List<SuperDeviceImage>> = runCatching {
        val blockSize = metadata.geometry.logicalBlockSize
        val maxSize = metadata.geometry.metadataMaxSize.toLong() and UINT
        val copies = (metadata.geometry.metadataSlotCount.toLong() and UINT) * 2
        require(blockSize > 0 && blockSize % SECTOR == 0) { "Block size must be a multiple of the sector size, $SECTOR" }
        require(maxSize % blockSize == 0L) { "Metadata max size must be a multiple of the block size, $blockSize" }
        require(GEOMETRY_SIZE % blockSize == 0) { "Geometry size is not a multiple of the block size, $blockSize" }
        val encoded = MetadataEncoding(metadata)
        val padded = (encoded.size + blockSize - 1) / blockSize * blockSize
        require(padded <= maxSize) { "Metadata size exceeds metadata max size" }
        val geometry = geometry(metadata.geometry)
        metadata.blockDevices.mapIndexed { index, device ->
            emptyDevice(device, index == 0, blockSize, maxSize, copies, geometry, encoded, padded)
        }
    }

    private fun emptyDevice(
        device: LpBlockDevice,
        primary: Boolean,
        blockSize: Int,
        maxSize: Long,
        copies: Long,
        geometry: ByteArray,
        encoded: MetadataEncoding,
        padded: Long,
    ): SuperDeviceImage {
        require(device.size >= 0 && device.size % blockSize == 0L) {
            "Device size must be a multiple of the block size, $blockSize"
        }
        val blocks = device.size / blockSize
        require(blocks <= UINT) { "Block device is too large to encode with libsparse." }
        val region = if (primary) Math.addExact(RESERVED + 2L * GEOMETRY_SIZE, Math.multiplyExact(copies, maxSize)) else 0L
        require(region <= device.size) { "Metadata overlaps logical partition contents" }
        val hasPadding = padded < maxSize
        val dataChunks = if (primary) 2L + copies * (if (hasPadding) 2 else 1) else 0L
        val tail = region < device.size
        val chunks = dataChunks + if (tail) 1 else 0
        require(chunks <= UINT) { "Too many chunks to encode with libsparse." }
        val copySize = FastbootSparse.CHUNK_HEADER_LEN + padded + if (hasPadding) FILL_CHUNK_SIZE else 0
        val size = FastbootSparse.HEADER_LEN + (if (primary) {
            FILL_CHUNK_SIZE + FastbootSparse.CHUNK_HEADER_LEN + 2L * GEOMETRY_SIZE + copies * copySize
        } else 0L) + if (tail) FastbootSparse.CHUNK_HEADER_LEN else 0
        return SuperDeviceImage(device.partitionName, device.flags and SLOT_SUFFIXED != 0, size) {
            IteratorStream(sequence {
                yield(sparseHeader(blockSize, blocks, chunks).inputStream())
                if (primary) {
                    yield(fill(RESERVED.toLong() / blockSize).inputStream())
                    yield(chunk(FastbootSparse.CHUNK_RAW, 2L * GEOMETRY_SIZE / blockSize, 2L * GEOMETRY_SIZE).inputStream())
                    yield(geometry.inputStream())
                    yield(geometry.inputStream())
                    var copy = 0L
                    while (copy < copies) {
                        yield(chunk(FastbootSparse.CHUNK_RAW, padded / blockSize, padded).inputStream())
                        yield(encoded.open())
                        yield(ZeroStream(padded - encoded.size))
                        if (hasPadding) yield(fill((maxSize - padded) / blockSize).inputStream())
                        copy++
                    }
                }
                if (tail) yield(chunk(FastbootSparse.CHUNK_DONT_CARE, (device.size - region) / blockSize, 0).inputStream())
            }.iterator())
        }
    }

    private fun sparseHeader(blockSize: Int, blocks: Long, chunks: Long): ByteArray =
        ByteBuffer.allocate(FastbootSparse.HEADER_LEN).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(FastbootSparse.MAGIC)
            putShort(1)
            putShort(0)
            putShort(FastbootSparse.HEADER_LEN.toShort())
            putShort(FastbootSparse.CHUNK_HEADER_LEN.toShort())
            putInt(blockSize)
            putInt(blocks.toInt())
            putInt(chunks.toInt())
            putInt(0)
        }.array()

    private fun chunk(type: Int, blocks: Long, payload: Long): ByteArray =
        ByteBuffer.allocate(FastbootSparse.CHUNK_HEADER_LEN).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(type.toShort())
            putShort(0)
            putInt(blocks.toInt())
            putInt((FastbootSparse.CHUNK_HEADER_LEN + payload).toInt())
        }.array()

    private fun fill(blocks: Long): ByteArray = chunk(FastbootSparse.CHUNK_FILL, blocks, Int.SIZE_BYTES.toLong()) +
        ByteArray(Int.SIZE_BYTES)

    /** `SerializeGeometry`: структура с суммой, дополненная нулями до 4096 байт. */
    public fun geometry(geometry: LpGeometry): ByteArray {
        val bytes = ByteBuffer.allocate(GEOMETRY_STRUCT).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(GEOMETRY_MAGIC)
            putInt(GEOMETRY_STRUCT)
            put(ByteArray(SHA256_BYTES))
            putInt(geometry.metadataMaxSize)
            putInt(geometry.metadataSlotCount)
            putInt(geometry.logicalBlockSize)
        }.array()
        sha256(bytes).copyInto(bytes, CHECKSUM_OFFSET)
        return bytes.copyOf(GEOMETRY_SIZE)
    }

    /** Небольшой самостоятельный срез метаданных; генерация образов использует [MetadataEncoding] потоком. */
    public fun metadata(metadata: LpMetadata): ByteArray {
        val encoded = MetadataEncoding(metadata)
        val bytes = ByteArray(Math.toIntExact(encoded.size))
        encoded.open().use { input ->
            var at = 0
            while (at < bytes.size) {
                val read = input.read(bytes, at, bytes.size - at)
                check(read > 0) { "Metadata encoding ended early" }
                at += read
            }
        }
        return bytes
    }

    private class MetadataEncoding(private val metadata: LpMetadata) {
        private val descriptions = listOf(
            metadata.partitions.size to PARTITION_BYTES,
            metadata.extents.size to EXTENT_BYTES,
            metadata.groups.size to GROUP_BYTES,
            metadata.blockDevices.size to DEVICE_BYTES,
        )
        private val tablesSize = descriptions.sumOf { (count, width) -> count.toLong() * width }
        private val headerSize = if (metadata.minorVersion >= EXPANDED_HEADER_VERSION) HEADER_V1_2 else HEADER_V1_0
        val size = headerSize + tablesSize
        private val header: ByteArray

        init {
            require(tablesSize <= UINT) { "Metadata tables size overflow" }
            val digest = MessageDigest.getInstance("SHA-256")
            records().forEach { digest.update(it) }
            header = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(HEADER_MAGIC)
                putShort(metadata.majorVersion.toShort())
                putShort(metadata.minorVersion.toShort())
                putInt(headerSize)
                put(ByteArray(SHA256_BYTES))
                putInt(tablesSize.toInt())
                put(digest.digest())
                var offset = 0L
                for ((count, width) in descriptions) {
                    putInt(offset.toInt())
                    putInt(count)
                    putInt(width)
                    offset += count.toLong() * width
                }
                if (headerSize > HEADER_V1_0) putInt(metadata.headerFlags)
            }.array()
            sha256(header).copyInto(header, HEADER_CHECKSUM_OFFSET)
        }

        private fun records(): Sequence<ByteArray> = sequence {
            metadata.partitions.forEach { yield(partition(it)) }
            metadata.extents.forEach { yield(extent(it)) }
            metadata.groups.forEach { yield(group(it)) }
            metadata.blockDevices.forEach { yield(device(it)) }
        }

        fun open(): InputStream = IteratorStream(sequence {
            yield(header.inputStream())
            records().forEach { yield(it.inputStream()) }
        }.iterator())
    }

    /** Закрытие не обходит ещё не открытые копии метаданных. */
    private class IteratorStream(private val streams: Iterator<InputStream>) : InputStream() {
        private var current: InputStream? = null
        private var closed = false
        override fun read(): Int = ByteArray(1).let { if (read(it, 0, 1) < 0) -1 else it[0].toInt() and 0xFF }
        override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
            if (count == 0) return 0
            if (closed) return -1
            while (true) {
                if (current == null) {
                    if (!streams.hasNext()) return -1
                    current = streams.next()
                }
                val read = checkNotNull(current).read(bytes, offset, count)
                if (read >= 0) return read
                current?.close()
                current = null
            }
        }
        override fun close() {
            closed = true
            current?.close()
            current = null
        }
    }

    private class ZeroStream(private var left: Long) : InputStream() {
        override fun read(): Int = if (left-- > 0) 0 else -1
        override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
            if (count == 0) return 0
            if (left <= 0) return -1
            val take = minOf(left, count.toLong()).toInt()
            bytes.fill(0, offset, offset + take)
            left -= take
            return take
        }
    }

    private fun partition(entry: LpPartition): ByteArray =
        ByteBuffer.allocate(PARTITION_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(name(entry.name))
            putInt(entry.attributes)
            putInt(entry.firstExtentIndex)
            putInt(entry.extentCount)
            putInt(entry.groupIndex)
        }.array()

    private fun extent(entry: LpExtent): ByteArray = ByteBuffer.allocate(EXTENT_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
        putLong(entry.sectors)
        putInt(entry.targetType)
        putLong(entry.targetData)
        putInt(entry.targetSource)
    }.array()

    private fun group(entry: LpGroup): ByteArray = ByteBuffer.allocate(GROUP_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
        put(name(entry.name))
        putInt(entry.flags)
        putLong(entry.maximumSize)
    }.array()

    private fun device(entry: LpBlockDevice): ByteArray = ByteBuffer.allocate(DEVICE_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
        putLong(entry.firstLogicalSector)
        putInt(entry.alignment)
        putInt(entry.alignmentOffset)
        putLong(entry.size)
        put(name(entry.partitionName))
        putInt(entry.flags)
    }.array()

    private fun name(value: String): ByteArray = value.toByteArray(Charsets.US_ASCII).copyOf(NAME_BYTES)

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private const val GEOMETRY_MAGIC = 0x616c4467
    private const val HEADER_MAGIC = 0x414C5030
    private const val GEOMETRY_SIZE = 4096
    private const val GEOMETRY_STRUCT = 52
    private const val RESERVED = 4096
    private const val SECTOR = 512
    private const val SHA256_BYTES = 32
    private const val CHECKSUM_OFFSET = 8
    private const val HEADER_CHECKSUM_OFFSET = 12
    private const val HEADER_V1_0 = 128
    private const val HEADER_V1_2 = 256
    private const val EXPANDED_HEADER_VERSION = 2
    private const val PARTITION_BYTES = 52
    private const val EXTENT_BYTES = 24
    private const val GROUP_BYTES = 48
    private const val DEVICE_BYTES = 64
    private const val NAME_BYTES = 36
    private const val SLOT_SUFFIXED = 1
    private const val FILL_CHUNK_SIZE = 16L
    private const val UINT = 0xFFFF_FFFFL
}
