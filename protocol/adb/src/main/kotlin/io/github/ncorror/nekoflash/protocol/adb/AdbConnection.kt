package io.github.ncorror.nekoflash.protocol.adb

import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticSink
import io.github.ncorror.nekoflash.usb.api.UsbTransportHandle
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Одно ADB-соединение поверх одного захваченного интерфейса.
 *
 * Класс существует ради единственного обещания, которое иначе легко разъехалось
 * бы по трём местам: `maxdata`, объявленный peer'у в `CNXN`, и `maxdata`, по
 * которому читатель отвергает слишком длинный кадр, — это одно и то же число,
 * посчитанное один раз по уровню API. В Legacy эти два значения жили порознь,
 * и именно там начиналась история inbound framing invariant.
 *
 * Соединение одноразовое, как и рукопожатие: следующее — это новый захват
 * интерфейса и новая `SessionGeneration`.
 *
 * Все операции блокирующие. Поток выделяет владелец: контракт требует
 * единственного физического читателя, и выбрать его может только тот, кто
 * знает, где этот поток живёт.
 */
public class AdbConnection(
    private val handle: UsbTransportHandle,
    keyStore: AdbKeyStore,
    apiLevel: Int,
    diagnostics: DiagnosticSink = DiagnosticSink { },
    onPublicKeySent: () -> Unit = { },
) {
    /** Что объявляется peer'у и чем проверяется входящий кадр. */
    public val advertisedMaxPayload: Int = AdbInboundFraming.advertisedMaxPayload(apiLevel)

    private val reader = AdbPacketReader(handle, advertisedMaxPayload)
    private val writer = AdbPacketWriter(handle)

    /**
     * Диспетчер один на соединение, и маршрутизатор внутри него тоже один.
     *
     * Идентификаторы потоков выдаёт маршрутизатор, и второй экземпляр начал бы
     * выдавать их заново — устройство получило бы два разных потока под одним
     * номером. Соединение больше не держит маршрутизатор отдельно: после шага 4
     * плана `docs/adr/0004_CONCURRENT_ADB_DISPATCHER_RU.md` напрямую к нему не
     * обращается никто.
     */
    private val dispatcher = AdbStreamDispatcher(diagnostics = diagnostics)

    private val dispatchLoop = AdbDispatchLoop(reader, writer, dispatcher)

    /**
     * Scope цикла принадлежит соединению.
     *
     * ADR-0003 §2 требует, чтобы каждый запуск принадлежал явному scope, живущему
     * не дольше своей `SessionGeneration`. Соединение одноразовое и кончается
     * вместе с захватом интерфейса, поэтому его scope — ровно та граница.
     */
    private val dispatchScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("adb-dispatch"),
    )

    private val dispatchLock = Any()
    private var dispatchJob: Job? = null

    /**
     * Поднимает цикл раскладки, если он ещё не работает.
     *
     * Запуск отложен до первой потоковой операции намеренно (`ADR-0004` §3):
     * `CNXN`/`AUTH` читает [connect] сам, логических потоков до рукопожатия не
     * существует, и работающий рядом цикл был бы вторым физическим читателем.
     */
    private fun ensureDispatching() {
        synchronized(dispatchLock) {
            if (dispatchJob == null) {
                dispatchJob = dispatchScope.launch { dispatchLoop.run() }
            }
        }
    }

    /**
     * Останавливает цикл и закрывает ящики.
     *
     * Соединение одноразовое: заново цикл не поднимется, а следующее соединение
     * — это новый захват интерфейса.
     */
    public fun close() {
        dispatchLoop.stop()
        dispatchScope.cancel()
    }

    private val services = AdbServiceCall(
        writer = writer,
        dispatcher = dispatcher,
        diagnostics = diagnostics,
    )

    private val reboots = AdbReboot(
        writer = writer,
        dispatcher = dispatcher,
        // Ящик потока хранит первую причину, и поздний обрыв в нём не виден,
        // поэтому судьба транспорта спрашивается отдельно — и у него самого,
        // а не только у цикла (`07` §6.48).
        transportEnd = { AdbTransportEnd.of(dispatchLoop.transportEndedBy, handle.held) },
        diagnostics = diagnostics,
    )

    private val handshake = AdbHandshake(
        reader = reader,
        writer = writer,
        keyStore = keyStore,
        localMaxPayload = advertisedMaxPayload,
        diagnostics = diagnostics,
        onPublicKeySent = onPublicKeySent,
    )

    /**
     * Возможности, объявленные устройством в `CNXN`.
     *
     * До рукопожатия пусто. Хост о своих возможностях не объявляет вовсе, и
     * решение о `shell,v2` принимается только по этому набору — так же, как в
     * Legacy.
     */
    @Volatile
    public var peerFeatures: Set<String> = emptySet()
        private set

    /**
     * Проводит рукопожатие.
     *
     * Блокирует вызывающий поток до ответа устройства или до истечения
     * таймаутов рукопожатия: при ожидании подтверждения диалога это до минуты.
     */
    public fun connect(): AdbHandshakeOutcome = handshake.connect().also { outcome ->
        if (outcome is AdbHandshakeOutcome.Connected) {
            peerFeatures = outcome.banner.features
        }
    }

    /** Поддерживает ли устройство `shell,v2`. */
    public val supportsShellV2: Boolean
        get() = peerFeatures.contains(SHELL_V2_FEATURE)

    /**
     * Выполняет команду в оболочке устройства.
     *
     * При поддержке `shell,v2` идёт через него: он разделяет stdout и stderr и
     * сообщает код возврата. Иначе — обычный `shell:`, где кода возврата нет и
     * подставлять его нельзя.
     *
     * Откат на `shell:` делается и тогда, когда `shell,v2` объявлен, но поток
     * не открылся: устройство может отказать в сервисе, которым похвасталось,
     * и терять из-за этого команду незачем. Так же поступает Legacy.
     */
    public fun shell(
        command: String,
        maxOutputBytes: Int = AdbServiceCall.DEFAULT_MAX_OUTPUT_BYTES,
        timeoutMillis: Int = AdbServiceCall.DEFAULT_TIMEOUT_MS,
    ): AdbShellOutcome {
        ensureDispatching()
        val shellV2 = if (supportsShellV2) {
            shellV2(command, maxOutputBytes, timeoutMillis)
        } else {
            null
        }
        return shellV2 ?: legacyShell(command, maxOutputBytes, timeoutMillis)
    }

    private fun shellV2(
        command: String,
        maxOutputBytes: Int,
        timeoutMillis: Int,
    ): AdbShellOutcome? = when (
        val outcome = services.run(
            service = "shell,v2,raw:$command",
            maxOutputBytes = maxOutputBytes,
            timeoutMillis = timeoutMillis,
            payloadOnOpen = AdbShellProtocol.closeStdinFrame(),
        )
    ) {
        is AdbServiceOutcome.Completed ->
            AdbShellOutcome.Finished(AdbShellProtocol.decode(outcome.output))

        is AdbServiceOutcome.Failed -> if (outcome.reason == AdbServiceFailure.REJECTED) {
            null
        } else {
            AdbShellOutcome.Failed(outcome.reason, outcome.detail)
        }
    }

    private fun legacyShell(
        command: String,
        maxOutputBytes: Int,
        timeoutMillis: Int,
    ): AdbShellOutcome = when (val legacy = services.run("shell:$command", maxOutputBytes, timeoutMillis)) {
        is AdbServiceOutcome.Completed -> AdbShellOutcome.Finished(
            AdbShellOutput(stdout = legacy.text(), stderr = "", exitCode = null),
        )

        is AdbServiceOutcome.Failed -> AdbShellOutcome.Failed(legacy.reason, legacy.detail)
    }

    /**
     * Вызывает сервис и ждёт его вывод.
     *
     * Блокирует вызывающий поток. Вызовы обязаны идти по одному: читатель
     * физически один, и два одновременных вызова разобрали бы пакеты друг
     * друга. Сериализацию обеспечивает владелец соединения — у него для этого
     * есть исполнитель с единственным потоком.
     */
    public fun call(
        service: String,
        maxOutputBytes: Int = AdbServiceCall.DEFAULT_MAX_OUTPUT_BYTES,
        timeoutMillis: Int = AdbServiceCall.DEFAULT_TIMEOUT_MS,
    ): AdbServiceOutcome {
        ensureDispatching()
        return services.run(service, maxOutputBytes, timeoutMillis)
    }

    /**
     * Просит устройство перезагрузиться.
     *
     * Сервис односторонний: устройство обычно рвёт USB, не ответив, и это
     * успех, а не отказ. Разбор — в [AdbReboot]; здесь важно лишь то, что
     * перезагрузка идёт по тому же логическому потоку, что и всё остальное, и
     * соседей не трогает.
     *
     * Цель не ограничивается списком (`01` §3): какие цели существуют, знает
     * устройство.
     */
    public fun reboot(
        target: String,
        timeoutMillis: Int = AdbReboot.DEFAULT_TIMEOUT_MS,
    ): AdbRebootOutcome {
        ensureDispatching()
        return reboots.reboot(target, timeoutMillis)
    }

    /**
     * Открывает живую оболочку.
     *
     * **Соседей она больше не вытесняет.** Здесь стояло, что при живой сессии
     * ни [shell], ни [call] вызывать нельзя: физический читатель был один, и
     * одноразовая команда разобрала бы пакеты сессии. С ADR-0004 читатель
     * по-прежнему один, но принадлежит соединению, а каждый логический поток
     * получил свой ящик. Запрет снят вместе с `busy()` и опровергнут на
     * железе: `07` §6.41 — одноразовые команды при живой оболочке, §6.43 —
     * чтение файла во время трёхминутного `logcat`.
     */
    public fun interactiveShell(diagnostics: DiagnosticSink = DiagnosticSink { }): AdbInteractiveShell {
        ensureDispatching()
        return AdbInteractiveShell(
            writer = writer,
            dispatcher = dispatcher,
            useShellV2 = supportsShellV2,
            diagnostics = diagnostics,
        )
    }

    /**
     * Открывает сессию сервиса `sync:`.
     *
     * Как и интерактивная оболочка, соседям не мешает: это и было целью
     * ADR-0004. Прогон `07` §6.40 держал оболочку девять минут, пока рядом шли
     * три файловые операции, и отпечаток прочитанного совпал с §6.29.
     */
    public fun syncSession(diagnostics: DiagnosticSink = DiagnosticSink { }): AdbSyncSession {
        ensureDispatching()
        return AdbSyncSession(
            writer = writer,
            dispatcher = dispatcher,
            diagnostics = diagnostics,
        )
    }

    /**
     * Драйвер Sideload.
     *
     * Живёт от передачи до передачи, как и сессия `sync:`: держать его между
     * ними незачем, а открытый поток занимал бы место в диспетчере.
     */
    public fun sideloadDriver(diagnostics: DiagnosticSink = DiagnosticSink { }): AdbSideloadDriver {
        ensureDispatching()
        return AdbSideloadDriver(
            writer = writer,
            dispatcher = dispatcher,
            diagnostics = diagnostics,
        )
    }

    /**
     * Запросы обратного проброса.
     *
     * Сами запросы — обычные сервисы, а вот соединения по ним приходят
     * входящими потоками; кому они достаются, назначается через
     * [acceptInboundStreams].
     */
    public fun reverse(diagnostics: DiagnosticSink = DiagnosticSink { }): AdbReverse {
        ensureDispatching()
        return AdbReverse(
            AdbServiceCall(writer = writer, dispatcher = dispatcher, diagnostics = diagnostics),
        )
    }

    /**
     * Назначает получателя потоков, заведённых устройством.
     *
     * `null` — отказывать, и это поведение по умолчанию. Приёмник живёт до
     * конца соединения: потоки приходят, пока устройство слушает.
     */
    public fun acceptInboundStreams(streams: AdbInboundStreams?) {
        dispatcher.inboundStreams(streams)
    }

    /**
     * Заводит одно проброшенное соединение.
     *
     * [channel] — сторона клиента: сокет, который принял слушатель приложения.
     * Протокольный модуль про сокеты не знает, и это не случайность
     * (`docs/adr/0005_LOCAL_SOCKET_FORWARDING_RU.md` §3).
     *
     * Возвращается объект, а не исход: у проброса две стороны, и качать их
     * обязаны два разных потока исполнения. Порядок вызовов описан в
     * [AdbForwardStream].
     *
     * Одновременных пробросов может быть сколько угодно — ровно за этим
     * делался ADR-0004.
     */
    public fun forwardStream(
        channel: AdbByteChannel,
        diagnostics: DiagnosticSink = DiagnosticSink { },
    ): AdbForwardStream {
        ensureDispatching()
        return AdbForwardStream(
            writer = writer,
            dispatcher = dispatcher,
            channel = channel,
            // Кадр крупнее объявленного нами peer отбросит целиком; свой предел
            // мы знаем точно, а чужой — только со слов рукопожатия.
            maxPayload = advertisedMaxPayload.coerceAtMost(AdbForwardStream.MAX_CHUNK_BYTES),
            diagnostics = diagnostics,
        )
    }

    private companion object {
        const val SHELL_V2_FEATURE = "shell_v2"
    }
}

/** Исход команды в оболочке. */
public sealed interface AdbShellOutcome {
    /** Команда отработала; вывод и код возврата внутри. */
    public data class Finished(val output: AdbShellOutput) : AdbShellOutcome

    /** Команда не выполнилась. */
    public data class Failed(val reason: AdbServiceFailure, val detail: String) : AdbShellOutcome
}
