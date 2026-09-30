package io.github.ncorror.nekoflash.usb.android

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import io.github.ncorror.nekoflash.usb.api.UsbDeviceDescriptor
import io.github.ncorror.nekoflash.usb.api.UsbEndpointDescriptor
import io.github.ncorror.nekoflash.usb.api.UsbEndpointDirection
import io.github.ncorror.nekoflash.usb.api.UsbInterfaceDescriptor
import io.github.ncorror.nekoflash.usb.api.UsbTransferType

/**
 * Перекладывает дескрипторы Android в модель [io.github.ncorror.nekoflash.usb.api].
 *
 * Здесь намеренно нет ни одного решения: разбор сырых значений направления и
 * типа передачи живёт в `usb:api` и покрыт тестами. Этот код невозможно
 * проверить без устройства или Android-окружения, поэтому в нём не должно быть
 * ничего, что можно проверить в другом месте.
 *
 * Порядок интерфейсов и эндпоинтов сохраняется как есть: подбор пары
 * bulk-эндпоинтов опирается на порядок объявления, и это проверенное на
 * реальных устройствах поведение.
 */
public object AndroidUsbDescriptorMapper {
    /**
     * Дескриптор подключённого устройства.
     *
     * Чтение `productName` и `serialNumber` защищено: начиная с Android 10
     * серийный номер доступен только после выдачи USB-permission и иначе
     * приводит к [SecurityException]. Отсутствие серийного номера — обычное
     * состояние до получения разрешения, а не ошибка; идентичность target в
     * этом случае строится по имени подключения и честно помечается менее
     * надёжной.
     */
    public fun map(device: UsbDevice): UsbDeviceDescriptor = UsbDeviceDescriptor(
        deviceId = device.deviceId,
        deviceName = device.deviceName,
        vendorId = device.vendorId,
        productId = device.productId,
        productName = readGuarded { device.productName },
        serialNumber = readGuarded { device.serialNumber },
        interfaces = (0 until device.interfaceCount).map { index ->
            map(device.getInterface(index))
        },
    )

    private fun map(usbInterface: UsbInterface): UsbInterfaceDescriptor = UsbInterfaceDescriptor(
        id = usbInterface.id,
        interfaceClass = usbInterface.interfaceClass,
        interfaceSubclass = usbInterface.interfaceSubclass,
        interfaceProtocol = usbInterface.interfaceProtocol,
        endpoints = (0 until usbInterface.endpointCount).map { index ->
            map(usbInterface.getEndpoint(index))
        },
    )

    private fun map(endpoint: UsbEndpoint): UsbEndpointDescriptor = UsbEndpointDescriptor(
        address = endpoint.address,
        direction = UsbEndpointDirection.fromRaw(endpoint.direction),
        transferType = UsbTransferType.fromRaw(endpoint.type),
        maxPacketSize = endpoint.maxPacketSize,
    )

    /**
     * Читает необязательное поле, не давая отказу в доступе уронить discovery.
     *
     * Пустая строка приравнивается к отсутствию значения: платформа возвращает
     * её для полей, которых у устройства нет.
     */
    private inline fun readGuarded(read: () -> String?): String? =
        runCatching(read).getOrNull()?.takeIf { it.isNotBlank() }
}
