package io.github.ncorror.nekoflash.protocol.adb

/**
 * Контрольная сумма payload из протокола ADB.
 *
 * Перенесена из A2 (`adb/codec/AdbChecksum.kt`), куда она в свою очередь была
 * вынесена из Legacy `AdbProtocol.kt`. Правило простое и не подлежит
 * улучшению: сумма байтов как беззнаковых значений.
 *
 * Проверка обязательна, пока **хотя бы одна** сторона говорит на версии до
 * [VERSION_SKIP_CHECKSUM]. Начиная с неё поле заголовка перестаёт быть
 * контрольной суммой, и сверять его с пересчитанным значением нельзя: у
 * современного peer там законно лежит что угодно, включая ноль.
 */
public object AdbChecksum {
    /** Версия протокола, в которой контрольная сумма ещё считается. */
    public const val VERSION_WITH_CHECKSUM: Int = 0x01000000

    /** Версия протокола, в которой обе стороны вправе не считать сумму. */
    public const val VERSION_SKIP_CHECKSUM: Int = 0x01000001

    /** Сумма байтов payload как беззнаковых значений. */
    public fun compute(payload: ByteArray): Int {
        var checksum = 0
        for (byte in payload) {
            checksum += byte.toInt() and 0xFF
        }
        return checksum
    }

    /**
     * Нужна ли проверка при согласованных версиях.
     *
     * Берётся минимум из двух: договорённость определяется более старой
     * стороной, а не собственным желанием хоста.
     */
    public fun isRequired(localVersion: Int, peerVersion: Int): Boolean =
        minOf(localVersion, peerVersion) < VERSION_SKIP_CHECKSUM

    /** Совпадает ли объявленное значение с содержимым payload. */
    public fun matches(
        expected: Int,
        payload: ByteArray,
        localVersion: Int,
        peerVersion: Int,
    ): Boolean = !isRequired(localVersion, peerVersion) || expected == compute(payload)
}
