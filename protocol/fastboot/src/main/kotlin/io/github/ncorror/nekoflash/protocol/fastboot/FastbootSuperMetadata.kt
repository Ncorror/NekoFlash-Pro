package io.github.ncorror.nekoflash.protocol.fastboot

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Логический раздел в метаданных `super`: `LpMetadataPartition`. */
public data class LpPartition(
    val name: String,
    val attributes: Int,
    val firstExtentIndex: Int,
    val extentCount: Int,
    val groupIndex: Int,
) {
    /** `LP_PARTITION_ATTR_SLOT_SUFFIXED`: имя на устройстве — с `_a`/`_b`. */
    val slotSuffixed: Boolean get() = attributes and LpMetadata.ATTR_SLOT_SUFFIXED != 0
}

/** Участок раздела: `LpMetadataExtent`. */
public data class LpExtent(val sectors: Long, val targetType: Int, val targetData: Long, val targetSource: Int)

/** Группа разделов: `LpMetadataPartitionGroup`. */
public data class LpGroup(val name: String, val flags: Int, val maximumSize: Long)

/** Физическое устройство под `super`: `LpMetadataBlockDevice`. */
public data class LpBlockDevice(
    val firstLogicalSector: Long,
    val alignment: Int,
    val alignmentOffset: Int,
    val size: Long,
    val partitionName: String,
    val flags: Int,
)

/** `LpMetadataGeometry` без контрольной суммы. */
public data class LpGeometry(val metadataMaxSize: Int, val metadataSlotCount: Int, val logicalBlockSize: Int)

/** Метаданные логических разделов, как их читает `liblp`. */
public data class LpMetadata(
    val geometry: LpGeometry,
    val majorVersion: Int,
    val minorVersion: Int,
    val headerFlags: Int,
    val partitions: List<LpPartition>,
    val extents: List<LpExtent>,
    val groups: List<LpGroup>,
    val blockDevices: List<LpBlockDevice>,
) {
    /**
     * `should_flash_in_userspace` (AOSP `fastboot/util.cpp`): раздел — логический,
     * если он есть в метаданных; у разделов со слотом подходят оба имени, `_a` и `_b`.
     */
    public fun flashesInUserspace(partition: String): Boolean = partitions.any { entry ->
        if (entry.slotSuffixed) {
            partition == entry.name + "_a" || partition == entry.name + "_b"
        } else {
            partition == entry.name
        }
    }

    public companion object {
        public const val ATTR_SLOT_SUFFIXED: Int = 1 shl 1
    }
}

/** Прочитаны метаданные или нет — с причиной словами `liblp`. */
public sealed interface LpMetadataRead {
    public data class Parsed(val metadata: LpMetadata) : LpMetadataRead
    public data class Invalid(val reason: String) : LpMetadataRead
}

/**
 * Разбор `super_empty.img` — `ReadFromImageBlob` из AOSP `fs_mgr/liblp`
 * (`images.cpp`, `reader.cpp`, `include/liblp/metadata_format.h`): геометрия
 * в первых 4096 байтах, за ней заголовок и таблицы. Каждая проверка `liblp`
 * здесь есть, и отказ называется её словами.
 */
public object FastbootSuperMetadata {
    public fun fromImageBlob(bytes: ByteArray): LpMetadataRead {
        if (bytes.size < GEOMETRY_SIZE) {
            return LpMetadataRead.Invalid("${bytes.size} is smaller than geometry header")
        }
        val geometry = when (val parsed = geometry(bytes)) {
            is Parsed.Ok -> parsed.value
            is Parsed.Bad -> return LpMetadataRead.Invalid(parsed.reason)
        }
        return metadata(geometry, ByteBuffer.wrap(bytes, GEOMETRY_SIZE, bytes.size - GEOMETRY_SIZE).slice())
    }

    private sealed interface Parsed<out T> {
        data class Ok<T>(val value: T) : Parsed<T>
        data class Bad(val reason: String) : Parsed<Nothing>
    }

    /** `ParseGeometry`. */
    private fun geometry(bytes: ByteArray): Parsed<LpGeometry> {
        val buffer = ByteBuffer.wrap(bytes, 0, GEOMETRY_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        val magic = buffer.getInt(0)
        val structSize = buffer.getInt(STRUCT_SIZE_OFFSET)
        return when {
            magic != GEOMETRY_MAGIC ->
                Parsed.Bad("Logical partition metadata has invalid geometry magic signature.")
            structSize < 0 || structSize > GEOMETRY_STRUCT ->
                Parsed.Bad("Logical partition metadata has unrecognized fields.")
            !checksumMatches(bytes, 0, structSize, CHECKSUM_OFFSET) ->
                Parsed.Bad("Logical partition metadata has invalid geometry checksum.")
            structSize != GEOMETRY_STRUCT -> Parsed.Bad("Logical partition metadata has invalid struct size.")
            else -> validGeometry(
                LpGeometry(
                    metadataMaxSize = buffer.getInt(MAX_SIZE_OFFSET),
                    metadataSlotCount = buffer.getInt(SLOT_COUNT_OFFSET),
                    logicalBlockSize = buffer.getInt(BLOCK_SIZE_OFFSET),
                ),
            )
        }
    }

    private fun validGeometry(geometry: LpGeometry): Parsed<LpGeometry> = when {
        geometry.metadataSlotCount == 0 -> Parsed.Bad("Logical partition metadata has invalid slot count.")
        Integer.remainderUnsigned(geometry.metadataMaxSize, SECTOR_SIZE) != 0 ->
            Parsed.Bad("Metadata max size is not sector-aligned.")
        else -> Parsed.Ok(geometry)
    }

    /** SHA-256 первых [length] байт с [offset] при обнулённом поле суммы на [checksumAt]. */
    private fun checksumMatches(bytes: ByteArray, offset: Int, length: Int, checksumAt: Int): Boolean {
        if (length < checksumAt + SHA256_BYTES || offset + length > bytes.size) return false
        val copy = bytes.copyOfRange(offset, offset + length)
        val stored = copy.copyOfRange(checksumAt, checksumAt + SHA256_BYTES)
        copy.fill(0, checksumAt, checksumAt + SHA256_BYTES)
        return MessageDigest.getInstance("SHA-256").digest(copy).contentEquals(stored)
    }

    /** `ReadMetadataHeader` и `ParseMetadata`. */
    private fun metadata(geometry: LpGeometry, data: ByteBuffer): LpMetadataRead {
        val header = when (val read = LpHeader.read(data)) {
            is Parsed.Ok -> read.value
            is Parsed.Bad -> return LpMetadataRead.Invalid(read.reason)
        }
        val tablesSize = header.tablesSize
        return when {
            Integer.compareUnsigned(tablesSize, geometry.metadataMaxSize) > 0 ->
                LpMetadataRead.Invalid("Invalid partition metadata header table size.")
            data.remaining() - header.size < tablesSize -> LpMetadataRead.Invalid("read $tablesSize bytes failed")
            else -> tables(geometry, header, data)
        }
    }

    private fun tables(geometry: LpGeometry, header: LpHeader, data: ByteBuffer): LpMetadataRead {
        val tables = ByteArray(header.tablesSize)
        data.position(header.size)
        data.get(tables)
        if (!MessageDigest.getInstance("SHA-256").digest(tables).contentEquals(header.tablesChecksum)) {
            return LpMetadataRead.Invalid("Logical partition metadata has invalid table checksum.")
        }
        val buffer = ByteBuffer.wrap(tables).order(ByteOrder.LITTLE_ENDIAN)
        val validAttributes = if (header.minor >= VERSION_FOR_UPDATED_ATTR) ATTRIBUTE_MASK else ATTRIBUTE_MASK_V0
        val partitions = header.partitions.entries(buffer) { at -> partition(buffer, at) }
        val extents = header.extents.entries(buffer) { at -> extent(buffer, at) }
        val groups = header.groups.entries(buffer) { at -> LpGroup(name(buffer, at), buffer.getInt(at + NAME_BYTES), buffer.getLong(at + GROUP_MAX_OFFSET)) }
        val devices = header.blockDevices.entries(buffer) { at -> blockDevice(buffer, at) }
        val problem = partitions.firstNotNullOfOrNull { partitionProblem(it, validAttributes, header) }
            ?: extents.firstOrNull { it.targetType == TARGET_LINEAR && Integer.compareUnsigned(it.targetSource, header.blockDevices.count) >= 0 }
                ?.let { "Logical partition extent has invalid block device." }
            ?: superProblem(geometry, devices)
        return problem?.let(LpMetadataRead::Invalid) ?: LpMetadataRead.Parsed(
            LpMetadata(geometry, header.major, header.minor, header.flags, partitions, extents, groups, devices),
        )
    }

    private fun partitionProblem(partition: LpPartition, validAttributes: Int, header: LpHeader): String? {
        val end = partition.firstExtentIndex.toLong().and(UINT) + partition.extentCount.toLong().and(UINT)
        return when {
            partition.attributes and validAttributes.inv() != 0 -> "Logical partition has invalid attribute set."
            end > UINT -> "Logical partition first_extent_index + num_extents overflowed."
            end > header.extents.count.toLong().and(UINT) -> "Logical partition has invalid extent list."
            Integer.compareUnsigned(partition.groupIndex, header.groups.count) >= 0 ->
                "Logical partition has invalid group index."
            else -> null
        }
    }

    /** Область метаданных не должна заходить на логические разделы. */
    private fun superProblem(geometry: LpGeometry, devices: List<LpBlockDevice>): String? {
        val superDevice = devices.firstOrNull() ?: return "Metadata does not specify a super device."
        val region = RESERVED_BYTES +
            (GEOMETRY_SIZE + geometry.metadataMaxSize.toLong().and(UINT) * geometry.metadataSlotCount.toLong().and(UINT)) * 2
        return if (region > superDevice.firstLogicalSector * SECTOR_SIZE) {
            "Logical partition metadata overlaps with logical partition contents."
        } else {
            null
        }
    }

    private fun partition(buffer: ByteBuffer, at: Int) = LpPartition(
        name = name(buffer, at),
        attributes = buffer.getInt(at + NAME_BYTES),
        firstExtentIndex = buffer.getInt(at + NAME_BYTES + INT_BYTES),
        extentCount = buffer.getInt(at + NAME_BYTES + 2 * INT_BYTES),
        groupIndex = buffer.getInt(at + NAME_BYTES + 3 * INT_BYTES),
    )

    private fun extent(buffer: ByteBuffer, at: Int) = LpExtent(
        sectors = buffer.getLong(at),
        targetType = buffer.getInt(at + LONG_BYTES),
        targetData = buffer.getLong(at + LONG_BYTES + INT_BYTES),
        targetSource = buffer.getInt(at + 2 * LONG_BYTES + INT_BYTES),
    )

    private fun blockDevice(buffer: ByteBuffer, at: Int) = LpBlockDevice(
        firstLogicalSector = buffer.getLong(at),
        alignment = buffer.getInt(at + LONG_BYTES),
        alignmentOffset = buffer.getInt(at + LONG_BYTES + INT_BYTES),
        size = buffer.getLong(at + 2 * LONG_BYTES),
        partitionName = name(buffer, at + DEVICE_NAME_OFFSET),
        flags = buffer.getInt(at + DEVICE_NAME_OFFSET + NAME_BYTES),
    )

    /** Имя до первого нуля, не длиннее 36 байт: `ExtractString`. */
    private fun name(buffer: ByteBuffer, at: Int): String {
        val raw = ByteArray(NAME_BYTES).also { bytes -> for (i in bytes.indices) bytes[i] = buffer.get(at + i) }
        val end = raw.indexOf(0).let { if (it < 0) NAME_BYTES else it }
        return String(raw, 0, end, Charsets.US_ASCII)
    }

    /** Описание таблицы: смещение от конца заголовка, число и размер записей. */
    private class Table(val offset: Int, val count: Int, val entrySize: Int) {
        fun <T> entries(buffer: ByteBuffer, read: (Int) -> T): List<T> =
            (0 until count).map { index -> read(offset + index * entrySize) }.also { buffer.position(0) }
    }

    private class LpHeader(
        val major: Int,
        val minor: Int,
        val size: Int,
        val tablesSize: Int,
        val tablesChecksum: ByteArray,
        val partitions: Table,
        val extents: Table,
        val groups: Table,
        val blockDevices: Table,
        val flags: Int,
    ) {
        companion object {
            /** `ReadMetadataHeader`: версия, размер, сумма, границы и размеры записей таблиц. */
            fun read(data: ByteBuffer): Parsed<LpHeader> {
                if (data.remaining() < HEADER_V1_0) return Parsed.Bad("ReadMetadataHeader read failed")
                val buffer = data.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                val magic = buffer.getInt(0)
                val major = buffer.getShort(MAJOR_OFFSET).toInt() and USHORT
                val minor = buffer.getShort(MINOR_OFFSET).toInt() and USHORT
                val size = buffer.getInt(HEADER_SIZE_OFFSET)
                val expected = if (minor < VERSION_FOR_EXPANDED_HEADER) HEADER_V1_0 else HEADER_V1_2
                return when {
                    magic != HEADER_MAGIC -> Parsed.Bad("Logical partition metadata has invalid magic value.")
                    major != MAJOR_VERSION || minor > MINOR_VERSION_MAX ->
                        Parsed.Bad("Logical partition metadata has incompatible version.")
                    size != expected -> Parsed.Bad("Invalid partition metadata header struct size.")
                    data.remaining() < size -> Parsed.Bad("ReadMetadataHeader read failed")
                    else -> checked(data, buffer, major, minor, size)
                }
            }

            private fun checked(data: ByteBuffer, buffer: ByteBuffer, major: Int, minor: Int, size: Int): Parsed<LpHeader> {
                val raw = ByteArray(size).also { data.duplicate().get(it) }
                val header = LpHeader(
                    major = major,
                    minor = minor,
                    size = size,
                    tablesSize = buffer.getInt(TABLES_SIZE_OFFSET),
                    tablesChecksum = raw.copyOfRange(TABLES_CHECKSUM_OFFSET, TABLES_CHECKSUM_OFFSET + SHA256_BYTES),
                    partitions = table(buffer, PARTITIONS_OFFSET),
                    extents = table(buffer, PARTITIONS_OFFSET + TABLE_BYTES),
                    groups = table(buffer, PARTITIONS_OFFSET + 2 * TABLE_BYTES),
                    blockDevices = table(buffer, PARTITIONS_OFFSET + 3 * TABLE_BYTES),
                    flags = if (size > HEADER_V1_0) buffer.getInt(HEADER_V1_0) else 0,
                )
                val tables = listOf(header.partitions, header.extents, header.groups, header.blockDevices)
                return when {
                    !checksumMatches(raw, 0, size, HEADER_CHECKSUM_OFFSET) ->
                        Parsed.Bad("Logical partition metadata has invalid checksum.")
                    tables.any { !withinTables(header.tablesSize, it) } ->
                        Parsed.Bad("Logical partition metadata has invalid table bounds.")
                    header.partitions.entrySize != PARTITION_BYTES ->
                        Parsed.Bad("Logical partition metadata has invalid partition table entry size.")
                    header.extents.entrySize != EXTENT_BYTES ->
                        Parsed.Bad("Logical partition metadata has invalid extent table entry size.")
                    header.groups.entrySize != GROUP_BYTES ->
                        Parsed.Bad("Logical partition metadata has invalid group table entry size.")
                    else -> Parsed.Ok(header)
                }
            }

            private fun table(buffer: ByteBuffer, at: Int) =
                Table(buffer.getInt(at), buffer.getInt(at + INT_BYTES), buffer.getInt(at + 2 * INT_BYTES))

            /** `ValidateTableBounds`, в беззнаковой арифметике, как в C. */
            private fun withinTables(tablesSize: Int, table: Table): Boolean {
                val total = tablesSize.toLong() and UINT
                val offset = table.offset.toLong() and UINT
                val size = (table.count.toLong() and UINT) * (table.entrySize.toLong() and UINT)
                return offset <= total && total - offset >= size
            }
        }
    }

    private const val GEOMETRY_MAGIC = 0x616c4467
    private const val HEADER_MAGIC = 0x414C5030
    private const val GEOMETRY_SIZE = 4096
    private const val RESERVED_BYTES = 4096L
    private const val SECTOR_SIZE = 512
    private const val GEOMETRY_STRUCT = 52
    private const val STRUCT_SIZE_OFFSET = 4
    private const val CHECKSUM_OFFSET = 8
    private const val MAX_SIZE_OFFSET = 40
    private const val SLOT_COUNT_OFFSET = 44
    private const val BLOCK_SIZE_OFFSET = 48
    private const val SHA256_BYTES = 32
    private const val MAJOR_VERSION = 10
    private const val MINOR_VERSION_MAX = 2
    private const val VERSION_FOR_UPDATED_ATTR = 1
    private const val VERSION_FOR_EXPANDED_HEADER = 2
    private const val HEADER_V1_0 = 128
    private const val HEADER_V1_2 = 256
    private const val MAJOR_OFFSET = 4
    private const val MINOR_OFFSET = 6
    private const val HEADER_SIZE_OFFSET = 8
    private const val HEADER_CHECKSUM_OFFSET = 12
    private const val TABLES_SIZE_OFFSET = 44
    private const val TABLES_CHECKSUM_OFFSET = 48
    private const val PARTITIONS_OFFSET = 80
    private const val TABLE_BYTES = 12
    private const val PARTITION_BYTES = 52
    private const val EXTENT_BYTES = 24
    private const val GROUP_BYTES = 48
    private const val NAME_BYTES = 36
    private const val GROUP_MAX_OFFSET = 40
    private const val DEVICE_NAME_OFFSET = 24
    private const val INT_BYTES = 4
    private const val LONG_BYTES = 8
    private const val TARGET_LINEAR = 0
    private const val ATTRIBUTE_MASK_V0 = 0x3
    private const val ATTRIBUTE_MASK = 0xF
    private const val USHORT = 0xFFFF
    private const val UINT = 0xFFFF_FFFFL
}
