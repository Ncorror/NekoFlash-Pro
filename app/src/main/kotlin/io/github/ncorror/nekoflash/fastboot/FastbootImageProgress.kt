package io.github.ncorror.nekoflash.fastboot

/** Live progress for an image that is being downloaded and written through Fastboot. */
internal sealed interface FastbootImageProgress {
    data class Transferring(
        val target: String,
        val sentBytes: Long,
        val totalBytes: Long,
    ) : FastbootImageProgress

    data class Writing(
        val target: String,
        val sentBytes: Long,
        val totalBytes: Long,
    ) : FastbootImageProgress
}
