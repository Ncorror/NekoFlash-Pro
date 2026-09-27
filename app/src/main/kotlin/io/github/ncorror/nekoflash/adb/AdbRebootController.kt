package io.github.ncorror.nekoflash.adb

import io.github.ncorror.nekoflash.protocol.adb.AdbConnection
import io.github.ncorror.nekoflash.protocol.adb.AdbRebootDevice
import io.github.ncorror.nekoflash.protocol.adb.AdbRebootOutcome
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Владелец запросов перезагрузки.
 *
 * Отдельный класс по той же причине, что оболочка и файловые операции: у
 * запроса своё время жизни, и мешать его с жизнью транспорта не нужно. Здесь
 * это особенно заметно — успешная перезагрузка **убивает** транспорт, и
 * соединение узнает об этом само, своим путём.
 *
 * Класс ничего не решает про протокол: разбор одностороннего сервиса живёт в
 * `AdbReboot`, а сюда приходит уже готовый исход.
 */
public class AdbRebootController(private val executor: Executor) {
    private val mutableState = MutableStateFlow<AdbRebootState>(AdbRebootState.None)

    /** Состояние последнего запроса. */
    public val state: StateFlow<AdbRebootState> = mutableState.asStateFlow()

    /** Идёт ли запрос прямо сейчас. */
    public val active: Boolean
        get() = mutableState.value is AdbRebootState.Running

    /**
     * Просит устройство перезагрузиться.
     *
     * Цель не проверяется и не ограничивается списком (`01` §3): какие цели
     * существуют, знает устройство, и его отказ — это ответ, а не наша ошибка.
     */
    public fun request(connection: AdbConnection, target: String) {
        if (active) return
        val trimmed = target.trim()
        mutableState.value = AdbRebootState.Running(trimmed)
        executor.execute {
            // Журналом заведует само соединение: `reboot_requested`,
            // `reboot_accepted` и `reboot_failed` пишет `AdbReboot`.
            val attempt = runCatching { connection.reboot(trimmed) }
            mutableState.value = finished(trimmed, attempt)
        }
    }

    private fun finished(
        target: String,
        attempt: Result<AdbRebootOutcome>,
    ): AdbRebootState = when (val outcome = attempt.getOrNull()) {
        is AdbRebootOutcome.Accepted -> AdbRebootState.Accepted(target, outcome.service)

        is AdbRebootOutcome.Failed -> AdbRebootState.Failed(
            target = target,
            reason = "${outcome.reason.name}: ${outcome.detail}",
            device = outcome.device,
        )

        // Исключение здесь — программистская ошибка, а не протокольный исход.
        // Устройство при этом могло уже уйти в перезагрузку, поэтому UNKNOWN.
        null -> AdbRebootState.Failed(
            target = target,
            reason = attempt.exceptionOrNull()
                ?.let { failure -> failure.message ?: failure.javaClass.simpleName }
                ?: "unknown",
            device = AdbRebootDevice.UNKNOWN,
        )
    }
}
