package io.github.ncorror.nekoflash.protocol.adb

import io.github.ncorror.nekoflash.usb.api.UsbDeviceDescriptor
import io.github.ncorror.nekoflash.usb.api.UsbEndpointDescriptor
import io.github.ncorror.nekoflash.usb.api.UsbEndpointDirection
import io.github.ncorror.nekoflash.usb.api.UsbInterfaceCandidate
import io.github.ncorror.nekoflash.usb.api.UsbInterfaceKind
import io.github.ncorror.nekoflash.usb.api.UsbMatchConfidence
import io.github.ncorror.nekoflash.usb.api.UsbTransferArguments
import io.github.ncorror.nekoflash.usb.api.UsbTransferFailure
import io.github.ncorror.nekoflash.usb.api.UsbTransferResult
import io.github.ncorror.nekoflash.usb.api.UsbTransferType
import io.github.ncorror.nekoflash.usb.api.UsbTransportHandle

/**
 * Подставное Recovery, **отвечающее на то, что ему прислали**.
 *
 * Записанная очередь ответов для Sideload не годится, и это не вкус: обмен
 * управляется запросами Recovery, и следующий запрос зависит от того, что хост
 * успел отдать. Очередь отвечала бы одинаково и на исправный обмен, и на
 * сломанный — то есть проверяла бы расстановку вызовов в тесте, а не протокол.
 *
 * Устройство здесь простое нарочно: оно повторяет только то, что записано в
 * `03` §6 как поведение Recovery, и ничего не выдумывает сверх.
 */
internal class FakeRecoveryDevice(
    /** Номера блоков по порядку. `-1` означает, что запросы кончились. */
    private val requests: List<Int>,
    /** Закрыть поток вместо `DONEDONE`: так выглядит обрыв со стороны Recovery. */
    private val closeInsteadOfDoneDone: Boolean = false,
    private val remoteId: Int = REMOTE_ID,
) : UsbTransportHandle {
    /** Нагрузка, дошедшая до устройства, в порядке получения. */
    val receivedBlocks: MutableList<ByteArray> = mutableListOf()

    /** Сколько раз хост подтвердил наш `WRTE`. */
    var acknowledgementsFromHost: Int = 0
        private set

    private val lock = Any()
    private val outgoing = ArrayDeque<ByteArray>()
    private var incoming = ByteArray(0)
    private var pending = requests.toMutableList()
    private var released = false
    private var closed = false

    override val candidate: UsbInterfaceCandidate = CANDIDATE

    override val held: Boolean
        get() = !released

    /**
     * Отдаёт очередной кусок.
     *
     * Тишина — это `NOT_COMPLETED`: цикл раскладки читает её как обычный
     * таймаут, а не как беду, и продолжает ждать.
     */
    override fun receive(
        destination: ByteArray,
        offset: Int,
        length: Int,
        timeoutMillis: Int,
    ): UsbTransferResult = synchronized(lock) {
        UsbTransferArguments.validate(destination.size, offset, length, timeoutMillis)
        val chunk = outgoing.removeFirstOrNull()
        if (chunk == null) {
            UsbTransferResult.Failed(UsbTransferFailure.NOT_COMPLETED)
        } else {
            val count = minOf(chunk.size, length)
            chunk.copyInto(destination, offset, 0, count)
            UsbTransferResult.Completed(count)
        }
    }

    override fun send(
        source: ByteArray,
        offset: Int,
        length: Int,
        timeoutMillis: Int,
    ): UsbTransferResult = synchronized(lock) {
        UsbTransferArguments.validate(source.size, offset, length, timeoutMillis)
        incoming += source.copyOfRange(offset, offset + length)
        consumeFrames()
        UsbTransferResult.Completed(length)
    }

    override fun close() {
        synchronized(lock) { released = true }
    }

    /** Разбирает всё, что успело собраться в целые кадры. */
    private fun consumeFrames() {
        var parsing = true
        while (parsing) {
            val decoded = header()
            val total = if (decoded == null) 0 else AdbPacketHeader.SIZE_BYTES + decoded.payloadLength
            if (decoded == null || incoming.size < total) {
                parsing = false
            } else {
                val payload = incoming.copyOfRange(AdbPacketHeader.SIZE_BYTES, total)
                incoming = incoming.copyOfRange(total, incoming.size)
                react(decoded, payload)
            }
        }
    }

    private fun header(): AdbPacketHeader? = if (incoming.size < AdbPacketHeader.SIZE_BYTES) {
        null
    } else {
        val decoding = AdbPacketHeader.decode(incoming, AdbInboundFraming.MODERN_MAX_PAYLOAD_BYTES)
        (decoding as? AdbHeaderDecoding.Decoded)?.header
    }

    private fun react(header: AdbPacketHeader, payload: ByteArray) {
        when (header.command) {
            AdbCommand.OPEN -> {
                reply(AdbCommand.OKAY, header.arg0, ByteArray(0))
                askNext(header.arg0)
            }

            // Подтверждение нашего запроса. Устройству от него ничего не нужно,
            // но посчитать его стоит: без него хост не двигал бы покрытие.
            AdbCommand.OKAY -> acknowledgementsFromHost += 1

            AdbCommand.WRTE -> onWrite(header.arg0, payload)

            AdbCommand.CLSE -> closed = true

            else -> Unit
        }
    }

    /**
     * Хост что-то отдал.
     *
     * Пустая запись означает, что он принял конец запросов; всё остальное —
     * блок нагрузки. Подтверждаем и спрашиваем дальше — так же, как Recovery.
     */
    private fun onWrite(localId: Int, payload: ByteArray) {
        if (payload.isNotEmpty()) receivedBlocks += payload
        reply(AdbCommand.OKAY, localId, ByteArray(0))
        askNext(localId)
    }

    private fun askNext(localId: Int) {
        val next = pending.removeFirstOrNull()
        when {
            next != null && next == AdbSideloadContract.END_OF_REQUESTS ->
                reply(AdbCommand.WRTE, localId, "%d".format(next).toByteArray(Charsets.US_ASCII))

            next != null -> reply(AdbCommand.WRTE, localId, "%08d".format(next).toByteArray(Charsets.US_ASCII))

            closeInsteadOfDoneDone -> reply(AdbCommand.CLSE, localId, ByteArray(0))

            else -> reply(
                AdbCommand.WRTE,
                localId,
                AdbSideloadContract.DONE_DONE.toByteArray(Charsets.US_ASCII),
            )
        }
    }

    /** Кладёт кадр так, как его прочитает читатель: заголовок и нагрузка порознь. */
    private fun reply(command: Long, localId: Int, payload: ByteArray) {
        if (closed) return
        val header = ByteArray(AdbPacketHeader.SIZE_BYTES)
        AdbPacketHeader.encode(header, command, remoteId, localId, payload, AdbChecksum.compute(payload))
        outgoing += header
        if (payload.isNotEmpty()) outgoing += payload
    }

    private companion object {
        const val REMOTE_ID = 9

        val CANDIDATE = UsbInterfaceCandidate(
            device = UsbDeviceDescriptor(
                deviceId = 1,
                deviceName = "/dev/bus/usb/001/003",
                vendorId = 0x2717,
                productId = 0xFF48,
            ),
            kind = UsbInterfaceKind.ADB,
            confidence = UsbMatchConfidence.CANONICAL,
            interfaceIndex = 0,
            interfaceId = 0,
            interfaceClass = 0xFF,
            interfaceSubclass = 0x42,
            interfaceProtocol = 0x01,
            endpointIn = UsbEndpointDescriptor(
                address = 0x81,
                direction = UsbEndpointDirection.IN,
                transferType = UsbTransferType.BULK,
            ),
            endpointOut = UsbEndpointDescriptor(
                address = 0x01,
                direction = UsbEndpointDirection.OUT,
                transferType = UsbTransferType.BULK,
            ),
        )
    }
}
