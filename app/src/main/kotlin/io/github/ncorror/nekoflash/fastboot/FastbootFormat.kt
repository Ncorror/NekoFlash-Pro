package io.github.ncorror.nekoflash.fastboot

import io.github.ncorror.nekoflash.core.artifact.ArtifactAccess
import io.github.ncorror.nekoflash.core.artifact.ArtifactIdentity
import io.github.ncorror.nekoflash.core.artifact.ArtifactRandomAccess
import io.github.ncorror.nekoflash.core.artifact.ArtifactSource
import io.github.ncorror.nekoflash.core.artifact.ArtifactStamp
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootExt4
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootF2fs
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootGetVar
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootLane
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootMutation
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootMutationOutcome
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootVariable
import java.io.Closeable
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.SecureRandom

/** Что `fb_perform_format` берёт из командной строки и откуда у него UUID и время. */
internal data class FormatOptions(
    val sparseLimit: Long? = null,
    /** `--fs-options`: `casefold`, `projid`, `compress`. */
    val fsOptions: Set<String> = emptySet(),
    /** Каталог приложения для сгенерированного sparse-образа; production передаёт cacheDir. */
    val stagingDirectory: File? = null,
    val random: (Int) -> ByteArray = { size -> ByteArray(size).also(SecureRandom()::nextBytes) },
    val now: () -> Long = { System.currentTimeMillis() / MILLIS },
    /** `uname -r` машины, где собирается f2fs: у телефона — его ядро. */
    val kernelRelease: () -> String = { System.getProperty("os.version").orEmpty() },
) {
    private companion object {
        const val MILLIS = 1000L
    }
}

/**
 * `fb_perform_format` и `WipeTask` из AOSP `fastboot/fastboot.cpp` и
 * `task.cpp`. Тип и размер раздела спрашиваются у устройства
 * (`partition-type:`, `partition-size:`), файловая система собирается на
 * хосте ([FastbootExt4]) и прошивается тем же [flashImage], что у `flash`.
 *
 * `format` останавливается на первой ошибке, как `die`; очистка (`-w`,
 * `erase` из `fastboot-info.txt`) пропускает раздел, который устройство не
 * знает или не стёрло, и формат, которого нет, — как настольный клиент.
 */
internal class FastbootFormat(private val lane: FastbootLane, private val options: FormatOptions) {
    private val commands = mutableListOf<String>()
    private val notes = mutableListOf<String>()
    private var applied = 0

    /** `fastboot format[:ТИП[:РАЗМЕР]] РАЗДЕЛ` для каждого имени после `do_for_partitions`. */
    fun format(partitions: List<String>, type: String?, size: String?): FastbootConsoleState.Planned =
        collect { partitions.forEach { perform(it, type, size, skipIfNotSupported = false) } }

    /**
     * `WipeTask` для каждого раздела: тип, `erase`, новая файловая система.
     * [force] — сначала `CancelSnapshotIfNeeded`; [then] — отложенные команды
     * той же строки (`reboot`), которые у настольного клиента идут следом.
     */
    fun wipe(
        partitions: List<String>,
        force: Boolean = false,
        then: List<String> = emptyList(),
    ): FastbootConsoleState.Planned = collect {
        if (force) cancelSnapshotIfNeeded()
        partitions.forEach(::wipeOne)
        then.forEach(::mutate)
    }

    /** `CancelSnapshotIfNeeded`: незаконченное обновление снимками отменяется. */
    private fun cancelSnapshotIfNeeded() {
        val status = (FastbootGetVar(lane).read(SNAPSHOT_STATUS) as? FastbootVariable.Present)?.value
        if (!status.isNullOrEmpty() && status != NONE) mutate(SNAPSHOT_CANCEL)
    }

    /** Команда, которой отказ останавливает всё, как `die`; `reboot` принят, когда устройство ушло. */
    private fun mutate(command: String) {
        commands += command
        val outcome = FastbootMutation(lane, FastbootGetVar(lane)).run(command)
        val done = outcome is FastbootMutationOutcome.Applied ||
            outcome is FastbootMutationOutcome.Departed && command.startsWith(REBOOT)
        if (!done) throw FlashAllStop("$command: ${describe(outcome)}", commands.size - 1)
        applied++
    }

    private fun collect(body: () -> Unit): FastbootConsoleState.Planned {
        val stopped = try {
            body()
            null
        } catch (stop: FlashAllStop) {
            notes += stop.detail
            stop.atCommand ?: commands.size
        }
        return FastbootConsoleState.Planned(commands.toList(), applied, stopped, notes.joinToString("\n"), lane.state)
    }

    private fun wipeOne(partition: String) {
        val type = (FastbootGetVar(lane).read(PARTITION_TYPE + partition) as? FastbootVariable.Present)?.value
        when {
            type == null -> notes += "wipe task partition not found: $partition"
            type.isEmpty() -> Unit
            erased(partition) -> perform(partition, type, null, skipIfNotSupported = true)
            else -> notes += "wipe task erase failed with partition: $partition"
        }
    }

    private fun erased(partition: String): Boolean {
        commands += ERASE + partition
        val outcome = FastbootMutation(lane, FastbootGetVar(lane)).run(ERASE + partition)
        val done = outcome is FastbootMutationOutcome.Applied
        if (done) applied++
        return done
    }

    /** `fb_perform_format`: при [skipIfNotSupported] неудача до сборки — заметка, а не остановка. */
    private fun perform(partition: String, typeOverride: String?, sizeOverride: String?, skipIfNotSupported: Boolean) {
        try {
            val type = override(
                partition,
                "type",
                value(PARTITION_TYPE + partition, "Can't determine partition type.", skipIfNotSupported),
                typeOverride,
            )
            val size = override(
                partition,
                "size",
                value(PARTITION_SIZE + partition, "Unable to get partition size", skipIfNotSupported),
                sizeOverride,
            )
            when (type) {
                EXT4 -> flash(partition, ext4(partition, size))
                F2FS -> flash(partition, f2fs(partition, size))
                else -> unsupported(type, skipIfNotSupported)
            }
        } catch (_: Skipped) {
            // Раздел пропущен; причина уже в заметках.
        }
    }

    private fun value(name: String, message: String, skip: Boolean): String {
        val read = FastbootGetVar(lane).read(name)
        return (read as? FastbootVariable.Present)?.value ?: failed(read, message, skip)
    }

    /** Замена из командной строки — с предупреждением, если устройство сказало другое. */
    private fun override(partition: String, what: String, device: String, requested: String?): String {
        if (requested.isNullOrEmpty()) return device
        if (device != requested) {
            notes += "Warning: $partition $what is $device, but $requested was requested for formatting."
        }
        return requested
    }

    /** `generate_ext4_image` для раздела [partition] размером [size] (как назвало устройство). */
    private fun ext4(partition: String, size: String): TemporaryImageSource {
        val bytes = deviceNumber(size) ?: throw FlashAllStop("Couldn't parse partition size '${fixNumeric(size)}'.")
        val request = FastbootExt4.Request(
            sizeBytes = bytes,
            eraseBlockSize = blockSize(ERASE_BLOCK_SIZE),
            logicalBlockSize = blockSize(LOGICAL_BLOCK_SIZE),
            projectIds = PROJID in options.fsOptions,
            casefold = CASEFOLD in options.fsOptions,
            uuid = options.random(UUID_BYTES),
            hashSeed = options.random(UUID_BYTES),
            now = options.now(),
        )
        return generated(partition) { output -> FastbootExt4.write(request, output) }
    }

    /**
     * `generate_f2fs_image` для раздела [partition]: `make_f2fs -S РАЗМЕР -g
     * android` и ключи из `--fs-options`. Версия чекпоинта — `rand() | 1`:
     * случайное число до `RAND_MAX` (2³¹ − 1), нечётное.
     */
    private fun f2fs(partition: String, size: String): TemporaryImageSource {
        val bytes = deviceNumber(size) ?: throw FlashAllStop("Couldn't parse partition size '${fixNumeric(size)}'.")
        val rand = java.nio.ByteBuffer.wrap(options.random(Int.SIZE_BYTES)).int and Int.MAX_VALUE or 1
        val request = FastbootF2fs.Request(
            sizeBytes = bytes,
            projectIds = PROJID in options.fsOptions,
            casefold = CASEFOLD in options.fsOptions,
            compression = COMPRESS in options.fsOptions,
            uuid = options.random(UUID_BYTES),
            now = options.now(),
            checkpointVersion = rand.toLong(),
            kernelRelease = options.kernelRelease(),
        )
        return generated(partition) { output -> FastbootF2fs.write(request, output) }
    }

    private fun generated(partition: String, write: (OutputStream) -> Result<Long>): TemporaryImageSource {
        val directory = options.stagingDirectory ?: File(System.getProperty("java.io.tmpdir") ?: ".")
        val file = runCatching {
            directory.mkdirs()
            File.createTempFile("nekoflash-format-", ".img", directory)
        }.getOrElse { failure ->
            val detail = failure.message ?: failure.javaClass.simpleName
            throw FlashAllStop("Cannot create temporary image for $partition: $detail")
        }
        return runCatching {
            file.outputStream().buffered().use { output -> write(output).getOrThrow() }
            TemporaryImageSource(file, "$partition.img")
        }.getOrElse { failure ->
            file.delete()
            val detail = failure.message ?: failure.javaClass.simpleName
            throw FlashAllStop("Cannot generate image for $partition: $detail")
        }
    }

    /** `flash_buf`: тот же путь, что у `fastboot flash`, но источник уже файловый и потоковый. */
    private fun flash(partition: String, image: TemporaryImageSource) {
        image.use { source ->
            val size = source.identity.sizeBytes ?: throw FlashAllStop("Generated image size is unknown.")
            val flashed = flashImage(
                lane,
                source,
                size,
                options.sparseLimit,
                listOf(FLASH + partition),
            ) as FastbootConsoleState.Planned
            val offset = commands.size
            commands += flashed.commands
            applied += flashed.applied
            flashed.stoppedAt?.let { throw FlashAllStop(flashed.detail, offset + it) }
        }
    }

    /** Пропуск раздела при очистке: причина уже записана. */
    private class Skipped : RuntimeException()

    /** Ветка `failed:`: `FAILED (ответ устройства)`; при очистке раздел пропускается. */
    private fun failed(read: FastbootVariable, message: String, skip: Boolean): Nothing {
        val answer = when (read) {
            is FastbootVariable.Unsupported -> read.detail
            is FastbootVariable.Unavailable -> read.detail
            is FastbootVariable.Present -> read.value
        }
        if (!skip) throw FlashAllStop("FAILED ($answer)\nCommand failed")
        notes += "Erase successful, but not automatically formatting.\n$message\nFAILED ($answer)"
        throw Skipped()
    }

    /** Генератора нет — `fs_get_generator` знает только ext4 и f2fs. */
    private fun unsupported(type: String, skip: Boolean) {
        if (!skip) throw FlashAllStop("Formatting is not supported for file system with type '$type'.")
        notes += "Erase successful, but not automatically formatting.\nFile system type $type not supported."
    }

    /** `fb_get_flash_block_size`: не назван, не число или не степень двойки — 0. */
    private fun blockSize(name: String): Long {
        val raw = (FastbootGetVar(lane).read(name) as? FastbootVariable.Present)?.value?.takeIf { it.isNotEmpty() }
            ?: return 0
        val size = deviceNumber(raw)?.takeIf { it in 0..UINT }
            ?: return 0L.also { notes += "Couldn't parse $name '${fixNumeric(raw)}'." }
        if (size and (size - 1) != 0L) return 0L.also { notes += "Invalid $name $size: must be a power of 2." }
        return size
    }

    private companion object {
        const val SNAPSHOT_STATUS = "snapshot-update-status"
        const val SNAPSHOT_CANCEL = "snapshot-update:cancel"
        const val NONE = "none"
        const val REBOOT = "reboot"
        const val PARTITION_TYPE = "partition-type:"
        const val PARTITION_SIZE = "partition-size:"
        const val ERASE_BLOCK_SIZE = "erase-block-size"
        const val LOGICAL_BLOCK_SIZE = "logical-block-size"
        const val ERASE = "erase:"
        const val FLASH = "flash:"
        const val EXT4 = "ext4"
        const val F2FS = "f2fs"
        const val PROJID = "projid"
        const val CASEFOLD = "casefold"
        const val COMPRESS = "compress"
        const val UUID_BYTES = 16
        const val HEX = 16
        const val UINT = 0xFFFF_FFFFL

        /** `fb_fix_numeric_var`: пробелы по краям прочь, без `0x` — всё равно шестнадцатеричное. */
        fun fixNumeric(raw: String): String = raw.trim().let { if (it.startsWith("0x")) it else "0x$it" }

        fun deviceNumber(raw: String): Long? = fixNumeric(raw).removePrefix("0x").toLongOrNull(HEX)?.takeIf { it >= 0 }
    }
}

/** Разделы, которые чистит `-w`: `WipeTask` настольного клиента. */
internal val WIPE_PARTITIONS = listOf("userdata", "cache", "metadata")

/**
 * `fastboot format`: имена раздела под слот, как `do_for_partitions`, затем
 * [FastbootFormat.format]. Слот не уточнился — отказ до первой команды.
 */
internal fun formatPartition(
    lane: FastbootLane,
    partition: String,
    type: String?,
    size: String?,
    slot: String,
    options: FormatOptions,
): FastbootConsoleState = when (val resolved = slotCommands(listOf(FLASH_PREFIX + partition), slot, lane)) {
    is SlotResolution.Failed -> FastbootConsoleState.Planned(emptyList(), 0, 0, resolved.detail, lane.state)
    is SlotResolution.Resolved ->
        FastbootFormat(lane, options).format(resolved.commands.map { it.removePrefix(FLASH_PREFIX) }, type, size)
}

private const val FLASH_PREFIX = "flash:"

/** Сгенерированный filesystem sparse: живёт только до конца своей операции. */
private class TemporaryImageSource(
    private val file: File,
    name: String,
) : ArtifactSource, Closeable {
    override val identity = ArtifactIdentity(name, file.length(), ArtifactAccess.SEEKABLE)
    override val openedStamp = stamp()

    override fun stamp(): ArtifactStamp = ArtifactStamp(
        sizeBytes = file.length(),
        modifiedAtMillis = file.lastModified(),
    )

    override fun open() = file.inputStream()

    override fun randomAccess(): ArtifactRandomAccess = ArtifactRandomAccess { offset, length ->
        ByteArray(length).also { bytes ->
            RandomAccessFile(file, "r").use { input ->
                input.seek(offset)
                input.readFully(bytes)
            }
        }
    }

    override fun close() {
        file.delete()
    }
}

/** Небольшой образ, собранный в памяти: служебная подпись или иной компактный payload. */
internal class BytesSource(private val bytes: ByteArray, name: String) : ArtifactSource {
    override val identity = ArtifactIdentity(name, bytes.size.toLong(), ArtifactAccess.SEEKABLE)
    override val openedStamp = ArtifactStamp(bytes.size.toLong())
    override fun stamp() = openedStamp
    override fun open() = bytes.inputStream()
    override fun randomAccess(): ArtifactRandomAccess? = null
}
