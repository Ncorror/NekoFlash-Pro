package io.github.ncorror.nekoflash.usb.api

import io.github.ncorror.nekoflash.core.model.TargetId

/**
 * Откуда взята идентичность target и насколько далеко ей можно доверять.
 *
 * Разница существенна: по одному лишь USB-подключению нельзя доказать, что
 * вернувшееся после переподключения устройство — то же самое. Приложение не
 * должно делать вид, что знает больше, чем знает.
 */
public enum class TargetIdentitySource {
    /**
     * Серийный номер, сообщённый устройством.
     *
     * Переживает и re-enumeration, и смену режима: при переходе ADB → fastboot
     * `vendorId`/`productId` обычно меняются, а серийный номер — нет.
     */
    SERIAL,

    /**
     * Имя USB-подключения, назначенное платформой.
     *
     * Устойчиво, пока устройство физически подключено, и меняется после
     * detach/re-enumeration. Пригодно для отслеживания текущего подключения, но
     * не доказывает тождество устройства между подключениями.
     */
    USB_ATTACHMENT,
}

/**
 * Идентичность target вместе с доказательством, на котором она построена.
 *
 * [id] пригоден как ключ; [source] говорит, что этот ключ на самом деле
 * означает. Слой выше обязан учитывать [survivesReattachment], а не считать
 * любой `TargetId` одинаково надёжным.
 */
public data class UsbTargetIdentity(
    val id: TargetId,
    val source: TargetIdentitySource,
    val vendorId: Int?,
    val productId: Int?,
    val serialNumber: String?,
    val attachmentName: String?,
) {
    /**
     * Сохранится ли эта идентичность после detach/re-enumeration.
     *
     * `false` означает не «устройство другое», а «доказать тождество нечем».
     * Считать такие target'ы одним и тем же можно только с явного решения
     * пользователя, но не автоматически.
     */
    public val survivesReattachment: Boolean
        get() = source == TargetIdentitySource.SERIAL

    public companion object {
        private const val SERIAL_SCHEME = "serial"
        private const val ATTACHMENT_SCHEME = "usb-attachment"

        /**
         * Идентичность по дескриптору USB.
         *
         * Серийный номер предпочитается всегда, когда он доступен. На Android
         * 10+ он читается только после выдачи permission, поэтому до этого
         * момента закономерно возвращается идентичность уровня
         * [TargetIdentitySource.USB_ATTACHMENT], которую позже уточняет
         * [refinedWithSerial].
         *
         * `vendorId`/`productId` намеренно не входят в ключ при наличии
         * серийного номера: при смене режима они меняются, и их включение
         * разорвало бы идентичность там, где устройство осталось тем же.
         */
        public fun fromDescriptor(device: UsbDeviceDescriptor): UsbTargetIdentity {
            val serial = device.normalizedSerialNumber
            return if (serial != null) {
                UsbTargetIdentity(
                    id = TargetId("$SERIAL_SCHEME:$serial"),
                    source = TargetIdentitySource.SERIAL,
                    vendorId = device.vendorId,
                    productId = device.productId,
                    serialNumber = serial,
                    attachmentName = device.deviceName,
                )
            } else {
                UsbTargetIdentity(
                    id = TargetId("$ATTACHMENT_SCHEME:${device.deviceName}"),
                    source = TargetIdentitySource.USB_ATTACHMENT,
                    vendorId = device.vendorId,
                    productId = device.productId,
                    serialNumber = null,
                    attachmentName = device.deviceName,
                )
            }
        }

        /**
         * Идентичность по серийному номеру, полученному от самого устройства.
         *
         * Применяется, когда серийный номер стал известен из протокола — из
         * баннера ADB или ответа `getvar:serialno` — а не из дескриптора USB.
         *
         * @throws IllegalArgumentException если серийный номер пуст.
         */
        public fun fromSerial(serialNumber: String): UsbTargetIdentity {
            val serial = serialNumber.trim()
            require(serial.isNotEmpty()) { "Serial number must not be blank" }
            return UsbTargetIdentity(
                id = TargetId("$SERIAL_SCHEME:$serial"),
                source = TargetIdentitySource.SERIAL,
                vendorId = null,
                productId = null,
                serialNumber = serial,
                attachmentName = null,
            )
        }
    }
}

/**
 * Уточняет идентичность серийным номером, ставшим известным из протокола.
 *
 * Возвращает идентичность уровня [TargetIdentitySource.SERIAL], сохраняя уже
 * собранные сведения о подключении. Если идентичность уже опирается на
 * серийный номер, значение не подменяется молча: расхождение означает, что это
 * другое устройство, и решать это должен вызывающий, а не эта функция.
 */
public fun UsbTargetIdentity.refinedWithSerial(serialNumber: String): UsbTargetIdentity {
    val serial = serialNumber.trim()
    require(serial.isNotEmpty()) { "Serial number must not be blank" }
    if (source == TargetIdentitySource.SERIAL) return this
    return copy(
        id = UsbTargetIdentity.fromSerial(serial).id,
        source = TargetIdentitySource.SERIAL,
        serialNumber = serial,
    )
}

/**
 * Относятся ли два дескриптора к одному и тому же физическому подключению.
 *
 * Перенесено из проверенного поведения A2 (`UsbSessionLifecyclePolicy.isCurrentDevice`,
 * там — из Legacy `DeviceViewModel.isCurrentUsbDevice`): совпадения имени
 * подключения достаточно, иначе требуется полное совпадение тройки
 * `deviceId`/`vendorId`/`productId`.
 *
 * Это проверка «то же подключение прямо сейчас», а не тождество устройства
 * между подключениями: для последнего нужна [UsbTargetIdentity] с источником
 * [TargetIdentitySource.SERIAL].
 */
public fun isSameAttachment(
    first: UsbDeviceDescriptor,
    second: UsbDeviceDescriptor,
): Boolean =
    first.deviceName == second.deviceName ||
        (
            first.deviceId == second.deviceId &&
                first.vendorId == second.vendorId &&
                first.productId == second.productId
            )
