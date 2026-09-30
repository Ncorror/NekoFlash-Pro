package io.github.ncorror.nekoflash.protocol.adb

/** Одна разобранная рамка `shell,v2`. */
public data class AdbShellFrame(val id: Int, val payload: ByteArray) {
    override fun toString(): String = "AdbShellFrame(id=$id, payload=${payload.size} bytes)"
}

/** Что удалось достать из накопителя. */
public sealed interface AdbShellFramePoll {
    /** Рамка собрана целиком. */
    public data class Ready(val frame: AdbShellFrame) : AdbShellFramePoll

    /** Байт пока не хватает: нужно принять ещё. */
    public data object Incomplete : AdbShellFramePoll

    /**
     * Заголовок непригоден.
     *
     * Дальше разбирать нечего: положение в потоке рамок потеряно так же, как
     * при потере ADB-кадра. Поток закрывается.
     */
    public data class Corrupt(val detail: String) : AdbShellFramePoll
}

/**
 * Накопитель рамок `shell,v2` для живого потока.
 *
 * Одноразовой команде он не нужен: там вывод собирается целиком и разбирается
 * один раз. Интерактивной оболочке нужен, потому что границы не совпадают ни с
 * чем: одна рамка может прийти несколькими пакетами `WRTE`, а один `WRTE` —
 * принести несколько рамок и половину следующей. Так же накапливает Legacy
 * (`stream.pending` и `consumeInteractiveShellV2Packets`).
 *
 * Экземпляр принадлежит одному потоку исполнения — тому, который читает.
 */
public class AdbShellFrameBuffer(private val maxFrameBytes: Int = MAX_FRAME_BYTES) {
    private val pending = ArrayDeque<Byte>()

    /** Сколько байт лежит неразобранными. */
    public val pendingBytes: Int
        get() = pending.size

    /** Добавляет принятое. */
    public fun append(bytes: ByteArray) {
        for (byte in bytes) pending.addLast(byte)
    }

    /**
     * Достаёт следующую целую рамку.
     *
     * Заголовок читается без изъятия: пока payload не пришёл целиком, байты
     * должны остаться на месте, иначе повторный вызов разберёт мусор.
     */
    public fun poll(): AdbShellFramePoll {
        if (pending.size < AdbShellProtocol.HEADER_SIZE_BYTES) return AdbShellFramePoll.Incomplete

        val id = pending.elementAt(0).toInt() and 0xFF
        val length = (pending.elementAt(1).toInt() and 0xFF) or
            ((pending.elementAt(2).toInt() and 0xFF) shl 8) or
            ((pending.elementAt(3).toInt() and 0xFF) shl 16) or
            ((pending.elementAt(4).toInt() and 0xFF) shl 24)

        return when {
            length < 0 || length > maxFrameBytes ->
                AdbShellFramePoll.Corrupt("id=$id length=$length")

            pending.size < AdbShellProtocol.HEADER_SIZE_BYTES + length ->
                AdbShellFramePoll.Incomplete

            else -> {
                repeat(AdbShellProtocol.HEADER_SIZE_BYTES) { pending.removeFirst() }
                val payload = ByteArray(length) { pending.removeFirst() }
                AdbShellFramePoll.Ready(AdbShellFrame(id, payload))
            }
        }
    }

    private companion object {
        const val MAX_FRAME_BYTES = 1_048_576
    }
}
