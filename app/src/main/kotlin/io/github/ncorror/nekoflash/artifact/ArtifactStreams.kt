package io.github.ncorror.nekoflash.artifact

import io.github.ncorror.nekoflash.core.artifact.ArtifactSource
import java.io.InputStream

/** Нулевой результат bulk-read не является EOF; один байт обеспечивает продвижение. */
internal fun readArtifactChunk(input: InputStream, bytes: ByteArray): Int {
    val read = input.read(bytes)
    if (read != 0) return read
    val one = input.read()
    return if (one < 0) -1 else {
        bytes[0] = one.toByte()
        1
    }
}

/** Длина источника, который можно открыть с начала; данные не накапливаются. */
internal fun measuredArtifactSize(source: ArtifactSource): Long = source.open().use { input ->
    val bytes = ByteArray(READ_CHUNK)
    var size = 0L
    var read = readArtifactChunk(input, bytes)
    while (read >= 0) {
        size += read
        read = readArtifactChunk(input, bytes)
    }
    size
}

private const val READ_CHUNK = 16 * 1024
