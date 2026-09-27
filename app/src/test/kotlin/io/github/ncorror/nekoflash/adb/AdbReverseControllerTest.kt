package io.github.ncorror.nekoflash.adb

import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticEvent
import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticSink
import io.github.ncorror.nekoflash.protocol.adb.AdbByteChannel
import io.github.ncorror.nekoflash.protocol.adb.AdbForwardEnd
import io.github.ncorror.nekoflash.protocol.adb.AdbForwardOutcome
import io.github.ncorror.nekoflash.protocol.adb.AdbReverseOutcome
import io.github.ncorror.nekoflash.protocol.adb.AdbServiceFailure
import io.github.ncorror.nekoflash.protocol.adb.AdbStreamDispatcher
import io.github.ncorror.nekoflash.protocol.adb.AdbStreamMailbox
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Обратный проброс со стороны приложения.
 *
 * Здесь проверяется не протокол — он живёт в `AdbReverseTest`, — а то, что
 * делает приложение: кого ждать, куда идти и что сказать, когда дойти нельзя.
 */
class AdbReverseControllerTest {
    private val pool = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "reverse-controller-test").apply { isDaemon = true }
    }

    @After
    fun stopPool() {
        pool.shutdownNow()
    }

    @Test
    fun anAcceptedRequestIsRememberedWithWhatTheDeviceAnswered() {
        val source = FakeSource(AdbReverseOutcome.Accepted(SERVICE, "7777"))
        val controller = controller()
        controller.bind(source)

        controller.add(onDevice = "tcp:7777", onHost = HOST)

        assertTrue(await { controller.state.value.reverses.size == 1 })
        val entry = controller.state.value.reverses.single()
        assertEquals("tcp:7777", entry.onDevice)
        assertEquals(HOST, entry.onHost)
        assertEquals("7777", entry.assigned)
    }

    /** Отказ устройства — видимый отказ, а не запомненный проброс. */
    @Test
    fun aRefusedRequestIsNotRemembered() {
        val source = FakeSource(AdbReverseOutcome.Failed(SERVICE, "cannot bind"))
        val controller = controller()
        controller.bind(source)

        controller.add("tcp:7777", HOST)

        assertTrue(await { controller.state.value.failure != null })
        assertTrue(controller.state.value.reverses.isEmpty())
    }

    /** Пустые поля — не ошибка, а нечего делать. */
    @Test
    fun emptyFieldsDoNothingAtAll() {
        val source = FakeSource(AdbReverseOutcome.Accepted(SERVICE, ""))
        val controller = controller()
        controller.bind(source)

        controller.add("  ", HOST)
        controller.add("tcp:7777", " ")

        assertFalse(await(QUIET_MS) { source.asked.isNotEmpty() })
    }

    /** Ждём ровно тот адрес, о котором просили, и ничей чужой. */
    @Test
    fun onlyTheExpectedAddressIsWanted() {
        val controller = controller()
        controller.bind(FakeSource(AdbReverseOutcome.Accepted(SERVICE, "7777")))
        controller.add("tcp:7777", HOST)
        assertTrue(await { controller.state.value.reverses.isNotEmpty() })

        assertTrue(controller.wants(HOST))
        assertFalse(controller.wants("tcp:9999"))
    }

    /** Пока не просили — не ждём никого. */
    @Test
    fun nothingIsWantedBeforeAnythingIsAsked() {
        assertFalse(controller().wants(HOST))
    }

    /** Принятый поток доводится до адреса на хосте и качается. */
    @Test
    fun anIncomingStreamIsCarriedToTheHostAddress() {
        val source = FakeSource(AdbReverseOutcome.Accepted(SERVICE, "7777"))
        val channel = SilentChannel()
        val controller = controller(connect = { port ->
            source.ports += port
            channel
        })
        controller.bind(source)
        controller.add("tcp:7777", HOST)
        assertTrue(await { controller.state.value.reverses.isNotEmpty() })

        controller.accepted(HOST, mailbox())

        assertTrue(await { source.adopted.isNotEmpty() })
        assertEquals(listOf(8888), source.ports)
    }

    /** Итог соединения запоминается: оператор должен видеть, чем оно кончилось. */
    @Test
    fun theEndOfAnIncomingConnectionIsRemembered() {
        val source = FakeSource(AdbReverseOutcome.Accepted(SERVICE, "7777"))
        val controller = controller(connect = { SilentChannel() })
        controller.bind(source)
        controller.add("tcp:7777", HOST)
        assertTrue(await { controller.state.value.reverses.isNotEmpty() })

        controller.accepted(HOST, mailbox())

        assertTrue(await { controller.state.value.reverses.single().brought == 1 })
        assertTrue(controller.state.value.reverses.single().lastEnd.orEmpty().contains("DEVICE_CLOSED"))
    }

    /** До адреса не дошли — причина названа, а не заминается. */
    @Test
    fun anUnreachableHostAddressIsNamed() {
        val source = FakeSource(AdbReverseOutcome.Accepted(SERVICE, "7777"))
        val sink = RecordingSink()
        val controller = controller(sink) { throw IOException("connection refused") }
        controller.bind(source)
        controller.add("tcp:7777", HOST)
        assertTrue(await { controller.state.value.reverses.isNotEmpty() })

        controller.accepted(HOST, mailbox())

        assertTrue(await { sink.messages().contains("reverse_unreachable") })
        assertTrue(
            await { controller.state.value.reverses.single().lastEnd.orEmpty().contains("HOST_UNREACHABLE") },
        )
    }

    /**
     * Адрес на хосте, до которого мы не умеем дойти, называется, а не молчит.
     *
     * `localabstract:` на **нашей** стороне не наблюдался, и придумывать, что с
     * ним делать, нельзя. Но и принять поток, а потом молча его бросить — тоже.
     */
    @Test
    fun anAddressWeCannotReachIsNamedRatherThanIgnored() {
        val source = FakeSource(AdbReverseOutcome.Accepted(SERVICE, ""))
        val sink = RecordingSink()
        val controller = controller(sink)
        controller.bind(source)
        controller.add("tcp:7777", "localabstract:whatever")
        assertTrue(await { controller.state.value.reverses.isNotEmpty() })

        controller.accepted("localabstract:whatever", mailbox())

        assertTrue(await { sink.messages().contains("reverse_unroutable") })
        assertTrue("до адреса не ходили", source.ports.isEmpty())
    }

    @Test
    fun theHostPortIsReadOnlyFromATcpAddress() {
        assertEquals(8888, AdbReverseController.hostPortOf("tcp:8888"))
        assertEquals(8888, AdbReverseController.hostPortOf("  tcp:8888 "))
        assertNull(AdbReverseController.hostPortOf("localabstract:x"))
        assertNull(AdbReverseController.hostPortOf("tcp:not-a-port"))
        assertNull(AdbReverseController.hostPortOf("8888"))
    }

    /** Список — ответ устройства, и пустой список отличается от «не спрашивали». */
    @Test
    fun anEmptyListingIsShownAsAnAnswer() {
        val source = FakeSource(AdbReverseOutcome.Accepted(SERVICE, ""))
        val controller = controller()
        controller.bind(source)

        controller.refresh()

        assertTrue(await { controller.state.value.listing != null })
        assertTrue(controller.state.value.listing.orEmpty().isNotEmpty())
    }

    /** Транспорта нет — ждать некого: иначе приняли бы поток в никуда. */
    @Test
    fun aDeadTransportForgetsEveryExpectation() {
        val controller = controller()
        controller.bind(FakeSource(AdbReverseOutcome.Accepted(SERVICE, "7777")))
        controller.add("tcp:7777", HOST)
        assertTrue(await { controller.state.value.reverses.isNotEmpty() })

        controller.bind(null)

        assertTrue(controller.state.value.reverses.isEmpty())
        assertFalse(controller.wants(HOST))
    }

    /** Без соединения запрос не уходит вовсе. */
    @Test
    fun withoutAConnectionNothingIsAsked() {
        val controller = controller()

        controller.add("tcp:7777", HOST)
        controller.refresh()
        controller.removeAll()

        assertFalse(await(QUIET_MS) { controller.state.value.failure != null })
    }

    private fun controller(
        sink: DiagnosticSink = DiagnosticSink { },
        connect: (Int) -> AdbByteChannel = { SilentChannel() },
    ) = AdbReverseController(executor = pool, diagnostics = sink, connect = connect)

    /**
     * Живой ящик оттуда, откуда его берут по-настоящему.
     *
     * Завести его напрямую нельзя и не нужно: конструктор принадлежит
     * протокольному модулю, потому что ящики заводит диспетчер, и ничто другое.
     */
    private fun mailbox(): AdbStreamMailbox = AdbStreamDispatcher().open("tcp:8888").first

    private fun await(timeoutMillis: Long = AWAIT_MS, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(POLL_MS)
        }
        return condition()
    }

    /** Соединение, которое сразу говорит, что устройство закрыло поток. */
    private class FakeSource(private val answer: AdbReverseOutcome) : AdbReverseSource {
        val asked: MutableList<String> = CopyOnWriteArrayList()
        val adopted: MutableList<AdbStreamMailbox> = CopyOnWriteArrayList()
        val ports: MutableList<Int> = CopyOnWriteArrayList()

        override fun request(onDevice: String, onHost: String): AdbReverseOutcome {
            asked += "$onDevice;$onHost"
            return answer
        }

        override fun list(): AdbReverseOutcome = answer

        override fun killAll(): AdbReverseOutcome = answer

        override fun adopt(mailbox: AdbStreamMailbox, channel: AdbByteChannel): AdbForwardConnection {
            adopted += mailbox
            return object : AdbForwardConnection {
                override fun open(address: String): AdbForwardOutcome? = null

                override fun fromDevice(): AdbForwardOutcome =
                    AdbForwardOutcome(AdbForwardEnd.DEVICE_CLOSED, "device closed the stream", 0, 0)

                override fun fromClient() = Unit

                override fun cancel(detail: String) = Unit
            }
        }
    }

    private class SilentChannel : AdbByteChannel {
        override fun read(destination: ByteArray): Int = -1

        override fun write(source: ByteArray, length: Int) = Unit

        override fun close() = Unit
    }

    private class RecordingSink : DiagnosticSink {
        private val events = CopyOnWriteArrayList<DiagnosticEvent>()

        override fun emit(event: DiagnosticEvent) {
            events += event
        }

        fun messages(): List<String> = events.map { event -> event.message }
    }

    private companion object {
        const val HOST = "tcp:8888"
        const val SERVICE = "reverse:forward:tcp:7777;tcp:8888"
        const val AWAIT_MS = 5_000L
        const val QUIET_MS = 200L
        const val POLL_MS = 5L
    }
}
