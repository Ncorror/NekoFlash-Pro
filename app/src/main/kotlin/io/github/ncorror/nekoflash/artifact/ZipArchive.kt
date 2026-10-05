package io.github.ncorror.nekoflash.artifact

import io.github.ncorror.nekoflash.core.artifact.ArtifactAccess
import io.github.ncorror.nekoflash.core.artifact.ArtifactIdentity
import io.github.ncorror.nekoflash.core.artifact.ArtifactRandomAccess
import io.github.ncorror.nekoflash.core.artifact.ArtifactSource
import io.github.ncorror.nekoflash.core.artifact.ArtifactStamp
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * Архив ZIP, выбранный через Android, — чтение по центральному каталогу.
 *
 * Как `OpenArchive`/`UnzipToFile` у настольного `fastboot update`: записи
 * находятся по центральному каталогу, данные читаются с места, а не копией
 * архива. ZIP64 поддерживается — образы обновлений бывают больше 4 ГиБ.
 * Методы — `stored` и `deflate`; другой метод — отказ с его номером.
 */
internal class ZipArchive(private val archive: ArtifactSource) {
    /** Запись архива: где лежат данные и сколько их. */
    data class Entry(
        val name: String,
        val method: Int,
        val compressedSize: Long,
        val size: Long,
        val localHeaderOffset: Long,
    )

    private val reads: ArtifactRandomAccess = archive.randomAccess()
        ?: throw IOException("${archive.identity.name}: провайдер не даёт читать архив с произвольного места")

    private val length: Long = archive.identity.sizeBytes
        ?: throw IOException("${archive.identity.name}: провайдер не сообщает размер архива")

    /** Записи по именам, в порядке центрального каталога. */
    val entries: Map<String, Entry> by lazy { centralDirectory() }

    /** Содержимое записи как источник; `null` — такой записи нет. */
    fun source(name: String): ArtifactSource? = entries[name]?.let { EntrySource(it) }

    private fun centralDirectory(): Map<String, Entry> {
        val end = endOfCentralDirectory()
        checkedRange(end.offset, end.size)
        val entries = linkedMapOf<String, Entry>()
        var at = end.offset
        var count = 0L
        while (count < end.count) {
            val (entry, next) = entry(at, end.offset + end.size)
            entries[entry.name] = entry
            at = next
            count++
        }
        return entries
    }

    private class End(val count: Long, val size: Long, val offset: Long)

    private fun checkedRange(offset: Long, size: Long) {
        val validOffset = offset >= 0 && offset <= length
        requireZip(validOffset && size >= 0 && size <= length - offset,
            "${archive.identity.name}: данные ZIP выходят за границы архива")
    }

    private fun readAt(offset: Long, size: Int): ByteArray {
        checkedRange(offset, size.toLong())
        return reads.read(offset, size).also {
            if (it.size != size) throw IOException("${archive.identity.name}: неполное чтение ZIP")
        }
    }

    /** Запись конца каталога — с конца файла, через комментарий; ZIP64 — по локатору перед ней. */
    private fun endOfCentralDirectory(): End {
        val tailLength = minOf(length, (END_BYTES + MAX_COMMENT).toLong()).toInt()
        val tail = readAt(length - tailLength, tailLength)
        val at = (tailLength - END_BYTES downTo 0).firstOrNull {
            le32(tail, it) == END_SIGNATURE && it + END_BYTES + le16(tail, it + END_COMMENT) == tailLength
        }
            ?: throw IOException("${archive.identity.name}: не архив ZIP — нет записи конца каталога")
        val count = le16(tail, at + END_COUNT)
        val size = le32(tail, at + END_SIZE).toLong() and UINT
        val offset = le32(tail, at + END_OFFSET).toLong() and UINT
        val zip64 = count == USHORT || size == UINT || offset == UINT
        return if (zip64) zip64End(length - tailLength + at) else End(count.toLong(), size, offset)
    }

    private fun zip64End(endAt: Long): End {
        val locator = readAt(endAt - LOCATOR_BYTES, LOCATOR_BYTES)
        requireZip(le32(locator, 0) == LOCATOR_SIGNATURE, "ZIP64 без локатора конца каталога")
        val recordAt = ByteBuffer.wrap(locator).order(ByteOrder.LITTLE_ENDIAN).getLong(LOCATOR_OFFSET)
        val record = ByteBuffer.wrap(readAt(recordAt, ZIP64_END_BYTES)).order(ByteOrder.LITTLE_ENDIAN)
        requireZip(record.getInt(0) == ZIP64_END_SIGNATURE, "ZIP64: неверная запись конца каталога")
        return End(
            count = record.getLong(ZIP64_COUNT).also { requireZip(it >= 0, "ZIP64: неверное число записей") },
            size = record.getLong(ZIP64_SIZE),
            offset = record.getLong(ZIP64_OFFSET),
        )
    }

    /** Одна запись каталога: длины имени/extra — uint16, размер каталога не задаёт буфер. */
    private fun entry(at: Long, end: Long): Pair<Entry, Long> {
        requireZip(at <= end && end - at >= CENTRAL_BYTES, "повреждён центральный каталог ZIP")
        val header = ByteBuffer.wrap(readAt(at, CENTRAL_BYTES)).order(ByteOrder.LITTLE_ENDIAN)
        requireZip(header.getInt(0) == CENTRAL_SIGNATURE, "повреждён центральный каталог ZIP")
        val method = header.getShort(CENTRAL_METHOD).toInt() and USHORT
        val compressed = header.getInt(CENTRAL_COMPRESSED).toLong() and UINT
        val size = header.getInt(CENTRAL_SIZE).toLong() and UINT
        val nameLength = header.getShort(CENTRAL_NAME).toInt() and USHORT
        val extraLength = header.getShort(CENTRAL_EXTRA).toInt() and USHORT
        val commentLength = header.getShort(CENTRAL_COMMENT).toInt() and USHORT
        val offset = header.getInt(CENTRAL_LOCAL).toLong() and UINT
        val bytes = CENTRAL_BYTES.toLong() + nameLength + extraLength + commentLength
        requireZip(bytes <= end - at, "повреждён центральный каталог ZIP")
        val name = String(readAt(at + CENTRAL_BYTES, nameLength), Charsets.UTF_8)
        val extra = readAt(at + CENTRAL_BYTES + nameLength, extraLength)
        return withZip64(Entry(name, method, compressed, size, offset), extra) to (at + bytes)
    }

    private fun withZip64(entry: Entry, bytes: ByteArray): Entry {
        val extra = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var result = entry
        var resolved = false
        while (extra.remaining() >= EXTRA_HEADER) {
            val id = extra.getShort().toInt() and USHORT
            val size = extra.getShort().toInt() and USHORT
            requireZip(size <= extra.remaining(), "${entry.name}: повреждено поле extra ZIP")
            val end = extra.position() + size
            if (id == ZIP64_EXTRA) {
                resolved = true
                val field = extra.slice().order(ByteOrder.LITTLE_ENDIAN).apply { limit(size) }
                result = entry.copy(
                    size = zip64Value(entry.size, field),
                    compressedSize = zip64Value(entry.compressedSize, field),
                    localHeaderOffset = zip64Value(entry.localHeaderOffset, field),
                )
            }
            extra.position(end)
        }
        val needsZip64 = listOf(entry.size, entry.compressedSize, entry.localHeaderOffset).any { it == UINT }
        requireZip(resolved || !needsZip64, "${entry.name}: отсутствует поле ZIP64")
        requireZip(listOf(result.size, result.compressedSize, result.localHeaderOffset).all { it >= 0 },
            "${entry.name}: неверный размер или смещение ZIP64")
        return result
    }

    private fun zip64Value(value: Long, field: ByteBuffer): Long {
        if (value != UINT) return value
        if (field.remaining() < Long.SIZE_BYTES) throw IOException("ZIP64: неполное поле размера или смещения")
        return field.long
    }

    /** Данные записи: за локальным заголовком, чья длина имени и поля — свои. */
    private fun dataOffset(entry: Entry): Long {
        val header = readAt(entry.localHeaderOffset, LOCAL_BYTES)
        if (le32(header, 0) != LOCAL_SIGNATURE) throw IOException("${entry.name}: неверный локальный заголовок ZIP")
        val offset = entry.localHeaderOffset + LOCAL_BYTES + le16(header, LOCAL_NAME) + le16(header, LOCAL_EXTRA)
        checkedRange(offset, entry.compressedSize)
        return offset
    }

    private inner class EntrySource(private val entry: Entry) : ArtifactSource {
        override val identity = ArtifactIdentity(entry.name, entry.size, ArtifactAccess.NON_SEEKABLE)
        override val openedStamp = ArtifactStamp(
            entry.size, archive.openedStamp.modifiedAtMillis, archive.openedStamp.version,
        )
        override fun stamp(): ArtifactStamp =
            archive.stamp().let { ArtifactStamp(entry.size, it.modifiedAtMillis, it.version) }

        override fun open(): InputStream {
            val raw = BufferedInputStream(Window(dataOffset(entry), entry.compressedSize), CHUNK)
            return when (entry.method) {
                STORED -> raw
                // Лишний нулевой байт после данных: `Inflater` в режиме
                // `nowrap` может его попросить, чтобы признать конец потока.
                DEFLATED -> deflated(raw)
                else -> throw IOException("${entry.name}: метод сжатия ${entry.method} не поддерживается")
            }
        }

        override fun randomAccess(): ArtifactRandomAccess? = null
    }

    private fun deflated(raw: InputStream): InputStream {
        val inflater = Inflater(true)
        return object : InflaterInputStream(SequenceInputStream(raw, ByteArray(1).inputStream()), inflater, CHUNK) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    inflater.end()
                }
            }
        }
    }

    /** Отрезок архива как поток — чтениями с места. */
    private inner class Window(private var position: Long, private var remaining: Long) : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and BYTE
        }

        override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
            if (count == 0) return 0
            if (remaining <= 0L) return -1
            val take = minOf(count.toLong(), remaining, CHUNK.toLong()).toInt()
            readAt(position, take).copyInto(buffer, offset)
            position += take
            remaining -= take
            return take
        }
    }

    private companion object {
        const val END_SIGNATURE = 0x06054b50
        const val LOCATOR_SIGNATURE = 0x07064b50
        const val ZIP64_END_SIGNATURE = 0x06064b50
        const val CENTRAL_SIGNATURE = 0x02014b50
        const val LOCAL_SIGNATURE = 0x04034b50
        const val END_BYTES = 22
        const val MAX_COMMENT = 0xFFFF
        const val END_COUNT = 10
        const val END_SIZE = 12
        const val END_OFFSET = 16
        const val END_COMMENT = 20
        const val LOCATOR_BYTES = 20
        const val LOCATOR_OFFSET = 8
        const val ZIP64_END_BYTES = 56
        const val ZIP64_COUNT = 32
        const val ZIP64_SIZE = 40
        const val ZIP64_OFFSET = 48
        const val CENTRAL_BYTES = 46
        const val CENTRAL_METHOD = 10
        const val CENTRAL_COMPRESSED = 20
        const val CENTRAL_SIZE = 24
        const val CENTRAL_NAME = 28
        const val CENTRAL_EXTRA = 30
        const val CENTRAL_COMMENT = 32
        const val CENTRAL_LOCAL = 42
        const val LOCAL_BYTES = 30
        const val LOCAL_NAME = 26
        const val LOCAL_EXTRA = 28
        const val EXTRA_HEADER = 4
        const val ZIP64_EXTRA = 0x0001
        const val STORED = 0
        const val DEFLATED = 8
        const val CHUNK = 64 * 1024
        const val USHORT = 0xFFFF
        const val UINT = 0xFFFF_FFFFL
        const val BYTE = 0xFF

        const val BYTE_BITS = 8
        const val SHORT_BITS = 16

        fun le16(bytes: ByteArray, at: Int): Int =
            (bytes[at].toInt() and BYTE) or ((bytes[at + 1].toInt() and BYTE) shl BYTE_BITS)

        fun le32(bytes: ByteArray, at: Int): Int = le16(bytes, at) or (le16(bytes, at + 2) shl SHORT_BITS)
    }
}

private fun requireZip(condition: Boolean, message: String) {
    if (!condition) throw IOException(message)
}
