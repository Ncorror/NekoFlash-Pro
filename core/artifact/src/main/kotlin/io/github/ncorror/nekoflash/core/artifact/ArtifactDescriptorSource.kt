package io.github.ncorror.nekoflash.core.artifact

import java.io.Closeable

/**
 * Источник, который умеет отдать дескриптор обычного файла ровно с теми байтами,
 * что вернул бы [ArtifactSource.open], — для нативного Fastboot DATA OUT (D077).
 *
 * Отдельный интерфейс, а не метод [ArtifactSource], намеренно: обёртка, которая
 * меняет байты по пути (флаги vbmeta, куски sparse), делегирует источник через
 * `by` и унаследовала бы метод, отдав исходный файл вместо правленого. Этот
 * интерфейс она не реализует, пока не объявит его сама.
 */
interface ArtifactDescriptorSource {
    /** Дескриптор на чтение с начала файла; закрывает вызывающий. `null` — не вышло. */
    fun openReadDescriptor(): ArtifactDescriptor?
}

/** Открытый дескриптор файла; [fd] действителен до [close]. */
interface ArtifactDescriptor : Closeable {
    val fd: Int
}
