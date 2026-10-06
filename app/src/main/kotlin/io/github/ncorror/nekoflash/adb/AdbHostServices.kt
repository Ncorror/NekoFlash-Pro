package io.github.ncorror.nekoflash.adb

import io.github.ncorror.nekoflash.artifact.filesystemStagingSpace
import java.io.File

/** Android owns these host services; protocol controllers receive facts and actions without a Context. */
public data class AdbHostServices(
    public val networkPermission: () -> String,
    public val holdProcess: () -> Unit,
    public val stagingSpace: (File, Long?) -> Long = { directory, _ -> filesystemStagingSpace(directory) },
)
