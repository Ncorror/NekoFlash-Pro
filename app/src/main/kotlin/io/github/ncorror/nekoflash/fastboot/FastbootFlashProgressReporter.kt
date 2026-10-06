package io.github.ncorror.nekoflash.fastboot

/** Converts image-engine callbacks into the four fields the operator UI needs. */
internal class FastbootFlashProgressReporter(
    private val publish: (FastbootOperationStage, String, Long, Long) -> Unit,
) {
    fun preparing(target: String, totalBytes: Long) {
        publish(FastbootOperationStage.PREPARING, target, 0L, totalBytes)
    }

    fun report(progress: FastbootImageProgress) {
        when (progress) {
            is FastbootImageProgress.Transferring -> publish(
                FastbootOperationStage.TRANSFERRING,
                progress.target,
                progress.sentBytes,
                progress.totalBytes,
            )
            is FastbootImageProgress.Writing -> publish(
                FastbootOperationStage.WRITING,
                progress.target,
                progress.sentBytes,
                progress.totalBytes,
            )
        }
    }
}
