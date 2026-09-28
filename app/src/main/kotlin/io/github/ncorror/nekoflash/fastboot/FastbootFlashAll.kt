package io.github.ncorror.nekoflash.fastboot

import com.ncorror.nekoflash.pro.engine.CURRENT_SLOT
import com.ncorror.nekoflash.pro.engine.SET_ACTIVE_DEFAULT
import io.github.ncorror.nekoflash.artifact.VbmetaFlagsSource
import io.github.ncorror.nekoflash.core.artifact.ArtifactAccess
import io.github.ncorror.nekoflash.core.artifact.ArtifactIdentity
import io.github.ncorror.nekoflash.core.artifact.ArtifactRandomAccess
import io.github.ncorror.nekoflash.core.artifact.ArtifactSource
import io.github.ncorror.nekoflash.core.artifact.ArtifactStamp
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootAndroidInfo
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootDownload
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootDownloadOutcome
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootGetVar
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootLane
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootMutation
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootMutationOutcome
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootReply
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootRequirementCheck
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootSparse
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootSuperMetadata
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootVariable
import io.github.ncorror.nekoflash.protocol.fastboot.LpMetadata
import io.github.ncorror.nekoflash.protocol.fastboot.LpMetadataRead
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Что попросили у `flashall`/`update`: опции настольного клиента. */
internal data class FlashAllRequest(
    /** `--slot`; [CURRENT_SLOT] — без него. */
    val slot: String = CURRENT_SLOT,
    val force: Boolean = false,
    val skipSecondary: Boolean = false,
    val skipReboot: Boolean = false,
    /** Биты `--disable-verity`/`--disable-verification` для образов vbmeta. */
    val vbmetaFlags: Int = 0,
    val sparseLimit: Long? = null,
    /** `flashall` с `--slot all` не прошивает второй слот; `update` только предупреждает. */
    val flashAll: Boolean = true,
)

/**
 * `fastboot flashall` и `fastboot update` — `FlashAllTool::Flash` из AOSP
 * `fastboot/fastboot.cpp` и задачи `task.cpp`, по порядку:
 *
 * сведения об устройстве; требования `android-info.txt` (`--force` проходит
 * мимо невыполненных); `set_active`; слот для образов второго слота; отмена
 * незаконченного обновления снимками; загрузочные образы; `update-super` по
 * `super_empty.img`; системные образы, перед первым логическим — сжатие
 * логических разделов до нуля; `reboot`, если нет `--skip-reboot`.
 *
 * Каждый образ — как `do_flash`: подпись `ИМЯ.sig`, если она есть в наборе;
 * у логического раздела — `resize-logical-partition` под размер образа; куски
 * sparse по пределу — тем же [flashImage], что у `fastboot flash`.
 *
 * Отказ устройства или невыполненное требование останавливает всё дальнейшее
 * так же, как `die` настольного клиента, и называется его словами.
 */
internal class FastbootFlashAll(
    private var lane: FastbootLane,
    private val images: FastbootImageSet,
    private val request: FlashAllRequest,
    private val rejoin: FastbootRejoin = FastbootRejoin.NONE,
) {
    /** Переменные — всегда текущей полосы: после перехода в fastbootd она новая. */
    private val variables: FastbootGetVar get() = FastbootGetVar(lane)
    private val report = mutableListOf<String>()
    private val commands = mutableListOf<String>()
    private var applied = 0
    private var skipSecondary = request.skipSecondary

    private val preflight = FlashAllPreflight({ variables }, report)
    private val superMetadata: LpMetadata? by lazy {
        images.bytes(SUPER_EMPTY)?.let { (FastbootSuperMetadata.fromImageBlob(it) as? LpMetadataRead.Parsed)?.metadata }
    }

    fun run(): FastbootConsoleState {
        val stopped = try {
            flash()
            null
        } catch (stop: FlashAllStop) {
            report += stop.detail
            stop.atCommand ?: commands.size
        }
        return FastbootConsoleState.Planned(
            commands = commands.toList(),
            applied = applied,
            stoppedAt = stopped,
            detail = report.joinToString("\n"),
            lane = lane.state,
        )
    }

    private fun flash() {
        if (request.slot == ALL) {
            report += "Warning: slot set to 'all'. Secondary slots will not be flashed."
            if (request.flashAll) skipSecondary = true
        }
        val slot = preflight.verifiedSlot(request.slot)
        preflight.dumpInfo()
        preflight.checkRequirements(images.bytes(ANDROID_INFO), request.force)
        val secondary = secondarySlot(slot)
        // Задачи собираются до первой команды, а не после `set_active`, как у
        // настольного клиента: недостающий образ и переход, которого здесь нет,
        // останавливают `flashall`, пока устройство не тронуто.
        val tasks = withResizeTasks(collect(slot, secondary), superMetadata, slot, preflight::currentSlot)
        gate(tasks)
        setActive(if (slot == ALL) FIRST_SLOT else slot)
        cancelSnapshotIfNeeded()
        tasks.forEach(::perform)
        if (!request.skipReboot) command(REBOOT)
    }

    private fun perform(task: FlashAllTask) {
        when (task) {
            is FlashAllTask.Flash -> flashTask(task)
            FlashAllTask.UpdateSuper -> updateSuper()
            is FlashAllTask.Resize -> resizeToZero(task.partition, task.slot)
            is FlashAllTask.Reboot -> reboot(task.target)
            // Отвергнуто [gate] до первой команды.
            is FlashAllTask.Wipe -> error("wipe task reached the device: ${task.partition}")
        }
    }

    private fun value(name: String): String? = preflight.value(name)

    /** `set_active`: только у устройства со слотами; без `--slot` — текущий слот. */
    private fun setActive(slot: String) {
        if (preflight.slotCount() < 2) return
        val target = if (slot == CURRENT_SLOT) preflight.currentSlot() else slot
        if (target.isNotEmpty()) command(SET_ACTIVE_DEFAULT + target)
    }


    /** `DetermineSlot`: второй слот — следующий за текущим; не вышло — образы второго слота не идут. */
    private fun secondarySlot(slot: String): String {
        if (skipSecondary) return ""
        val count = preflight.slotCount()
        val base = if (slot != CURRENT_SLOT && slot != ALL) slot else preflight.currentSlot()
        val other = if (count == 0 || base.isEmpty()) "" else ('a' + (base[0] - 'a' + 1).mod(count)).toString()
        if (other.isEmpty()) {
            if (count >= 2) report += "Warning: Could not determine slot for secondary images. Ignoring."
            skipSecondary = true
        }
        return other
    }

    private fun cancelSnapshotIfNeeded() {
        val status = value(SNAPSHOT_STATUS)
        if (!status.isNullOrEmpty() && status != NONE) command(SNAPSHOT_CANCEL)
    }

    /**
     * `CollectTasks`: по `fastboot-info.txt`, если он есть в наборе, иначе
     * `CollectTasksFromImageList` — загрузочные образы, `update-super`,
     * системные. Отсутствие обязательного образа таблицы останавливает всё,
     * как `AddFlashTasks`.
     */
    private fun collect(slot: String, secondary: String): List<FlashAllTask> {
        val info = images.bytes(FASTBOOT_INFO)
        if (info != null) {
            return when (val read = FastbootInfoFile.parse(String(info, Charsets.UTF_8), slot, secondary)) {
                is FastbootInfoRead.Tasks -> read.tasks
                is FastbootInfoRead.Invalid -> throw FlashAllStop(read.reason)
            }
        }
        val chosen = FLASH_ALL_IMAGES.filter { it.type != ImageType.EXTRA && !(it.secondary && skipSecondary) }
            .filter { image -> present(image) }
            .map { image ->
                image.type to FlashAllTask.Flash(
                    partition = image.partition,
                    imageName = image.imageName,
                    slot = if (image.secondary) secondary else slot,
                    applyVbmeta = VBMETA.any { image.partition.endsWith(it) },
                )
            }
        return chosen.filter { it.first == ImageType.BOOT_CRITICAL }.map { it.second } +
            FlashAllTask.UpdateSuper +
            chosen.filter { it.first == ImageType.NORMAL }.map { it.second }
    }

    /** Образ есть; нет обязательного — `could not load`. */
    private fun present(image: FlashAllImage): Boolean {
        val found = images.source(image.imageName) != null
        if (!found && !(image.optional && image.nickname !in preflight.required)) {
            throw FlashAllStop("could not load '${image.imageName}': No such file or directory")
        }
        return found
    }

    /**
     * Набор с `super_empty.img` из загрузчика настольный клиент доводит, уходя
     * посреди работы в fastbootd (`UpdateSuperTask`) и возвращаясь к новому
     * подключению. Этого перехода здесь нет, поэтому — отказ до первой
     * команды, а не на середине.
     */
    private fun gate(tasks: List<FlashAllTask>) {
        // `erase` из `fastboot-info.txt` — это `WipeTask`: он создаёт файловую
        // систему на хосте.
        val wipe = tasks.filterIsInstance<FlashAllTask.Wipe>().firstOrNull()
        if (wipe != null) {
            throw FlashAllStop(
                "erase ${wipe.partition}: formatting on the host (fb_perform_format) is not implemented here",
            )
        }
        val hop = tasks.any { it == FlashAllTask.Reboot(FASTBOOTD) } ||
            FlashAllTask.UpdateSuper in tasks && images.source(SUPER_EMPTY) != null
        if (hop && !rejoin.possible && value(IS_USERSPACE) != YES) {
            throw FlashAllStop(
                "The image set needs fastbootd, and the device is in the bootloader; the device has no serial " +
                    "number, so after it reboots there is no way to know it is the same device. " +
                    "Run 'fastboot reboot fastboot', then flashall again.",
            )
        }
    }

    /**
     * `RebootTask`: в fastbootd уже здесь — ничего; из загрузчика —
     * `reboot_to_userspace_fastboot` (`reboot-fastboot`, новое подключение той
     * же цели, и оно обязано ответить `is-userspace` = `yes`); иначе `reboot`
     * или `reboot-ЦЕЛЬ`.
     */
    private fun reboot(target: String) {
        when (target) {
            FASTBOOTD -> if (value(IS_USERSPACE) != YES) {
                command("$REBOOT-$FASTBOOTD")
                lane = rejoin.await() ?: throw FlashAllStop("The device did not come back after reboot-fastboot.")
                if (value(IS_USERSPACE) != YES) {
                    throw FlashAllStop(
                        "Failed to boot into userspace fastboot; one or more components might be unbootable.",
                    )
                }
            }
            "" -> command(REBOOT)
            else -> command("$REBOOT-$target")
        }
    }

    /** `FlashTask::Run`: логический раздел из загрузчика без `--force` — отказ словами AOSP. */
    private fun flashTask(task: FlashAllTask.Flash) {
        partitions(task.partition, task.slot).forEach { partition ->
            if (superMetadata?.flashesInUserspace(partition) == true && value(IS_USERSPACE) != YES && !request.force) {
                throw FlashAllStop(DYNAMIC_FROM_BOOTLOADER)
            }
            doFlash(partition, task.imageName, task.applyVbmeta)
        }
    }

    /** `do_flash`: подпись, размер логического раздела, образ (vbmeta — с флагами). */
    private fun doFlash(partition: String, imageName: String, applyVbmeta: Boolean) {
        val source = images.source(imageName)
            ?: throw FlashAllStop("could not load '$imageName': No such file or directory")
        images.bytes(imageName.substringBefore('.') + ".sig")?.let { signature ->
            download(ByteArraySource(signature, "signature"))
            command("signature")
        }
        if (value(IS_LOGICAL + partition) == YES) {
            command("resize-logical-partition:$partition:${imageSize(source)}")
        }
        val flags = request.vbmetaFlags
        val image = if (flags != 0 && applyVbmeta) VbmetaFlagsSource(source, flags) else source
        val size = image.identity.sizeBytes ?: throw FlashAllStop("could not load '$imageName': size unknown")
        merge(flashImage(lane, image, size, request.sparseLimit, listOf(FLASH + partition)))
    }

    /** `UpdateSuperTask`: `super_empty.img` в буфер и `update-super:ИМЯ`. */
    private fun updateSuper() {
        val empty = images.source(SUPER_EMPTY) ?: return
        reboot(FASTBOOTD)
        val name = value(SUPER_NAME) ?: "super"
        download(empty)
        command("update-super:$name")
    }

    /** `ResizeTask` с размером `0`: только у раздела, который устройство называет логическим. */
    private fun resizeToZero(partition: String, slot: String) {
        partitions(partition, slot).filter { value(IS_LOGICAL + it) == YES }
            .forEach { command("resize-logical-partition:$it:0") }
    }

    /** `do_for_partitions`: имена раздела под слот. */
    private fun partitions(partition: String, slot: String): List<String> =
        when (val resolved = FastbootSlotTargets.resolve(listOf(FLASH + partition), slot, ::value)) {
            is SlotResolution.Failed -> throw FlashAllStop(resolved.detail)
            is SlotResolution.Resolved -> resolved.commands.map { it.removePrefix(FLASH) }
        }

    /** `Download`: весь объём с `OKAY`, иначе дальше не идём. */
    private fun download(source: ArtifactSource) {
        val size = source.identity.sizeBytes ?: throw FlashAllStop("${source.identity.name}: size unknown")
        commands += downloadStep(size)
        val outcome = source.open().use { input -> FastbootDownload(lane).send(input, size) }
        val accepted = outcome is FastbootDownloadOutcome.Answered &&
            outcome.reply == FastbootReply.OKAY && outcome.bytesSent == size
        if (!accepted) throw FlashAllStop(describeDownload(outcome), commands.size - 1)
        applied++
    }

    /** Команда плана; всё, кроме `OKAY`, останавливает `flashall`. */
    private fun command(command: String) {
        commands += command
        val outcome = FastbootMutation(lane, variables).run(command)
        val done = outcome is FastbootMutationOutcome.Applied ||
            outcome is FastbootMutationOutcome.Departed && command.startsWith(REBOOT)
        if (!done) throw FlashAllStop("$command: ${describe(outcome)}", commands.size - 1)
        applied++
    }

    /** Исход [flashImage] в общий план: с командами у него всегда [FastbootConsoleState.Planned]. */
    private fun merge(state: FastbootConsoleState) {
        val planned = state as FastbootConsoleState.Planned
        val offset = commands.size
        commands += planned.commands
        applied += planned.applied
        planned.stoppedAt?.let { throw FlashAllStop(planned.detail, offset + it) }
    }


    /** Небольшие данные набора — подпись. */
    private class ByteArraySource(private val bytes: ByteArray, name: String) : ArtifactSource {
        override val identity = ArtifactIdentity(name, bytes.size.toLong(), ArtifactAccess.SEEKABLE)
        override val openedStamp = ArtifactStamp(bytes.size.toLong())
        override fun stamp() = openedStamp
        override fun open() = bytes.inputStream()
        override fun randomAccess(): ArtifactRandomAccess? = null
    }

    private companion object {
        const val ALL = "all"
        const val FIRST_SLOT = "a"
        const val YES = "yes"
        const val NONE = "none"
        const val REBOOT = "reboot"
        const val FLASH = "flash:"
        const val IS_LOGICAL = "is-logical:"
        const val IS_USERSPACE = "is-userspace"
        const val SUPER_NAME = "super-partition-name"
        const val SNAPSHOT_STATUS = "snapshot-update-status"
        const val SNAPSHOT_CANCEL = "snapshot-update:cancel"
        const val ANDROID_INFO = "android-info.txt"
        const val FASTBOOT_INFO = "fastboot-info.txt"
        const val FASTBOOTD = "fastboot"
        const val SUPER_EMPTY = "super_empty.img"
        val VBMETA = listOf("vbmeta", "vbmeta_a", "vbmeta_b")
        const val DYNAMIC_FROM_BOOTLOADER = "The partition you are trying to flash is dynamic, and should be " +
            "flashed via fastbootd. Please run:\n\n    fastboot reboot fastboot\n\nAnd try again. If you are " +
            "intentionally trying to overwrite a fixed partition, use --force."
    }
}

/** `buf.image_size`: у sparse — развёрнутый размер из заголовка, у прочих — длина файла. */
private fun imageSize(source: ArtifactSource): Long {
    // `readNBytes` есть только с API 33: заголовок дочитывается сам.
    val header = source.open().use { input ->
        val bytes = ByteArray(SPARSE_HEADER)
        var read = 0
        while (read < SPARSE_HEADER) {
            val chunk = input.read(bytes, read, SPARSE_HEADER - read)
            if (chunk < 0) break
            read += chunk
        }
        bytes.copyOf(read)
    }
    val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
    return if (header.size == SPARSE_HEADER && buffer.getInt(0) == FastbootSparse.MAGIC) {
        (buffer.getInt(BLOCK_SIZE_AT).toLong() and UINT) * (buffer.getInt(TOTAL_BLOCKS_AT).toLong() and UINT)
    } else {
        source.identity.sizeBytes ?: 0L
    }
}

private const val SPARSE_HEADER = 28
private const val BLOCK_SIZE_AT = 12
private const val TOTAL_BLOCKS_AT = 16
private const val UINT = 0xFFFF_FFFFL

/** `die` настольного клиента: дальше ничего не идёт. */
internal class FlashAllStop(val detail: String, val atCommand: Int? = null) : RuntimeException(detail)
