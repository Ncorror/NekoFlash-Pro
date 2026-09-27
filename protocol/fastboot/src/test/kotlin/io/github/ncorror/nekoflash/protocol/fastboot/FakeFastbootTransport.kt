package io.github.ncorror.nekoflash.protocol.fastboot

import io.github.ncorror.nekoflash.usb.api.UsbDeviceDescriptor
import io.github.ncorror.nekoflash.usb.api.UsbEndpointDescriptor
import io.github.ncorror.nekoflash.usb.api.UsbEndpointDirection
import io.github.ncorror.nekoflash.usb.api.UsbInterfaceCandidate
import io.github.ncorror.nekoflash.usb.api.UsbInterfaceKind
import io.github.ncorror.nekoflash.usb.api.UsbMatchConfidence
import io.github.ncorror.nekoflash.usb.api.UsbTransferFailure
import io.github.ncorror.nekoflash.usb.api.UsbTransferResult
import io.github.ncorror.nekoflash.usb.api.UsbTransferType
import io.github.ncorror.nekoflash.usb.api.UsbTransportHandle

/** Что подставной транспорт отдаст на очередной приём. */
internal sealed interface FakeInbound {
    /** Кадр целиком. */
    data class Frame(val text: String) : FakeInbound

    /** Тишина: приём не состоялся. Так же выглядит обычный таймаут. */
    data object Silence : FakeInbound

    /** Успешный приём нулевой длины — устройство молчит, но передача прошла. */
    data object Empty : FakeInbound

    /**
     * Сырые байты фазы DATA IN, а не кадр.
     *
     * Отдельный вид, потому что снаружи они ничем не отличаются от кадра:
     * направление фазы данных знает команда, а не то, что пришло по проводу.
     * Подставной транспорт обязан уметь это воспроизвести, иначе приём
     * проверялся бы на том, чего в нём нет.
     */
    data class Payload(val bytes: ByteArray) : FakeInbound {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }
}

/**
 * Транспорт, который отдаёт заранее назначенные ответы.
 *
 * Здесь проверяется поведение полосы, а не USB: настоящий обмен подтверждается
 * только прогоном на устройстве, и подменять его подставным нельзя (`16` §5.1).
 */
internal class FakeFastbootTransport(
    private val inbound: MutableList<FakeInbound> = mutableListOf(),
) : UsbTransportHandle {
    val sent: MutableList<String> = mutableListOf()
    var shortWriteAfter: Int? = null
    var failWrite: Boolean = false
    var closed: Boolean = false

    /** Сколько байт получено в фазе данных — считается отдельно от команд. */
    var dataBytes: Long = 0L
        private set

    /**
     * Отдавать по столько байт за запись, а не всё разом.
     *
     * Короткая запись хост → устройство законна, и передача обязана дописывать
     * остаток. Без такой возможности проверить это нечем.
     */
    var writeAtMost: Int? = null

    /** Считать команды и данные вместе перестаёт после этого числа команд. */
    private var commandsSeen: Int = 0

    /** С какой команды считать записи данными, а не командами. */
    var dataAfterCommands: Int? = null

    /** Отдать неоднозначный ответ на записи данных: больше запрошенного. */
    var overlongWrite: Boolean = false

    /** Ронять только записи данных, оставив команду проходящей. */
    var failDataWrite: Boolean = false

    /** Сколько приёмов было запрошено — по нему видно, что ожидание дробится. */
    var receiveCalls: Int = 0
        private set

    fun willReply(vararg frames: String): FakeFastbootTransport = apply {
        frames.forEach { inbound += FakeInbound.Frame(it) }
    }

    fun willBeSilent(times: Int): FakeFastbootTransport = apply {
        repeat(times) { inbound += FakeInbound.Silence }
    }

    fun willReceiveEmpty(): FakeFastbootTransport = apply { inbound += FakeInbound.Empty }

    /** Отдать эти байты как содержимое фазы DATA IN. */
    fun willSendData(bytes: ByteArray): FakeFastbootTransport = apply { inbound += FakeInbound.Payload(bytes) }

    override val candidate: UsbInterfaceCandidate = CANDIDATE

    override val held: Boolean get() = !closed

    override fun receive(destination: ByteArray, offset: Int, length: Int, timeoutMillis: Int): UsbTransferResult {
        receiveCalls += 1
        val next = inbound.removeFirstOrNull() ?: FakeInbound.Silence
        return when (next) {
            is FakeInbound.Silence -> UsbTransferResult.Failed(UsbTransferFailure.NOT_COMPLETED)
            is FakeInbound.Empty -> UsbTransferResult.Completed(0)
            is FakeInbound.Frame -> {
                val bytes = next.text.toByteArray(Charsets.US_ASCII)
                bytes.copyInto(destination, offset, 0, bytes.size)
                UsbTransferResult.Completed(bytes.size)
            }

            is FakeInbound.Payload -> {
                // Отдаём не больше, чем попросили: короткий приём законен, и
                // остаток должен дочитываться следующим вызовом.
                val count = minOf(next.bytes.size, length)
                next.bytes.copyInto(destination, offset, 0, count)
                if (count < next.bytes.size) {
                    inbound.add(0, FakeInbound.Payload(next.bytes.copyOfRange(count, next.bytes.size)))
                }
                UsbTransferResult.Completed(count)
            }
        }
    }

    override fun send(source: ByteArray, offset: Int, length: Int, timeoutMillis: Int): UsbTransferResult {
        val boundary = dataAfterCommands
        val isData = boundary != null && commandsSeen >= boundary
        if (isData) {
            dataBytes += length.toLong()
        } else {
            sent += String(source, offset, length, Charsets.US_ASCII)
            commandsSeen += 1
        }
        return when {
            failWrite -> UsbTransferResult.Failed(UsbTransferFailure.NOT_COMPLETED)
            isData && failDataWrite -> UsbTransferResult.Failed(UsbTransferFailure.NOT_COMPLETED)
            isData && overlongWrite -> UsbTransferResult.Completed(length + 1)
            isData && writeAtMost != null -> UsbTransferResult.Completed(minOf(length, writeAtMost!!))
            shortWriteAfter != null -> UsbTransferResult.Completed(shortWriteAfter!!)
            else -> UsbTransferResult.Completed(length)
        }
    }

    override fun close() {
        closed = true
    }

    private companion object {
        val CANDIDATE = UsbInterfaceCandidate(
            device = UsbDeviceDescriptor(
                deviceId = 1,
                deviceName = "/dev/bus/usb/001/002",
                vendorId = 0x2717,
                productId = 0xD00D,
            ),
            kind = UsbInterfaceKind.FASTBOOT,
            confidence = UsbMatchConfidence.CANONICAL,
            interfaceIndex = 0,
            interfaceId = 0,
            interfaceClass = 0xFF,
            interfaceSubclass = 0x42,
            interfaceProtocol = 0x03,
            endpointIn = UsbEndpointDescriptor(
                address = 0x81,
                direction = UsbEndpointDirection.IN,
                transferType = UsbTransferType.BULK,
                maxPacketSize = 512,
            ),
            endpointOut = UsbEndpointDescriptor(
                address = 0x01,
                direction = UsbEndpointDirection.OUT,
                transferType = UsbTransferType.BULK,
                maxPacketSize = 512,
            ),
        )
    }
}
