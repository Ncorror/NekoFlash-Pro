package io.github.ncorror.nekoflash.protocol.adb

import java.security.MessageDigest

/**
 * Таблицы Brotli (RFC 7932) в том виде, в каком их держит `libbrotli` 1.1.0:
 * длины вставки и копирования (`kCmdLut`), длины блоков
 * (`_kBrotliPrefixCodeRanges`), контексты литералов
 * (`_kBrotliContextLookupTable`), преобразования слов словаря (`transform.c`).
 */
internal object BrotliTables {
    val INSERT_BASE = intArrayOf(
        0, 1, 2, 3, 4, 5, 6, 8, 10, 14, 18, 26, 34, 50, 66, 98, 130, 194, 322, 578, 1090, 2114, 6210, 22594,
    )
    val INSERT_EXTRA = intArrayOf(0, 0, 0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 7, 8, 9, 10, 12, 14, 24)
    val COPY_BASE = intArrayOf(
        2, 3, 4, 5, 6, 7, 8, 9, 10, 12, 14, 18, 22, 30, 38, 54, 70, 102, 134, 198, 326, 582, 1094, 2118,
    )
    val COPY_EXTRA = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 7, 8, 9, 10, 24)

    /** Клетки кода вставки-копирования: базовые коды вставки и копирования по `code >> 6`. */
    val CELL_INSERT = intArrayOf(0, 0, 0, 0, 8, 8, 0, 16, 8, 16, 16)
    val CELL_COPY = intArrayOf(0, 8, 0, 8, 0, 8, 16, 0, 16, 8, 16)

    val BLOCK_LENGTH_BASE = intArrayOf(
        1, 5, 9, 13, 17, 25, 33, 41, 49, 65, 81, 97, 113, 145, 177, 209, 241, 305, 369, 497, 753, 1265, 2289,
        4337, 8433, 16625,
    )
    val BLOCK_LENGTH_EXTRA = intArrayOf(
        2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 6, 6, 7, 8, 9, 10, 11, 12, 13, 24,
    )

    /** Порядок длин кода длин кодов (`kCodeLengthCodeOrder`). */
    val CODE_LENGTH_ORDER = intArrayOf(1, 2, 3, 4, 0, 5, 17, 6, 16, 7, 8, 9, 10, 11, 12, 13, 14, 15)
    val CODE_LENGTH_PREFIX_LENGTH = intArrayOf(2, 2, 2, 3, 2, 2, 2, 4, 2, 2, 2, 3, 2, 2, 2, 4)
    val CODE_LENGTH_PREFIX_VALUE = intArrayOf(0, 4, 3, 2, 0, 4, 3, 1, 0, 4, 3, 2, 0, 4, 3, 5)

    /**
     * Контекст литерала: `lut[p1] | lut[256 + p2]` для режимов LSB6, MSB6,
     * UTF8 и SIGNED — по 512 значений на режим.
     */
    val CONTEXT_LOOKUP: IntArray = IntArray(CONTEXT_MODES * 512).also { lut ->
        val utf8Ascii = "000000000110010000000000000000002343353467338393bbbbbbbbbb886a73" +
        "3cdddcdddcdddddcdddddcddddd637333efffefffefffffefffffefffff63730"
        val utf8Previous = intArrayOf(0, 33, 1, 15, 2, 10, 1, 7, 2, 26, 1, 6, 3, 26, 1, 4, 0, 97, 2, 32)
        val signed = intArrayOf(0, 1, 1, 15, 2, 48, 3, 64, 4, 64, 5, 48, 6, 15, 7, 1)
        for (b in 0 until 256) {
            lut[b] = b and 0x3F
            lut[512 + b] = b ushr 2
            lut[1024 + b] = when {
                b < 128 -> Character.digit(utf8Ascii[b], 16) shl 2
                b < 192 -> b and 1
                else -> 2 + (b and 1)
            }
        }
        runs(utf8Previous) { at, value -> lut[1024 + 256 + at] = value }
        runs(signed) { at, value ->
            lut[1536 + at] = value shl 3
            lut[1536 + 256 + at] = value
        }
    }

    /** Приставки и окончания преобразований: байт длины, затем байты (`kPrefixSuffix`). */
    val PREFIX_SUFFIX: ByteArray = (
        "\u0001 \u0002, \u0008 of the \u0004 of \u0002s \u0001.\u0005 and \u0004 in \u0001\u0022\u0004" +
        " to \u0002\u0022>\u0001\u000a\u0002. \u0001]\u0005 for \u0003 a \u0006 that \u0001'\u0006" +
        " with \u0006 from \u0004 by \u0001(\u0006. The \u0004 on \u0004 as \u0004 is \u0004ing \u0002" +
        "\u000a\u0009\u0001:\u0003ed \u0002=\u0022\u0004 at \u0003ly \u0001,\u0002='\u0005.com/\u0007" +
        ". This \u0005 not \u0003er \u0003al \u0004ful \u0004ive \u0005less \u0004est \u0004ize \u0002" +
        "\u00c2\u00a0\u0004ous \u0005 the \u0002e \u0000"
        ).toByteArray(Charsets.ISO_8859_1)
    val PREFIX_SUFFIX_MAP = intArrayOf(
        0, 2, 5, 14, 19, 22, 24, 30, 35, 37, 42, 45, 47, 50, 52, 58, 62, 69, 71, 78, 85, 90, 92, 99, 104,
        109, 114, 119, 122, 124, 128, 131, 136, 140, 142, 145, 151, 159, 165, 169, 173, 178, 183, 189, 194,
        199, 202, 207, 213, 216,
    )

    /** Тройки преобразований 0..120: приставка, тип, окончание (`kTransformsData`). */
    val TRANSFORMS = intArrayOf(
        49, 0, 49, 49, 0, 0, 0, 0, 0, 49, 12, 49, 49, 10, 0, 49, 0, 47, 0, 0, 49, 4, 0, 0,
        49, 0, 3, 49, 10, 49, 49, 0, 6, 49, 13, 49, 49, 1, 49, 1, 0, 0, 49, 0, 1, 0, 10, 0,
        49, 0, 7, 49, 0, 9, 48, 0, 0, 49, 0, 8, 49, 0, 5, 49, 0, 10, 49, 0, 11, 49, 3, 49,
        49, 0, 13, 49, 0, 14, 49, 14, 49, 49, 2, 49, 49, 0, 15, 49, 0, 16, 0, 10, 49, 49, 0, 12,
        5, 0, 49, 0, 0, 1, 49, 15, 49, 49, 0, 18, 49, 0, 17, 49, 0, 19, 49, 0, 20, 49, 16, 49,
        49, 17, 49, 47, 0, 49, 49, 4, 49, 49, 0, 22, 49, 11, 49, 49, 0, 23, 49, 0, 24, 49, 0, 25,
        49, 7, 49, 49, 1, 26, 49, 0, 27, 49, 0, 28, 0, 0, 12, 49, 0, 29, 49, 20, 49, 49, 18, 49,
        49, 6, 49, 49, 0, 21, 49, 10, 1, 49, 8, 49, 49, 0, 31, 49, 0, 32, 47, 0, 3, 49, 5, 49,
        49, 9, 49, 0, 10, 1, 49, 10, 8, 5, 0, 21, 49, 11, 0, 49, 10, 10, 49, 0, 30, 0, 0, 5,
        35, 0, 49, 47, 0, 2, 49, 10, 17, 49, 0, 36, 49, 0, 33, 5, 0, 0, 49, 10, 21, 49, 10, 5,
        49, 0, 37, 0, 0, 30, 49, 0, 38, 0, 11, 0, 49, 0, 39, 0, 11, 49, 49, 0, 34, 49, 11, 8,
        49, 10, 12, 0, 0, 21, 49, 0, 40, 0, 10, 12, 49, 0, 41, 49, 0, 42, 49, 11, 17, 49, 0, 43,
        0, 10, 5, 49, 11, 10, 0, 0, 34, 49, 10, 33, 49, 0, 44, 49, 11, 5, 45, 0, 49, 0, 0, 33,
        49, 10, 30, 49, 11, 30, 49, 0, 46, 49, 11, 1, 49, 10, 34, 0, 10, 33, 0, 11, 30, 0, 11, 1,
        49, 11, 33, 49, 11, 21, 49, 11, 12, 0, 11, 5, 49, 11, 34, 0, 11, 12, 0, 10, 30, 0, 11, 34,
        0, 10, 34,
    )

    const val TRANSFORM_COUNT = 121
    const val OMIT_LAST_9 = 9
    const val UPPERCASE_FIRST = 10
    const val UPPERCASE_ALL = 11
    const val OMIT_FIRST_1 = 12
    const val OMIT_FIRST_9 = 20
    private const val CONTEXT_MODES = 4

    private inline fun runs(pairs: IntArray, put: (Int, Int) -> Unit) {
        var at = 0
        for (i in pairs.indices step 2) {
            repeat(pairs[i + 1]) { put(at++, pairs[i]) }
        }
    }
}

/**
 * Встроенный словарь RFC 7932, приложение A: 122 784 байта, слова длиной
 * 4..24. Лежит ресурсом и сверяется с SHA-256 из RFC при первом обращении.
 */
internal object BrotliDictionary {
    val SIZE_BITS = intArrayOf(0, 0, 0, 0, 10, 10, 11, 11, 10, 10, 10, 10, 10, 9, 9, 8, 7, 7, 8, 7, 7, 6, 6, 5, 5)
    val OFFSETS = IntArray(SIZE_BITS.size).also { offsets ->
        for (length in 1 until SIZE_BITS.size) {
            val bits = SIZE_BITS[length - 1]
            offsets[length] = offsets[length - 1] + if (bits == 0) 0 else (length - 1) shl bits
        }
    }
    const val MIN_WORD = 4
    const val MAX_WORD = 24

    val data: ByteArray by lazy {
        val bytes = BrotliDictionary::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes() }
            ?: throw SyncCodecException("Brotli dictionary resource missing")
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        corruptIf(digest != SHA256) { "Brotli dictionary resource damaged" }
        bytes
    }

    /**
     * `BrotliTransformDictionaryWord`: слово длины [length] номер [word] под
     * преобразованием [transform].
     */
    fun word(length: Int, word: Int, transform: Int): ByteArray {
        val t = BrotliTables.TRANSFORMS
        val prefix = BrotliTables.PREFIX_SUFFIX_MAP[t[transform * 3]]
        val type = t[transform * 3 + 1]
        val suffix = BrotliTables.PREFIX_SUFFIX_MAP[t[transform * 3 + 2]]
        val table = BrotliTables.PREFIX_SUFFIX
        val out = ByteArray(table[prefix] + length + table[suffix] + UTF8_SLACK)
        var used = 0
        repeat(table[prefix].toInt()) { out[used++] = table[prefix + 1 + it] }
        var from = OFFSETS[length] + word * length
        var body = length
        if (type <= BrotliTables.OMIT_LAST_9) {
            body -= type
        } else if (type in BrotliTables.OMIT_FIRST_1..BrotliTables.OMIT_FIRST_9) {
            val skip = type - (BrotliTables.OMIT_FIRST_1 - 1)
            from += skip
            body -= skip
        }
        val start = used
        repeat(maxOf(body, 0)) { out[used++] = data[from + it] }
        if (type == BrotliTables.UPPERCASE_FIRST) {
            upperCase(out, start)
        } else if (type == BrotliTables.UPPERCASE_ALL) {
            var at = start
            var left = body
            while (left > 0) {
                val step = upperCase(out, at)
                at += step
                left -= step
            }
        }
        repeat(table[suffix].toInt()) { out[used++] = table[suffix + 1 + it] }
        return out.copyOf(used)
    }

    /** `ToUpperCase`: упрощённая модель UTF-8 из `transform.c`. */
    private fun upperCase(word: ByteArray, at: Int): Int {
        val first = word[at].toInt() and BYTE
        return when {
            first < 0xC0 -> {
                if (first in 'a'.code..'z'.code) word[at] = (first xor CASE_BIT).toByte()
                1
            }
            first < 0xE0 -> {
                word[at + 1] = (word[at + 1].toInt() xor CASE_BIT).toByte()
                2
            }
            else -> {
                word[at + 2] = (word[at + 2].toInt() xor THREE_BYTE_FLIP).toByte()
                3
            }
        }
    }

    private const val RESOURCE = "brotli-dictionary.bin"
    private const val SHA256 = "20e42eb1b511c21806d4d227d07e5dd06877d8ce7b3a817f378f313653f35c70"
    private const val UTF8_SLACK = 3
    private const val BYTE = 0xFF
    private const val CASE_BIT = 32
    private const val THREE_BYTE_FLIP = 5
}
