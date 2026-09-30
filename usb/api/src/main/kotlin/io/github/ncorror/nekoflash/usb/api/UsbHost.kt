package io.github.ncorror.nekoflash.usb.api

/**
 * Платформенная часть работы с USB, вынесенная за интерфейс.
 *
 * Существует ради того, чтобы связующая логика оставалась проверяемой: с
 * настоящим `UsbManager` её нельзя выполнить без устройства, а с подставным
 * хостом — можно.
 *
 * Хост не принимает решений. Он сообщает факты и выполняет запрошенное;
 * классификация, идентичность, владение сессиями и разбор ответа на запрос
 * разрешения живут в этом же модуле и покрыты тестами.
 */
public interface UsbHost {
    /** Наблюдатель за событиями платформы. */
    public interface Listener {
        /** Устройство подключено. */
        public fun onDeviceAttached(device: UsbDeviceDescriptor)

        /** Устройство отключено. Handle прошлой сессии после этого непригоден. */
        public fun onDeviceDetached(device: UsbDeviceDescriptor)

        /**
         * Пришёл ответ на запрос разрешения.
         *
         * [device] может отсутствовать: система вправе ответить без
         * дескриптора.
         */
        public fun onPermissionResult(device: UsbDeviceDescriptor?, granted: Boolean)
    }

    /** Начинает наблюдение. Повторный вызов заменяет предыдущего наблюдателя. */
    public fun start(listener: Listener)

    /** Прекращает наблюдение. Повторный вызов безопасен. */
    public fun stop()

    /** Дескрипторы всех подключённых сейчас устройств. */
    public fun devices(): List<UsbDeviceDescriptor>

    /**
     * Выдано ли разрешение на устройство прямо сейчас.
     *
     * Возвращает `false` для устройства, которого уже нет: отсутствующее
     * устройство не имеет разрешения, и это не ошибка.
     */
    public fun hasPermission(device: UsbDeviceDescriptor): Boolean

    /**
     * Запрашивает разрешение на устройство.
     *
     * Возвращает `false`, если устройство исчезло между обнаружением и
     * запросом. Ответ приходит в [Listener.onPermissionResult].
     */
    public fun requestPermission(device: UsbDeviceDescriptor): Boolean

    /**
     * Захватывает интерфейс устройства.
     *
     * Захват исключителен: пока интерфейс удерживается, другой владелец его не
     * получит. Освобождение — обязанность вызывающего через
     * [UsbTransportHandle.close].
     *
     * Отказ платформы возвращается как есть и не подменяется собственным
     * умолчанием.
     */
    public fun claim(candidate: UsbInterfaceCandidate): UsbClaimResult
}
