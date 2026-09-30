package io.github.ncorror.nekoflash.usb.api

/**
 * Платформенно-независимое описание подключённого USB-устройства.
 *
 * Дескрипторы читаются до того, как открыто хоть одно соединение, и до любого
 * протокольного обмена. Всё, что здесь есть, — это то, что устройство сообщает
 * о себе на уровне USB. Ни одно поле не доказывает, что peer действительно
 * говорит по ADB или Fastboot: это решает только handshake.
 *
 * Модель перенесена из проверенного A2 (`usb/model/UsbDescriptor.kt`) и
 * дополнена [serialNumber], которого там не было: он нужен для устойчивой
 * идентичности target между сменами режима.
 */
public data class UsbDeviceDescriptor(
    /**
     * Идентификатор подключения, назначенный платформой. Не переживает
     * detach/re-enumeration и не является идентичностью устройства.
     */
    val deviceId: Int,
    /**
     * Имя подключения, назначенное платформой (на Android — путь вида
     * `/dev/bus/usb/001/002`). Стабильно, пока устройство физически
     * подключено, и меняется после переподключения.
     */
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val productName: String? = null,
    /**
     * Серийный номер, сообщённый устройством, если он доступен.
     *
     * На Android 10+ чтение требует уже выданного USB-permission, поэтому до
     * получения разрешения здесь закономерно `null`. Это единственный признак,
     * который может пережить смену режима (ADB → fastboot), потому что
     * `vendorId`/`productId` при такой смене обычно меняются.
     */
    val serialNumber: String? = null,
    val interfaces: List<UsbInterfaceDescriptor> = emptyList(),
) {
    init {
        require(deviceName.isNotBlank()) { "UsbDeviceDescriptor.deviceName must not be blank" }
    }

    /** Серийный номер без окружающих пробелов, либо `null`, если он пуст или отсутствует. */
    val normalizedSerialNumber: String?
        get() = serialNumber?.trim()?.takeIf(String::isNotEmpty)
}

public data class UsbInterfaceDescriptor(
    val id: Int,
    val interfaceClass: Int,
    val interfaceSubclass: Int,
    val interfaceProtocol: Int,
    val endpoints: List<UsbEndpointDescriptor> = emptyList(),
)

public data class UsbEndpointDescriptor(
    val address: Int,
    val direction: UsbEndpointDirection,
    val transferType: UsbTransferType,
    val maxPacketSize: Int = 0,
)

public enum class UsbEndpointDirection {
    IN,
    OUT;

    public companion object {
        /**
         * Бит направления в адресе эндпоинта по спецификации USB.
         *
         * Установленный бит означает IN. Значение спецификационное, а не
         * платформенное: Android отдаёт его как есть.
         */
        public const val DIRECTION_MASK_IN: Int = 0x80

        /**
         * Направление по сырому значению.
         *
         * Проверяется бит, а не равенство: значение может прийти и как готовое
         * направление, и как полный адрес эндпоинта, а бит направления в них
         * один и тот же.
         */
        public fun fromRaw(raw: Int): UsbEndpointDirection =
            if (raw and DIRECTION_MASK_IN != 0) IN else OUT
    }
}

/**
 * Тип передачи эндпоинта.
 *
 * Различается только BULK, потому что и ADB, и Fastboot работают через пару
 * bulk-эндпоинтов. Остальные типы объединены в [OTHER]: они не отбрасывают
 * устройство, а просто не участвуют в подборе пары.
 */
public enum class UsbTransferType {
    BULK,
    OTHER;

    public companion object {
        /** Код bulk-передачи по спецификации USB. */
        public const val TRANSFER_TYPE_BULK: Int = 2

        /** Тип передачи по сырому значению. */
        public fun fromRaw(raw: Int): UsbTransferType =
            if (raw == TRANSFER_TYPE_BULK) BULK else OTHER
    }
}
