package io.github.ncorror.nekoflash.usb.api

/**
 * Итог одного разбора подключённых устройств.
 *
 * Существует ради вопроса, на который пустой список сессий не отвечает: система
 * не видит устройства или видит, но подходящего интерфейса в нём нет? Разница
 * решающая — в первом случае дело в кабеле, OTG или самом хосте, во втором в
 * режиме устройства или в нашей классификации.
 */
public data class UsbScanSummary(
    /** Сколько USB-устройств отдала система. */
    val visibleDevices: Int,
    /** Из них разобрано впервые. */
    val newDevices: Int,
    /** Из них уже имеют незавершённую сессию. */
    val knownDevices: Int,
    /** Был ли разбор вообще. */
    val scanned: Boolean = true,
) {
    public companion object {
        /**
         * До первого разбора.
         *
         * Отдельное состояние, а не ноль устройств: «ещё не смотрели» и
         * «посмотрели, ничего нет» — разные ответы.
         */
        public val NEVER_SCANNED: UsbScanSummary = UsbScanSummary(
            visibleDevices = 0,
            newDevices = 0,
            knownDevices = 0,
            scanned = false,
        )
    }
}
