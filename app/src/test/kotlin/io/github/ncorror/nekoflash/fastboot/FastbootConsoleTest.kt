package io.github.ncorror.nekoflash.fastboot

import io.github.ncorror.nekoflash.core.diagnostics.InMemoryDiagnosticSink
import io.github.ncorror.nekoflash.core.model.SessionGeneration
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootCommands
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootLaneState
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootMutationClass
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootMutationOutcome
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootReply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Произвольная команда, переменная по имени и `getvar:all` в production.
 *
 * Проверяется не протокол — он закрыт тестами своего модуля, — а то, что исход
 * доходит до оператора и до журнала неискажённым: отказ устройства остаётся
 * отказом устройства, а незнание остаётся незнанием.
 */
class FastbootConsoleTest {
    @Test
    fun anArbitraryCommandReachesTheDeviceAsTyped() {
        val coordinator = ClaimingCoordinator(replies = PROBE + listOf("OKAY"))
        val controller = connected(coordinator)

        controller.runCommand("  oem device-info  ")

        assertEquals(
            "команда уходит без окружающих пробелов и без изменений",
            listOf("getvar:is-userspace", "getvar:unlocked", "oem device-info"),
            coordinator.lastHandle?.sent,
        )
    }

    /**
     * `FAIL` показывается как ответ устройства, а не как сбой.
     *
     * Иначе оператор не отличит «устройство сказало нет» от «мы не смогли
     * спросить», а это разные вещи (`03` §2).
     */
    @Test
    fun aDeviceRefusalIsShownAsAnAnswer() {
        val sink = InMemoryDiagnosticSink()
        val controller = connected(ClaimingCoordinator(PROBE + listOf("FAILunknown command")), sink)

        controller.runCommand("oem something")

        val state = controller.console.value as FastbootConsoleState.Mutated
        val refused = state.outcome as FastbootMutationOutcome.Refused
        assertEquals("unknown command", refused.detail)
        assertEquals("отказ рамку не портит", FastbootLaneState.IDLE, state.lane)
        assertEquals("refused", sink.snapshot().last().fields["claim"])
    }

    /** Строки `INFO` доходят до оператора, а не теряются по пути. */
    @Test
    fun theInfoLinesReachTheOperator() {
        val controller = connected(
            ClaimingCoordinator(PROBE + listOf("INFOerasing", "INFOdone", "OKAY")),
        )

        controller.runCommand("erase:cache")

        val state = controller.console.value as FastbootConsoleState.Mutated
        val applied = state.outcome as FastbootMutationOutcome.Applied
        assertEquals(listOf("erasing", "done"), applied.info)
        assertEquals(FastbootMutationClass.PARTITION, applied.mutation)
    }

    /** Молчание — это не отказ, и в журнале оно называется иначе. */
    @Test
    fun silenceIsReportedSeparatelyFromARefusal() {
        val sink = InMemoryDiagnosticSink()
        val controller = connected(ClaimingCoordinator(PROBE), sink)

        controller.runCommand("getvar:product")

        val state = controller.console.value as FastbootConsoleState.Mutated
        assertTrue(state.outcome is FastbootMutationOutcome.Unknown)
        assertEquals(FastbootLaneState.STALLED, state.lane)
        assertEquals("none", sink.snapshot().last().fields["reply"])
        assertEquals("unknown", sink.snapshot().last().fields["claim"])
    }

    /**
     * Та же тишина, та же полоса — и разные исходы, потому что команды разные.
     *
     * Набранный руками `erase:` обязан читаться как неизвестное состояние
     * раздела, а `reboot` — как уход по нашей же просьбе. До того, как
     * произвольная команда пошла через движок мутаций, обе писались в журнал
     * одинаково, и разобрать по нему прогон было бы нельзя.
     */
    @Test
    fun theSameSilenceIsJournalledDifferentlyForRebootAndForErase() {
        val eraseSink = InMemoryDiagnosticSink()
        connected(ClaimingCoordinator(PROBE), eraseSink).runCommand("erase:boot")
        val rebootSink = InMemoryDiagnosticSink()
        connected(ClaimingCoordinator(PROBE), rebootSink).runCommand("reboot-bootloader")

        assertEquals("unknown", eraseSink.snapshot().last().fields["claim"])
        assertEquals("PARTITION", eraseSink.snapshot().last().fields["mutation"])
        assertEquals("departed", rebootSink.snapshot().last().fields["claim"])
        assertEquals("REBOOT", rebootSink.snapshot().last().fields["mutation"])
    }

    /**
     * Кнопка типизованной секции и поле консоли — один путь.
     *
     * Проверяется не «кнопка работает», а что она не заводит второго обмена:
     * строка, собранная `FastbootCommands`, уходит через тот же `runCommand`,
     * и на устройстве видно ровно её.
     */
    @Test
    fun aTypedButtonAndTheRawFieldTravelTheSamePath() {
        val coordinator = ClaimingCoordinator(PROBE + listOf("OKAY", "OKAYb"))
        val controller = connected(coordinator)

        controller.runCommand(FastbootCommands.setActive("_B"))

        val state = controller.console.value as FastbootConsoleState.Mutated
        assertEquals("current-slot=b", (state.outcome as FastbootMutationOutcome.Applied).confirmation)
        assertEquals(
            listOf("getvar:is-userspace", "getvar:unlocked", "set_active:b", "getvar:current-slot"),
            coordinator.lastHandle?.sent,
        )
    }

    /**
     * Команда, которую провод не несёт, называется и не уходит.
     *
     * Набрать её никто не мешает — но отправить мы обязаны ровно набранное
     * либо ничего (ADR-0006 §4).
     */
    @Test
    fun aCommandTheWireCannotCarryIsNamedAndNotSent() {
        val coordinator = ClaimingCoordinator(PROBE + listOf("OKAY"))
        val controller = connected(coordinator)

        controller.runCommand("oem разблокировать")

        val state = controller.console.value as FastbootConsoleState.Mutated
        val notStarted = state.outcome as FastbootMutationOutcome.NotStarted
        assertTrue("причина должна быть названа", notStarted.detail.contains("ASCII"))
        assertEquals("на устройство ушёл только опрос", 2, coordinator.lastHandle?.sent?.size)
    }

    @Test
    fun aVariableIsReadByName() {
        val coordinator = ClaimingCoordinator(PROBE + listOf("OKAYvayu"))
        val controller = connected(coordinator)

        controller.readVariable("product")

        val state = controller.console.value as FastbootConsoleState.Answered
        assertEquals("vayu", state.payload)
        assertEquals(listOf("getvar:is-userspace", "getvar:unlocked", "getvar:product"), coordinator.lastHandle?.sent)
    }

    @Test
    fun theWholeVariableListIsParsedAndCounted() {
        val sink = InMemoryDiagnosticSink()
        val controller = connected(
            ClaimingCoordinator(PROBE + listOf("INFOproduct: vayu", "INFOsecure: yes", "OKAY")),
            sink,
        )

        controller.readAllVariables()

        val state = controller.console.value as FastbootConsoleState.Variables
        assertEquals("vayu", state.snapshot.value("product"))
        assertTrue(state.snapshot.complete)
        assertEquals("2", sink.snapshot().last().fields["variables"])
    }

    /** Без соединения команда не уходит и говорит почему. */
    @Test
    fun withoutAConnectionNothingIsSent() {
        val controller = FastbootLinkController({ RefusingCoordinator().claim() }, { it.run() }, InMemoryDiagnosticSink())

        controller.runCommand("getvar:product")

        val state = controller.console.value as FastbootConsoleState.NotAnswered
        assertEquals("соединения нет", state.detail)
        assertEquals(FastbootLaneState.CLOSED, state.lane)
    }

    /** Соединение отпущено — консоль возвращается в исходное, а не хранит старый ответ. */
    @Test
    fun disconnectingClearsTheConsole() {
        val controller = connected(ClaimingCoordinator(PROBE + listOf("OKAY")))
        controller.runCommand("getvar:product")

        controller.disconnect()

        assertEquals(FastbootConsoleState.Idle, controller.console.value)
    }

    /**
     * Ответы, которые съедает сам опрос.
     *
     * Их два, потому что опрос читает и роль, и замок: роль различает
     * загрузчик и `fastbootd`, замок задаёт форму предупреждения перед
     * разрушающим действием. Оба `no` — заперто и не userspace, как у vayu.
     */
    private val PROBE = listOf("OKAYno", "OKAYno")

    private fun connected(
        coordinator: ClaimingCoordinator,
        diagnostics: InMemoryDiagnosticSink = InMemoryDiagnosticSink(),
    ): FastbootLinkController {
        val controller = FastbootLinkController({ coordinator.claim() }, { it.run() }, diagnostics)
        controller.connect(SessionGeneration(1))
        return controller
    }

    /**
     * Расхождения и неразобранные строки называются в журнале, а значения — нет.
     *
     * Прогон §6.72 дал `duplicates=2 ignored=2`, и по журналу нельзя было
     * узнать какие: видно, что что-то есть, и не видно что. А значения не
     * записываются намеренно — среди них `token` разблокировки, и выгружать
     * весь ответ устройства в отчёт незачем.
     */
    @Test
    fun theJournalNamesDuplicatesAndIgnoredLinesButNotValues() {
        val sink = InMemoryDiagnosticSink()
        val controller = connected(
            ClaimingCoordinator(
                PROBE + listOf(
                    "INFOcurrent-slot: a",
                    "INFOcurrent-slot: b",
                    "INFOtoken: s3cret-value",
                    "INFOline without a colon",
                    "OKAY",
                ),
            ),
            sink,
        )

        controller.readAllVariables()

        val fields = sink.snapshot().last().fields
        assertEquals("current-slot", fields["duplicateNames"])
        assertEquals("1", fields["conflicting"])
        assertEquals("line without a colon", fields["ignoredLines"])
        assertTrue(
            "значения переменных в журнал не попадают",
            sink.snapshot().none { event -> event.fields.values.any { it.contains("s3cret-value") } },
        )
    }

    /**
     * Токен разблокировки не попадает в выгрузку — ни одним из трёх путей.
     *
     * Решение принято в §6.72 для `getvar:all`, где пишутся имена и счётчики,
     * но не значения. Два пути его обходили, и это нашлось при подготовке
     * гейта §6.78, а не после прогона: одиночное чтение переменной по имени и
     * ответ произвольной команды — а `oem get_token` как раз отдаёт токен.
     *
     * Оператор при этом не теряет ничего: значение показывается на экране
     * целиком. Скрыто оно только в архиве, который человек отдаёт кому-то ещё.
     */
    @Test
    fun theUnlockTokenNeverReachesTheDiagnosticsBundle() {
        val secret = "VQEBHgEQgHK4syxLQw4eZsMvvbcRywMEdmF5dQIEhNX7aQ"
        val sink = InMemoryDiagnosticSink()
        val controller = connected(ClaimingCoordinator(PROBE + listOf("OKAY$secret")), sink)

        controller.readVariable("token")

        val state = controller.console.value as FastbootConsoleState.Answered
        assertEquals("на экране значение видно целиком", secret, state.payload)
        assertTrue(
            "в выгрузке его нет",
            sink.snapshot().none { event -> event.fields.values.any { it.contains(secret) } },
        )
        assertTrue(
            "но видно, что ответ был и какой длины",
            sink.snapshot().last().fields["payload"]?.contains(secret.length.toString()) == true,
        )
    }

    /** Тот же токен тем же путём, но спрошенный вендорской командой. */
    @Test
    fun theSameTokenIsHiddenWhenAVendorCommandAsksForIt() {
        val secret = "AAAABBBBCCCCDDDD"
        val sink = InMemoryDiagnosticSink()
        val controller = connected(ClaimingCoordinator(PROBE + listOf("OKAY$secret")), sink)

        controller.runCommand("oem get_token")

        assertTrue(
            sink.snapshot().none { event -> event.fields.values.any { it.contains(secret) } },
        )
    }

    /** Обычный ответ при этом записывается как был: прячется не всё подряд. */
    @Test
    fun anOrdinaryAnswerIsStillJournalledAsItCame() {
        val sink = InMemoryDiagnosticSink()
        val controller = connected(ClaimingCoordinator(PROBE + listOf("OKAYvayu")), sink)

        controller.readVariable("product")

        assertEquals("vayu", sink.snapshot().last().fields["payload"])
    }

    /**
     * Чтение раздела: содержимое не хранится, а подтверждается счётом и отпечатком.
     *
     * Тот же приём уже принят для ADB `pull` (`07` §6.32). Раздел бывает на
     * несколько гигабайт, и положить его в память значило бы убить приложение
     * на первом же большом чтении.
     */
    @Test
    fun aFetchIsConfirmedByBytesAndDigestRatherThanKeptInMemory() {
        val sink = InMemoryDiagnosticSink()
        // Шестнадцать печатных символов служат шестнадцатью байтами фазы
        // данных: по проводу кадр и содержимое ничем не отличаются, направление
        // задаёт команда. Именно это и надо воспроизвести.
        val controller = connected(
            ClaimingCoordinator(
                PROBE + listOf("FAILno", "FAILno", "DATA00000010", "0123456789abcdef", "OKAY"),
            ),
            sink,
        )

        controller.fetchPartition("boot")

        val state = controller.console.value as FastbootConsoleState.Fetched
        assertEquals(16L, state.bytes)
        assertTrue("отпечаток должен быть посчитан", state.sha256.length == 64)
        assertTrue(state.complete)
    }

    /**
     * Частичное чтение называется частичным — и в состоянии, и в журнале.
     *
     * Выдать его за раздел нельзя: недостающий кусок снаружи не отличить от
     * нулей внутри (`03` §3).
     */
    @Test
    fun aPartialFetchIsNeverReportedAsComplete() {
        val sink = InMemoryDiagnosticSink()
        val controller = connected(
            ClaimingCoordinator(PROBE + listOf("FAILno", "FAILno", "FAILFetch is not allowed in Lock State")),
            sink,
        )

        controller.fetchPartition("boot")

        val state = controller.console.value as FastbootConsoleState.Fetched
        assertEquals(0L, state.bytes)
        assertTrue("отказ полным чтением не считается", !state.complete)
        assertEquals("false", sink.snapshot().last().fields["complete"])
    }

    /**
     * Загрузка доходит до устройства и её исход не приукрашивается.
     *
     * Проверяется главное поле — `untouched`: утверждать «ничего не
     * изменилось» можно только при отказе до фазы данных.
     */
    @Test
    fun aDownloadReportsHowManyBytesWentAndWhetherAnythingChanged() {
        val coordinator = ClaimingCoordinator(PROBE + listOf("DATA00001000", "OKAY"))
        val sink = InMemoryDiagnosticSink()
        val controller = connected(coordinator, sink)
        coordinator.lastHandle?.dataAfterCommands = 3

        controller.downloadGenerated(4096)

        val state = controller.console.value as FastbootConsoleState.Downloaded
        assertEquals(4096L, state.sentBytes)
        assertEquals(4096L, state.declaredBytes)
        assertEquals(FastbootReply.OKAY, state.reply)
        assertEquals("байты ушли — «не изменилось» уже неверно", false, state.untouched)
        assertEquals("4096", sink.snapshot().last().fields["sentBytes"])
        assertEquals("false", sink.snapshot().last().fields["untouched"])
    }

    /**
     * Отказ до фазы данных — единственный случай, когда «не изменилось» правда.
     *
     * Ни одного байта не отправлено, буфер устройства не тронут.
     */
    @Test
    fun aRefusalBeforeTheDataPhaseIsTheOnlyUntouchedOutcome() {
        val sink = InMemoryDiagnosticSink()
        val controller = connected(ClaimingCoordinator(PROBE + listOf("FAILtoo large")), sink)

        controller.downloadGenerated(4096)

        val state = controller.console.value as FastbootConsoleState.Downloaded
        assertEquals(0L, state.sentBytes)
        assertEquals("до данных не дошло — состояние известно", true, state.untouched)
        assertEquals("true", sink.snapshot().last().fields["untouched"])
    }

    /** Оборванная передача — неизвестность, и «не изменилось» про неё сказать нельзя. */
    @Test
    fun anInterruptedDownloadNeverClaimsNothingChanged() {
        val coordinator = ClaimingCoordinator(PROBE + listOf("DATA00001000", "OKAY"))
        val controller = connected(coordinator)
        coordinator.lastHandle?.dataAfterCommands = 3
        coordinator.lastHandle?.failDataWrite = true

        controller.downloadGenerated(4096)

        val state = controller.console.value as FastbootConsoleState.Downloaded
        assertEquals("ответа устройства не было", null, state.reply)
        assertEquals("буфер неизвестен", false, state.untouched)
        assertEquals(FastbootLaneState.STALLED, state.lane)
    }
}
