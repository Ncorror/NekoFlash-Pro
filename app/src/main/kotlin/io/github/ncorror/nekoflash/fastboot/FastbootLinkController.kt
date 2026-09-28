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
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootLockProbe
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootLockState
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootLockStatus
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootReply
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootMode
import io.github.ncorror.nekoflash.payload.DigestingSink
import io.github.ncorror.nekoflash.payload.GeneratedPayload
import io.github.ncorror.nekoflash.payload.GeneratedPayloadStream
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootDownload
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
import io.github.ncorror.nekoflash.usb.api.UsbSession
import io.github.ncorror.nekoflash.usb.api.UsbSessionState
import io.github.ncorror.nekoflash.usb.api.UsbTransportHandle
import java.time.Instant
import java.util.concurrent.Executor
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
) {
    private val mutableState = MutableStateFlow<FastbootLinkState>(FastbootLinkState.Idle)

    /** Состояние соединения. */
    public val state: StateFlow<FastbootLinkState> = mutableState.asStateFlow()

    private var lane: FastbootLane? = null
    private var handle: UsbTransportHandle? = null

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
        if (mutableState.value is FastbootLinkState.Probing) return
        mutableState.value = FastbootLinkState.Probing(generation)

        when (val claimed = claim(generation)) {
            is UsbClaimResult.Failed -> {
                emit("fastboot_claim_failed", mapOf("reason" to claimed.reason.name))
                mutableState.value = FastbootLinkState.Failed(generation, claimed.reason.name)
            }

            is UsbClaimResult.Claimed -> executor.execute { probe(generation, claimed.handle) }
        }
    }

    /** Отпускает интерфейс. Повторный вызов безопасен. */
    public fun disconnect() {
        lane?.close()
        lane = null
        handle?.close()
        handle = null
        mutableConsole.value = FastbootConsoleState.Idle
        mutableState.value = FastbootLinkState.Idle
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
        val stale = when (val state = mutableState.value) {
            is FastbootLinkState.Connected -> state.generation.takeIf { generation ->
                sessions.none { it.generation == generation && !it.closed && it.state == UsbSessionState.CLAIMED }
            }
            // Отказ остаётся на экране, пока его сессия существует: он
            // единственное, что есть у оператора для разбора.
            is FastbootLinkState.Failed -> state.generation.takeIf { generation ->
                sessions.none { it.generation == generation && !it.closed }
            }
            // Опрос завершится сам и приведёт к одному из двух состояний выше.
            FastbootLinkState.Idle, is FastbootLinkState.Probing -> null
        }
        if (stale != null) {
            emit("fastboot_link_dropped", mapOf("generation" to stale.value.toString()))
            disconnect()
        }
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
        val connected = mutableState.value as? FastbootLinkState.Connected
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
        pendingUnlock = null
        val client = MiUnlockClient(auth.host, auth.ssecurity, auth.serviceCookies, auth.userId, auth.deviceId)
        unlockReporter.stage(MiUnlockStatus.Stage.NONCE)
        val nonce = client.getNonce()
        unlockReporter.stage(MiUnlockStatus.Stage.CHECK)
        client.checkClear(product, nonce)
        if (mutableState.value != connected || lane !== active || active.state != FastbootLaneState.IDLE) {
            return unlockReporter.fail(active, MiUnlockStatus.Reason.DEVICE)
        }
        unlockReporter.stage(MiUnlockStatus.Stage.AUTHORIZE)
        // This request changes server-side unlock state: it is deliberately never retried.
        val encryptedHex = client.requestUnlock(product, token, nonce)
        val bytes = parseUnlockData(encryptedHex) ?: return unlockReporter.fail(active, MiUnlockStatus.Reason.SERVER)
        if (mutableState.value != connected || lane !== active || active.state != FastbootLaneState.IDLE) {
            return unlockReporter.fail(active, MiUnlockStatus.Reason.DEVICE)
        }
        unlockReporter.stage(MiUnlockStatus.Stage.DOWNLOAD)
        // Download and oem unlock are one synchronous operation on the same lane.
        return when (FastbootXiaomiUnlock(active).unlock(bytes) {
            unlockReporter.stage(MiUnlockStatus.Stage.UNLOCK)
        }) {
            XiaomiUnlockOutcome.Accepted -> {
                pendingUnlock = PendingUnlock(connected.generation, product, serial)
                mutableMiUnlock.value = MiUnlockStatus.AwaitingReconnect
                emit("mi_unlock_command_accepted", mapOf("verification" to "pending"))
                FastbootConsoleState.Answered(
                    "Mi Unlock", FastbootReply.OKAY,
                    "ожидается проверка после переподключения", emptyList(), active.state,
                )
            }
            is XiaomiUnlockOutcome.Refused -> unlockReporter.fail(active, MiUnlockStatus.Reason.REJECTED)
            is XiaomiUnlockOutcome.Unknown -> unlockReporter.fail(active, MiUnlockStatus.Reason.UNKNOWN)
            is XiaomiUnlockOutcome.NotStarted -> unlockReporter.fail(active, MiUnlockStatus.Reason.NOT_STARTED)
        }
    }

    private fun parseUnlockData(hex: String): ByteArray? {
        if (hex.length !in HEX_CHARS_PER_BYTE..MAX_UNLOCK_HEX_CHARS ||
            hex.length % HEX_CHARS_PER_BYTE != 0) return null
        val bytes = ByteArray(hex.length / HEX_CHARS_PER_BYTE)
        for (index in bytes.indices) {
            val high = Character.digit(hex[index * HEX_CHARS_PER_BYTE], HEX_RADIX)
            val low = Character.digit(hex[index * HEX_CHARS_PER_BYTE + 1], HEX_RADIX)
            if (high < 0 || low < 0) return null
            bytes[index] = ((high shl HEX_NIBBLE_BITS) or low).toByte()
        }
        return bytes
    }

    /** Спрашивает всё, что устройство готово рассказать. */
    public fun readAllVariables() {
        busy("fastboot_getvar_all", "all") { lane, _ ->
            FastbootConsoleState.Variables(FastbootGetVar(lane).readAll(), lane.state)
        }
    }

    /**
     * Загружает в буфер устройства [sizeBytes] порождённых приложением байт.
     *
     * Содержимое своё, а не выбранный файл: выбор файла — это artifact source
     * из Phase 8. Для протокольного пути этого достаточно, и тот же приём уже
     * принят для sync `SEND` (`07` §6.36).
     *
     * **Ничего не прошивает.** `download:` наполняет буфер загрузки; раздел
     * меняет `flash:`, которого в этой фазе нет вовсе.
     */
    public fun downloadGenerated(sizeBytes: Long) {
        busy("fastboot_download", sizeBytes.toString()) { lane, _ ->
            val outcome = FastbootDownload(lane).send(
                source = GeneratedPayloadStream(GeneratedPayload(sizeBytes)),
                sizeBytes = sizeBytes,
            )
            downloaded(sizeBytes, outcome, lane.state)
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
            busy("fastboot_flashall", if (request.flashAll) "flashall" else "update") { lane, _ ->
                FastbootFlashAll(lane, images(), request).run()
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
     * Читает раздел с устройства — фаза DATA IN.
     *
     * Содержимое нигде не собирается: приёмник считает байты и отпечаток, как
     * это уже принято для ADB `pull` (`07` §6.32). Сохранение в пользовательское
     * место требует artifact sink из Phase 8.
     *
     * **Ничего не меняет.** `fetch:` только читает; на запертом загрузчике
     * устройство, скорее всего, откажет — Legacy предупреждает об этом и
     * запрещать не пытается, решение принадлежит устройству.
     */
    public fun fetchPartition(partition: String) {
        busy("fastboot_fetch", partition) { lane, name ->
            val sink = DigestingSink()
            val outcome = FastbootFetch(lane, FastbootGetVar(lane))
                .fetch(name, sink)
            fetched(name, sink, outcome, lane.state)
        }
    }

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
        val live = lane
        val trimmed = raw.trim()
        when {
            live == null -> mutableConsole.value =
                FastbootConsoleState.NotAnswered(trimmed, "соединения нет", FastbootLaneState.CLOSED)

            mutableConsole.value is FastbootConsoleState.Running -> Unit

            else -> {
                mutableConsole.value = FastbootConsoleState.Running(trimmed, clock().toEpochMilli())
                executor.execute {
                    emit(event, mapOf("command" to trimmed))
                    val outcome = runCatching { action(live, trimmed) }
                    mutableConsole.value = outcome.getOrElse { failure ->
                        FastbootConsoleState.NotAnswered(
                            command = trimmed,
                            detail = failure.message ?: failure.javaClass.simpleName,
                            lane = live.state,
                        )
                    }
                    record(event, mutableConsole.value)
                }
            }
        }
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

    private fun probe(generation: SessionGeneration, claimed: UsbTransportHandle) {
        handle = claimed
        val opened = FastbootLane(claimed)
        lane = opened
        emit("fastboot_probe_started", mapOf("generation" to generation.value.toString()))

        val variables = FastbootGetVar(opened)
        val attempt = runCatching { FastbootModeProbe.probe(variables) }
        val identity = attempt.getOrElse { failure ->
            // Исключение — программистская ошибка, а не ответ устройства.
            // Роль от этого не становится известной, поэтому UNKNOWN с текстом.
            FastbootIdentity(FastbootMode.UNKNOWN, failure.message ?: failure.javaClass.simpleName)
        }

        // Замок читается тем же опросом, что и роль: он нужен до первого
        // разрушающего действия, а не в момент нажатия, и принадлежит этой
        // generation.
        val lock = runCatching { FastbootLockProbe.probe(variables) }.getOrElse { failure ->
            FastbootLockStatus(FastbootLockState.UNKNOWN, failure.message ?: failure.javaClass.simpleName)
        }

        pendingUnlock?.takeIf { it.generation != generation && lock.state == FastbootLockState.UNLOCKED }
            ?.let { pending ->
                val product = (variables.read("product") as? FastbootVariable.Present)?.value?.trim()
                val serial = (variables.read("serialno") as? FastbootVariable.Present)?.value?.trim()
                // Same model alone cannot identify the target when several phones are attached.
                if (pending.serial != null && pending.serial == serial && pending.product == product) {
                    mutableMiUnlock.value = MiUnlockStatus.Verified
                    pendingUnlock = null
                    emit("mi_unlock_verified", mapOf("outcome" to "unlocked"))
                }
            }

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
        mutableState.value = FastbootLinkState.Connected(generation, identity, lock)
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
        const val HEX_CHARS_PER_BYTE = 2
        const val HEX_RADIX = 16
        const val HEX_NIBBLE_BITS = 4
        const val MAX_UNLOCK_HEX_CHARS = 16 * 1024 * 1024
        const val FETCH = "fetch:"
        const val DIAGNOSTIC_CATEGORY = "fastboot"

        /** Вопросы, ответ на которые в выгрузку не идёт. Совпадение — по вопросу. */
        val SECRET_ANSWERS = listOf("token")

        const val HIDDEN = "не записано"
    }
}
