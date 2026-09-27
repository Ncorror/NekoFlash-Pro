package io.github.ncorror.nekoflash.fastboot

import io.github.ncorror.nekoflash.core.diagnostics.InMemoryDiagnosticSink
import io.github.ncorror.nekoflash.core.model.SessionGeneration
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootLockState
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootMode
import io.github.ncorror.nekoflash.usb.api.UsbDeviceDescriptor
import io.github.ncorror.nekoflash.usb.api.UsbEndpointDescriptor
import io.github.ncorror.nekoflash.usb.api.UsbEndpointDirection
import io.github.ncorror.nekoflash.usb.api.UsbInterfaceClassifier
import io.github.ncorror.nekoflash.usb.api.UsbInterfaceDescriptor
import io.github.ncorror.nekoflash.usb.api.UsbSession
import io.github.ncorror.nekoflash.usb.api.UsbSessionClosureReason
import io.github.ncorror.nekoflash.usb.api.UsbSessionState
import io.github.ncorror.nekoflash.usb.api.UsbTargetIdentity
import io.github.ncorror.nekoflash.usb.api.UsbTransferType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Владелец Fastboot-соединения.
 *
 * Здесь проверяется не протокол — он закрыт тестами своего модуля, — а то, что
 * результат опроса доходит до состояния и до журнала неискажённым. Особенно
 * `UNKNOWN`: он обязан оставаться отдельным исходом с названной причиной.
 */
class FastbootLinkControllerTest {
    @Test
    fun aClaimFailureNeverReachesTheProtocol() {
        val sink = InMemoryDiagnosticSink()
        val controller = FastbootLinkController(
            claim = { RefusingCoordinator().claim() },
            executor = { it.run() },
            diagnostics = sink,
        )

        controller.connect(SessionGeneration(1))

        val state = controller.state.value
        assertTrue("до обмена дойти не должно", state is FastbootLinkState.Failed)
        assertEquals("fastboot_claim_failed", sink.snapshot().single().message)
    }

    @Test
    fun theProbeResultReachesTheStateAndTheJournal() {
        val sink = InMemoryDiagnosticSink()
        val controller = FastbootLinkController(
            claim = { ClaimingCoordinator(replies = listOf("OKAYyes", "OKAYyes")).claim() },
            executor = { it.run() },
            diagnostics = sink,
        )

        controller.connect(SessionGeneration(7))

        val connected = controller.state.value as FastbootLinkState.Connected
        assertEquals(FastbootMode.FASTBOOTD, connected.identity.mode)
        assertEquals(FastbootLockState.UNLOCKED, connected.lock.state)

        val finished = sink.snapshot().last()
        assertEquals("fastboot_probe_finished", finished.message)
        assertEquals("FASTBOOTD", finished.fields["mode"])
        assertEquals("UNLOCKED", finished.fields["lock"])
    }

    /**
     * Замок читается тем же опросом и принадлежит этой generation.
     *
     * Проверяется не значение, а то, что состояние доходит до экрана и до
     * журнала: форму предупреждения задаёт оно, и подставить сюда догадку
     * значило бы спрашивать слово подтверждения не тогда, когда положено.
     */
    @Test
    fun theLockStateIsReadByTheSameProbe() {
        val sink = InMemoryDiagnosticSink()
        val controller = FastbootLinkController(
            claim = { ClaimingCoordinator(replies = listOf("OKAYno", "OKAYno")).claim() },
            executor = { it.run() },
            diagnostics = sink,
        )

        controller.connect(SessionGeneration(4))

        val connected = controller.state.value as FastbootLinkState.Connected
        assertEquals(FastbootLockState.LOCKED, connected.lock.state)
        assertTrue("подтверждённый замок требует слова", connected.lock.typedConfirmation)
        assertEquals("LOCKED", sink.snapshot().last().fields["lock"])
    }

    /**
     * Устройство не знает про замок — это **не** `LOCKED`.
     *
     * `03` §5.1 требует для таких случаев обычный advisory без typed
     * confirmation: иначе незнание превращается в запрет, а отменённый guard
     * возвращается через чёрный ход.
     */
    @Test
    fun anUnreadableLockIsNotTreatedAsLocked() {
        val sink = InMemoryDiagnosticSink()
        val controller = FastbootLinkController(
            claim = { ClaimingCoordinator(replies = listOf("OKAYno", "FAILnot found")).claim() },
            executor = { it.run() },
            diagnostics = sink,
        )

        controller.connect(SessionGeneration(5))

        val connected = controller.state.value as FastbootLinkState.Connected
        assertEquals(FastbootLockState.UNKNOWN, connected.lock.state)
        assertTrue("слова подтверждения не требуем", !connected.lock.typedConfirmation)
        assertTrue(
            "причина должна быть названа",
            sink.snapshot().last().fields["lockDetail"]?.contains("not found") == true,
        )
    }

    /**
     * Неустановленная роль — тоже результат, и причина обязана быть в журнале.
     *
     * Иначе разбор прогона упрётся в то же, во что упирались §6.52 и §6.55:
     * видно, что не получилось, и не видно почему.
     */
    @Test
    fun anUnknownModeIsRecordedWithItsReason() {
        val sink = InMemoryDiagnosticSink()
        val controller = FastbootLinkController(
            claim = { ClaimingCoordinator(replies = listOf("FAILunknown variable", "FAILunknown variable")).claim() },
            executor = { it.run() },
            diagnostics = sink,
        )

        controller.connect(SessionGeneration(3))

        val connected = controller.state.value as FastbootLinkState.Connected
        assertEquals(FastbootMode.UNKNOWN, connected.identity.mode)

        val finished = sink.snapshot().last()
        assertEquals("UNKNOWN", finished.fields["mode"])
        assertTrue(
            "причина должна быть названа, а не подразумеваться",
            finished.fields["detail"]?.contains("unknown variable") == true,
        )
    }

    /**
     * Переподключённый кабель закрывает сессию — и связь уходит вместе с ней.
     *
     * Прогон этапа 2: после переподключения экран держал «Загрузчик» сессии 1,
     * а команды отвечали `NOT_HELD`. Новая сессия при этом сама не
     * захватывается: опрос остаётся за оператором.
     */
    @Test
    fun aClosedSessionDropsItsLink() {
        val sink = InMemoryDiagnosticSink()
        val controller = FastbootLinkController(
            claim = { ClaimingCoordinator(replies = listOf("OKAYno", "OKAYyes")).claim() },
            executor = { it.run() },
            diagnostics = sink,
        )
        controller.connect(SessionGeneration(1))

        controller.onUsbSessionsChanged(listOf(fastbootSession(1, UsbSessionState.CLAIMED)))
        assertTrue("живая сессия связь не теряет", controller.state.value is FastbootLinkState.Connected)

        controller.onUsbSessionsChanged(
            listOf(
                fastbootSession(1, UsbSessionState.CLOSED, UsbSessionClosureReason.DETACHED),
                fastbootSession(2, UsbSessionState.READY),
            ),
        )

        assertEquals(FastbootLinkState.Idle, controller.state.value)
        assertEquals(FastbootConsoleState.Idle, controller.console.value)
        assertEquals("fastboot_link_dropped", sink.snapshot().last().message)
        assertEquals("1", sink.snapshot().last().fields["generation"])
    }

    /** Отказ захвата остаётся на экране, пока его сессия существует. */
    @Test
    fun aFailureStaysWhileItsSessionIsPresent() {
        val controller = FastbootLinkController(
            claim = { RefusingCoordinator().claim() },
            executor = { it.run() },
            diagnostics = InMemoryDiagnosticSink(),
        )
        controller.connect(SessionGeneration(3))

        controller.onUsbSessionsChanged(listOf(fastbootSession(3, UsbSessionState.READY)))
        assertTrue(controller.state.value is FastbootLinkState.Failed)

        controller.onUsbSessionsChanged(emptyList())
        assertEquals(FastbootLinkState.Idle, controller.state.value)
    }

    private fun fastbootSession(
        generation: Long,
        state: UsbSessionState,
        closure: UsbSessionClosureReason? = null,
    ): UsbSession {
        val device = UsbDeviceDescriptor(
            deviceId = 1002,
            deviceName = "/dev/bus/usb/001/002",
            vendorId = 0x18D1,
            productId = 0xD00D,
            productName = "Android",
            serialNumber = "SERIAL",
            interfaces = listOf(
                UsbInterfaceDescriptor(
                    id = 0,
                    interfaceClass = UsbInterfaceClassifier.USB_CLASS_VENDOR_SPECIFIC,
                    interfaceSubclass = UsbInterfaceClassifier.ANDROID_USB_SUBCLASS,
                    interfaceProtocol = UsbInterfaceClassifier.FASTBOOT_INTERFACE_PROTOCOL,
                    endpoints = listOf(
                        UsbEndpointDescriptor(0x81, UsbEndpointDirection.IN, UsbTransferType.BULK, 512),
                        UsbEndpointDescriptor(0x01, UsbEndpointDirection.OUT, UsbTransferType.BULK, 512),
                    ),
                ),
            ),
        )
        return UsbSession(
            generation = SessionGeneration(generation),
            targetId = UsbTargetIdentity.fromDescriptor(device).id,
            identity = UsbTargetIdentity.fromDescriptor(device),
            candidate = UsbInterfaceClassifier.primaryCandidate(device)!!,
            state = state,
            closureReason = closure,
        )
    }

    /** Состояние полосы попадает в журнал: по нему видно, цела ли рамка. */
    @Test
    fun theLaneStateIsRecordedAlongsideTheOutcome() {
        val sink = InMemoryDiagnosticSink()
        val controller = FastbootLinkController(
            claim = { ClaimingCoordinator(replies = listOf("OKAYno", "OKAYno")).claim() },
            executor = { it.run() },
            diagnostics = sink,
        )

        controller.connect(SessionGeneration(2))

        assertEquals("IDLE", sink.snapshot().last().fields["lane"])
    }

    @Test
    fun disconnectingReleasesTheInterfaceAndClearsTheState() {
        val coordinator = ClaimingCoordinator(replies = listOf("OKAYno", "OKAYno"))
        val controller = FastbootLinkController({ coordinator.claim() }, { it.run() }, InMemoryDiagnosticSink())
        controller.connect(SessionGeneration(1))

        controller.disconnect()

        assertEquals(FastbootLinkState.Idle, controller.state.value)
        assertTrue("интерфейс должен быть отпущен", coordinator.lastHandle?.closed == true)
    }
}
