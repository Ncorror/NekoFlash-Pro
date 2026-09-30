package io.github.ncorror.nekoflash.protocol.adb

/** Элемент каталога из ответа `LIST`: имя и те же поля, что у `STAT`. */
public data class AdbSyncEntry(val name: String, val stat: AdbSyncStat) {
    /** Символьная ссылка: чем она окажется, скажет только `STAT` по ней. */
    public val symlink: Boolean
        get() = stat.mode and AdbSyncProtocol.MODE_TYPE_MASK == AdbSyncProtocol.MODE_SYMLINK
}

/**
 * `LIST` первой версии — как `FinishLsImpl<false>` в AOSP
 * `adb/client/file_sync_client.cpp` (android11-release).
 *
 * Каждый элемент — `DENT` с режимом в числе заголовка, затем размер, время и
 * длина имени, затем имя. Конец — `DONE` той же длины, что и `DENT`: его
 * поля добираются, иначе следующая операция сессии прочитала бы их как свой
 * заголовок. Имя длиннее 255 байт клиент AOSP считает сбоем, и здесь так же.
 * `.` и `..` устройство присылает, отбрасывает вызывающий.
 */
internal class AdbSyncList(private val exchange: AdbSyncExchange) {
    fun list(path: String, timeoutMillis: Int): AdbSyncOutcome<List<AdbSyncEntry>> {
        if (!exchange.active) {
            return exchange.endReason() ?: exchange.failure(AdbSyncFailure.NOT_OPEN, "list $path")
        }
        val deadline = exchange.deadlineFrom(timeoutMillis)
        exchange.request(AdbSyncProtocol.ID_LIST, path)?.let { return it }
        val entries = mutableListOf<AdbSyncEntry>()
        var outcome: AdbSyncOutcome<List<AdbSyncEntry>>? = null
        while (outcome == null) {
            outcome = when (val read = exchange.readHeader(deadline)) {
                is AdbSyncOutcome.Failed -> read
                is AdbSyncOutcome.Done -> next(path, read.value, deadline, entries)
            }
        }
        return outcome
    }

    /** Разбирает очередную запись; `null` — читать дальше. */
    private fun next(
        path: String,
        header: AdbSyncHeader,
        deadline: Long,
        entries: MutableList<AdbSyncEntry>,
    ): AdbSyncOutcome<List<AdbSyncEntry>>? = when (header.id) {
        AdbSyncProtocol.ID_DENT, AdbSyncProtocol.ID_DONE -> when (
            val body = exchange.readExactly(DENT_BODY_BYTES, deadline)
        ) {
            is AdbSyncOutcome.Failed -> body
            is AdbSyncOutcome.Done -> if (header.id == AdbSyncProtocol.ID_DONE) {
                exchange.emit("sync_list", mapOf("path" to path, "entries" to entries.size.toString()))
                AdbSyncOutcome.Done(entries.toList())
            } else {
                entry(path, header.value, body.value, deadline, entries)
            }
        }

        AdbSyncProtocol.ID_FAIL -> exchange.refusal(header, deadline, "list $path")
        else -> exchange.failure(AdbSyncFailure.UNEXPECTED_RESPONSE, "list $path got ${header.id}")
    }

    private fun entry(
        path: String,
        mode: Int,
        body: ByteArray,
        deadline: Long,
        entries: MutableList<AdbSyncEntry>,
    ): AdbSyncOutcome<List<AdbSyncEntry>>? {
        val length = readIntLe(body, NAME_LENGTH_AT)
        return if (length < 0 || length > MAX_NAME_BYTES) {
            exchange.failure(AdbSyncFailure.INVALID_LENGTH, "list $path name=$length")
        } else {
            when (val name = exchange.readExactly(length, deadline)) {
                is AdbSyncOutcome.Failed -> name
                is AdbSyncOutcome.Done -> {
                    val stat = AdbSyncStat(mode, readIntLe(body, 0).toLong() and UINT_MASK, readIntLe(body, TIME_AT))
                    entries += AdbSyncEntry(name.value.toString(Charsets.UTF_8), stat)
                    null
                }
            }
        }
    }

    private fun readIntLe(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and BYTE_MASK) or
            ((source[offset + 1].toInt() and BYTE_MASK) shl BYTE_BITS) or
            ((source[offset + 2].toInt() and BYTE_MASK) shl (2 * BYTE_BITS)) or
            ((source[offset + 3].toInt() and BYTE_MASK) shl (3 * BYTE_BITS))

    private companion object {
        /** После заголовка `DENT`: размер, время, длина имени. */
        const val DENT_BODY_BYTES = 12
        const val TIME_AT = 4
        const val NAME_LENGTH_AT = 8
        const val MAX_NAME_BYTES = 255
        const val UINT_MASK = 0xFFFF_FFFFL
        const val BYTE_MASK = 0xFF
        const val BYTE_BITS = 8
    }
}
