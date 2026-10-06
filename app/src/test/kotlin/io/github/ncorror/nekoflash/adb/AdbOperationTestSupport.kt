package io.github.ncorror.nekoflash.adb

import io.github.ncorror.nekoflash.core.artifact.ArtifactAccess
import io.github.ncorror.nekoflash.core.artifact.ArtifactIdentity
import io.github.ncorror.nekoflash.core.artifact.ArtifactRandomAccess
import io.github.ncorror.nekoflash.core.artifact.ArtifactSource
import io.github.ncorror.nekoflash.core.artifact.ArtifactStamp
import io.github.ncorror.nekoflash.core.operation.OperationHistory
import io.github.ncorror.nekoflash.core.operation.OperationId
import io.github.ncorror.nekoflash.core.operation.OperationJournal
import io.github.ncorror.nekoflash.core.operation.OperationRecord
import io.github.ncorror.nekoflash.protocol.adb.AdbCommand
import io.github.ncorror.nekoflash.protocol.adb.AdbSyncProtocol
import java.io.ByteArrayInputStream
import java.io.InputStream

internal class AdbTestJournal : OperationJournal {
    private val records = LinkedHashMap<OperationId, OperationRecord>()
    override fun save(record: OperationRecord) { records[record.id] = record }
    override fun history() = OperationHistory(records.values.toList())
    override fun forget(id: OperationId) { records.remove(id) }
}

internal open class AdbTestArtifact(private val bytes: ByteArray = byteArrayOf(1)) : ArtifactSource {
    override val identity = ArtifactIdentity("test.apk", bytes.size.toLong(), ArtifactAccess.SEEKABLE)
    override val openedStamp = ArtifactStamp(bytes.size.toLong(), modifiedAtMillis = 1)
    override fun stamp() = openedStamp
    override fun open(): InputStream = ByteArrayInputStream(bytes)
    override fun randomAccess() = ArtifactRandomAccess { offset, length ->
        bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
    }
}

/** Replies are actual sync frames, routed by the production dispatcher. */
internal fun AdbLifecycleHandle.syncReplies(text: () -> String = { "log\n" }) {
    serviceHook = { header, _ -> enqueue(AdbCommand.OKAY, 77, header.arg0) }
    payloadHook = { header, payload ->
        if (header.command == AdbCommand.WRTE) {
            enqueue(AdbCommand.OKAY, 77, header.arg0)
            val id = String(payload, 0, 4, Charsets.US_ASCII)
            val answer = when (id) {
                AdbSyncProtocol.ID_STAT -> AdbSyncProtocol.header("STAT", 0x81A4) +
                    AdbSyncProtocol.header("\u0000\u0000\u0000\u0000", 0)
                AdbSyncProtocol.ID_RECV -> text().toByteArray().let { body ->
                    AdbSyncProtocol.header("DATA", body.size) + body + AdbSyncProtocol.header("DONE", 0)
                }
                AdbSyncProtocol.ID_DONE -> AdbSyncProtocol.header("OKAY", 0)
                else -> byteArrayOf()
            }
            if (answer.isNotEmpty()) enqueue(AdbCommand.WRTE, 77, header.arg0, answer)
        }
    }
}
