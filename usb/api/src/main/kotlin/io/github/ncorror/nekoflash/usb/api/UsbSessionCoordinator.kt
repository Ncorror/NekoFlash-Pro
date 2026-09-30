package io.github.ncorror.nekoflash.usb.api

import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticEvent
import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticSink
import io.github.ncorror.nekoflash.core.model.SessionGeneration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

/**
 * Превращает события USB в состояние сессий.
 *
 * Единственная задача — связывание. Классификация, идентичность, владение
 * сессиями и разбор ответа на запрос разрешения находятся в отдельных, уже
 * проверенных местах; здесь только порядок их применения.
 *
 * Границы намеренно узкие. В A2 ровно эта склейка разрослась в класс на 2495
 * строк, потому что туда же попали запуск и остановка транспортов, оркестрация
 * прошивки и Sideload, экспорт диагностики. Всё это принадлежит другим модулям.
 *
 * Координатор не планирует время. Стартовое сканирование, повторные попытки и
 * таймаут ожидания разрешения запускает платформенный слой, вызывая методы
 * этого класса: планирование нельзя выполнить детерминированно в тесте, а всё
 * остальное здесь — можно.
 */
public class UsbSessionCoordinator(
    private val host: UsbHost,
    private val registry: UsbSessionRegistry = UsbSessionRegistry(),
    private val allowGenericVendor: Boolean = true,
    /**
     * Вызывается после успешно отправленного запроса разрешения.
     *
     * Существует ради планирования таймаута: сам координатор время не
     * планирует, потому что планирование нельзя выполнить детерминированно в
     * тесте. Платформенный слой по этому уведомлению заводит отсчёт и по
     * истечении вызывает [onPermissionTimeout].
     */
    private val onPermissionRequested: (SessionGeneration) -> Unit = {},
    /**
     * Куда записываются события USB.
     *
     * Нужен для evidence: `07_TESTING_CI_HARDWARE_EVIDENCE_RU.md` требует
     * структурных логов, а не пересказа увиденного на экране. По умолчанию
     * события никуда не идут — это позволяет использовать координатор в тестах,
     * не собирая журнал.
     */
    private val diagnostics: DiagnosticSink = DiagnosticSink { },
    /** Источник времени. Вынесен, чтобы в тестах отметки были предсказуемы. */
    private val clock: () -> Instant = Instant::now,
) : UsbHost.Listener {
    private val handles = java.util.concurrent.ConcurrentHashMap<Long, UsbTransportHandle>()

    private val mutableLastScan = MutableStateFlow(UsbScanSummary.NEVER_SCANNED)

    /** Незавершённые сессии. Для показа на экране и для владельцев операций. */
    public val sessions: StateFlow<List<UsbSession>>
        get() = registry.activeSessions

    /**
     * Захватывает интерфейс сессии.
     *
     * Требует состояния [UsbSessionState.READY]: захват без выданного
     * разрешения платформа всё равно не выполнит, а попытка выглядела бы в
     * журнале как отказ устройства, которым она не является.
     *
     * Сам координатор ничего не захватывает по своей инициативе: он не знает,
     * есть ли движок, готовый воспользоваться захватом. Решение принимает
     * владелец протокола. С появлением `protocol:adb` захват стал частью
     * открытия транспорта, как и предполагалось здесь раньше и как это было
     * устроено в Legacy: `AdbLinkController` подключается сам, ограничиваясь
     * тем, что разрешает `UsbAutoConnectPolicy`.
     */
    public fun claim(generation: SessionGeneration): UsbClaimResult {
        val session = registry.session(generation)
        return when {
            session == null || session.closed -> {
                emit("claim_rejected_session_unavailable", session)
                UsbClaimResult.Failed(UsbClaimFailure.DEVICE_GONE)
            }

            session.state != UsbSessionState.READY && session.state != UsbSessionState.CLAIMED -> {
                emit(
                    message = "claim_rejected_not_ready",
                    session = session,
                    fields = mapOf("required" to UsbSessionState.READY.name),
                )
                UsbClaimResult.Failed(UsbClaimFailure.OPEN_REFUSED)
            }

            else -> claimReadySession(generation, session)
        }
    }

    private fun claimReadySession(generation: SessionGeneration, session: UsbSession): UsbClaimResult =
        when (val result = host.claim(session.candidate)) {
            is UsbClaimResult.Claimed -> {
                handles.put(generation.value, result.handle)?.close()
                emit("interface_claimed", registry.markClaimed(generation).or(session))
                result
            }

            is UsbClaimResult.Failed -> {
                emit(
                    message = "claim_failed",
                    session = session,
                    fields = mapOf("reason" to result.reason.name),
                )
                result
            }
        }

    /**
     * Освобождает удерживаемый интерфейс, не закрывая сессию.
     *
     * Устройство остаётся подключённым, разрешение — выданным, generation той
     * же: меняется лишь то, держим мы интерфейс или нет. Возможность отпустить
     * устройство обязательна — удерживать его без способа освободить было бы
     * ограничением, созданным приложением.
     */
    public fun release(generation: SessionGeneration) {
        val handle = handles.remove(generation.value)
        handle?.close()
        val session = registry.session(generation) ?: return
        if (session.state != UsbSessionState.CLAIMED) return
        emit("interface_released", registry.markReady(generation).or(session))
    }

    /** Недавно завершённые сессии. Нужны отчёту после отключения устройства. */
    public fun recentlyClosedSessions(): List<UsbSession> = registry.recentlyClosedSessions()

    /** Начинает наблюдение и сразу разбирает уже подключённые устройства. */
    public fun start() {
        host.start(this)
        scanAttachedDevices()
    }

    /**
     * Итог последнего разбора подключённых устройств.
     *
     * Отдельно от списка сессий: пустой список не отвечает на вопрос, есть ли
     * устройство вообще. Экран, показывающий «устройств нет», обязан уметь
     * сказать, чего именно нет — устройства или подходящего интерфейса.
     */
    public val lastScan: StateFlow<UsbScanSummary> = mutableLastScan.asStateFlow()

    /** Прекращает наблюдение. Открытые сессии не закрываются: устройства никуда не делись. */
    public fun stop() {
        host.stop()
    }

    /**
     * Разбирает устройства, подключённые прямо сейчас.
     *
     * Устройство, для которого уже есть незавершённая сессия, пропускается:
     * повторное открытие вытеснило бы работающую сессию новой без причины.
     */
    public fun scanAttachedDevices() {
        val known = registry.activeSessions.value.map { it.candidate.device.deviceName }.toSet()
        val visible = host.devices()
        val fresh = visible.filterNot { it.deviceName in known }
        // Итог скана записывается всегда, в том числе когда система не отдала
        // ни одного устройства. Молчание в этом месте невозможно отличить от
        // сломанного приложения: разбор 2026-09-04 упёрся ровно в это —
        // выгруженный evidence был пуст, и по нему нельзя было сказать, видит
        // ли система устройство вообще.
        mutableLastScan.value = UsbScanSummary(
            visibleDevices = visible.size,
            newDevices = fresh.size,
            knownDevices = visible.size - fresh.size,
        )
        emit(
            message = "scan_completed",
            fields = mapOf(
                "visibleDevices" to visible.size.toString(),
                "newDevices" to fresh.size.toString(),
                "knownDevices" to (visible.size - fresh.size).toString(),
            ),
        )
        fresh.forEach(::onDeviceAttached)
    }

    override fun onDeviceAttached(device: UsbDeviceDescriptor) {
        val candidate = UsbInterfaceClassifier.primaryCandidate(device, allowGenericVendor)
        if (candidate == null) {
            emit(
                message = "device_ignored_no_usable_interface",
                fields = deviceFields(device),
            )
            return
        }
        val session = registry.open(UsbTargetIdentity.fromDescriptor(device), candidate)
        emit(
            message = "session_opened",
            session = session,
            fields = deviceFields(device) + candidateFields(candidate),
        )
        if (host.hasPermission(device)) {
            emit("permission_already_granted", registry.markReady(session.generation).or(session))
            return
        }
        val pending = registry.markPermissionPending(session.generation).or(session)
        if (host.requestPermission(device)) {
            onPermissionRequested(session.generation)
            emit("permission_requested", pending)
        } else {
            registry.close(session.generation, UsbSessionClosureReason.DETACHED)
            emit("permission_request_failed_device_gone", pending)
        }
    }

    override fun onDeviceDetached(device: UsbDeviceDescriptor) {
        val closed = registry.closeDetached(device)
        if (closed.isEmpty()) {
            emit("detached_without_session", fields = deviceFields(device))
            return
        }
        closed.forEach { session ->
            // Удерживаемый интерфейс отпускается вместе с сессией: держать
            // ресурс отключённого устройства бессмысленно, а handle прошлой
            // generation всё равно непригоден.
            handles.remove(session.generation.value)?.close()
            emit("session_closed_detached", session)
        }
    }

    override fun onPermissionResult(device: UsbDeviceDescriptor?, granted: Boolean) {
        val pending = registry.activeSessions.value
            .filter { it.state == UsbSessionState.PERMISSION_PENDING }
        when (val decision = UsbPermissionPolicy.resolve(pending, device, granted)) {
            is UsbPermissionPolicy.Decision.Proceed -> proceed(decision)
            is UsbPermissionPolicy.Decision.Denied -> {
                handles.remove(decision.session.generation.value)?.close()
                val closed = registry.close(
                    decision.session.generation,
                    UsbSessionClosureReason.PERMISSION_DENIED,
                )
                emit("permission_denied", closed.or(decision.session))
            }

            UsbPermissionPolicy.Decision.MissingDevice ->
                emit("permission_answer_without_device")

            UsbPermissionPolicy.Decision.NoCandidate ->
                emit("permission_answer_no_usable_interface")

            UsbPermissionPolicy.Decision.UnmatchedDenial ->
                emit("permission_denial_without_session")
        }
    }

    /**
     * Обрабатывает истечение ожидания разрешения.
     *
     * Вызывается платформенным слоем через
     * [UsbPermissionPolicy.RESPONSE_TIMEOUT_MS] после запроса. Возвращает
     * принятое решение, чтобы вызывающий мог сообщить о нём пользователю: молча
     * закрытая сессия выглядела бы как исчезнувшее без причины устройство.
     */
    public fun onPermissionTimeout(
        generation: SessionGeneration,
        permissionGrantedNow: Boolean,
    ): UsbPermissionPolicy.TimeoutOutcome {
        val session = registry.session(generation)
        val outcome = UsbPermissionPolicy.onTimeout(session, permissionGrantedNow)
        if (outcome != UsbPermissionPolicy.TimeoutOutcome.IGNORE) {
            registry.close(generation, UsbSessionClosureReason.PERMISSION_TIMED_OUT)
        }
        emit(
            message = "permission_timeout",
            session = session,
            fields = mapOf("outcome" to outcome.name),
        )
        return outcome
    }

    private fun proceed(decision: UsbPermissionPolicy.Decision.Proceed) {
        val existing = decision.session
        if (existing == null) {
            val opened = registry.open(
                identity = UsbTargetIdentity.fromDescriptor(decision.candidate.device),
                candidate = decision.candidate,
            )
            emit("permission_granted_without_request", registry.markReady(opened.generation).or(opened))
            return
        }
        registry.refresh(existing.generation, decision.candidate)
        val updated = registry.markReady(existing.generation).or(existing)
        emit("permission_granted", updated)
        if (updated.identity.source != existing.identity.source) {
            emit(
                message = "identity_refined",
                session = updated,
                fields = mapOf(
                    "from" to existing.identity.source.name,
                    "to" to updated.identity.source.name,
                ),
            )
        }
    }

    /**
     * Состояние сессии после применённого перехода.
     *
     * Событие обязано сообщать состояние **после** перехода, а не до него.
     * Снимок, взятый заранее, попадал в evidence как текущее состояние и врал:
     * `permission_requested` сообщал `DISCOVERED`, а `permission_granted` —
     * `PERMISSION_PENDING`. Отчёт, который называет прошлое настоящим, хуже
     * отсутствующего.
     *
     * Если переход отклонён, остаётся прежний снимок: отклонение само по себе
     * состояния не меняет.
     */
    private fun UsbSessionTransition.or(fallback: UsbSession): UsbSession =
        (this as? UsbSessionTransition.Applied)?.session ?: fallback

    private fun emit(
        message: String,
        session: UsbSession? = null,
        fields: Map<String, String> = emptyMap(),
    ) {
        diagnostics.emit(
            DiagnosticEvent(
                timestamp = clock(),
                category = DIAGNOSTIC_CATEGORY,
                message = message,
                targetId = session?.targetId,
                sessionGeneration = session?.generation,
                fields = if (session == null) fields else fields + ("state" to session.state.name),
            ),
        )
    }

    private fun deviceFields(device: UsbDeviceDescriptor): Map<String, String> = mapOf(
        "connection" to device.deviceName,
        "vendorId" to device.vendorId.toHex(),
        "productId" to device.productId.toHex(),
    )

    private fun candidateFields(candidate: UsbInterfaceCandidate): Map<String, String> = mapOf(
        "interfaceKind" to candidate.kind.name,
        "matchConfidence" to candidate.confidence.name,
        "interfaceIndex" to candidate.interfaceIndex.toString(),
    )

    private fun Int.toHex(): String = "0x%04X".format(this)

    private companion object {
        const val DIAGNOSTIC_CATEGORY = "usb"
    }
}
