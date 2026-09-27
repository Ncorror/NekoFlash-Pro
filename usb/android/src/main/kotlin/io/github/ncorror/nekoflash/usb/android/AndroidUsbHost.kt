package io.github.ncorror.nekoflash.usb.android

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import io.github.ncorror.nekoflash.usb.api.UsbDeviceDescriptor
import io.github.ncorror.nekoflash.usb.api.UsbClaimFailure
import io.github.ncorror.nekoflash.usb.api.UsbClaimResult
import io.github.ncorror.nekoflash.usb.api.UsbHost
import io.github.ncorror.nekoflash.usb.api.UsbInterfaceCandidate
import io.github.ncorror.nekoflash.usb.api.UsbPermissionCallbackIdentity
import io.github.ncorror.nekoflash.usb.api.UsbTransferArguments
import io.github.ncorror.nekoflash.usb.api.UsbTransferFailure
import io.github.ncorror.nekoflash.usb.api.UsbTransferResult
import io.github.ncorror.nekoflash.usb.api.UsbTransportHandle

/**
 * Владение USB на стороне Android: перечисление устройств, приёмники
 * подключения и отключения, запрос разрешения.
 *
 * Хост не принимает решений о том, что делать с устройством. Он сообщает
 * наблюдателю факты и выполняет запрошенные действия; классификация,
 * идентичность target, владение сессиями и разбор ответа на запрос разрешения
 * живут в `usb:api` и покрыты тестами.
 *
 * Наблюдение построено на слушателе, а не на `Flow`, а ввод-вывод —
 * блокирующий: корутины появятся там, где они действительно нужны, — в
 * протокольном движке с единственным читающим циклом. Обёртка в `Flow` — задача
 * слоя выше.
 *
 * Контекст должен быть областью приложения: владение USB переживает
 * пересоздание экрана.
 */
public class AndroidUsbHost(
    context: Context,
    private val callbackIdentity: UsbPermissionCallbackIdentity,
) : UsbHost {
    private val appContext: Context = context.applicationContext
    private val usbManager: UsbManager =
        appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    private var listener: UsbHost.Listener? = null
    private var permissionAction: String? = null

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            if (!callbackIdentity.matchesCurrent(intent.action)) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            listener?.onPermissionResult(intent.usbDevice()?.let(AndroidUsbDescriptorMapper::map), granted)
        }
    }

    private val attachDetachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val device = intent?.usbDevice()?.let(AndroidUsbDescriptorMapper::map) ?: return
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> listener?.onDeviceAttached(device)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> listener?.onDeviceDetached(device)
                else -> Unit
            }
        }
    }

    /**
     * Начинает наблюдение и открывает новую активацию.
     *
     * Повторный вызов сначала останавливает предыдущую активацию: два
     * одновременных владельца USB привели бы к двойной обработке одного
     * события.
     */
    override fun start(listener: UsbHost.Listener) {
        stop()
        this.listener = listener
        val action = callbackIdentity.nextAction()
        permissionAction = action
        registerPermissionReceiver(action)
        registerAttachDetachReceiver()
    }

    /**
     * Прекращает наблюдение.
     *
     * Снятие приёмника, который не был зарегистрирован, платформа считает
     * ошибкой, поэтому она подавляется: повторная остановка — обычное дело при
     * завершении, а не сбой.
     */
    override fun stop() {
        if (permissionAction == null) return
        runCatching { appContext.unregisterReceiver(permissionReceiver) }
        runCatching { appContext.unregisterReceiver(attachDetachReceiver) }
        permissionAction = null
        listener = null
    }

    /** Дескрипторы всех подключённых сейчас устройств. */
    override fun devices(): List<UsbDeviceDescriptor> =
        usbManager.deviceList.values.map(AndroidUsbDescriptorMapper::map)

    /**
     * Выдано ли разрешение на устройство прямо сейчас.
     *
     * Возвращает `false`, если устройство уже отключено: отсутствующее
     * устройство не имеет разрешения, и это не ошибка.
     */
    override fun hasPermission(device: UsbDeviceDescriptor): Boolean =
        findDevice(device)?.let { usbManager.hasPermission(it) } == true

    /**
     * Запрашивает разрешение на устройство.
     *
     * Возвращает `false`, если устройство исчезло между обнаружением и
     * запросом. Ответ придёт в [Listener.onPermissionResult]; таймаут ожидания
     * задаёт вызывающий по `UsbPermissionPolicy.RESPONSE_TIMEOUT_MS`.
     */
    override fun requestPermission(device: UsbDeviceDescriptor): Boolean {
        val action = permissionAction ?: return false
        val target = findDevice(device) ?: return false
        val intent = Intent(action).setPackage(appContext.packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or mutabilityFlag()
        val pendingIntent = PendingIntent.getBroadcast(
            appContext,
            target.deviceId,
            intent,
            flags,
        )
        usbManager.requestPermission(target, pendingIntent)
        return true
    }

    /**
     * Захватывает интерфейс устройства.
     *
     * Захват принудительный: вендорный интерфейс на некоторых хостах занят
     * ядерным драйвером, и без принудительного отбора ADB и Fastboot не
     * работают. Это отбор у драйвера, который всё равно не говорит по этим
     * протоколам, а не ограничение возможностей пользователя. Так сделано и в
     * Legacy, и в A2.
     *
     * При неудаче захвата соединение закрывается сразу: оставленное открытым,
     * оно удерживало бы файловый дескриптор без пользы.
     */
    override fun claim(candidate: UsbInterfaceCandidate): UsbClaimResult {
        val device = findDevice(candidate.device)
        return if (device == null) {
            UsbClaimResult.Failed(UsbClaimFailure.DEVICE_GONE)
        } else {
            claimDevice(candidate, device)
        }
    }

    private fun claimDevice(candidate: UsbInterfaceCandidate, device: UsbDevice): UsbClaimResult {
        val usbInterface = device.getInterfaceOrNull(candidate.interfaceIndex)
        return if (usbInterface == null) {
            UsbClaimResult.Failed(UsbClaimFailure.DEVICE_GONE)
        } else {
            claimInterface(candidate, device, usbInterface)
        }
    }

    private fun claimInterface(
        candidate: UsbInterfaceCandidate,
        device: UsbDevice,
        usbInterface: UsbInterface,
    ): UsbClaimResult {
        val endpointIn = usbInterface.findEndpoint(candidate.endpointIn.address)
        val endpointOut = usbInterface.findEndpoint(candidate.endpointOut.address)
        return if (endpointIn == null || endpointOut == null) {
            UsbClaimResult.Failed(UsbClaimFailure.ENDPOINTS_MISSING)
        } else {
            val connection = usbManager.openDevice(device)
            when {
                connection == null -> UsbClaimResult.Failed(UsbClaimFailure.OPEN_REFUSED)

                !connection.claimInterface(usbInterface, true) -> {
                    runCatching { connection.close() }
                    UsbClaimResult.Failed(UsbClaimFailure.INTERFACE_REFUSED)
                }

                else -> UsbClaimResult.Claimed(
                    AndroidUsbTransportHandle(
                        connection,
                        usbInterface,
                        candidate,
                        endpointIn,
                        endpointOut,
                    ),
                )
            }
        }
    }

    private fun UsbDevice.getInterfaceOrNull(index: Int): UsbInterface? =
        if (index in 0 until interfaceCount) getInterface(index) else null

    /**
     * Платформенный эндпоинт по адресу из дескриптора.
     *
     * Поиск идёт по адресу, а не по порядковому номеру: порядок объявления
     * участвовал в подборе пары, но адрес — то, что действительно принадлежит
     * эндпоинту.
     */
    private fun UsbInterface.findEndpoint(address: Int): UsbEndpoint? =
        (0 until endpointCount).map(::getEndpoint).firstOrNull { it.address == address }

    /**
     * Удерживаемый интерфейс на стороне Android.
     *
     * Освобождение защищено от исключений: устройство может исчезнуть между
     * захватом и освобождением, и это обычный ход событий, а не сбой.
     *
     * Решений здесь нет ни одного: проверка окна передачи живёт в `usb:api` и
     * покрыта тестами, а всё, что осталось, — один вызов платформы и перевод
     * его результата в контракт.
     *
     * Признак освобождения объявлен `@Volatile`: приём и передача идут из
     * разных потоков, и освобождение должно быть видно им обоим сразу.
     */
    private class AndroidUsbTransportHandle(
        private val connection: UsbDeviceConnection,
        private val usbInterface: UsbInterface,
        override val candidate: UsbInterfaceCandidate,
        private val endpointIn: UsbEndpoint,
        private val endpointOut: UsbEndpoint,
    ) : UsbTransportHandle {
        @Volatile
        private var released = false

        override val held: Boolean
            get() = !released

        override fun receive(
            destination: ByteArray,
            offset: Int,
            length: Int,
            timeoutMillis: Int,
        ): UsbTransferResult = transfer(endpointIn, destination, offset, length, timeoutMillis)

        override fun send(
            source: ByteArray,
            offset: Int,
            length: Int,
            timeoutMillis: Int,
        ): UsbTransferResult = transfer(endpointOut, source, offset, length, timeoutMillis)

        /**
         * Одна передача — один вызов платформы.
         *
         * Цикла дочитывания здесь нет и не будет: именно он ломал приём в A2.
         * Отрицательный результат платформа отдаёт и на таймаут, и на ошибку, и
         * на исчезнувшее устройство, поэтому он переводится в единственную
         * честную причину, а не в угаданную.
         *
         * Публичные USB Host API до API 28 не принимают передачу больше 16 КиБ.
         * Ограничение платформенное, но живёт оно в протокольной политике,
         * которая объявляет peer'у согласованный `maxdata`: транспорт не вправе
         * решать за протокол, каким должен быть размер рамки.
         */
        private fun transfer(
            endpoint: UsbEndpoint,
            buffer: ByteArray,
            offset: Int,
            length: Int,
            timeoutMillis: Int,
        ): UsbTransferResult {
            UsbTransferArguments.validate(buffer.size, offset, length, timeoutMillis)
            return if (released) {
                UsbTransferResult.Failed(UsbTransferFailure.NOT_HELD)
            } else {
                val transferred = connection.bulkTransfer(endpoint, buffer, offset, length, timeoutMillis)
                val result = bulkTransferResult(transferred)
                if (result !is UsbTransferResult.Completed && endpoint === endpointOut) {
                    clearEndpointHalt(endpoint)
                }
                result
            }
        }

        /**
         * Снимает halted-состояние эндпоинта передачи после неудачной отправки.
         *
         * Перенесено из Legacy (`AdbProtocol.bulkWriteFully`) и A2
         * (`adb/transport/AdbUsbTransport.kt`), где сделано одинаково и с прямо
         * названной причиной: без сброса все последующие передачи на этом
         * эндпоинте продолжают проваливаться после одного сбоя, даже маленькие.
         * В Android USB Host API нет `clearStall`, поэтому шлётся стандартный
         * `CLEAR_FEATURE(ENDPOINT_HALT)` через нулевой эндпоинт.
         *
         * Это не повтор: ни один байт заново не отправляется, а неудача всё
         * равно возвращается вызывающему. Сброс лишь возвращает эндпоинт в
         * состояние, в котором следующая попытка вообще имеет смысл; будет ли
         * она — решает протокольный слой.
         *
         * На приёме halt не снимается: так же поступают оба архива, и читающий
         * слой вместо этого закрывается fail-closed. Придумывать здесь
         * симметрию, которой нет ни в одном источнике, нельзя.
         */
        private fun clearEndpointHalt(endpoint: UsbEndpoint) {
            if (released) return
            runCatching {
                connection.controlTransfer(
                    CLEAR_FEATURE_TO_ENDPOINT,
                    REQUEST_CLEAR_FEATURE,
                    FEATURE_ENDPOINT_HALT,
                    endpoint.address,
                    null,
                    0,
                    CLEAR_HALT_TIMEOUT_MS,
                )
            }
        }

        override fun close() {
            if (released) return
            released = true
            runCatching { connection.releaseInterface(usbInterface) }
            runCatching { connection.close() }
        }

        private companion object {
            /** Стандартный запрос, хост → устройство, получатель — эндпоинт. */
            const val CLEAR_FEATURE_TO_ENDPOINT = 0x02

            /** `bRequest` = `CLEAR_FEATURE`. */
            const val REQUEST_CLEAR_FEATURE = 0x01

            /** `wValue` = `ENDPOINT_HALT`. */
            const val FEATURE_ENDPOINT_HALT = 0x00

            const val CLEAR_HALT_TIMEOUT_MS = 500
        }
    }

    /**
     * Соответствующее платформенное устройство.
     *
     * Сопоставление ведётся по имени подключения: идентификатор может
     * поменяться, если платформа пересоздала дескриптор.
     */
    private fun findDevice(device: UsbDeviceDescriptor): UsbDevice? =
        usbManager.deviceList.values.firstOrNull { it.deviceName == device.deviceName }

    private fun registerPermissionReceiver(action: String) {
        registerReceiverCompat(permissionReceiver, IntentFilter(action))
    }

    /**
     * Подключение и отключение — защищённые системные широковещания. Приёмник
     * регистрируется неэкспортированным: чужое приложение отправить их не
     * может, а системные доходят и до такого приёмника.
     */
    private fun registerAttachDetachReceiver() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        registerReceiverCompat(attachDetachReceiver, filter)
    }

    private fun registerReceiverCompat(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiverBeforeTiramisu(receiver, filter)
        }
    }

    /**
     * До появления флага экспорта приёмник регистрируется обычным путём.
     *
     * Ответ на запрос разрешения доставляется отложенным намерением,
     * ограниченным пакетом приложения, а подключение и отключение — защищённые
     * системные широковещания, поэтому отсутствие флага здесь ничего не
     * открывает наружу.
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Suppress("DEPRECATION")
    private fun registerReceiverBeforeTiramisu(
        receiver: BroadcastReceiver,
        filter: IntentFilter,
    ) {
        appContext.registerReceiver(receiver, filter)
    }

    private fun mutabilityFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0

    private fun Intent.usbDevice(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            legacyUsbDevice()
        }

    @Suppress("DEPRECATION")
    private fun Intent.legacyUsbDevice(): UsbDevice? = getParcelableExtra(UsbManager.EXTRA_DEVICE)
}
