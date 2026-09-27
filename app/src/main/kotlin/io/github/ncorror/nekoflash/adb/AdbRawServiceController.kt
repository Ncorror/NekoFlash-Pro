package io.github.ncorror.nekoflash.adb

import io.github.ncorror.nekoflash.protocol.adb.AdbConnection
import io.github.ncorror.nekoflash.protocol.adb.AdbRebootOutcome
import io.github.ncorror.nekoflash.protocol.adb.AdbServiceOutcome
import io.github.ncorror.nekoflash.protocol.adb.AdbServicePolicy
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Вызов произвольного сервиса ADB.
 *
 * Сервис задаётся целиком и не проверяется: `shell:ls`, `sync:`, `exec:id`,
 * `track-devices` и всё, чего мы не знаем. Список отсутствует намеренно
 * (`01` §3) — какие сервисы есть, знает устройство, и его отказ это ответ, а
 * не наша ошибка.
 *
 * **Односторонний сервис узнаётся по имени.** Набранный здесь `reboot:` ведёт
 * себя как нажатие «Перезагрузить»: устройство уходит с шины, не ответив, и
 * показать это отказом значило бы соврать. Правило пришло из Legacy
 * (`AdbServiceCompletionPolicy`), где оно тоже применялось к имени сервиса, а
 * не к тому, откуда пришёл запрос.
 */
public class AdbRawServiceController(private val executor: Executor) {
    private val mutableState = MutableStateFlow<AdbRawServiceState>(AdbRawServiceState.None)

    /** Состояние последнего вызова. */
    public val state: StateFlow<AdbRawServiceState> = mutableState.asStateFlow()

    /** Идёт ли вызов прямо сейчас. */
    public val active: Boolean
        get() = mutableState.value is AdbRawServiceState.Running

    /** Вызывает сервис. Пустое имя — не ошибка, а нечего делать. */
    public fun call(connection: AdbConnection, service: String) {
        val trimmed = service.trim()
        if (trimmed.isEmpty() || active) return
        mutableState.value = AdbRawServiceState.Running(trimmed)
        executor.execute {
            mutableState.value = runCatching { invoke(connection, trimmed) }
                .getOrElse { failure -> crashed(trimmed, failure) }
        }
    }

    private fun invoke(connection: AdbConnection, service: String): AdbRawServiceState =
        if (AdbServicePolicy.expectsOneWayDisconnect(service)) {
            oneWay(service, connection.reboot(AdbServicePolicy.rebootTargetOf(service)))
        } else {
            plain(service, connection.call(service))
        }

    private fun oneWay(service: String, outcome: AdbRebootOutcome): AdbRawServiceState =
        when (outcome) {
            is AdbRebootOutcome.Accepted -> AdbRawServiceState.OneWay(service, outcome.evidence)

            is AdbRebootOutcome.Failed -> AdbRawServiceState.Failed(
                service = service,
                reason = "${outcome.reason.name} (${outcome.device.name}): ${outcome.detail}",
            )
        }

    /**
     * Обычный сервис.
     *
     * Размер вывода показывается всегда и отдельно от текста: сервис может
     * отвечать не текстом вовсе — `framebuffer:` отдаёт пиксели, — и тогда
     * единственное честное, что мы о нём знаем, это сколько байт пришло.
     */
    private fun plain(service: String, outcome: AdbServiceOutcome): AdbRawServiceState =
        when (outcome) {
            is AdbServiceOutcome.Completed -> AdbRawServiceState.Finished(
                service = service,
                bytes = outcome.output.size,
                text = outcome.text().take(MAX_SHOWN_CHARS),
            )

            is AdbServiceOutcome.Failed -> AdbRawServiceState.Failed(
                service = service,
                reason = "${outcome.reason.name}: ${outcome.detail}",
            )
        }

    // Исключение здесь — программистская ошибка, а не протокольный исход.
    private fun crashed(service: String, failure: Throwable): AdbRawServiceState =
        AdbRawServiceState.Failed(service, failure.message ?: failure.javaClass.simpleName)

    private companion object {
        /** Сколько символов вывода показывается: экран не журнал. */
        const val MAX_SHOWN_CHARS = 4 * 1024
    }
}
