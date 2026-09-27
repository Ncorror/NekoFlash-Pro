package io.github.ncorror.nekoflash.adb

import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticEvent
import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticSink
import io.github.ncorror.nekoflash.protocol.adb.AdbByteChannel
import io.github.ncorror.nekoflash.protocol.adb.AdbConnection
import io.github.ncorror.nekoflash.protocol.adb.AdbForwardOutcome
import io.github.ncorror.nekoflash.protocol.adb.AdbInboundStreams
import io.github.ncorror.nekoflash.protocol.adb.AdbReverseOutcome
import io.github.ncorror.nekoflash.protocol.adb.AdbStreamMailbox
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Один обратный проброс в том виде, в каком его показывает экран. */
public data class AdbReverseEntry(
    /** Что слушает устройство, как его назвал оператор. */
    val onDevice: String,
    /** Куда на хосте идут соединения. */
    val onHost: String,
    /** Что устройство ответило на запрос; для `tcp:` там оказался порт. */
    val assigned: String,
    /** Сколько соединений устройство привело за жизнь проброса. */
    val brought: Int,
    /** Чем кончилось последнее из них; `null` — ещё ни одного. */
    val lastEnd: String?,
)

/** Состояние обратных пробросов. */
public data class AdbReverseState(
    val reverses: List<AdbReverseEntry> = emptyList(),
    /** Что устройство ответило на последний запрос списка; `null` — не спрашивали. */
    val listing: String? = null,
    /** Почему не удалось последнее действие; `null` — всё получилось. */
    val failure: String? = null,
) {
    public companion object {
        public val None: AdbReverseState = AdbReverseState()
    }
}

/** Как обратиться к устройству и как завести соединение поверх принятого потока. */
public interface AdbReverseSource {
    /** Шлёт запрос семейства `reverse:` и возвращает ответ устройства. */
    public fun request(onDevice: String, onHost: String): AdbReverseOutcome

    /** Спрашивает, что устройство слушает сейчас. */
    public fun list(): AdbReverseOutcome

    /** Снимает все обратные пробросы разом. */
    public fun killAll(): AdbReverseOutcome

    /** Берёт поток, заведённый устройством, и качает его в [channel]. */
    public fun adopt(mailbox: AdbStreamMailbox, channel: AdbByteChannel): AdbForwardConnection
}

/**
 * Обратный проброс: слушает устройство, соединения приходят к нам.
 *
 * Зеркало [AdbForwardController] и его противоположность. Там слушатель наш и
 * клиент приходит к нам снаружи; здесь слушает устройство, а мы на каждое
 * соединение **сами идём** к адресу на хосте — тому, который назвал оператор
 * (`docs/adr/0005_LOCAL_SOCKET_FORWARDING_RU.md` §1).
 *
 * **Адрес на хосте понимается только как `tcp:порт`, и это ограничение
 * наблюдения, а не возможностей.** Устройство присылает имя сервиса, как его
 * записали в запросе, и `tcp:8888` мы умеем превратить в соединение с
 * `127.0.0.1:8888`. Что делать с `localabstract:` на стороне хоста, мы не
 * наблюдали, и выдумывать не будем: такой поток получит отказ с названной
 * причиной, а не тишину.
 *
 * Запросить при этом можно **что угодно**: адрес уходит на устройство как
 * набран, и его отказ — это ответ, а не наша ошибка (`01` §3).
 */
public class AdbReverseController(
    private val executor: Executor,
    private val diagnostics: DiagnosticSink = DiagnosticSink { },
    private val clock: () -> Instant = { Clock.systemUTC().instant() },
    /** Как дойти до адреса на хосте. Подменяется в тестах. */
    private val connect: (Int) -> AdbByteChannel = { port ->
        AdbSocketChannel(Socket(InetAddress.getByAddress(LOOPBACK_V4), port))
    },
) : AdbInboundStreams {
    private val mutableState = MutableStateFlow(AdbReverseState.None)

    /** Что мы просили слушать, по адресу на хосте: по нему приходят потоки. */
    private val expected = ConcurrentHashMap<String, Reverse>()

    @Volatile
    private var source: AdbReverseSource? = null

    /** Состояние обратных пробросов. Экран подписывается и ничего не опрашивает. */
    public val state: StateFlow<AdbReverseState> = mutableState.asStateFlow()

    /** Привязывает контроллер к живому соединению; `null` — соединения больше нет. */
    public fun bind(live: AdbReverseSource?) {
        source = live
        if (live == null) forget()
    }

    /**
     * Просит устройство слушать [onDevice] и приводить соединения к [onHost].
     *
     * Пустые поля — не ошибка, а нечего делать.
     */
    public fun add(onDevice: String, onHost: String) {
        val device = onDevice.trim()
        val host = onHost.trim()
        if (device.isEmpty() || host.isEmpty()) return
        val live = source ?: return
        executor.execute { ask(live, device, host) }
    }

    /** Спрашивает устройство, что оно слушает. */
    public fun refresh() {
        val live = source ?: return
        executor.execute {
            when (val outcome = live.list()) {
                is AdbReverseOutcome.Accepted -> publish(listing = outcome.body.ifEmpty { EMPTY_LISTING })
                else -> fail(describe(outcome))
            }
        }
    }

    /** Снимает все обратные пробросы разом — и на устройстве, и у себя. */
    public fun removeAll() {
        val live = source ?: return
        executor.execute {
            val outcome = live.killAll()
            expected.clear()
            emit("reverse_removed_all", mapOf("outcome" to describe(outcome)))
            if (outcome is AdbReverseOutcome.Accepted) publish(listing = null) else fail(describe(outcome))
        }
    }

    override fun wants(service: String): Boolean = expected.containsKey(service.trim())

    /**
     * Поток принят: идём к адресу на хосте и качаем.
     *
     * Возврат обязан быть немедленным — вызов идёт под замком диспетчера, из
     * потока цикла раскладки, и задержка здесь останавливает приём для всех
     * потоков разом. Поэтому работа уходит на пул сразу же.
     */
    override fun accepted(service: String, mailbox: AdbStreamMailbox) {
        executor.execute { serve(service.trim(), mailbox) }
    }

    private fun ask(live: AdbReverseSource, device: String, host: String) {
        when (val outcome = live.request(device, host)) {
            is AdbReverseOutcome.Accepted -> {
                expected[host] = Reverse(device, host, outcome.body)
                emit(
                    "reverse_listening",
                    mapOf("onDevice" to device, "onHost" to host, "assigned" to outcome.body),
                )
                publish(failure = null)
            }

            else -> fail(describe(outcome))
        }
    }

    private fun serve(service: String, mailbox: AdbStreamMailbox) {
        val reverse = expected[service]
        val port = hostPortOf(service)
        if (reverse == null || port == null) {
            // Причина называется, а не заминается: поток приняли, а дойти до
            // адреса не можем, и оператор должен знать, почему.
            emit("reverse_unroutable", mapOf("service" to service))
            return
        }
        val channel = try {
            connect(port)
        } catch (failure: IOException) {
            val detail = failure.message ?: failure.javaClass.simpleName
            emit("reverse_unreachable", mapOf("service" to service, "detail" to detail))
            reverse.closed("HOST_UNREACHABLE: $detail")
            publish()
            return
        }
        pump(live = requireNotNull(source), reverse = reverse, mailbox = mailbox, channel = channel)
    }

    private fun pump(
        live: AdbReverseSource,
        reverse: Reverse,
        mailbox: AdbStreamMailbox,
        channel: AdbByteChannel,
    ) {
        val connection = live.adopt(mailbox, channel)
        publish()
        executor.execute { connection.fromClient() }
        val outcome = connection.fromDevice()
        reverse.closed("${outcome.end.name}: ${outcome.detail} (${outcome.fromClient}/${outcome.fromDevice} bytes)")
        publish()
    }

    private fun forget() {
        if (expected.isEmpty()) return
        expected.clear()
        emit("reverse_forgotten", mapOf("reason" to "transport is gone"))
        publish()
    }

    private fun fail(detail: String) {
        emit("reverse_failed", mapOf("detail" to detail))
        publish(failure = detail)
    }

    private fun publish(
        failure: String? = mutableState.value.failure,
        listing: String? = mutableState.value.listing,
    ) {
        mutableState.value = AdbReverseState(
            reverses = expected.values.map { reverse -> reverse.entry() }.sortedBy { it.onHost },
            listing = listing,
            failure = failure,
        )
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

    /** Один обратный проброс и его счёт приведённых соединений. */
    private class Reverse(val onDevice: String, val onHost: String, val assigned: String) {
        private val brought = AtomicInteger()

        @Volatile
        private var lastEnd: String? = null

        fun closed(end: String) {
            brought.incrementAndGet()
            lastEnd = end
        }

        fun entry(): AdbReverseEntry = AdbReverseEntry(
            onDevice = onDevice,
            onHost = onHost,
            assigned = assigned,
            brought = brought.get(),
            lastEnd = lastEnd,
        )
    }

    public companion object {
        /**
         * Порт на хосте из имени сервиса.
         *
         * `null` означает, что дойти по такому адресу мы не умеем. Умеем пока
         * только `tcp:`: что делать с `localabstract:` на **нашей** стороне, не
         * наблюдалось, и придумывать это без наблюдения нельзя.
         */
        public fun hostPortOf(service: String): Int? =
            service.trim().removePrefix(TCP).takeIf { it != service.trim() }?.toIntOrNull()

        private const val TCP = "tcp:"
        private const val DIAGNOSTIC_CATEGORY = "adb"

        /** Пустой список — это ответ, и на экране он должен отличаться от «не спрашивали». */
        private const val EMPTY_LISTING = "(пусто)"

        private val LOOPBACK_V4 = byteArrayOf(127, 0, 0, 1)

        private fun describe(outcome: AdbReverseOutcome): String = when (outcome) {
            is AdbReverseOutcome.Accepted -> outcome.body.ifEmpty { "OK" }
            is AdbReverseOutcome.Failed -> outcome.detail
            is AdbReverseOutcome.Unreachable -> "${outcome.reason.name}: ${outcome.detail}"
        }
    }
}

/**
 * Обратный проброс поверх живого соединения.
 *
 * Живёт здесь, а не в контроллере транспорта, и это не вкусовщина: счётчик
 * detekt сказал о том же, о чём уже говорил на пробросах — контроллер
 * соединения знает слишком много. Собирать источник возможности рядом с самой
 * возможностью правильнее, чем рядом с транспортом.
 */
internal fun AdbConnection.reverseSource(diagnostics: DiagnosticSink): AdbReverseSource {
    val requests = reverse(diagnostics)
    return object : AdbReverseSource {
        override fun request(onDevice: String, onHost: String) = requests.forward(onDevice, onHost)

        override fun list() = requests.list()

        override fun killAll() = requests.killAll()

        override fun adopt(mailbox: AdbStreamMailbox, channel: AdbByteChannel) =
            AdbForwardConnection.over(
                forwardStream(channel, diagnostics).also { stream -> stream.adopt(mailbox) },
            )
    }
}
