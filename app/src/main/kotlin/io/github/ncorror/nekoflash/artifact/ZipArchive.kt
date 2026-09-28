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
    val entries: Map<String, Entry> by lazy { centralDirectory().associateBy { it.name } }

    /** Содержимое записи как источник; `null` — такой записи нет. */
    fun source(name: String): ArtifactSource? = entries[name]?.let { EntrySource(it) }

    /** Небольшая запись целиком: `android-info.txt`, `super_empty.img`. */
    fun bytes(name: String): ByteArray? = source(name)?.open()?.use { it.readBytes() }

    private fun centralDirectory(): List<Entry> {
        val end = endOfCentralDirectory()
        val directory = ByteBuffer.wrap(reads.read(end.offset, Math.toIntExact(end.size)))
            .order(ByteOrder.LITTLE_ENDIAN)
        return List(end.count) { entry(directory) }
    }

    private class End(val count: Int, val size: Long, val offset: Long)

    /** Запись конца каталога — с конца файла, через комментарий; ZIP64 — по локатору перед ней. */
    private fun endOfCentralDirectory(): End {
        val tailLength = minOf(length, (END_BYTES + MAX_COMMENT).toLong()).toInt()
        val tail = reads.read(length - tailLength, tailLength)
        val at = (tailLength - END_BYTES downTo 0).firstOrNull { le32(tail, it) == END_SIGNATURE }
            ?: throw IOException("${archive.identity.name}: не архив ZIP — нет записи конца каталога")
        val count = le16(tail, at + END_COUNT)
        val size = le32(tail, at + END_SIZE).toLong() and UINT
        val offset = le32(tail, at + END_OFFSET).toLong() and UINT
        val zip64 = count == USHORT || size == UINT || offset == UINT
        return if (zip64) zip64End(length - tailLength + at) else End(count, size, offset)
    }

    private fun zip64End(endAt: Long): End {
        val locator = reads.read(endAt - LOCATOR_BYTES, LOCATOR_BYTES)
        if (le32(locator, 0) != LOCATOR_SIGNATURE) throw IOException("ZIP64 без локатора конца каталога")
        val recordAt = ByteBuffer.wrap(locator).order(ByteOrder.LITTLE_ENDIAN).getLong(LOCATOR_OFFSET)
        val record = ByteBuffer.wrap(reads.read(recordAt, ZIP64_END_BYTES)).order(ByteOrder.LITTLE_ENDIAN)
        if (record.getInt(0) != ZIP64_END_SIGNATURE) throw IOException("ZIP64: неверная запись конца каталога")
        return End(
            count = Math.toIntExact(record.getLong(ZIP64_COUNT)),
            size = record.getLong(ZIP64_SIZE),
            offset = record.getLong(ZIP64_OFFSET),
        )
    }

    /** Запись центрального каталога; размеры и смещение уточняются полем ZIP64 `0x0001`. */
    private fun entry(directory: ByteBuffer): Entry {
        val start = directory.position()
        if (directory.getInt(start) != CENTRAL_SIGNATURE) throw IOException("повреждён центральный каталог ZIP")
        val method = directory.getShort(start + CENTRAL_METHOD).toInt() and USHORT
        var compressed = directory.getInt(start + CENTRAL_COMPRESSED).toLong() and UINT
        var size = directory.getInt(start + CENTRAL_SIZE).toLong() and UINT
        val nameLength = directory.getShort(start + CENTRAL_NAME).toInt() and USHORT
        val extraLength = directory.getShort(start + CENTRAL_EXTRA).toInt() and USHORT
        val commentLength = directory.getShort(start + CENTRAL_COMMENT).toInt() and USHORT
        var offset = directory.getInt(start + CENTRAL_LOCAL).toLong() and UINT
        val name = ByteArray(nameLength).also { directory.position(start + CENTRAL_BYTES); directory.get(it) }
        val extra = ByteBuffer.wrap(ByteArray(extraLength).also { directory.get(it) }).order(ByteOrder.LITTLE_ENDIAN)
        while (extra.remaining() >= EXTRA_HEADER) {
            val id = extra.getShort().toInt() and USHORT
            val fieldEnd = extra.position() + (extra.getShort().toInt() and USHORT)
            if (id == ZIP64_EXTRA) {
                if (size == UINT) size = extra.getLong()
                if (compressed == UINT) compressed = extra.getLong()
                if (offset == UINT) offset = extra.getLong()
            }
            extra.position(minOf(fieldEnd, extra.limit()))
        }
        directory.position(start + CENTRAL_BYTES + nameLength + extraLength + commentLength)
        return Entry(String(name, Charsets.UTF_8), method, compressed, size, offset)
    }

    /** Данные записи: за локальным заголовком, чья длина имени и поля — свои. */
    private fun dataOffset(entry: Entry): Long {
        val header = reads.read(entry.localHeaderOffset, LOCAL_BYTES)
        if (le32(header, 0) != LOCAL_SIGNATURE) throw IOException("${entry.name}: неверный локальный заголовок ZIP")
        return entry.localHeaderOffset + LOCAL_BYTES + le16(header, LOCAL_NAME) + le16(header, LOCAL_EXTRA)
    }

    private inner class EntrySource(private val entry: Entry) : ArtifactSource {
        override val identity = ArtifactIdentity(entry.name, entry.size, ArtifactAccess.NON_SEEKABLE)
        override val openedStamp = ArtifactStamp(entry.size, archive.openedStamp.modifiedAtMillis, null)
        override fun stamp(): ArtifactStamp =
            archive.stamp().let { ArtifactStamp(entry.size, it.modifiedAtMillis, it.version) }

        override fun open(): InputStream {
            val raw = BufferedInputStream(Window(dataOffset(entry), entry.compressedSize), CHUNK)
            return when (entry.method) {
                STORED -> raw
                // Лишний нулевой байт после данных: `Inflater` в режиме
                // `nowrap` может его попросить, чтобы признать конец потока.
                DEFLATED -> InflaterInputStream(
                    SequenceInputStream(raw, ByteArray(1).inputStream()),
                    Inflater(true),
                    CHUNK,
                )
                else -> throw IOException("${entry.name}: метод сжатия ${entry.method} не поддерживается")
            }
        }

        override fun randomAccess(): ArtifactRandomAccess? = null
    }

    /** Отрезок архива как поток — чтениями с места. */
    private inner class Window(private var position: Long, private var remaining: Long) : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and BYTE
        }

        override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
            if (remaining <= 0L) return -1
            val take = minOf(count.toLong(), remaining, CHUNK.toLong()).toInt()
            reads.read(position, take).copyInto(buffer, offset)
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
