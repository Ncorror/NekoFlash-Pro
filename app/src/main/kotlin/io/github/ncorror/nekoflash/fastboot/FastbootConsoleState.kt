package io.github.ncorror.nekoflash.fastboot

import io.github.ncorror.nekoflash.protocol.fastboot.FastbootLaneState
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootMutationOutcome
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootReply
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootVariableSnapshot

/**
 * Что вышло из последнего обмена по Fastboot.
 *
 * Состояние полосы входит в каждый исход, кроме простоя, и это не отладочная
 * подробность: по нему видно, можно ли слать следующую команду. У Fastboot
 * полоса одна (ADR-0006 §1), и потеря рамки — липкая, поэтому скрыть её от
 * оператора значило бы дать ему нажимать кнопку, которая заведомо не сработает.
 */
public sealed interface FastbootConsoleState {
    /**
     * Полоса на момент исхода, либо `null`, пока обмена не было.
     *
     * Объявлена здесь, а не только в каждом исходе, чтобы экран мог сказать про
     * потерянную рамку **один раз** и в одном месте, не перебирая исходы. До
     * `07` §6.89 перебирать было незачем: слово `STALLED` печаталось, а что с
     * ним делать — нет.
     */
    public val lane: FastbootLaneState? get() = null

    /** Ничего не отправлялось. */
    public data object Idle : FastbootConsoleState

    /**
     * Команда ушла, ответа ещё нет.
     *
     * [startedAtMillis] — когда её отправили. Нужен затем, что ожидание здесь
     * бывает долгим по построению: терпение на фазу данных — две минуты, и
     * неподвижная строка «ждём ответа» за это время читается как зависание.
     * Прогон `07` §6.95 оборвали на 68-й секунде именно поэтому.
     */
    public data class Running(
        val command: String,
        val startedAtMillis: Long,
    ) : FastbootConsoleState

    /**
     * Устройство ответило терминально.
     *
     * `FAIL` приходит сюда же, как и `OKAY`: отказ — это ответ устройства
     * (`03` §2), и показывать его иначе, чем согласие, значило бы подменять
     * слово peer'а своей оценкой. Отличаются они [reply], а не местом.
     */
    public data class Answered(
        val command: String,
        val reply: FastbootReply,
        val payload: String,
        val info: List<String>,
        override val lane: FastbootLaneState,
    ) : FastbootConsoleState

    /**
     * Ответа не получили либо команду не отправили.
     *
     * От [Answered] с `FAIL` отличается принципиально: там устройство сказало
     * «нет», здесь мы не знаем, что оно сказало.
     */
    public data class NotAnswered(
        val command: String,
        val detail: String,
        override val lane: FastbootLaneState,
    ) : FastbootConsoleState

    /**
     * Исход загрузки полезной нагрузки в буфер устройства.
     *
     * [untouched] отвечает на единственный вопрос, который здесь важен: можно
     * ли утверждать, что состояние устройства не изменилось. Утверждать это
     * можно **только** при отказе до фазы данных; во всех остальных случаях
     * буфер загрузки содержит неизвестно что, и прошивать из него нельзя
     * (`03` §3).
     */
    public data class Downloaded(
        val declaredBytes: Long,
        val sentBytes: Long,
        val reply: FastbootReply?,
        val detail: String,
        val untouched: Boolean,
        override val lane: FastbootLaneState,
    ) : FastbootConsoleState

    /**
     * Исход команды, способной изменить устройство.
     *
     * Исход хранится целиком, а не разложенный по полям, и это не лень:
     * [FastbootMutationOutcome] уже различает случаи, которые нельзя смешивать —
     * отказ устройства, неизвестность и уход по нашей же просьбе, — и
     * пересобирать их в плоскую запись значило бы дать экрану возможность
     * показать «ответа нет» там, где верно «раздел в неизвестном состоянии».
     *
     * Через этот исход идут **все** команды: и набранная руками, и построенная
     * кнопкой. Иначе «один движок» перестал бы быть правдой в самом важном
     * месте — набранный вручную `erase:` читался бы без границы мутации.
     */
    public data class Mutated(
        val outcome: FastbootMutationOutcome,
        override val lane: FastbootLaneState,
    ) : FastbootConsoleState

    /**
     * Исход чтения раздела с устройства — фаза DATA IN.
     *
     * [complete] отвечает на единственный важный здесь вопрос: прочитано всё
     * или часть. Частичное чтение обязано называться частичным на всём пути —
     * недостающий кусок снаружи не отличить от нулей внутри, и выдать одно за
     * другое значило бы соврать о содержимом раздела (`03` §3).
     *
     * Содержимое не хранится: его подтверждают количество байт и отпечаток, как
     * это уже принято для ADB `pull` (`07` §6.32). Сохранение в пользовательское
     * место — artifact sink из Phase 8.
     */
    public data class Fetched(
        val partition: String,
        val bytes: Long,
        val sha256: String,
        val complete: Boolean,
        val detail: String,
        override val lane: FastbootLaneState,
    ) : FastbootConsoleState

    /**
     * Исход плана — последовательности команд как одного действия.
     *
     * Хранится **и** сделанное, **и** место остановки: устройство после обрыва
     * оказывается между «до» и «после», и показать это как неудачу целиком
     * значило бы скрыть сделанное, а как успех — скрыть несделанное.
     */
    public data class Planned(
        val commands: List<String>,
        val applied: Int,
        val stoppedAt: Int?,
        val detail: String,
        override val lane: FastbootLaneState,
    ) : FastbootConsoleState

    /** Ответ на `getvar:all` — разобранный список переменных. */
    public data class Variables(
        val snapshot: FastbootVariableSnapshot,
        override val lane: FastbootLaneState,
    ) : FastbootConsoleState
}
