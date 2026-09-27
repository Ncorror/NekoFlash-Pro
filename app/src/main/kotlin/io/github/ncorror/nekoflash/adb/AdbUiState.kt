package io.github.ncorror.nekoflash.adb

import io.github.ncorror.nekoflash.core.model.SessionGeneration
import io.github.ncorror.nekoflash.protocol.adb.AdbHandshakeFailure
import io.github.ncorror.nekoflash.protocol.adb.AdbPeerMode
import io.github.ncorror.nekoflash.protocol.adb.AdbRebootDevice

/** Что происходит с ADB-соединением прямо сейчас. */
public sealed interface AdbLinkState {
    /** Соединения нет и не запрашивалось. */
    public data object Idle : AdbLinkState

    /** Идёт рукопожатие. */
    public data class Connecting(val generation: SessionGeneration) : AdbLinkState

    /**
     * Публичный ключ хоста уже отправлен, решение об авторизации ещё не пришло.
     *
     * На обычном Android это часто означает системный RSA-диалог, но протокол
     * сам по себе не доказывает наличие UI: Recovery и vendor-сборки могут
     * обрабатывать авторизацию иначе. Состояние поэтому описывает факт на
     * проводе, а не предполагаемое действие пользователя.
     */
    public data class WaitingForAuthorization(val generation: SessionGeneration) : AdbLinkState

    /** Соединение установлено. */
    public data class Connected(
        val generation: SessionGeneration,
        val peerMode: AdbPeerMode,
        val banner: String,
        val features: Set<String>,
    ) : AdbLinkState

    /** Соединение не состоялось. */
    public data class Failed(
        val generation: SessionGeneration,
        val reason: AdbHandshakeFailure,
        val detail: String,
    ) : AdbLinkState
}

/** Что происходит с последней командой. */
public sealed interface AdbCommandState {
    /** Команд ещё не было. */
    public data object None : AdbCommandState

    /** Команда выполняется. */
    public data class Running(val command: String) : AdbCommandState

    /**
     * Команда закончилась.
     *
     * [exitCode] отсутствует, когда устройство не поддерживает `shell,v2`: там
     * кода возврата нет вовсе, и подставлять ноль означало бы сообщить об
     * успехе, о котором ничего не известно.
     */
    public data class Finished(
        val command: String,
        val output: String,
        val errorOutput: String,
        val exitCode: Int?,
    ) : AdbCommandState

    /** Команда не выполнилась. */
    public data class Failed(val command: String, val reason: String) : AdbCommandState
}

/**
 * Что происходит с последним вызовом произвольного сервиса.
 */
public sealed interface AdbRawServiceState {
    /** Сервисов ещё не вызывали. */
    public data object None : AdbRawServiceState

    /** Вызов идёт. */
    public data class Running(val service: String) : AdbRawServiceState

    /**
     * Сервис отработал.
     *
     * [bytes] отдельно от [text] намеренно: ответ может быть не текстом вовсе,
     * и тогда число байт — единственное, что о нём известно достоверно.
     */
    public data class Finished(
        val service: String,
        val bytes: Int,
        val text: String,
    ) : AdbRawServiceState

    /**
     * Односторонний сервис принят.
     *
     * Отдельно от [Finished], потому что вывода здесь нет и не будет:
     * устройство ушло с шины. Показывать это как «отработал, 0 байт» значило бы
     * назвать успешный переход пустым ответом.
     */
    public data class OneWay(val service: String, val evidence: String) : AdbRawServiceState

    /** Сервис не отработал. */
    public data class Failed(val service: String, val reason: String) : AdbRawServiceState
}

/**
 * Что происходит с последним запросом перезагрузки.
 *
 * Состояние **запроса**; что стало с устройством, говорит `AdbRebootDevice`
 * внутри [Failed]. Это разные вопросы: запрос мог не удаться, а устройство при
 * этом уже уходить в перезагрузку.
 */
public sealed interface AdbRebootState {
    /** Перезагрузку ещё не просили. */
    public data object None : AdbRebootState

    /** Запрос отправлен, ждём признака перехода. */
    public data class Running(val target: String) : AdbRebootState

    /** Устройство приняло команду и начало переход. */
    public data class Accepted(val target: String, val service: String) : AdbRebootState

    /**
     * Перехода не подтвердилось.
     *
     * [device] решает, можно ли повторять: `UNTOUCHED` — да, `UNKNOWN` — нет,
     * пока не выяснится, что с устройством.
     */
    public data class Failed(
        val target: String,
        val reason: String,
        val device: AdbRebootDevice,
    ) : AdbRebootState
}
