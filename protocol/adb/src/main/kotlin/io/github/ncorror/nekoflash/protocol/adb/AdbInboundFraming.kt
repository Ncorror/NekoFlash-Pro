package io.github.ncorror.nekoflash.protocol.adb

/**
 * Правило приёма кадра ADB, доказанное аппаратным прогоном на `vayu`.
 *
 * Обмен устройство → хост разложен на две передачи USB: заголовок в 24 байта и
 * payload, длина которого объявлена этим заголовком. Получатель обязан
 * подготовить **одну** операцию приёма на весь объявленный payload.
 * Положительный короткий результат означает, что короткая передача USB
 * завершилась; продолжать её следующим приёмом нельзя — с этого момента поток
 * протокола недостоверен, и поколение закрывается fail-closed.
 *
 * Так это устроено в AOSP на стороне хоста. Дробление одного payload на
 * произвольные меньшие запросы не является допустимой абстракцией потока на
 * этом уровне: в Legacy (`AdbProtocol.readDataDirect`) payload читался
 * повторными кусками по 16 КиБ, и на железе приём `/tmp/recovery.log` размером
 * 50–65 КиБ детерминированно падал ровно после 32768 байт, унося транспорт
 * раньше, чем удавалось снять baseline перед Sideload. Разбор —
 * `reference/archives/NekoFlash-A2-frozen.zip`, `docs/ADB_INBOUND_USB_FRAMING.md`.
 *
 * Инвариант записан в `docs/03_PROTOCOL_AND_SAFETY_INVARIANTS_RU.md` §4 как
 * correctness invariant нового ядра, а не как архитектура A2.
 */
public object AdbInboundFraming {
    /**
     * Что объявляется peer'у в `CNXN` на хостах до Android 9.
     *
     * Публичные USB Host API до API 28 не принимают одну передачу больше
     * 16 КиБ. Раз получатель не может принять кадр целиком, он не вправе
     * разрешать peer'у такой кадр отправлять: `maxdata` — это обещание
     * получателя, и оно должно быть честным.
     */
    public const val PRE_P_MAX_PAYLOAD_BYTES: Int = 16 * 1024

    /** Что объявляется peer'у начиная с Android 9. */
    public const val MODERN_MAX_PAYLOAD_BYTES: Int = 1_048_576

    /** Уровень API, с которого ограничение в 16 КиБ снято. */
    public const val ANDROID_P_API_LEVEL: Int = 28

    /**
     * Значение `maxdata` для `CNXN` по уровню API хоста.
     *
     * Уровень передаётся параметром: модуль остаётся чистым JVM и проверяемым
     * без Android, а `Build.VERSION.SDK_INT` читается на границе платформы.
     */
    public fun advertisedMaxPayload(apiLevel: Int): Int =
        if (apiLevel >= ANDROID_P_API_LEVEL) MODERN_MAX_PAYLOAD_BYTES else PRE_P_MAX_PAYLOAD_BYTES

    /**
     * Считается ли приём объявленного payload состоявшимся.
     *
     * Единственное допустимое совпадение — точное. Ни «почти всё», ни «больше
     * ожидаемого» кадром не являются.
     */
    public fun payloadReadIsComplete(expectedBytes: Int, actualBytes: Int): Boolean =
        expectedBytes >= 0 && actualBytes == expectedBytes
}
