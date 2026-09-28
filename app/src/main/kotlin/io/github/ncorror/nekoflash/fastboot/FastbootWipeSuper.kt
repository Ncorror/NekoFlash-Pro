package io.github.ncorror.nekoflash.fastboot

import com.ncorror.nekoflash.pro.engine.CURRENT_SLOT
import io.github.ncorror.nekoflash.core.artifact.ArtifactAccess
import io.github.ncorror.nekoflash.core.artifact.ArtifactIdentity
import io.github.ncorror.nekoflash.core.artifact.ArtifactRandomAccess
import io.github.ncorror.nekoflash.core.artifact.ArtifactSource
import io.github.ncorror.nekoflash.core.artifact.ArtifactStamp
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootGetVar
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootLane
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootMutation
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootMutationOutcome
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootSuperImage
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootSuperMetadata
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootVariable
import io.github.ncorror.nekoflash.protocol.fastboot.LpMetadata
import io.github.ncorror.nekoflash.protocol.fastboot.LpMetadataRead
import io.github.ncorror.nekoflash.protocol.fastboot.SuperDeviceImage

/**
 * `fastboot wipe-super` — `do_wipe_super` и `wipe_super` из AOSP
 * `fastboot/fastboot.cpp`: метаданные из [emptySuper], пустой `super` по ним
 * ([FastbootSuperImage]) и прошивка каждого физического устройства под слот.
 * Устройство не `super` (retrofit) — сначала `oem allow-flash-super`, ответ
 * которого, как у настольного клиента, дальнейшего не останавливает.
 */
internal fun wipeSuper(
    lane: FastbootLane,
    emptySuper: ArtifactSource,
    slot: String,
    sparseLimit: Long?,
): FastbootConsoleState = WipeSuperRun(lane, sparseLimit).run(emptySuper, slot)

/** Ход `wipe_super`: что ушло и что принято, до первого отказа. */
private class WipeSuperRun(private val lane: FastbootLane, private val sparseLimit: Long?) {
    private val commands = mutableListOf<String>()
    private var applied = 0

    fun run(emptySuper: ArtifactSource, slot: String): FastbootConsoleState {
        val stopped = try {
            wipe(emptySuper, slot)
            null
        } catch (stop: FlashAllStop) {
            stop
        }
        return FastbootConsoleState.Planned(
            commands = commands.toList(),
            applied = applied,
            stoppedAt = stopped?.let { it.atCommand ?: commands.size },
            detail = stopped?.detail.orEmpty(),
            lane = lane.state,
        )
    }

    private fun wipe(emptySuper: ArtifactSource, slot: String) {
        val (metadata, images) = superImages(emptySuper)
        val target = if (slot == CURRENT_SLOT) currentSlot(lane) else slot
        if (metadata.blockDevices.first().partitionName != SUPER) {
            commands += ALLOW_FLASH_SUPER
            val allowed = FastbootMutation(lane, FastbootGetVar(lane)).run(ALLOW_FLASH_SUPER)
            if (allowed is FastbootMutationOutcome.Applied) applied++
        }
        images.forEach { image -> names(image, target, lane).forEach { name -> flash(image, name) } }
    }

    private fun flash(image: SuperDeviceImage, name: String) {
        val size = image.sparse.size.toLong()
        val flashed = flashImage(lane, SuperSource(image), size, sparseLimit, listOf(name))
            as FastbootConsoleState.Planned
        val offset = commands.size
        commands += flashed.commands
        applied += flashed.applied
        flashed.stoppedAt?.let { throw FlashAllStop(flashed.detail, offset + it) }
    }
}

/** Метаданные и пустые образы; не вышло — остановка до первой команды словами AOSP. */
private fun superImages(emptySuper: ArtifactSource): Pair<LpMetadata, List<SuperDeviceImage>> {
    val blob = emptySuper.open().use { it.readBytes() }
    val metadata = when (val read = FastbootSuperMetadata.fromImageBlob(blob)) {
        is LpMetadataRead.Parsed -> read.metadata
        is LpMetadataRead.Invalid ->
            throw FlashAllStop("Could not parse image: ${emptySuper.identity.name}: ${read.reason}")
    }
    val images = FastbootSuperImage.empty(metadata).getOrElse { failure ->
        throw FlashAllStop("Could not generate a flashable super image file: ${failure.message}")
    }
    return metadata to images
}

/** `do_for_partitions` для устройства `super`. */
private fun names(image: SuperDeviceImage, slot: String, lane: FastbootLane): List<String> =
    when (val resolved = slotCommands(listOf(FLASH + image.partition), slot, lane)) {
        is SlotResolution.Failed -> throw FlashAllStop(resolved.detail)
        is SlotResolution.Resolved -> resolved.commands
    }

/** `get_current_slot`: без `_`; не назван — пусто, и тогда имя уточняет `do_for_partitions`. */
private fun currentSlot(lane: FastbootLane): String =
    (FastbootGetVar(lane).read("current-slot") as? FastbootVariable.Present)?.value?.removePrefix("_").orEmpty()

/** Образ `super.img` или `super_ИМЯ.img` из памяти — несколько сотен КиБ. */
private class SuperSource(private val image: SuperDeviceImage) : ArtifactSource {
    override val identity =
        ArtifactIdentity("${image.partition}.img", image.sparse.size.toLong(), ArtifactAccess.SEEKABLE)
    override val openedStamp = ArtifactStamp(image.sparse.size.toLong())
    override fun stamp() = openedStamp
    override fun open() = image.sparse.inputStream()
    override fun randomAccess(): ArtifactRandomAccess? = null
}

private const val SUPER = "super"
private const val FLASH = "flash:"
private const val ALLOW_FLASH_SUPER = "oem allow-flash-super"
