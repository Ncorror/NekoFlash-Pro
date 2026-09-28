package io.github.ncorror.nekoflash.artifact

import io.github.ncorror.nekoflash.core.artifact.ArtifactSink
import io.github.ncorror.nekoflash.core.artifact.ArtifactSource

/**
 * Папка, выбранная через Android, — то, чем для настольного `adb` служит
 * каталог на диске хоста.
 *
 * Путь из команды здесь не открывается (`04` §3, правило 7): содержимое даёт
 * папка, выбранная в системном диалоге, а имена на устройстве строятся из
 * команды, как у настольного клиента.
 */
interface LocalFolder {
    /** Как папку назвать в сообщении оператору. */
    val name: String

    /** Что лежит в папке сейчас. */
    fun entries(): List<LocalEntry>

    /** Вложенная папка [name]: существующая или новая. */
    fun folder(name: String): LocalFolder

    /** Файл [name] на запись: существующий переписывается, как у настольного клиента. */
    fun file(name: String): ArtifactSink

    /**
     * `set_time_and_mode` настольного клиента для файла [name]: время
     * изменения [modifiedAtSeconds] и режим [mode] с учётом `umask`.
     * `null` — выставлено и проверено; иначе — почему нет.
     */
    fun keepTimeAndMode(name: String, modifiedAtSeconds: Long, mode: Int): String?
}

/** Элемент папки. */
sealed interface LocalEntry {
    val name: String

    data class Folder(override val name: String, val folder: LocalFolder) : LocalEntry

    data class File(
        override val name: String,
        val sizeBytes: Long,
        /** Время изменения, если провайдер его знает. */
        val modifiedAtMillis: Long?,
        val open: () -> ArtifactSource,
    ) : LocalEntry
}
