package io.github.ncorror.nekoflash.fastboot

import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticEvent
import io.github.ncorror.nekoflash.core.artifact.ArtifactSink
import io.github.ncorror.nekoflash.core.artifact.ArtifactSource
import io.github.ncorror.nekoflash.core.artifact.ArtifactWriteOutcome
import io.github.ncorror.nekoflash.core.artifact.ArtifactWriter
import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticSink
import io.github.ncorror.nekoflash.core.model.SessionGeneration
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootGetVar
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootIdentity
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootLane
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootLaneState
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootXiaomiUnlock
import io.github.ncorror.nekoflash.protocol.fastboot.XiaomiUnlockOutcome
import io.github.ncorror.nekoflash.miunlock.MiUnlockStatus
import io.github.ncorror.nekoflash.miunlock.MiUnlockReporter
import io.github.ncorror.nekoflash.vendor.xiaomi.MiAccountClient
import io.github.ncorror.nekoflash.vendor.xiaomi.MiUnlockClient
import io.github.ncorror.nekoflash.vendor.xiaomi.MiUnlockData
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootLockProbe
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootLockState
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootLockStatus
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootReply
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootMode
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootFetch
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootFetchOutcome
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootModeProbe
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootMutation
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootMutationClass
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootMutationOutcome
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootPlan
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootPlanOutcome
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootVariable
import io.github.ncorror.nekoflash.usb.api.UsbClaimResult
import io.github.ncorror.nekoflash.usb.api.UsbInterfaceKind
import io.github.ncorror.nekoflash.usb.api.UsbSession
import io.github.ncorror.nekoflash.usb.api.UsbSessionState
import io.github.ncorror.nekoflash.usb.api.UsbTransportHandle
import java.time.Instant
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Что сейчас известно про Fastboot-соединение. */
public sealed interface FastbootLinkState {
    /** Соединения нет. */
    public data object Idle : FastbootLinkState

    /** Интерфейс захвачен, роль ещё выясняется. */
    public data class Probing(val generation: SessionGeneration) : FastbootLinkState

    /**
     * Устройство ответило.
     *
     * [identity] может нести и [FastbootMode.UNKNOWN] — это тоже результат
     * опроса, а не его отсутствие: устройство ответило, но роль по ответу не
     * устанавливается, и причина названа в `identity.detail`.
     */
    public data class Connected(
        val generation: SessionGeneration,
        val identity: FastbootIdentity,
        /**
         * Замок **этой** generation.
         *
         * Принадлежит текущей сессии и в новую не переезжает: после
         * перезагрузки старая generation инвалидируется, и замок определяется
         * заново (`03` §5.1). Ничего не разрешает и не запрещает — задаёт
         * форму предупреждения перед разрушающим действием.
         */
        val lock: FastbootLockStatus,
    ) : FastbootLinkState

    /** До обмена дело не дошло. */
    public data class Failed(val generation: SessionGeneration, val detail: String) : FastbootLinkState
}

/** Класс мутации исхода: у каждого варианта он свой, у отправленного заранее — по команде. */
private fun mutationClassOf(outcome: FastbootMutationOutcome): FastbootMutationClass = when (outcome) {
    is FastbootMutationOutcome.Applied -> outcome.mutation
    is FastbootMutationOutcome.Refused -> outcome.mutation
    is FastbootMutationOutcome.Unconfirmed -> outcome.mutation
    is FastbootMutationOutcome.Unknown -> outcome.mutation
    is FastbootMutationOutcome.Departed -> FastbootMutationClass.REBOOT
    is FastbootMutationOutcome.NotStarted -> FastbootMutation.classify(outcome.command)
}

/**
 * Владелец Fastboot-соединения на уровне приложения.
 *
 * Отдельный от `AdbLinkController` по существу, а не для симметрии: устройство
 * в загрузчике перечисляется **другим интерфейсом**, ADB там не отвечает вовсе,
 * и смены роли (`07` §6.46: `reboot bootloader` поднял generation с интерфейсом
 * **FASTBOOT**) это ровно тот переход, ради которого обе стороны существуют
 * порознь.
 *
 * Полоса обмена здесь одна и синхронная — так устроен сам Fastboot, см.
 * `FastbootLane`. Поэтому владелец ничего не мультиплексирует и не пытается:
 * это не упрощение, а свойство протокола.
 */
/**
 * The controller deliberately exposes one operation per Fastboot capability;
 * the function count is the public operation surface, not duplicated logic.
 */
@Suppress("TooManyFunctions")
public class FastbootLinkController(
    /**
     * Откуда берётся захваченный интерфейс.
     *
     * Швом, а не целым координатором: владельцу нужен один вызов, и зависеть
     * от всего USB-слоя ради него значило бы тащить в тест то, что к делу не
     * относится. В production сюда приходит `UsbSessionCoordinator::claim`.
     */
    private val claim: (SessionGeneration) -> UsbClaimResult,
    private val executor: Executor,
    private val diagnostics: DiagnosticSink,
    private val clock: () -> Instant = Instant::now,
    /**
     * Сессии USB сейчас — чтобы узнать цель после её перезагрузки посреди
     * сценария (`flashall` уходит в fastbootd). В production —
     * `UsbSessionCoordinator.sessions`.
     */
    private val sessions: () -> List<UsbSession> = { emptyList() },
    private val pause: (Long) -> Unit = Thread::sleep,
) {
    /** Идёт сценарий, который вправе сменить подключение: смена сессии — его дело. */
    @Volatile
    private var scenarioRunning = false
    private val mutableState = MutableStateFlow<FastbootLinkState>(FastbootLinkState.Idle)

    /** Состояние соединения. */
    public val state: StateFlow<FastbootLinkState> = mutableState.asStateFlow()

    private var lane: FastbootLane? = null
    private var handle: UsbTransportHandle? = null
    private val lifecycleLock = Any()
    private val lifecycleEpoch = AtomicLong(0L)

    /** Только собственный rejoin операции вправе перенести эту привязку на новую полосу. */
    private class ConsoleOwnership(var lane: FastbootLane?, var epoch: Long)

    /** Вызывается под [lifecycleLock]. */
    private fun ownsConnection(owner: ConsoleOwnership): Boolean =
        lane === owner.lane && lifecycleEpoch.get() == owner.epoch

    private val mutableConsole = MutableStateFlow<FastbootConsoleState>(FastbootConsoleState.Idle)

    /** Исход последнего обмена. */
    public val console: StateFlow<FastbootConsoleState> = mutableConsole.asStateFlow()

    private val mutableMiUnlock = MutableStateFlow<MiUnlockStatus>(MiUnlockStatus.Idle)
    public val miUnlock: StateFlow<MiUnlockStatus> = mutableMiUnlock.asStateFlow()
    private val unlockReporter = MiUnlockReporter(mutableMiUnlock, ::emit)

    private data class PendingUnlock(val generation: SessionGeneration, val product: String, val serial: String?)
    private var pendingUnlock: PendingUnlock? = null

    /**
     * Захватывает интерфейс и спрашивает устройство, кто оно.
     *
     * Опрос уходит на исполнитель: ответа ждать до семи секунд, и держать этим
     * поток раскладки нельзя.
     */
    public fun connect(generation: SessionGeneration) {
        val transition = synchronized(lifecycleLock) {
            when (val current = mutableState.value) {
                is FastbootLinkState.Probing -> if (current.generation == generation) return
                is FastbootLinkState.Connected -> if (current.generation == generation) return
                else -> Unit
            }
            val next = lifecycleEpoch.incrementAndGet()
            val resources = detachResources()
            mutableState.value = FastbootLinkState.Probing(generation)
            next to resources
        }
        closeResources(transition.second)
        val epoch = transition.first
        if (!synchronized(lifecycleLock) { isCurrentProbe(generation, epoch) }) return

        when (val claimed = claim(generation)) {
            is UsbClaimResult.Failed -> failProbe(generation, epoch, claimed.reason.name, "fastboot_claim_failed")

            is UsbClaimResult.Claimed -> scheduleProbe(generation, epoch, claimed.handle)
        }
    }

    /** Queued probes own their handle before execution; publication is serialized with disconnect. */
    private fun retainProbeHandle(generation: SessionGeneration, epoch: Long, claimed: UsbTransportHandle): Boolean =
        synchronized(lifecycleLock) {
            if (!isCurrentProbe(generation, epoch)) {
                false
            } else {
                handle = claimed
                true
            }
        }

    private fun scheduleProbe(generation: SessionGeneration, epoch: Long, claimed: UsbTransportHandle) {
        if (!retainProbeHandle(generation, epoch, claimed)) {
            claimed.close()
            return
        }
        try {
            executor.execute { probe(generation, claimed, epoch) }
        } catch (rejected: RejectedExecutionException) {
            failProbe(generation, epoch, rejected.message ?: rejected.javaClass.simpleName, "fastboot_probe_failed")
            claimed.close()
        }
    }

    /** Called under [lifecycleLock]. */
    private fun isCurrentProbe(generation: SessionGeneration, epoch: Long): Boolean =
        lifecycleEpoch.get() == epoch && mutableState.value == FastbootLinkState.Probing(generation)

    private fun failProbe(generation: SessionGeneration, epoch: Long, detail: String, event: String) {
        val resources = synchronized(lifecycleLock) {
            if (!isCurrentProbe(generation, epoch)) return
            detachResources().also { mutableState.value = FastbootLinkState.Failed(generation, detail) }
        }
        closeResources(resources)
        emit(event, mapOf("reason" to detail, "generation" to generation.value.toString()))
    }

    /** Called under [lifecycleLock]; invalidation precedes any potentially blocking close. */
    private fun detachResources(): Pair<FastbootLane?, UsbTransportHandle?> {
        val resources = lane to handle
        scenarioRunning = false
        lane = null
        handle = null
        mutableConsole.value = FastbootConsoleState.Idle
        return resources
    }

    private fun closeResources(resources: Pair<FastbootLane?, UsbTransportHandle?>) {
        resources.first?.close()
        resources.second?.close()
    }

    /** Отпускает интерфейс. Повторный вызов безопасен. */
    public fun disconnect() {
        val resources = synchronized(lifecycleLock) {
            lifecycleEpoch.incrementAndGet()
            detachResources().also { mutableState.value = FastbootLinkState.Idle }
        }
        closeResources(resources)
    }

    /**
     * Сверяет связь с действительным состоянием сессий USB.
     *
     * Связь существует ровно столько, сколько жива сессия, в которой она
     * заведена. Прогон этапа 2 показал цену обратного: кабель переподключили,
     * сессия 1 закрылась, пришла сессия 2 — а экран продолжал показывать
     * «Загрузчик» и кнопки сессии 1. Каждая команда честно отвечала «запись не
     * состоялась: NOT_HELD», но экран утверждал связь, которой не было.
     *
     * Сбрасывается только связь закрытой или отпущенной сессии: живую новую
     * никто не захватывает сам — опрос остаётся за оператором.
     */
    public fun onUsbSessionsChanged(sessions: List<UsbSession>) {
        // Сценарий сам переводит связь на новое подключение; после него сверка
        // повторяется (см. [busy]).
        val dropped = synchronized(lifecycleLock) {
            if (scenarioRunning) return
            val stale = when (val state = mutableState.value) {
                is FastbootLinkState.Connected -> state.generation.takeIf { generation ->
                    sessions.none { it.generation == generation && !it.closed && it.state == UsbSessionState.CLAIMED }
                }
                // Отказ остаётся на экране, пока его сессия существует.
                is FastbootLinkState.Failed -> state.generation.takeIf { generation ->
                    sessions.none { it.generation == generation && !it.closed }
                }
                is FastbootLinkState.Probing -> state.generation.takeIf { generation ->
                    sessions.none { it.generation == generation && !it.closed }
                }
                FastbootLinkState.Idle -> null
            } ?: return
            lifecycleEpoch.incrementAndGet()
            stale to detachResources().also { mutableState.value = FastbootLinkState.Idle }
        }
        closeResources(dropped.second)
        emit("fastboot_link_dropped", mapOf("generation" to dropped.first.value.toString()))
    }

    /**
     * Отправляет команду как набрана.
     *
     * Ни имя, ни форма не проверяются и не ограничиваются списком: что бывает у
     * Fastboot, знает устройство, и его `FAIL` — это ответ, а не наша ошибка
     * (`01` §3). Отказать полоса может только по двум причинам провода, и обе
     * она называет: команда не передаётся в ASCII либо не помещается в кадр.
     *
     * **Через этот же вызов идут кнопки типизованной секции.** Они не ходят в
     * полосу мимо него и не строят свой обмен: кнопка собирает строку через
     * `FastbootCommands` и отдаёт её сюда. Так «один движок» становится
     * проверяемым утверждением, а не обещанием: второго пути просто нет.
     *
     * Исход читается через границу мутации (`03` §3), и для набранной руками
     * команды это так же важно, как для кнопки: оборванный `erase:` обязан
     * читаться как неизвестное состояние раздела, а не как «ответа нет».
     */
    public fun runCommand(command: String) {
        busy("fastboot_command", command) { lane, trimmed ->
            FastbootConsoleState.Mutated(
                outcome = FastbootMutation(lane, FastbootGetVar(lane)).run(trimmed),
                lane = lane.state,
            )
        }
    }

    /**
     * Выполняет план — последовательность команд как одно действие.
     *
     * Второго пути к полосе не заводит: каждый шаг уходит тем же
     * `FastbootMutation`, что и набранная руками команда. План не добавляет
     * возможностей — он показывает оператору весь список **до** нажатия и
     * останавливается на первом непринятом шаге, чтобы не складывать
     * неизвестность с неизвестностью.
     */
    public fun runPlan(commands: List<String>, slot: String? = null) {
        busy("fastboot_plan", commands.joinToString(" ; ")) { lane, _ ->
            when (val resolved = slotCommands(commands, slot, lane)) {
                is SlotResolution.Failed -> notResolved(commands, resolved, lane.state)
                is SlotResolution.Resolved -> planned(
                    resolved.commands,
                    FastbootPlan(resolved.commands).run(FastbootMutation(lane, FastbootGetVar(lane))),
                    lane.state,
                )
            }
        }
    }

    /** Спрашивает одну переменную по имени. */
    public fun readVariable(name: String) {
        busy("fastboot_getvar", name) { lane, trimmed ->
            val variable = FastbootGetVar(lane).read(trimmed)
            when (variable) {
                is FastbootVariable.Present -> FastbootConsoleState.Answered(
                    command = "getvar:$trimmed",
                    reply = FastbootReply.OKAY,
                    payload = variable.value,
                    info = emptyList(),
                    lane = lane.state,
                )

                is FastbootVariable.Unsupported -> FastbootConsoleState.Answered(
                    command = "getvar:$trimmed",
                    reply = FastbootReply.FAIL,
                    payload = variable.detail,
                    info = emptyList(),
                    lane = lane.state,
                )

                is FastbootVariable.Unavailable ->
                    FastbootConsoleState.NotAnswered("getvar:$trimmed", variable.detail, lane.state)
            }
        }
    }

    /** Official Xiaomi account flow; the USB lane is held exclusively across all steps. */
    public fun runMiUnlock(auth: MiAccountClient.AuthResult) {
        busy("mi_unlock", "Mi Unlock") { active, _ ->
            try {
                runMiUnlockOnLane(active, auth)
            } catch (_: MiUnlockClient.SessionExpiredException) {
                unlockReporter.fail(active, MiUnlockStatus.Reason.SESSION_EXPIRED)
            } catch (business: MiUnlockClient.BusinessException) {
                unlockReporter.fail(active, MiUnlockStatus.Reason.SERVER, business.code)
            } catch (_: Exception) {
                // A transport exception can embed a signed URL or a server body.
                unlockReporter.fail(active, MiUnlockStatus.Reason.SERVER)
            }
        }
    }

    // Each early exit is a safety boundary: never proceed from an absent token,
    // stale USB session or rejected server response to the mutating command.
    @Suppress("ReturnCount")
    private fun runMiUnlockOnLane(active: FastbootLane, auth: MiAccountClient.AuthResult): FastbootConsoleState {
        val connected = synchronized(lifecycleLock) { mutableState.value as? FastbootLinkState.Connected }
            ?: return unlockReporter.fail(active, MiUnlockStatus.Reason.DEVICE)
        unlockReporter.stage(MiUnlockStatus.Stage.DEVICE)
        val product = (FastbootGetVar(active).read("product") as? FastbootVariable.Present)?.value
            ?.filterNot(Char::isWhitespace)
            ?.takeIf { it.length in 1..MAX_PRODUCT_LENGTH && it.matches(Regex("[A-Za-z0-9_.-]+")) }
            ?: return unlockReporter.fail(active, MiUnlockStatus.Reason.DEVICE)
        val serial = (FastbootGetVar(active).read("serialno") as? FastbootVariable.Present)?.value
            ?.trim()?.takeIf { it.isNotBlank() }
        val token = FastbootXiaomiUnlock(active).readToken()
            ?: return unlockReporter.fail(active, MiUnlockStatus.Reason.TOKEN)
        // A new explicit attempt supersedes the previous pending marker only after a fresh token.
        if (!beginUnlockAttempt(active, connected)) {
            return unlockReporter.fail(active, MiUnlockStatus.Reason.DEVICE)
        }
        val client = MiUnlockClient(auth.host, auth.ssecurity, auth.serviceCookies, auth.userId, auth.deviceId)
        unlockReporter.stage(MiUnlockStatus.Stage.NONCE)
        val nonce = client.getNonce()
        unlockReporter.stage(MiUnlockStatus.Stage.CHECK)
        client.checkClear(product, nonce)
        if (!isCurrentIdle(active, connected)) {
            return unlockReporter.fail(active, MiUnlockStatus.Reason.DEVICE)
        }
        unlockReporter.stage(MiUnlockStatus.Stage.AUTHORIZE)
        // This request changes server-side unlock state: it is deliberately never retried.
        val encryptedHex = client.requestUnlock(product, token, nonce)
        val bytes = MiUnlockData.decode(encryptedHex)
            ?: return unlockReporter.fail(active, MiUnlockStatus.Reason.SERVER)
        if (!isCurrentIdle(active, connected)) {
            return unlockReporter.fail(active, MiUnlockStatus.Reason.DEVICE)
        }
        unlockReporter.stage(MiUnlockStatus.Stage.DOWNLOAD)
        // Download and oem unlock are one synchronous operation on the same lane.
        return when (FastbootXiaomiUnlock(active).unlock(bytes) {
            unlockReporter.stage(MiUnlockStatus.Stage.UNLOCK)
        }) {
            XiaomiUnlockOutcome.Accepted -> acceptedUnlock(active, connected, product, serial)
            is XiaomiUnlockOutcome.Refused -> unlockReporter.fail(active, MiUnlockStatus.Reason.REJECTED)
            is XiaomiUnlockOutcome.Unknown -> unlockReporter.fail(active, MiUnlockStatus.Reason.UNKNOWN)
            is XiaomiUnlockOutcome.NotStarted -> unlockReporter.fail(active, MiUnlockStatus.Reason.NOT_STARTED)
        }
    }

    private fun beginUnlockAttempt(
        active: FastbootLane,
        connected: FastbootLinkState.Connected,
    ): Boolean = synchronized(lifecycleLock) {
        if (lane !== active || mutableState.value != connected) {
            false
        } else {
            pendingUnlock = null
            true
        }
    }

    private fun isCurrentIdle(
        active: FastbootLane,
        connected: FastbootLinkState.Connected,
    ): Boolean = synchronized(lifecycleLock) {
        lane === active && mutableState.value == connected && active.state == FastbootLaneState.IDLE
    }

    private fun acceptedUnlock(
        active: FastbootLane,
        connected: FastbootLinkState.Connected,
        product: String,
        serial: String?,
    ): FastbootConsoleState {
        val published = synchronized(lifecycleLock) {
            if (lane !== active || mutableState.value != connected) {
                false
            } else {
                pendingUnlock = PendingUnlock(connected.generation, product, serial)
                mutableMiUnlock.value = MiUnlockStatus.AwaitingReconnect
                true
            }
        }
        if (!published) return unlockReporter.fail(active, MiUnlockStatus.Reason.DEVICE)
        emit("mi_unlock_command_accepted", mapOf("verification" to "pending"))
        return FastbootConsoleState.Answered(
            "Mi Unlock", FastbootReply.OKAY,
            "ожидается проверка после переподключения", emptyList(), active.state,
        )
    }

    /** Спрашивает всё, что устройство готово рассказать. */
    public fun readAllVariables() {
        busy("fastboot_getvar_all", "all") { lane, _ ->
            FastbootConsoleState.Variables(FastbootGetVar(lane).readAll(), lane.state)
        }
    }

    /**
     * Загружает в буфер устройства **выбранный пользователем** файл — и, если
     * даны [then], выполняет их следом одной операцией.
     *
     * Произвольный доступ здесь не нужен: фаза данных Fastboot читает источник
     * подряд, и стажировать поэтому нечего — этим `download:` и отличается от
     * Sideload, где Recovery просит блоки в своём порядке.
     *
     * Размер обязан быть известен заранее: он объявляется в самой команде
     * восемью шестнадцатеричными цифрами. Источник, длины которого провайдер не
     * сообщает, поэтому отвергается **до** первого байта, а не на середине.
     *
     * Без [then] ничего не прошивает. С ними это `fastboot flash РАЗДЕЛ ФАЙЛ`
     * настольного клиента: `download` и `flash:РАЗДЕЛ` (или `boot`) одним
     * действием, а не двумя кнопками, между которыми буфер мог смениться.
     * Команды идут, только если устройство приняло **весь** объём ответом
     * `OKAY`: из недогруженного буфера не прошивают.
     */
    public fun downloadFrom(
        then: List<String> = emptyList(),
        slot: String? = null,
        sparseLimit: Long? = null,
        origin: () -> ArtifactSource,
    ) {
        val label = (listOf("выбранный файл") + then).joinToString(" ; ")
        busy("fastboot_download_file", label) { lane, _ ->
            // Имена уточняются до первого байта: грузить файл под команду,
            // которую не уточнить, незачем.
            val resolved = slotCommands(then, slot, lane)
            val commands = (resolved as? SlotResolution.Resolved)?.commands.orEmpty()
            val source = origin()
            val size = source.identity.sizeBytes
            when {
                resolved is SlotResolution.Failed -> notResolved(then, resolved, lane.state)
                size == null -> FastbootConsoleState.NotAnswered(
                    command = "download:",
                    detail = "провайдер не сообщает размер файла, " +
                        "а download: обязан объявить его до первого байта",
                    lane = lane.state,
                )
                else -> flashImage(lane, source, size, sparseLimit, commands)
            }
        }
    }

    /**
     * Сценарии хоста настольного клиента: `flashall`/`update`, `wipe-super`,
     * `flash vendor_boot:ИМЯ`. Одна грань, чтобы не раздувать сам контроллер;
     * полоса та же, через тот же [busy].
     */
    internal val scenarios: HostScenarios = HostScenarios()

    /** Сценарии хоста — одной операцией полосы каждый. */
    internal inner class HostScenarios internal constructor() {
        /**
         * `fastboot flashall` и `fastboot update` — одной операцией полосы, как
         * `FlashAllTool` настольного клиента ([FastbootFlashAll]).
         */
        internal fun flashAll(request: FlashAllRequest, images: () -> FastbootImageSet) {
            busyOwned(
                "fastboot_flashall",
                if (request.flashAll) "flashall" else "update",
                scenario = true,
            ) { lane, _, owner ->
                FastbootFlashAll(lane, images(), request, rejoin(owner)).run()
            }
        }

        /** `fastboot flash vendor_boot:ИМЯ` ([flashVendorRamdisk]); копия раздела — в [staging]. */
        internal fun flashVendorRamdisk(
            ramdisk: String,
            slot: String,
            sparseLimit: Long?,
            staging: java.io.File,
            origin: () -> ArtifactSource,
        ) {
            busy("fastboot_vendor_ramdisk", "vendor_boot:$ramdisk") { lane, _ ->
                flashVendorRamdisk(lane, origin(), ramdisk, slot, sparseLimit, staging)
            }
        }

        /** `fastboot wipe-super`: пустой `super` по выбранному `super_empty.img` ([wipeSuper]). */
        internal fun wipeSuper(slot: String, sparseLimit: Long?, origin: () -> ArtifactSource) {
            busy("fastboot_wipe_super", "wipe-super") { lane, _ -> wipeSuper(lane, origin(), slot, sparseLimit) }
        }

        /** `fastboot format[:ТИП[:РАЗМЕР]] РАЗДЕЛ` — файловая система с хоста ([formatPartition]). */
        internal fun format(partition: String, type: String?, size: String?, slot: String, options: FormatOptions) {
            busy("fastboot_format", "format $partition") { lane, _ ->
                formatPartition(lane, partition, type, size, slot, options)
            }
        }

        /** `fastboot -w [reboot…]`: `userdata`, `cache`, `metadata`, затем [then] ([FastbootFormat.wipe]). */
        internal fun wipe(force: Boolean, then: List<String>, options: FormatOptions) {
            busy("fastboot_wipe", "-w") { lane, _ -> FastbootFormat(lane, options).wipe(WIPE_PARTITIONS, force, then) }
        }

        /** Как вернуться к этой цели после её перезагрузки: по серийному номеру текущей сессии. */
        private fun rejoin(owner: ConsoleOwnership): FastbootRejoin {
            val generation = synchronized(lifecycleLock) {
                if (ownsConnection(owner)) (mutableState.value as? FastbootLinkState.Connected)?.generation else null
            }
            val previous = generation?.let { current -> sessions().firstOrNull { it.generation == current } }
            return if (previous == null || !previous.identity.survivesReattachment) {
                FastbootRejoin.NONE
            } else {
                SessionReturn(previous, owner)
            }
        }
    }

    /**
     * Ждёт новую сессию Fastboot той же цели, захватывает её и опрашивает, как
     * [connect]. Разрешение на новое подключение даёт оператор в диалоге
     * системы; ожидание ограничено [REJOIN_BUDGET_MS], чтобы полоса не
     * оставалась занятой навсегда.
     */
    private inner class SessionReturn(
        private val previous: UsbSession,
        private val owner: ConsoleOwnership,
    ) : FastbootRejoin {
        override val possible = true

        override fun await(): FastbootLane? {
            emit("fastboot_rejoin_waiting", mapOf("generation" to previous.generation.value.toString()))
            val deadline = clock().toEpochMilli() + REJOIN_BUDGET_MS
            while (clock().toEpochMilli() < deadline && synchronized(lifecycleLock) { ownsConnection(owner) }) {
                val next = sessions().firstOrNull { session ->
                    session.targetId == previous.targetId && session.generation != previous.generation &&
                        session.candidate.kind == UsbInterfaceKind.FASTBOOT && session.state == UsbSessionState.READY
                }
                val claimed = next?.let { claim(it.generation) as? UsbClaimResult.Claimed }
                if (next != null && claimed != null) {
                    return reclaim(next, claimed.handle)
                }
                pause(REJOIN_POLL_MS)
            }
            if (synchronized(lifecycleLock) { ownsConnection(owner) }) {
                emit("fastboot_rejoin_timed_out", mapOf("generation" to previous.generation.value.toString()))
            }
            return null
        }

        private fun beginRejoin(next: UsbSession): Long? = synchronized(lifecycleLock) {
            if (!ownsConnection(owner)) {
                null
            } else {
                owner.epoch = lifecycleEpoch.incrementAndGet()
                lane?.close()
                handle?.close()
                lane = null
                handle = null
                owner.lane = null
                mutableState.value = FastbootLinkState.Probing(next.generation)
                owner.epoch
            }
        }

        private fun reclaim(next: UsbSession, claimed: UsbTransportHandle): FastbootLane? {
            val epoch = beginRejoin(next)
            if (epoch == null) {
                claimed.close()
                return null
            }
            return probe(next.generation, claimed, epoch)?.let { returned ->
                val current = synchronized(lifecycleLock) {
                    if (lifecycleEpoch.get() != epoch || lane !== returned) {
                        false
                    } else {
                        owner.lane = returned
                        true
                    }
                }
                if (!current) {
                    returned.close()
                    claimed.close()
                }
                returned.takeIf { current }
            }
        }
    }

    /** Буфер устройства: `fastboot get_staged`. Отдельная грань, как [scenarios]. */
    internal val buffer: StagedBuffer = StagedBuffer()

    internal inner class StagedBuffer internal constructor() {
        /** `upload` — содержимое буфера устройства в место, выбранное пользователем. */
        internal fun uploadTo(destination: () -> ArtifactSink) {
            busy("fastboot_upload_file", "get_staged") { lane, _ ->
                val writer = ArtifactWriter(destination())
                val outcome = FastbootFetch(lane, FastbootGetVar(lane)).upload(WriterStream(writer))
                saved(UPLOAD, writer, outcome, lane.state)
            }
        }
    }

    /** Читает раздел в место, выбранное пользователем. */
    public fun fetchPartitionTo(partition: String, slot: String? = null, destination: () -> ArtifactSink) {
        busy("fastboot_fetch_file", partition) { lane, typed ->
            // `fastboot fetch` уточняет имя раздела так же, как `flash`: до
            // первого байта, по `has-slot` и `current-slot`.
            when (val resolved = slotCommands(listOf(FETCH + typed), slot, lane)) {
                is SlotResolution.Failed -> notResolved(listOf(FETCH + typed), resolved, lane.state)
                is SlotResolution.Resolved -> {
                    val name = resolved.commands.single().removePrefix(FETCH)
                    val writer = ArtifactWriter(destination())
                    val outcome = FastbootFetch(lane, FastbootGetVar(lane)).fetch(name, WriterStream(writer))
                    saved(name, writer, outcome, lane.state)
                }
            }
        }
    }

    /** Приёмник Fastboot говорит на `OutputStream`; запись артефакта — на кусках. */

    /**
     * Выполняет обмен, если полоса свободна.
     *
     * Второй обмен поверх идущего не ставится в очередь и не отбрасывается
     * молча: у Fastboot полоса одна, и очередь здесь означала бы, что оператор
     * не знает, когда его команда уйдёт. Отказ виден сразу.
     */
    private fun busy(
        event: String,
        raw: String,
        action: (FastbootLane, String) -> FastbootConsoleState,
    ) {
        busyOwned(event, raw) { lane, typed, _ -> action(lane, typed) }
    }

    private fun busyOwned(
        event: String,
        raw: String,
        scenario: Boolean = false,
        action: (FastbootLane, String, ConsoleOwnership) -> FastbootConsoleState,
    ) {
        val trimmed = raw.trim()
        val operation = synchronized(lifecycleLock) {
            val live = lane
            when {
                live == null -> {
                    mutableConsole.value = FastbootConsoleState.NotAnswered(
                        trimmed,
                        "соединения нет",
                        FastbootLaneState.CLOSED,
                    )
                    null
                }

                mutableConsole.value is FastbootConsoleState.Running -> null

                else -> {
                    mutableConsole.value = FastbootConsoleState.Running(trimmed, clock().toEpochMilli())
                    ConsoleOwnership(live, lifecycleEpoch.get())
                }
            }
        } ?: return
        val live = operation.lane ?: return
        executor.execute {
            val started = synchronized(lifecycleLock) {
                if (ownsConnection(operation)) {
                    scenarioRunning = scenario
                    true
                } else {
                    false
                }
            }
            if (!started) return@execute
            emit(event, mapOf("command" to trimmed))
            val outcome = runCatching { action(live, trimmed, operation) }
            completeConsoleOperation(event, trimmed, operation, scenario, outcome, live)
        }
    }

    private fun completeConsoleOperation(
        event: String,
        command: String,
        operation: ConsoleOwnership,
        scenario: Boolean,
        outcome: Result<FastbootConsoleState>,
        previousLane: FastbootLane,
    ) {
        val current = synchronized(lifecycleLock) {
            ownsConnection(operation).also { if (it) scenarioRunning = false }
        }
        if (!current) return
        if (scenario) onUsbSessionsChanged(sessions())
        val published = synchronized(lifecycleLock) {
            if (!ownsConnection(operation)) {
                null
            } else {
                outcome.getOrElse { failure ->
                    FastbootConsoleState.NotAnswered(
                        command = command,
                        detail = failure.message ?: failure.javaClass.simpleName,
                        lane = operation.lane?.state ?: previousLane.state,
                    )
                }.also { mutableConsole.value = it }
            }
        }
        published?.let { record(event, it) }
    }

    private fun record(event: String, state: FastbootConsoleState) {
        val fields = when (state) {
            is FastbootConsoleState.Answered -> mapOf(
                "command" to state.command,
                "reply" to state.reply.name,
                "payload" to journalled(state.command, state.payload),
                "infoLines" to state.info.size.toString(),
                "lane" to state.lane.name,
            )

            is FastbootConsoleState.NotAnswered -> mapOf(
                "command" to state.command,
                "reply" to "none",
                "detail" to state.detail,
                "lane" to state.lane.name,
            )

            // Имена расхождений и неразобранные строки записываются, а числа
            // одни — нет. Прогон §6.72 дал `duplicates=2 ignored=2` на живом
            // устройстве, и по журналу нельзя было узнать, какие именно: видно,
            // что что-то есть, и не видно что. Тот же дефект наблюдаемости
            // стоил прогона в §6.55.
            //
            // Значения переменных при этом **не** записываются, и это
            // намеренно: среди них `token` разблокировки. Имя расхождения и
            // строка, которую не удалось разобрать, для разбора достаточны, а
            // выгружать весь ответ устройства в отчёт — нет.
            is FastbootConsoleState.Downloaded -> mapOf(
                "declaredBytes" to state.declaredBytes.toString(),
                "sentBytes" to state.sentBytes.toString(),
                "reply" to (state.reply?.name ?: "none"),
                "detail" to state.detail,
                // Ключевое поле для разбора прогона: можно ли утверждать, что
                // состояние устройства не изменилось.
                "untouched" to state.untouched.toString(),
                "lane" to state.lane.name,
                // Путь байтов и почему он (D077): по нему замер шага M1
                // отличает нативную передачу от потоковой.
                "dataPath" to (state.route?.path?.name ?: "none"),
                "dataPathReason" to (state.route?.reason ?: ""),
            )

            // Класс мутации и утверждение о состоянии пишутся всегда, включая
            // команды, набранные руками. Без класса запись «ответа не было» не
            // отличить от «раздел в неизвестном состоянии», а это ровно та
            // разница, ради которой написан `03` §3.
            is FastbootConsoleState.Mutated -> mutationFields(state)

            // Полнота пишется отдельным полем, а не выводится из числа байт:
            // прочитанный целиком маленький раздел и оборванный большой дают
            // одинаково правдоподобные числа.
            is FastbootConsoleState.Fetched -> mapOf(
                "partition" to state.partition,
                "bytes" to state.bytes.toString(),
                "sha256" to state.sha256,
                "complete" to state.complete.toString(),
                "detail" to state.detail,
                "lane" to state.lane.name,
            )

            is FastbootConsoleState.Planned -> mapOf(
                "commands" to state.commands.joinToString(" ; "),
                "steps" to state.commands.size.toString(),
                "applied" to state.applied.toString(),
                // Место остановки пишется отдельно от числа сделанного: по
                // одному только счётчику не отличить «всё прошло» от «первый шаг
                // прошёл, а второго не было».
                "stoppedAt" to (state.stoppedAt?.toString() ?: "none"),
                "detail" to state.detail,
                "lane" to state.lane.name,
            )

            is FastbootConsoleState.Variables -> mapOf(
                "variables" to state.snapshot.variables.size.toString(),
                "duplicates" to state.snapshot.duplicates.size.toString(),
                "duplicateNames" to state.snapshot.duplicates.joinToString(",") { it.name },
                "conflicting" to state.snapshot.duplicates.count { it.conflicting }.toString(),
                "ignored" to state.snapshot.ignored.size.toString(),
                "ignoredLines" to state.snapshot.ignored.joinToString(" | "),
                "complete" to state.snapshot.complete.toString(),
                "reply" to state.snapshot.finalReply.name,
                "lane" to state.lane.name,
            )

            else -> mapOf("state" to state.javaClass.simpleName)
        }
        emit(event + "_finished", fields)
    }

    /**
     * Исход мутации в поля журнала.
     *
     * `claim` — то, что можно утверждать о состоянии устройства, одним словом:
     * по нему прогон разбирается без чтения текста. `applied` означает, что
     * устройство сказало «сделал»; `refused` — что оно сказало «не сделал», и
     * доказательством целости раздела это не является; `unknown` — что мы не
     * знаем; `departed` — что устройство ушло по нашей же просьбе.
     */
    private fun mutationFields(state: FastbootConsoleState.Mutated): Map<String, String> {
        val outcome = state.outcome
        val common = mapOf(
            "command" to outcome.command,
            "mutation" to mutationClassOf(outcome).name,
            "lane" to state.lane.name,
        )
        return common + when (outcome) {
            is FastbootMutationOutcome.Applied -> mapOf(
                "claim" to "applied",
                "reply" to "OKAY",
                "payload" to journalled(outcome.command, outcome.payload),
                "infoLines" to outcome.info.size.toString(),
                "confirmation" to (outcome.confirmation ?: "none"),
            )

            is FastbootMutationOutcome.Refused -> mapOf(
                "claim" to "refused",
                "reply" to "FAIL",
                "detail" to outcome.detail,
            )

            is FastbootMutationOutcome.Unconfirmed -> mapOf(
                "claim" to "unconfirmed",
                "reply" to "OKAY",
                "expected" to outcome.expected,
                "observed" to outcome.observed,
            )

            is FastbootMutationOutcome.Departed -> mapOf(
                "claim" to "departed",
                "reply" to "none",
                "waitedMillis" to outcome.waitedMillis.toString(),
                "infoLines" to outcome.info.size.toString(),
            )

            is FastbootMutationOutcome.Unknown -> mapOf(
                "claim" to "unknown",
                "reply" to "none",
                "detail" to outcome.detail,
            )

            is FastbootMutationOutcome.NotStarted -> mapOf(
                "claim" to "not_started",
                "reply" to "none",
                "detail" to outcome.detail,
            )
        }
    }

    /**
     * Значение ответа так, как оно попадёт в **выгрузку диагностики**.
     *
     * Токен разблокировки в журнал не пишется. Решение об этом было принято в
     * §6.72 для `getvar:all` — там записываются имена и счётчики, но не
     * значения, — и обходилось двумя путями, которые тогда не заметили:
     * одиночное чтение переменной по имени и ответ произвольной команды. Оба
     * закрыты здесь.
     *
     * **Оператор при этом не теряет ничего**: значение показывается на экране
     * целиком, как он и просил. Скрыто оно только в архиве, который человек
     * отдаёт кому-то ещё. Это решение о **нашем отчёте**, а не ограничение
     * возможностей (`01` §3): команда уходит как набрана, ответ приходит
     * целиком, прячется одна строка в файле, который мы же и составляем.
     *
     * Совпадение ищется по **вопросу**, а не по ответу: спросили про токен —
     * значение не записываем. Это узкая заглушка на известный случай, а не
     * общее правило. Общее — чьи ещё ответы нельзя выгружать — принадлежит
     * `diagnostics privacy review` из Phase 11, и придумывать его на бегу
     * значило бы дать ложную уверенность списком, который заведомо неполон.
     */
    private fun journalled(command: String, payload: String): String =
        if (payload.isNotBlank() && SECRET_ANSWERS.any { command.contains(it, ignoreCase = true) }) {
            "$HIDDEN, символов: ${payload.length}"
        } else {
            payload
        }

    private fun probe(
        generation: SessionGeneration,
        claimed: UsbTransportHandle,
        epoch: Long,
    ): FastbootLane? {
        if (!retainProbeHandle(generation, epoch, claimed)) {
            claimed.close()
            return null
        }
        val opened = FastbootLane(claimed)
        emit("fastboot_probe_started", mapOf("generation" to generation.value.toString()))

        val variables = FastbootGetVar(opened)
        val attempt = runCatching { FastbootModeProbe.probe(variables) }
        val identity = attempt.getOrElse { failure ->
            // Исключение — программистская ошибка, а не ответ устройства.
            // Роль от этого не становится известной, поэтому UNKNOWN с текстом.
            FastbootIdentity(FastbootMode.UNKNOWN, failure.message ?: failure.javaClass.simpleName)
        }

        if (!synchronized(lifecycleLock) { isCurrentProbe(generation, epoch) }) {
            opened.close()
            claimed.close()
            return null
        }

        // Замок читается тем же опросом, что и роль: он нужен до первого
        // разрушающего действия, а не в момент нажатия, и принадлежит этой
        // generation.
        val lock = runCatching { FastbootLockProbe.probe(variables) }.getOrElse { failure ->
            FastbootLockStatus(FastbootLockState.UNKNOWN, failure.message ?: failure.javaClass.simpleName)
        }

        verifyPendingUnlock(generation, epoch, lock, variables)

        emit(
            "fastboot_probe_finished",
            mapOf(
                "mode" to identity.mode.name,
                "detail" to identity.detail,
                "lock" to lock.state.name,
                "lockDetail" to lock.detail,
                "lane" to opened.state.name,
            ),
        )
        val published = publishProbe(generation, epoch, claimed, opened, identity, lock)
        if (!published) {
            // A disconnect or a newer connect won while the probe was blocked
            // in USB I/O. Never publish the stale lane or leak its handle.
            opened.close()
            claimed.close()
            return null
        }
        return opened
    }

    private fun verifyPendingUnlock(
        generation: SessionGeneration,
        epoch: Long,
        lock: FastbootLockStatus,
        variables: FastbootGetVar,
    ) {
        val pending = synchronized(lifecycleLock) {
            pendingUnlock?.takeIf {
                lifecycleEpoch.get() == epoch &&
                    it.generation != generation &&
                    lock.state == FastbootLockState.UNLOCKED
            }
        } ?: return
        val product = (variables.read("product") as? FastbootVariable.Present)?.value?.trim()
        val serial = (variables.read("serialno") as? FastbootVariable.Present)?.value?.trim()
        // Same model alone cannot identify the target when several phones are attached.
        val verified = synchronized(lifecycleLock) {
            when {
                lifecycleEpoch.get() != epoch || pendingUnlock !== pending -> false
                pending.serial == null || pending.serial != serial || pending.product != product -> false
                else -> {
                    mutableMiUnlock.value = MiUnlockStatus.Verified
                    pendingUnlock = null
                    true
                }
            }
        }
        if (verified) emit("mi_unlock_verified", mapOf("outcome" to "unlocked"))
    }

    private fun publishProbe(
        generation: SessionGeneration,
        epoch: Long,
        claimed: UsbTransportHandle,
        opened: FastbootLane,
        identity: FastbootIdentity,
        lock: FastbootLockStatus,
    ): Boolean = synchronized(lifecycleLock) {
        if (lifecycleEpoch.get() != epoch ||
            mutableState.value != FastbootLinkState.Probing(generation)
        ) {
            false
        } else {
            handle = claimed
            lane = opened
            mutableState.value = FastbootLinkState.Connected(generation, identity, lock)
            true
        }
    }

    private fun emit(message: String, fields: Map<String, String>) {
        diagnostics.emit(
            DiagnosticEvent(
                timestamp = clock(),
                category = DIAGNOSTIC_CATEGORY,
                message = message,
                fields = fields,
            ),
        )
    }

    private companion object {
        const val MAX_PRODUCT_LENGTH = 128
        const val FETCH = "fetch:"
        const val UPLOAD = "upload"

        /** Сколько ждать возвращения цели после её перезагрузки посреди сценария. */
        const val REJOIN_BUDGET_MS = 180_000L
        const val REJOIN_POLL_MS = 250L
        const val DIAGNOSTIC_CATEGORY = "fastboot"

        /** Вопросы, ответ на которые в выгрузку не идёт. Совпадение — по вопросу. */
        val SECRET_ANSWERS = listOf("token")

        const val HIDDEN = "не записано"
    }
}
