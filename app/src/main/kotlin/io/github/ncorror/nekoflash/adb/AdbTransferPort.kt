package io.github.ncorror.nekoflash.adb

import io.github.ncorror.nekoflash.core.artifact.ArtifactSink
import io.github.ncorror.nekoflash.core.artifact.ArtifactSource
import io.github.ncorror.nekoflash.protocol.adb.AdbSyncEntry
import io.github.ncorror.nekoflash.protocol.adb.AdbSyncOutcome
import io.github.ncorror.nekoflash.protocol.adb.AdbSyncProtocol
import io.github.ncorror.nekoflash.protocol.adb.AdbSyncStat

/**
 * Что перенос каталогов делает с устройством: те же примитивы `sync:`, что у
 * одиночного файла, плюс `mkdir` в оболочке для обхода b/110953234.
 *
 * Отдельный интерфейс — чтобы правила `do_sync_push`/`do_sync_pull` проверялись
 * без устройства.
 */
internal interface AdbTransferPort {
    fun stat(path: String): AdbSyncOutcome<AdbSyncStat>
    fun list(path: String): AdbSyncOutcome<List<AdbSyncEntry>>

    /** Пишет файл; исход — сколько байт принято или почему нет. */
    fun send(path: String, modifiedAtSeconds: Int, source: ArtifactSource): Moved

    /** Читает файл в [sink]; неудачная запись убирает недописанное. */
    fun receive(path: String, sink: ArtifactSink): Moved

    /**
     * Нужен ли `mkdir` через оболочку: у устройства нет `fixed_push_mkdir`, а
     * `shell_v2` есть — как проверяет `copy_local_dir_remote`.
     */
    val needsMkdir: Boolean

    /** `mkdir` каталогов одной командой оболочки; неудача не важна: каталог мог уже быть. */
    fun mkdir(paths: List<String>)
}

/** Исход одного файла. */
internal sealed interface Moved {
    data class Done(val bytes: Long) : Moved
    data class Failed(val detail: String) : Moved
}

/** Чем кончился перенос: как итог настольного клиента, с ошибками и пропусками. */
data class AdbTransferReport(
    val files: Int,
    val bytes: Long,
    val skipped: Int,
    /** Ошибки и предупреждения — строками настольного клиента. */
    val messages: List<String>,
    val success: Boolean,
)

/** Копит итог; [fatal] — как `return false` в AOSP, дальше не идём. */
internal class TransferLedger {
    var files = 0
    var bytes = 0L
    var skipped = 0
    var success = true
    val messages = mutableListOf<String>()

    fun moved(outcome: Moved, path: String): Boolean = when (outcome) {
        is Moved.Done -> true.also { files++; bytes += outcome.bytes }
        is Moved.Failed -> false.also { error("$path: ${outcome.detail}") }
    }

    fun error(message: String) {
        messages += "error: $message"
        success = false
    }

    fun warning(message: String) {
        messages += "warning: $message"
    }

    /** Строка вывода без оценки: `would push: …`. */
    fun note(message: String) {
        messages += message
    }

    fun report(): AdbTransferReport = AdbTransferReport(files, bytes, skipped, messages.toList(), success)
}

/** Что лежит по пути на устройстве — после `sync_stat_fallback`. */
internal sealed interface RemoteKind {
    data object Missing : RemoteKind
    data object Directory : RemoteKind
    data class Other(val stat: AdbSyncStat) : RemoteKind
}

/**
 * `sync_stat_fallback` для устройства без `stat_v2`: `STAT` первой версии —
 * это `lstat`, и символьная ссылка уточняется вопросом о `ПУТЬ/`: ответил —
 * каталог, нет — файл с нулевым размером.
 */
internal fun AdbTransferPort.kindOf(path: String): AdbSyncOutcome<RemoteKind> =
    when (val answer = stat(path)) {
        is AdbSyncOutcome.Failed -> answer
        is AdbSyncOutcome.Done -> AdbSyncOutcome.Done(
            when {
                !answer.value.exists -> RemoteKind.Missing
                answer.value.directory -> RemoteKind.Directory
                answer.value.mode and AdbSyncProtocol.MODE_TYPE_MASK == AdbSyncProtocol.MODE_SYMLINK ->
                    if ((stat("$path/") as? AdbSyncOutcome.Done)?.value?.exists == true) {
                        RemoteKind.Directory
                    } else {
                        RemoteKind.Other(answer.value.copy(mode = AdbSyncProtocol.MODE_REGULAR_FILE, size = 0))
                    }
                else -> RemoteKind.Other(answer.value)
            },
        )
    }

/** `android::base::Basename`: хвостовые `/` не считаются. */
internal fun basename(path: String): String = path.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }

/** `android::base::Dirname` для путей устройства. */
internal fun dirname(path: String): String {
    val trimmed = path.trimEnd('/')
    return when {
        trimmed.isEmpty() -> "/"
        '/' !in trimmed -> "."
        else -> trimmed.substringBeforeLast('/').trimEnd('/').ifEmpty { "/" }
    }
}

internal fun withSlash(path: String): String = if (path.endsWith('/')) path else "$path/"
