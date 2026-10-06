package io.github.ncorror.nekoflash.artifact

import android.content.Context
import android.os.storage.StorageManager
import java.io.File
import java.nio.file.Files

/** Query reclaimable space and make known-size staging possible before the first copy byte. */
internal class ArtifactStorageSpace(context: Context) {
    private val storage = requireNotNull(context.getSystemService(StorageManager::class.java))

    fun prepare(directory: File, requiredBytes: Long?): Long {
        val volume = storage.getUuidForPath(existingStagingPath(directory))
        val available = storage.getAllocatableBytes(volume)
        if (requiredBytes != null && requiredBytes <= available) storage.allocateBytes(volume, requiredBytes)
        return available
    }
}

/** The staging folder may not exist yet; its filesystem is determined by the closest existing parent. */
private fun existingStagingPath(directory: File): File {
    var current = directory
    while (!current.exists()) current = checkNotNull(current.parentFile)
    return current
}

/** Real filesystem capacity for callers without Android's reclaimable-cache API. */
internal fun filesystemStagingSpace(directory: File): Long =
    Files.getFileStore(existingStagingPath(directory).toPath()).usableSpace
