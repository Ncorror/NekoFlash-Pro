package io.github.ncorror.nekoflash.adb

import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticEvent
import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticSink
import io.github.ncorror.nekoflash.protocol.adb.AdbByteChannel
import io.github.ncorror.nekoflash.protocol.adb.AdbForwardOutcome
import io.github.ncorror.nekoflash.protocol.adb.AdbForwardStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Один живой проброс в том виде, в каком его показывает экран. */
public data class AdbForwardEntry(
    /** Порт на хосте, который слушаем. Назначенный системой, а не запрошенный. */
    val localPort: Int,
    /** Адрес на устройстве, как его набрал оператор. */
    val address: String,
    /** Сколько соединений идёт прямо сейчас. */
    val live: Int,
    /**
     * Сколько соединений **принял слушатель** за жизнь проброса.
     *
     * Отдельно от [served] намеренно. Прогон `07` §6.55 упёрся в то, что
     * «0 обслужено» одинаково читается и как «клиент не приходил», и как
     * «пришёл, а до устройства не дошёл». Это разные беды: первая — на стороне
     * клиента, вторая — наша. Принятое соединение считается **до** попытки
     * открыть поток, и потому их различает.
     */
    val accepted: Int,
    /** Сколько соединений доведено до конца, с итогом. */
    val served: Int,
    /** Чем кончилось последнее соединение; `null` — ещё ни одного. */
    val lastEnd: String?,
)

/** Состояние всех пробросов. */
public data class AdbForwardState(
    val forwards: List<AdbForwardEntry> = emptyList(),
    /** Почему не удалось последнее действие оператора; `null` — всё получилось. */
    val failure: String? = null,
) {
    public companion object {
        public val None: AdbForwardState = AdbForwardState()
    }
}

/**
 * Одно проброшенное соединение с точки зрения слушателя.
 *
 * Три вызова в том же порядке, что и у [AdbForwardStream], поверх которого это
 * и работает. Промежуточный интерфейс нужен не ради слоёв: без него контроллер
 * можно было бы проверить только живым транспортом, то есть на устройстве, а
 * проверять здесь надо приём соединений, потолок и снятие — к протоколу
 * отношения не имеющие.
 */
public interface AdbForwardConnection {
    /** `null` — поток открыт; иначе соединение уже кончилось. */
    public fun open(address: String): AdbForwardOutcome?

    /** Качает из устройства, пока соединение живо. Блокирующий. */
    public fun fromDevice(): AdbForwardOutcome

    /** Качает из клиента, пока соединение живо. Блокирующий, и на своём потоке. */
    public fun fromClient()

    /** Обрывает живое соединение снаружи. */
    public fun cancel(detail: String)

    public companion object {
        /** Настоящее соединение поверх логического потока ADB. */
        public fun over(stream: AdbForwardStream): AdbForwardConnection =
            object : AdbForwardConnection {
                override fun open(address: String): AdbForwardOutcome? = stream.open(address)

                override fun fromDevice(): AdbForwardOutcome = stream.fromDevice()

                override fun fromClient(): Unit = stream.fromClient()

                override fun cancel(detail: String): Unit = stream.cancel(detail)
            }
    }
}

/** Как завести проброшенное соединение поверх живого транспорта. */
public fun interface AdbForwardSource {
    public fun connect(channel: AdbByteChannel): AdbForwardConnection
}

/**
 * Слушатели на хосте и соединения, проброшенные через них на устройство.
 *
 * Владелец сокетов: протокольный модуль про них не знает вовсе
 * (`docs/adr/0005_LOCAL_SOCKET_FORWARDING_RU.md` §3), а разрешение, время жизни
 * и экран принадлежат приложению.
 *
 * **Слушатель привязан к loopback, и это требование, а не настройка.** Сокет,
 * открытый на всех интерфейсах, отдал бы устройство любому, кто окажется в той
 * же сети. Ни поля для адреса привязки, ни способа её сменить здесь нет
 * намеренно.
 *
 * **Ни адрес, ни порт не проверяются.** `tcp:5555`, `localabstract:имя`,
 * вендорское — что бывает на той стороне, знает устройство, и его отказ это
 * ответ, а не наша ошибка (`01` §3). Потолок [MAX_CONNECTIONS] относится к
 * нашим потокам, а не к тому, что оператору разрешено просить: за каждым
 * соединением стоит два блокирующих цикла, и без предела десяток клиентов
 * съел бы пул.
 */
public class AdbForwardController(
    private val executor: Executor,
    private val diagnostics: DiagnosticSink = DiagnosticSink { },
    private val clock: () -> Instant = { Clock.systemUTC().instant() },
    /** Как завести слушатель. Подменяется в тестах — настоящий сокет там не нужен. */
    private val listen: (Int) -> ServerSocket = { port ->
        ServerSocket(port, BACKLOG, LOOPBACK_V4)
    },
    /**
     * Что платформа говорит о праве открыть сокет.
     *
     * Подставляется владельцем, потому что `Context` сюда не спускается. По
     * умолчанию — `unknown`: соврать «granted» там, где не спрашивали, хуже,
     * чем сказать, что не знаем.
     *
     * Нужно ровно для одной строки — отказа завести слушатель. Прогон `07`
     * §6.52 показал, зачем: сокет отказывал **на создании**, до всякой
     * привязки, и по журналу нельзя было отличить «мы не просили разрешения»
     * от «просили, дали, а отказывает всё равно кто-то другой». Первое — наша
     * ошибка, второе — нет, и лечатся они по-разному.
     */
    private val networkPermission: () -> String = { UNKNOWN_PERMISSION },
) {
    private val mutableState = MutableStateFlow(AdbForwardState.None)

    private val forwards = ConcurrentHashMap<Int, Forward>()

    /** Состояние пробросов. Экран подписывается и ничего не опрашивает. */
    public val state: StateFlow<AdbForwardState> = mutableState.asStateFlow()

    /**
     * Заводит слушатель на [localPort] и направляет его на [address].
     *
     * Порт `0` означает «любой свободный»: какой достался, видно в состоянии.
     * Пустой адрес — не ошибка, а нечего делать.
     */
    public fun add(source: AdbForwardSource, localPort: Int, address: String) {
        val target = address.trim()
        if (target.isEmpty()) return
        executor.execute { bind(source, localPort, target) }
    }

    /** Снимает проброс: слушатель закрывается, живые соединения обрываются. */
    public fun remove(localPort: Int) {
        forwards.remove(localPort)?.let { forward ->
            forward.shutdown(REMOVED_BY_OPERATOR)
            emit("forward_removed", mapOf("localPort" to localPort.toString()))
            publish()
        }
    }

    /**
     * Снимает все пробросы разом.
     *
     * Вызывается, когда транспорта больше нет: проброс поверх мёртвого
     * соединения — это слушатель, принимающий клиентов в никуда.
     */
    public fun stopAll(reason: String) {
        val live = forwards.values.toList()
        forwards.clear()
        live.forEach { forward -> forward.shutdown(reason) }
        if (live.isEmpty()) return
        // Снятие обязано быть видно так же, как заведение: иначе «пробросов в
        // списке нет» нельзя отличить от «их и не заводили» (`07` §6.55).
        emit("forward_stopped", mapOf("count" to live.size.toString(), "reason" to reason))
        publish()
    }

    private fun bind(source: AdbForwardSource, requestedPort: Int, address: String) {
        val server = try {
            listen(requestedPort)
        } catch (failure: IOException) {
            val reason = failure.message ?: failure.javaClass.simpleName
            fail("port $requestedPort: $reason (INTERNET=${networkPermission()})")
            return
        }
        val forward = Forward(server, address)
        forwards[server.localPort] = forward
        emit(
            "forward_listening",
            mapOf(
                "localPort" to server.localPort.toString(),
                // Адрес привязки записывается, а не подразумевается: прогон
                // `07` §6.57 не мог ответить, слушаем ли мы там, куда стучится
                // клиент, — а порт об этом не говорит ничего.
                "bindAddress" to server.inetAddress.hostAddress.orEmpty().ifEmpty { "unknown" },
                "address" to address,
            ),
        )
        mutableState.value = snapshot(failure = null)
        executor.execute { accept(source, forward) }
    }

    private fun accept(source: AdbForwardSource, forward: Forward) {
        while (!forward.server.isClosed) {
            val socket = try {
                forward.server.accept()
            } catch (_: IOException) {
                // Закрытый слушатель — это снятие проброса, а не беда.
                return
            }
            admit(source, forward, socket)
        }
    }

    private fun admit(source: AdbForwardSource, forward: Forward, socket: Socket) {
        forward.accepted()
        emit(
            "forward_accepted",
            mapOf("localPort" to forward.port().toString(), "live" to forward.live().toString()),
        )
        publish()
        if (forward.live() >= MAX_CONNECTIONS) {
            // Отказ наблюдаемый: клиент узнаёт о нём закрытым соединением, а
            // оператор — журналом. Молча держать сокет было бы хуже.
            closeQuietly(socket)
            emit(
                "forward_refused",
                mapOf("localPort" to forward.port().toString(), "limit" to MAX_CONNECTIONS.toString()),
            )
            return
        }
        executor.execute { serve(source, forward, socket) }
    }

    private fun serve(source: AdbForwardSource, forward: Forward, socket: Socket) {
        val channel = try {
            AdbSocketChannel(socket)
        } catch (failure: IOException) {
            closeQuietly(socket)
            fail("connection on ${forward.port()}: ${failure.message ?: "socket is gone"}")
            return
        }
        val stream = source.connect(channel)
        forward.opened(stream)
        publish()
        val refused = stream.open(forward.address)
        val outcome = refused ?: pumpBothWays(stream)
        forward.closed(stream, outcome)
        publish()
    }

    /**
     * Качает обе стороны и возвращает итог соединения.
     *
     * Сторона клиента уходит на пул: оба ожидания блокирующие, и на одном
     * потоке работала бы только одна из них.
     */
    private fun pumpBothWays(stream: AdbForwardConnection): AdbForwardOutcome {
        executor.execute { stream.fromClient() }
        return stream.fromDevice()
    }

    private fun fail(detail: String) {
        emit("forward_failed", mapOf("detail" to detail))
        mutableState.value = snapshot(failure = detail)
    }

    private fun publish() {
        mutableState.value = snapshot(failure = mutableState.value.failure)
    }

    private fun snapshot(failure: String?): AdbForwardState = AdbForwardState(
        forwards = forwards.values
            .map { forward -> forward.entry() }
            .sortedBy { entry -> entry.localPort },
        failure = failure,
    )

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

    /** Один слушатель со своими соединениями. */
    private class Forward(val server: ServerSocket, val address: String) {
        private val streams = ConcurrentHashMap.newKeySet<AdbForwardConnection>()

        @Volatile
        private var taken = 0

        @Volatile
        private var served = 0

        @Volatile
        private var lastEnd: String? = null

        fun port(): Int = server.localPort

        fun live(): Int = streams.size

        @Synchronized
        fun accepted() {
            taken += 1
        }

        fun opened(stream: AdbForwardConnection) {
            streams += stream
        }

        @Synchronized
        fun closed(stream: AdbForwardConnection, outcome: AdbForwardOutcome) {
            streams -= stream
            served += 1
            lastEnd = "${outcome.end.name}: ${outcome.detail} " +
                "(${outcome.fromClient}/${outcome.fromDevice} bytes)"
        }

        fun shutdown(reason: String) {
            closeQuietly(server)
            streams.forEach { stream -> stream.cancel(reason) }
            streams.clear()
        }

        fun entry(): AdbForwardEntry = AdbForwardEntry(
            localPort = port(),
            address = address,
            live = live(),
            accepted = taken,
            served = served,
            lastEnd = lastEnd,
        )
    }

    public companion object {
        /**
         * Сколько соединений держит один проброс.
         *
         * За каждым стоят два блокирующих цикла, и это единственная причина
         * предела: он про наши потоки, а не про то, что оператору разрешено
         * просить (`01` §3). Превышение — наблюдаемый отказ, а не тишина.
         */
        public const val MAX_CONNECTIONS: Int = 16

        /** Очередь непринятых соединений у слушателя. */
        private const val BACKLOG = 8

        /**
         * Тот самый `127.0.0.1`, который оператор набирает в браузере.
         *
         * Раньше здесь был `InetAddress.getLoopbackAddress()`, и это была
         * неопределённость: он отдаёт то `127.0.0.1`, то `::1` — в зависимости
         * от настройки JVM, а не от того, куда придёт клиент. Слушать на `::1`,
         * когда стучатся на `127.0.0.1`, значит не слушать вовсе, и снаружи это
         * неотличимо от «приложению запретили сеть».
         *
         * Адрес собирается из байт, а не разбирается из строки: имя `127.0.0.1`
         * пришлось бы резолвить, а резолвить тут нечего.
         */
        private val LOOPBACK_V4: InetAddress =
            InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

        private const val DIAGNOSTIC_CATEGORY = "adb"

        /** Владелец не сказал — значит не знаем, и так и записываем. */
        private const val UNKNOWN_PERMISSION = "unknown"

        private const val REMOVED_BY_OPERATOR = "removed by operator"

        private fun closeQuietly(closeable: java.io.Closeable) {
            try {
                closeable.close()
            } catch (_: IOException) {
                // Закрывать уже закрытое — обычное дело, и сообщать тут нечего.
            }
        }
    }
}

/**
 * Принятое соединение как источник и приёмник байт.
 *
 * Закрытие сокета — единственный способ разбудить чужой поток, висящий в
 * [read]: он вылетит `IOException`, и проброс назовёт это концом клиента.
 * Поэтому [close] обязан переживать повторный вызов, и переживает.
 */
internal class AdbSocketChannel(private val socket: Socket) : AdbByteChannel {
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()

    override fun read(destination: ByteArray): Int = input.read(destination)

    override fun write(source: ByteArray, length: Int) {
        output.write(source, 0, length)
        // Без сброса ответ устройства ждал бы наполнения буфера, а на той
        // стороне сидит клиент, который ждёт ответа, чтобы прислать следующее.
        output.flush()
    }

    override fun close() {
        try {
            socket.close()
        } catch (_: IOException) {
            // Уже закрыт встречным направлением — это штатный ход событий.
        }
    }
}
