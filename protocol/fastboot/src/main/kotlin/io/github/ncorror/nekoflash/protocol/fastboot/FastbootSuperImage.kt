package io.github.ncorror.nekoflash.protocol.fastboot

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Образ одного физического устройства под `super`: имя раздела и файл sparse. */
public data class SuperDeviceImage(val partition: String, val slotSuffixed: Boolean, val sparse: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is SuperDeviceImage && other.partition == partition && other.sparse.contentEquals(sparse)

    override fun hashCode(): Int = partition.hashCode()
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
        val maxSize = metadata.geometry.metadataMaxSize
        require(blockSize > 0 && blockSize % SECTOR == 0) { "Block size must be a multiple of the sector size, $SECTOR" }
        require(maxSize % blockSize == 0) { "Metadata max size must be a multiple of the block size, $blockSize" }
        require(GEOMETRY_SIZE % blockSize == 0) { "Geometry size is not a multiple of the block size, $blockSize" }
        val total = metadata.blockDevices.sumOf { it.size }
        require(total % blockSize == 0L) { "Device size must be a multiple of the block size, $blockSize" }
        val geometry = geometry(metadata.geometry)
        val serialized = metadata(metadata).copyOf(maxSize)
        val all = geometry + geometry + ByteArray(0).let { copies ->
            (0 until metadata.geometry.metadataSlotCount * 2).fold(copies) { acc, _ -> acc + serialized }
        }
        metadata.blockDevices.mapIndexed { index, device ->
            val blocks = device.size / blockSize
            require(blocks < UINT) { "Block device is too large to encode with libsparse." }
            val chunks = if (index == 0) listOf(Chunk.Fill(RESERVED / blockSize), Chunk.Raw(all)) else emptyList()
            SuperDeviceImage(
                partition = device.partitionName,
                slotSuffixed = device.flags and SLOT_SUFFIXED != 0,
                sparse = sparse(blockSize, blocks, chunks),
            )
        }
    }

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

    /** `SerializeMetadata`: заголовок своей версии и таблицы подряд. */
    public fun metadata(metadata: LpMetadata): ByteArray {
        val partitions = metadata.partitions.map(::partition)
        val extents = metadata.extents.map(::extent)
        val groups = metadata.groups.map(::group)
        val devices = metadata.blockDevices.map(::device)
        val tables = (partitions + extents + groups + devices).fold(ByteArray(0)) { acc, entry -> acc + entry }
        val headerSize = if (metadata.minorVersion >= EXPANDED_HEADER_VERSION) HEADER_V1_2 else HEADER_V1_0
        val header = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(HEADER_MAGIC)
            putShort(metadata.majorVersion.toShort())
            putShort(metadata.minorVersion.toShort())
            putInt(headerSize)
            put(ByteArray(SHA256_BYTES))
            putInt(tables.size)
            put(sha256(tables))
            var offset = 0
            for ((count, size) in listOf(
                partitions.size to PARTITION_BYTES,
                extents.size to EXTENT_BYTES,
                groups.size to GROUP_BYTES,
                devices.size to DEVICE_BYTES,
            )) {
                putInt(offset)
                putInt(count)
                putInt(size)
                offset += count * size
            }
            if (headerSize > HEADER_V1_0) putInt(metadata.headerFlags)
        }.array()
        sha256(header).copyInto(header, HEADER_CHECKSUM_OFFSET)
        return header + tables
    }

    private sealed interface Chunk {
        /** Заполнение нулём на [blocks] блоков. */
        data class Fill(val blocks: Int) : Chunk

        /** Данные, дополненные нулями до целого блока. */
        class Raw(val bytes: ByteArray) : Chunk
    }

    /** `sparse_file_write` без сжатия и суммы: заданные участки подряд, хвост — пропуск. */
    private fun sparse(blockSize: Int, totalBlocks: Long, chunks: List<Chunk>): ByteArray {
        val body = mutableListOf<ByteArray>()
        var at = 0L
        for (chunk in chunks) {
            when (chunk) {
                is Chunk.Fill -> {
                    body += chunkHeader(FastbootSparse.CHUNK_FILL, chunk.blocks, CHUNK_HEADER + FILL_BYTES) +
                        ByteArray(FILL_BYTES)
                    at += chunk.blocks
                }
                is Chunk.Raw -> {
                    val blocks = (chunk.bytes.size + blockSize - 1) / blockSize
                    val padded = chunk.bytes.copyOf(blocks * blockSize)
                    body += chunkHeader(FastbootSparse.CHUNK_RAW, blocks, CHUNK_HEADER + padded.size) + padded
                    at += blocks
                }
            }
        }
        if (at < totalBlocks) body += chunkHeader(FastbootSparse.CHUNK_DONT_CARE, (totalBlocks - at).toInt(), CHUNK_HEADER)
        val header = ByteBuffer.allocate(FILE_HEADER).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(FastbootSparse.MAGIC)
            putShort(MAJOR.toShort())
            putShort(0)
            putShort(FILE_HEADER.toShort())
            putShort(CHUNK_HEADER.toShort())
            putInt(blockSize)
            putInt(totalBlocks.toInt())
            putInt(body.size)
            putInt(0)
        }.array()
        return body.fold(header) { acc, part -> acc + part }
    }

    private fun chunkHeader(type: Int, blocks: Int, total: Int): ByteArray =
        ByteBuffer.allocate(CHUNK_HEADER).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(type.toShort())
            putShort(0)
            putInt(blocks)
            putInt(total)
        }.array()

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
    private const val FILE_HEADER = 28
    private const val CHUNK_HEADER = 12
    private const val FILL_BYTES = 4
    private const val MAJOR = 1
    private const val UINT = 0xFFFF_FFFFL
}
