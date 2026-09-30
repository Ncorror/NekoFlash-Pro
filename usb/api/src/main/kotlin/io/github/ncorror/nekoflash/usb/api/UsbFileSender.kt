package io.github.ncorror.nekoflash.usb.api

/**
 * Передача файла целиком в bulk OUT удерживаемого интерфейса — нативный путь
 * USBFS для Fastboot DATA OUT (D077, A2 `NativeUsbfsBackend`).
 *
 * Файл передаётся дескриптором: байты не проходят через Java, URB уходят в
 * ядро по два по 256 КиБ. Путь выбирается **до** `download:` — пока
 * устройство ещё не ждёт данных, — и только если [unavailable] ответил `null`;
 * иначе DATA идёт обычной передачей [UsbTransportHandle.send]. Посреди фазы
 * данных путь не меняется.
 */
public interface UsbFileSender {
    /**
     * Почему этим путём [totalBytes] байт из [payloadFd] сейчас не отправить;
     * `null` — можно. Проверяет библиотеку, состояние backend, дескриптор
     * соединения и то, что [payloadFd] — обычный файл ровно этой длины.
     */
    public fun unavailable(payloadFd: Int, totalBytes: Long): String?

    /**
     * Отправляет [totalBytes] байт [payloadFd] с начала файла. Дескриптор
     * принадлежит вызывающему и закрывается им после возврата.
     *
     * [progress] получает число байт, подтверждённых устройством.
     */
    public fun send(payloadFd: Int, totalBytes: Long, progress: (Long) -> Unit = {}): UsbFileSendResult

    /** Просит остановить идущую передачу; она вернётся после дочитывания URB. */
    public fun cancel(): Boolean
}

/**
 * Исход передачи файла.
 *
 * [confirmedBytes] — только байты, чьи URB устройство подтвердило; отправленное
 * без подтверждения сюда не входит. [detail] — строка backend со стадией,
 * `errno`, состоянием дочитывания и отравления.
 */
public data class UsbFileSendResult(
    val success: Boolean,
    val confirmedBytes: Long,
    val cancelled: Boolean,
    val detail: String,
)
