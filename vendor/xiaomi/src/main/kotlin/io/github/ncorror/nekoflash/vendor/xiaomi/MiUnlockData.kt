package io.github.ncorror.nekoflash.vendor.xiaomi

/** Decodes the bounded hex payload supplied by the Xiaomi unlock API. */
public object MiUnlockData {
    public fun decode(hex: String): ByteArray? {
        if (hex.length !in CHARS_PER_BYTE..MAX_HEX_CHARS || hex.length % CHARS_PER_BYTE != 0) return null
        val bytes = ByteArray(hex.length / CHARS_PER_BYTE)
        for (index in bytes.indices) {
            val high = Character.digit(hex[index * CHARS_PER_BYTE], RADIX)
            val low = Character.digit(hex[index * CHARS_PER_BYTE + 1], RADIX)
            if (high < 0 || low < 0) return null
            bytes[index] = ((high shl NIBBLE_BITS) or low).toByte()
        }
        return bytes
    }

    private const val CHARS_PER_BYTE = 2
    private const val RADIX = 16
    private const val NIBBLE_BITS = 4
    private const val MAX_HEX_CHARS = 16 * 1024 * 1024
}
