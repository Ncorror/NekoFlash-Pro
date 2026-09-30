package io.github.ncorror.nekoflash.protocol.adb

/**
 * Накопитель байт логического потока.
 *
 * Сервис `sync:` читает точными порциями: восемь байт заголовка, затем ровно
 * столько, сколько заголовок назвал. Границы пакетов `WRTE` к этим порциям не
 * имеют никакого отношения — заголовок может прийти двумя пакетами, а один
 * пакет принести заголовок и половину данных.
 *
 * От [AdbShellFrameBuffer] отличается тем, что не знает формата: там разбор
 * рамок `shell,v2`, здесь просто очередь байт, из которой берут по счёту.
 * Общего кода у них меньше, чем кажется, и объединять их значило бы сделать
 * один класс, знающий два протокола.
 *
 * Экземпляр принадлежит одному потоку исполнения — тому, который читает.
 */
public class AdbStreamBuffer {
    private val pending = ArrayDeque<Byte>()

    /** Сколько байт лежит непрочитанными. */
    public val available: Int
        get() = pending.size

    /** Добавляет принятое. */
    public fun append(bytes: ByteArray) {
        for (byte in bytes) pending.addLast(byte)
    }

    /**
     * Забирает ровно [count] байт.
     *
     * `null` означает, что их пока меньше; в этом случае не забирается ничего.
     * Частичная выдача разрушила бы разбор: следующий вызов принял бы середину
     * порции за её начало.
     */
    public fun take(count: Int): ByteArray? {
        require(count >= 0) { "Byte count must not be negative: $count" }
        if (pending.size < count) return null
        return ByteArray(count) { pending.removeFirst() }
    }
}
