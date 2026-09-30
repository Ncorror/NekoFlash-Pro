package io.github.ncorror.nekoflash.usb.api

/**
 * Транспортный вид интерфейса, определённый по дескрипторам.
 *
 * Это **не** `TargetMode`. Дескриптор способен отличить интерфейс ADB-класса от
 * интерфейса Fastboot-класса, но не может отличить обычный Android от Recovery
 * или Sideload, а bootloader fastboot от fastbootd. Это различие устанавливает
 * только протокольный handshake, поэтому сопоставление с `TargetMode`
 * происходит выше по стеку, а не здесь.
 */
public enum class UsbInterfaceKind {
    ADB,
    FASTBOOT,
}

/**
 * Насколько дескриптор соответствует известному профилю Android.
 *
 * Более низкая уверенность не означает отказ: устройство с нестандартными
 * дескрипторами остаётся доступным кандидатом. Legacy прямо отмечает, что
 * generic-путь нужен для OEM Fastboot, который не использует `0xFF/0x42/0x03`.
 */
public enum class UsbMatchConfidence(internal val rank: Int) {
    /** `0xFF/0x42/0x01` для ADB или `0xFF/0x42/0x03` для Fastboot. */
    CANONICAL(0),

    /** Android-подкласс `0x42`, но протокол не ADB и не Fastboot. */
    ANDROID_COMPATIBLE(1),

    /** Vendor-specific класс с парой bulk-эндпоинтов и протоколом, отличным от ADB. */
    GENERIC_VENDOR(2),
}

/**
 * Интерфейс устройства, пригодный для попытки установить транспорт.
 *
 * Кандидат — это только предположение по дескрипторам. Подтверждение, что peer
 * действительно говорит по выбранному протоколу, даёт handshake.
 */
public data class UsbInterfaceCandidate(
    val device: UsbDeviceDescriptor,
    val kind: UsbInterfaceKind,
    val confidence: UsbMatchConfidence,
    val interfaceIndex: Int,
    val interfaceId: Int,
    val interfaceClass: Int,
    val interfaceSubclass: Int,
    val interfaceProtocol: Int,
    val endpointIn: UsbEndpointDescriptor,
    val endpointOut: UsbEndpointDescriptor,
) {
    /**
     * Ключ конкретного физического подключения.
     *
     * Содержит имя подключения, назначенное платформой, поэтому меняется после
     * detach/re-enumeration. Пригоден для отслеживания текущего присоединения,
     * но не является идентичностью устройства.
     */
    public val attachmentKey: String
        get() = buildString {
            append(device.deviceName)
            append(':').append(device.vendorId)
            append(':').append(device.productId)
            append(':').append(kind.name)
            append(':').append(interfaceIndex)
        }

    /**
     * Подпись логического USB-профиля.
     *
     * Не зависит от имени подключения, поэтому переживает re-enumeration и
     * позволяет отличить «то же самое устройство вернулось» от «появился другой
     * профиль» при смене режима.
     */
    public val profileSignature: String
        get() = buildString {
            append(device.vendorId)
            append(':').append(device.productId)
            append(':').append(kind.name)
            append(':').append(interfaceClass)
            append(':').append(interfaceSubclass)
            append(':').append(interfaceProtocol)
            append(':').append(endpointIn.address)
            append(':').append(endpointOut.address)
        }
}

/**
 * Классификация USB-интерфейсов по дескрипторам.
 *
 * Константы и предикаты перенесены из двух независимых источников, которые
 * сошлись на одном и том же: Legacy `UsbDeviceInspector.kt` и A2
 * `usb/discovery/UsbInterfaceSelector.kt`.
 *
 * Классификатор ничего не запрещает. Он лишь упорядочивает кандидатов по
 * убыванию уверенности; отказать может только само устройство.
 */
public object UsbInterfaceClassifier {
    /** Vendor-specific класс интерфейса. */
    public const val USB_CLASS_VENDOR_SPECIFIC: Int = 0xFF

    /** Подкласс, используемый Android для ADB и Fastboot. */
    public const val ANDROID_USB_SUBCLASS: Int = 0x42

    /** Протокол канонического ADB-интерфейса. */
    public const val ADB_INTERFACE_PROTOCOL: Int = 0x01

    /** Протокол канонического Fastboot-интерфейса. */
    public const val FASTBOOT_INTERFACE_PROTOCOL: Int = 0x03

    /**
     * Пригодные интерфейсы устройства — только лучшего доступного уровня.
     *
     * Уровни ниже победившего подавляются намеренно. Если устройство
     * предъявляет канонический ADB, трактовать соседние vendor-интерфейсы как
     * возможный Fastboot — это шум, который превратил бы однозначный выбор в
     * ложную неоднозначность. Поведение перенесено из A2 без изменений.
     *
     * Порядок предпочтения: канонический ADB, канонический Fastboot,
     * Android-совместимый, generic vendor.
     *
     * Возвращаются только интерфейсы с парой bulk-эндпоинтов: без неё транспорт
     * невозможен технически, а не «не разрешён».
     *
     * @param allowGenericVendor включать ли интерфейсы уровня
     *   [UsbMatchConfidence.GENERIC_VENDOR]. Нужен для OEM Fastboot с
     *   нестандартными дескрипторами.
     */
    public fun candidates(
        device: UsbDeviceDescriptor,
        allowGenericVendor: Boolean = true,
    ): List<UsbInterfaceCandidate> {
        val classified = device.interfaces.mapIndexedNotNull { index, usbInterface ->
            classifyInterface(device, index, usbInterface, allowGenericVendor)
        }
        val tiers = listOf(
            classified.filter {
                it.confidence == UsbMatchConfidence.CANONICAL && it.kind == UsbInterfaceKind.ADB
            },
            classified.filter {
                it.confidence == UsbMatchConfidence.CANONICAL && it.kind == UsbInterfaceKind.FASTBOOT
            },
            classified.filter { it.confidence == UsbMatchConfidence.ANDROID_COMPATIBLE },
            classified.filter { it.confidence == UsbMatchConfidence.GENERIC_VENDOR },
        )
        val winning = tiers.firstOrNull { it.isNotEmpty() } ?: return emptyList()
        return winning.sortedBy { it.interfaceIndex }
    }

    /**
     * Наиболее вероятный интерфейс устройства, либо `null`, если пригодных нет.
     *
     * Это первый интерфейс победившего уровня по возрастанию индекса —
     * совпадает с проверенным поведением Legacy и A2.
     */
    public fun primaryCandidate(
        device: UsbDeviceDescriptor,
        allowGenericVendor: Boolean = true,
    ): UsbInterfaceCandidate? = candidates(device, allowGenericVendor).firstOrNull()

    /**
     * Кандидаты по нескольким устройствам, без дубликатов и в устойчивом порядке.
     *
     * Устойчивость важна для UI: перечисление не должно «прыгать» между
     * одинаковыми по сути сканированиями.
     */
    public fun candidates(
        devices: Collection<UsbDeviceDescriptor>,
        allowGenericVendor: Boolean = true,
    ): List<UsbInterfaceCandidate> = devices
        .flatMap { candidates(it, allowGenericVendor) }
        .distinctBy { it.attachmentKey }
        .sortedWith(
            compareBy<UsbInterfaceCandidate> { it.confidence.rank }
                .thenBy { it.kind.name }
                .thenBy { it.device.productName ?: it.device.deviceName }
                .thenBy { it.device.deviceName }
                .thenBy { it.interfaceIndex },
        )

    /**
     * Повторно находит ранее выбранный интерфейс в свежем дескрипторе того же
     * подключения.
     *
     * Нужен после получения permission: платформа отдаёт новый объект
     * дескриптора, и выбранный интерфейс надо привязать заново, не потеряв
     * прежний выбор. Возвращает `null`, если это другое подключение или
     * прежний интерфейс исчез.
     */
    public fun rebind(
        device: UsbDeviceDescriptor,
        previous: UsbInterfaceCandidate,
    ): UsbInterfaceCandidate? {
        if (device.deviceName != previous.device.deviceName) return null
        val allowGenericVendor = previous.confidence == UsbMatchConfidence.GENERIC_VENDOR

        val sameIndex = device.interfaces.getOrNull(previous.interfaceIndex)?.let { usbInterface ->
            classifyInterface(device, previous.interfaceIndex, usbInterface, allowGenericVendor)
        }
        if (sameIndex != null &&
            sameIndex.kind == previous.kind &&
            sameIndex.confidence == previous.confidence
        ) {
            return sameIndex
        }

        val fresh = candidates(device, allowGenericVendor)
        return fresh.firstOrNull {
            it.kind == previous.kind && it.profileSignature == previous.profileSignature
        } ?: fresh.singleOrNull {
            it.kind == previous.kind && it.confidence == previous.confidence
        }
    }

    /**
     * Единственный изменившийся профиль среди подключённых устройств.
     *
     * Используется после detach при ожидаемой смене режима: устройство
     * перечисляется заново с другим профилем. Неоднозначность не разрешается
     * автоматически — при нескольких изменившихся профилях возвращается `null`,
     * потому что угадывать, какой из них тот самый, значит рисковать чужим
     * устройством.
     *
     * @param previousVendorId если задан, рассматриваются только устройства
     *   этого производителя.
     */
    public fun modeSwitchCandidate(
        devices: Collection<UsbDeviceDescriptor>,
        previousProfileSignature: String?,
        previousVendorId: Int? = null,
    ): UsbInterfaceCandidate? = candidates(devices, allowGenericVendor = true)
        .filter { previousProfileSignature == null || it.profileSignature != previousProfileSignature }
        .filter { previousVendorId == null || it.device.vendorId == previousVendorId }
        .singleOrNull()

    private fun classifyInterface(
        device: UsbDeviceDescriptor,
        interfaceIndex: Int,
        usbInterface: UsbInterfaceDescriptor,
        allowGenericVendor: Boolean,
    ): UsbInterfaceCandidate? {
        val endpoints = bulkEndpointPair(usbInterface) ?: return null
        val (kind, confidence) = when {
            isCanonicalAdb(usbInterface) ->
                UsbInterfaceKind.ADB to UsbMatchConfidence.CANONICAL

            isCanonicalFastboot(usbInterface) ->
                UsbInterfaceKind.FASTBOOT to UsbMatchConfidence.CANONICAL

            isAndroidCompatible(usbInterface) ->
                UsbInterfaceKind.FASTBOOT to UsbMatchConfidence.ANDROID_COMPATIBLE

            allowGenericVendor && isGenericVendorBulkPair(usbInterface) ->
                UsbInterfaceKind.FASTBOOT to UsbMatchConfidence.GENERIC_VENDOR

            else -> return null
        }
        return UsbInterfaceCandidate(
            device = device,
            kind = kind,
            confidence = confidence,
            interfaceIndex = interfaceIndex,
            interfaceId = usbInterface.id,
            interfaceClass = usbInterface.interfaceClass,
            interfaceSubclass = usbInterface.interfaceSubclass,
            interfaceProtocol = usbInterface.interfaceProtocol,
            endpointIn = endpoints.first,
            endpointOut = endpoints.second,
        )
    }

    private fun isCanonicalAdb(usbInterface: UsbInterfaceDescriptor): Boolean =
        usbInterface.interfaceClass == USB_CLASS_VENDOR_SPECIFIC &&
            usbInterface.interfaceSubclass == ANDROID_USB_SUBCLASS &&
            usbInterface.interfaceProtocol == ADB_INTERFACE_PROTOCOL

    private fun isCanonicalFastboot(usbInterface: UsbInterfaceDescriptor): Boolean =
        usbInterface.interfaceClass == USB_CLASS_VENDOR_SPECIFIC &&
            usbInterface.interfaceSubclass == ANDROID_USB_SUBCLASS &&
            usbInterface.interfaceProtocol == FASTBOOT_INTERFACE_PROTOCOL

    private fun isAndroidCompatible(usbInterface: UsbInterfaceDescriptor): Boolean =
        usbInterface.interfaceClass == USB_CLASS_VENDOR_SPECIFIC &&
            usbInterface.interfaceSubclass == ANDROID_USB_SUBCLASS &&
            usbInterface.interfaceProtocol != ADB_INTERFACE_PROTOCOL &&
            usbInterface.interfaceProtocol != FASTBOOT_INTERFACE_PROTOCOL

    private fun isGenericVendorBulkPair(usbInterface: UsbInterfaceDescriptor): Boolean =
        usbInterface.interfaceClass == USB_CLASS_VENDOR_SPECIFIC &&
            usbInterface.interfaceProtocol != ADB_INTERFACE_PROTOCOL

    /**
     * Первая пара bulk IN и bulk OUT в порядке объявления эндпоинтов.
     *
     * Порядок объявления сохраняется намеренно: он совпадает с проверенным
     * поведением Legacy и A2 на реальных устройствах.
     */
    private fun bulkEndpointPair(
        usbInterface: UsbInterfaceDescriptor,
    ): Pair<UsbEndpointDescriptor, UsbEndpointDescriptor>? {
        var input: UsbEndpointDescriptor? = null
        var output: UsbEndpointDescriptor? = null
        for (endpoint in usbInterface.endpoints) {
            if (endpoint.transferType != UsbTransferType.BULK) continue
            when (endpoint.direction) {
                UsbEndpointDirection.IN -> if (input == null) input = endpoint
                UsbEndpointDirection.OUT -> if (output == null) output = endpoint
            }
        }
        return if (input != null && output != null) input to output else null
    }
}
