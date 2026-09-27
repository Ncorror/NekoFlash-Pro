package io.github.ncorror.nekoflash.adb

import java.io.File
import io.github.ncorror.nekoflash.core.artifact.ArtifactSink
import io.github.ncorror.nekoflash.core.model.TargetId
import io.github.ncorror.nekoflash.operation.OperationEngine
import io.github.ncorror.nekoflash.core.artifact.ArtifactSource
import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticSink
import io.github.ncorror.nekoflash.core.model.SessionGeneration
import io.github.ncorror.nekoflash.protocol.adb.AdbConnection
import io.github.ncorror.nekoflash.protocol.adb.AdbHandshakeFailure
import io.github.ncorror.nekoflash.protocol.adb.AdbHandshakeOutcome
import io.github.ncorror.nekoflash.protocol.adb.AdbKeyStore
import io.github.ncorror.nekoflash.protocol.adb.AdbShellOutcome
import io.github.ncorror.nekoflash.usb.api.UsbAutoConnectPolicy
import io.github.ncorror.nekoflash.usb.api.UsbClaimResult
import io.github.ncorror.nekoflash.usb.api.UsbSession
import io.github.ncorror.nekoflash.usb.api.UsbSessionCoordinator
import io.github.ncorror.nekoflash.usb.api.UsbInterfaceKind
import io.github.ncorror.nekoflash.usb.api.UsbSessionState
import java.util.concurrent.Executor
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Удерживается ли интерфейс этой сессии прямо сейчас. */
private fun isHeld(session: UsbSession): Boolean =
    !session.closed && session.state == UsbSessionState.CLAIMED

/** Существует ли сессия ещё. */
private fun isPresent(session: UsbSession): Boolean = !session.closed

/**
 * Владелец ADB-соединения на уровне приложения.
 *
 * Захватывает интерфейс через координатор USB и проводит рукопожатие. Захват и
 * рукопожатие живут вместе намеренно: удерживать исключительный ресурс без
 * протокольного обмена незачем, а обмен без удержания невозможен.
 *
 * Рукопожатие идёт на [executor] и обязано быть единственным: оно читает
 * транспорт само, до того как поднимется цикл раскладки. Два одновременных
 * рукопожатия разрушили бы кадр ещё до того, как появился бы маршрутизатор
 * потоков; порядок держится тем, что [connection] до конца рукопожатия пуст, а
 * все операции без него ничего не делают.
 *
 * Дальше единственность читателя обеспечивает не этот класс, а `AdbConnection`
 * со своим циклом раскладки (`docs/adr/0004_CONCURRENT_ADB_DISPATCHER_RU.md`).
 * Поэтому операции больше не выстроены в одну очередь и не гасят друг друга:
 * оболочка, файловая операция и одноразовая команда идут одновременно, каждая
 * ждёт на своём ящике.
 *
 * Подключение происходит само, как только устройство готово: приложение
 * существует ради работы с устройством, и требовать нажатия ради того, что
 * всё равно будет нажато, — не осторожность, а лишний шаг. Так это было
 * устроено и в Legacy.
 *
 * У автоматизма есть две границы, и обе перенесены из Legacy, а не придуманы.
 * Первая: подключение автоматично только для интерфейсов, в которых мы
 * уверены (`UsbAutoConnectPolicy`) — захват исключителен, и отбирать чужое
 * устройство по одному лишь совпадению класса `0xFF` нельзя. Вторая: попытка
 * делается **один раз на поколение сессии**. Отключившись вручную,
 * пользователь остаётся отключённым; неудачное рукопожатие не повторяется по
 * кругу. Новая попытка — это новое подключение устройства.
 */
public class AdbLinkController(
    private val coordinator: UsbSessionCoordinator,
    private val keyStore: AdbKeyStore,
    private val apiLevel: Int,
    private val executor: Executor,
    private val terminalWriterExecutor: Executor,
    private val diagnostics: DiagnosticSink = DiagnosticSink { },
    /**
     * Что платформа говорит о праве открыть сокет.
     *
     * Прокидывается насквозь до пробросов: `Context` есть у приложения, а не у
     * контроллера. Зачем это нужно — `AdbForwardController.networkPermission`.
     */
    private val networkPermission: () -> String = { "unknown" },
    /**
     * Владелец записей длительных операций.
     *
     * `null` означает, что записей нет вовсе — так бывает в тестах. Операции от
     * этого не меняются, меняется только то, останется ли от них след.
     */
    private val operations: OperationEngine? = null,
    /**
     * Поднять foreground service.
     *
     * Функция, а не `Context`: контроллер про Android не знает, и кто именно
     * держит процесс, решает приложение (`06` §1).
     */
    private val holdProcess: () -> Unit = {},
) {
    private val mutableState = MutableStateFlow<AdbLinkState>(AdbLinkState.Idle)

    private val mutableCommand = MutableStateFlow<AdbCommandState>(AdbCommandState.None)

    /**
     * Владелец интерактивных сессий.
     *
     * Отдельный класс: этот следит за жизнью транспорта, тот — за жизнью одной
     * сессии оболочки, и заканчиваются они по разным причинам.
     */
    private val shellSessions = AdbTerminalController(executor, terminalWriterExecutor, diagnostics)

    /**
     * Владелец файловых операций — и читающих, и записи.
     *
     * Отдельный класс по той же причине, что и оболочка: у операции своё время
     * жизни, и мешать его с жизнью транспорта не нужно.
     */
    private val fileOperations = AdbSyncController(executor, diagnostics)

    /**
     * Владелец запросов перезагрузки.
     *
     * Отдельный класс по той же причине, что оболочка и файловые операции.
     */
    private val reboots = AdbRebootController(executor)

    /** Владелец вызовов произвольного сервиса. */
    private val rawServices = AdbRawServiceController(executor)

    /**
     * Владелец передачи пакета в Recovery.
     *
     * Отдельный класс по той же причине, что и остальные, и ещё по одной: у
     * передачи есть необратимая граница, после которой отмены не существует, и
     * следить за ней должен тот, кто её проходит, а не тот, кто держит
     * транспорт.
     */
    private val sideloads = AdbSideloadController(executor, diagnostics, operations, holdProcess)

    /**
     * Владелец пробросов портов.
     *
     * Отдельный класс по той же причине, что и остальные, и ещё по одной: у
     * него есть собственные сокеты, которые переживают отдельную команду и
     * обязаны кончиться вместе с транспортом.
     */
    /**
     * Владелец обратных пробросов.
     *
     * Зеркало пробросов и их противоположность: там слушаем мы, здесь —
     * устройство, и соединения приходят к нам входящими потоками.
     */
    private val reverseController = AdbReverseController(executor, diagnostics)

    private val forwardController = AdbForwardController(
        executor = executor,
        diagnostics = diagnostics,
        networkPermission = networkPermission,
    )

    /**
     * Живое соединение.
     *
     * Хранится, потому что после рукопожатия оно продолжает быть нужным: через
     * него идут команды. Обнуляется вместе со сбросом состояния — соединение
     * поверх отпущенного интерфейса недействительно.
     */
    @Volatile
    private var connection: AdbConnection? = null

    /**
     * Поколения, к которым автоматически подключаться больше не нужно.
     *
     * Попытка была: она удалась, провалилась или пользователь отключился сам.
     * Множество живёт до конца процесса, а поколения монотонны и не
     * переиспользуются, так что перепутать их между устройствами нельзя.
     */
    private val handled = ConcurrentHashMap.newKeySet<Long>()

    /** Состояние соединения. Экран подписывается и ничего не опрашивает. */
    public val state: StateFlow<AdbLinkState> = mutableState.asStateFlow()

    /** Состояние последней команды. */
    public val command: StateFlow<AdbCommandState> = mutableCommand.asStateFlow()

    /** Состояние интерактивной оболочки. */
    public val terminal: StateFlow<AdbTerminalState> = shellSessions.state

    /** Состояние последней файловой операции. */
    public val files: StateFlow<AdbFileState> = fileOperations.state

    /** Состояние передачи пакета в Recovery. */
    public val sideload: StateFlow<AdbSideloadState> = sideloads.state

    /** Состояние последнего запроса перезагрузки. */
    public val reboot: StateFlow<AdbRebootState> = reboots.state

    /** Состояние последнего вызова произвольного сервиса. */
    public val rawService: StateFlow<AdbRawServiceState> = rawServices.state

    /** Состояние пробросов портов. */
    public val forward: StateFlow<AdbForwardState> = forwardController.state

    /** Состояние обратных пробросов. */
    public val reverse: StateFlow<AdbReverseState> = reverseController.state

    /** Что можно сделать с обратными пробросами. */
    public val reverses: ReverseActions = ReverseActions()

    /** Действия над обратными пробросами. Собраны так же и по той же причине. */
    public inner class ReverseActions internal constructor() {
        /** Просит устройство слушать [onDevice] и приводить соединения к [onHost]. */
        public fun add(onDevice: String, onHost: String) {
            reverseController.add(onDevice, onHost)
        }

        /** Спрашивает устройство, что оно слушает. */
        public fun refresh() {
            reverseController.refresh()
        }

        /** Снимает все обратные пробросы разом. */
        public fun removeAll() {
            reverseController.removeAll()
        }
    }

    /**
     * Действия над пробросами, собранные в один объект.
     *
     * Собраны не ради красоты. На этом контроллере метод на возможность копился
     * с Phase 3, и счётчик detekt сказал об этом вслух первым: двадцать первая
     * функция. Порог не поднят — он гонец, а не проблема, и контроллер
     * действительно знает слишком много. Пробросы съезжают первыми, потому что
     * их ровно два; следующая возможность съедет так же.
     */
    public val forwards: ForwardActions = ForwardActions()

    /** Что можно сделать с пробросами. */
    public inner class ForwardActions internal constructor() {
        /**
         * Заводит проброс с [localPort] на [address].
         *
         * Ни порт, ни адрес не проверяются: `0` означает «любой свободный», а
         * что бывает на той стороне, знает устройство (`01` §3).
         */
        public fun add(localPort: Int, address: String) {
            val live = connection ?: return
            forwardController.add(
                source = { channel ->
                    AdbForwardConnection.over(live.forwardStream(channel, diagnostics))
                },
                localPort = localPort,
                address = address,
            )
        }

        /** Снимает проброс вместе с его живыми соединениями. */
        public fun remove(localPort: Int) {
            forwardController.remove(localPort)
        }
    }

    /**
     * Вызывает произвольный сервис ADB.
     *
     * Имя сервиса не проверяется и не ограничивается (`01` §3). Односторонние
     * сервисы узнаются по имени и обрабатываются как перезагрузка — иначе
     * ожидаемый разрыв показался бы отказом.
     */
    public fun callRawService(service: String) {
        val live = connection ?: return
        rawServices.call(live, service)
    }

    /**
     * Просит устройство перезагрузиться.
     *
     * Успех здесь выглядит как обрыв: устройство уходит с шины, generation
     * закрывается по `DETACHED`, и это ожидаемо. Состояние запроса живёт
     * отдельно от состояния соединения именно поэтому.
     */
    public fun requestReboot(target: String) {
        val live = connection ?: return
        reboots.request(live, target)
    }

    /**
     * Работа с файлами устройства — отдельной гранью, а не россыпью методов.
     *
     * Их пять, все про один и тот же сервис `sync:` и одно и то же состояние.
     * Держать их рядом честнее, чем вперемешку с пробросами и перезагрузкой, —
     * и это же удержало класс под порогом detekt, который поднимать запрещено
     * (`15` §4.1).
     */
    public val storage: StorageActions = StorageActions()

    /** Проверка пути, чтение, запись — и то же самое через выбор файла. */
    public inner class StorageActions internal constructor() {

        /** Спрашивает сведения о пути на устройстве. */
        public fun describe(path: String) {
            val live = connection ?: return
            if (fileOperations.active) return
            fileOperations.describe(live, path)
        }

        /** Читает файл целиком, считая размер и отпечаток. */
        public fun read(path: String) {
            val live = connection ?: return
            if (fileOperations.active) return
            fileOperations.read(live, path)
        }

        /**
         * Пишет на устройство файл заданного размера из содержимого, порождённого
         * приложением.
         *
         * Размер задаёт вызывающий, а не пользователь: выбор своего файла — работа
         * artifact source из Phase 8. Два предложенных размера покрывают
         * аппаратный гейт `07` §6.34, где нужны и малый файл, и файл больше 2 MiB.
         */
        public fun write(path: String, sizeBytes: Long) {
            val live = connection ?: return
            if (fileOperations.active) return
            fileOperations.write(live, path, sizeBytes)
        }

        /** Читает файл устройства в место, выбранное пользователем. */
        public fun readTo(path: String, destination: () -> ArtifactSink) {
            val live = connection ?: return
            if (fileOperations.active) return
            fileOperations.readTo(live, path, destination)
        }

        /** Пишет на устройство файл, выбранный пользователем. */
        public fun writeFrom(path: String, origin: () -> ArtifactSource) {
            val live = connection ?: return
            if (fileOperations.active) return
            fileOperations.writeFrom(live, path, origin)
        }
    }

    /**
     * Работа с evidence Recovery — отдельной гранью, а не россыпью методов.
     *
     * Их две, они всегда идут парой и всегда про один и тот же файл; держать
     * их рядом честнее, чем вперемешку с файловыми операциями, к которым они
     * относятся только способом чтения.
     */
    public val recovery: RecoveryActions = RecoveryActions()

    /** Снятие базы и чтение вердикта. */
    public inner class RecoveryActions internal constructor() {
        /**
         * Снимает базу журнала Recovery — **до** установки.
         *
         * Без неё вердикт объявить будет нельзя: успех прошлой установки,
         * оставшийся в том же файле, выглядел бы сегодняшним (`03` §6,
         * инвариант 10).
         */
        public fun captureBaseline() {
            val live = connection ?: return
            if (fileOperations.active) return
            fileOperations.captureRecoveryBaseline(live)
        }

        /** Читает журнал Recovery и объявляет вердикт, если его разрешает база. */
        public fun readVerdict() {
            val live = connection ?: return
            if (fileOperations.active) return
            fileOperations.readRecoveryVerdict(live)
        }

        /**
         * Отдаёт пакет Recovery.
         *
         * Режим peer'а берётся из состояния связи, а не угадывается: он прочитан
         * из баннера при рукопожатии. Отказ из-за режима называет драйвер, а не
         * прячет эта кнопка, — тогда на прогоне видно, **что** ответило
         * устройство, а не только что мы решили не спрашивать.
         */
        public fun sideload(sizeBytes: Long) {
            val live = connection ?: return
            val shown = mutableState.value
            if (shown !is AdbLinkState.Connected || sideloads.active) return
            val target = targetOf(shown.generation) ?: return
            sideloads.start(live, shown.peerMode, sizeBytes, target, shown.generation)
        }

        /**
         * Отдаёт Recovery пакет, выбранный пользователем.
         *
         * [stagingDirectory] — куда класть копию, если источник читается только
         * подряд. Каталог приходит снаружи: у контроллера `Context` нет, и
         * заводить его здесь ради одного пути значило бы протащить Android в
         * слой, который без него обходится.
         */
        public fun sideloadFrom(stagingDirectory: File, origin: () -> ArtifactSource) {
            val live = connection ?: return
            val shown = mutableState.value
            if (shown !is AdbLinkState.Connected || sideloads.active) return
            val target = targetOf(shown.generation) ?: return
            sideloads.startFrom(live, shown.peerMode, stagingDirectory, target, shown.generation, origin)
        }

        /** Просит отменить передачу. После границы мутации сессия откажет. */
        public fun cancelSideload() {
            sideloads.cancel()
        }
    }

    /**
     * Открывает интерактивную оболочку по этому соединению.
     *
     * Одноразовые команды и файловые операции при этом остаются доступны:
     * каждая работает на своём логическом потоке и ждёт на своём ящике.
     */
    public fun startShell() {
        val live = connection ?: return
        if (shellSessions.active) return
        shellSessions.start(live)
    }

    /** Передаёт строку в оболочку. */
    public fun sendShellInput(text: String) {
        shellSessions.sendInput(text)
    }

    /** Прерывает текущую команду в оболочке, не закрывая её. */
    public fun interruptShell() {
        shellSessions.interrupt()
    }

    /** Закрывает оболочку. */
    public fun stopShell() {
        shellSessions.stop()
    }

    /**
     * Выполняет команду в неинтерактивной оболочке устройства.
     *
     * Живая оболочка и файловая операция этому больше не мешают: у команды
     * свой логический поток и свой ящик.
     *
     * Второй команде мешает только то, что показать её некуда: на экране один
     * слот результата. Это ограничение **вида**, а не возможности, и снимется
     * оно вместе с экраном, который сможет показать больше одного результата.
     *
     * Команда выполняется как есть. Приложение не проверяет, что она делает:
     * оболочка на то и оболочка. Предохранители лежат в правилах мутации
     * (`docs/03`), а не в списке разрешённых команд.
     */
    public fun runCommand(command: String) {
        val trimmed = command.trim()
        val live = connection
        // Пустая команда, отсутствующее соединение и уже занятый слот
        // результата — три разные причины ничего не делать, и ни одна из них
        // не ошибка.
        if (trimmed.isEmpty() || live == null || mutableCommand.value is AdbCommandState.Running) {
            return
        }

        mutableCommand.value = AdbCommandState.Running(trimmed)
        executor.execute {
            val outcome = runCatching { live.shell(trimmed) }
            mutableCommand.value = when (val result = outcome.getOrNull()) {
                is AdbShellOutcome.Finished -> AdbCommandState.Finished(
                    command = trimmed,
                    output = result.output.stdout.trimEnd('\n', '\r'),
                    errorOutput = result.output.stderr.trimEnd('\n', '\r'),
                    exitCode = result.output.exitCode,
                )

                is AdbShellOutcome.Failed -> AdbCommandState.Failed(
                    trimmed,
                    "${result.reason.name}: ${result.detail}",
                )

                null -> AdbCommandState.Failed(
                    trimmed,
                    outcome.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName } ?: "unknown",
                )
            }
        }
    }

    /**
     * Захватывает интерфейс сессии и проводит рукопожатие.
     *
     * Возвращается сразу: рукопожатие уходит на исполнитель, потому что
     * ожидание подтверждения на устройстве длится до минуты и заморозило бы
     * экран.
     */
    public fun connect(generation: SessionGeneration) {
        // Второе подключение поверх живого — это второй CNXN и второй читатель.
        // Оба запрещены контрактом, поэтому отказ здесь, а не попытка.
        if (mutableState.value !is AdbLinkState.Idle && mutableState.value !is AdbLinkState.Failed) {
            return
        }
        handled.add(generation.value)
        mutableState.value = AdbLinkState.Connecting(generation)

        when (val claim = coordinator.claim(generation)) {
            is UsbClaimResult.Failed -> {
                mutableState.value = AdbLinkState.Failed(
                    generation = generation,
                    reason = AdbHandshakeFailure.TRANSPORT_CLOSED,
                    detail = claim.reason.name,
                )
            }

            is UsbClaimResult.Claimed -> executor.execute {
                runHandshake(generation, claim)
            }
        }
    }

    /**
     * Разрывает соединение и освобождает интерфейс.
     *
     * Отдельного «закрыть только ADB» нет: соединение и захват начинаются
     * вместе и заканчиваются вместе. Повторное подключение — это новый захват,
     * а не второй `CNXN` в том же соединении.
     */
    public fun disconnect(generation: SessionGeneration) {
        handled.add(generation.value)
        coordinator.release(generation)
        forgetConnection()
    }

    /**
     * Забывает соединение вместе с его состоянием.
     *
     * Вывод последней команды тоже уходит: он относился к устройству, которого
     * больше нет, и оставлять его на экране значило бы приписывать его
     * следующему.
     */
    /**
     * Цель этого поколения.
     *
     * `null` означает, что сессии больше нет: устройство отключили между
     * нажатием и этим вызовом. Заводить запись операции на цель, которой нет,
     * незачем — операция всё равно не начнётся.
     */
    private fun targetOf(generation: SessionGeneration): TargetId? =
        coordinator.sessions.value.firstOrNull { session -> session.generation == generation }?.targetId

    private fun forgetConnection() {
        shellSessions.stop()
        // Слушатели переживают отдельную команду, но не транспорт: проброс
        // поверх мёртвого соединения принимал бы клиентов в никуда.
        // Слушатели переживают отдельную команду, но не транспорт: проброс
        // поверх мёртвого соединения принимал бы клиентов в никуда, а ожидание
        // обратного — поток, идти по которому уже некуда.
        forwardController.stopAll("transport is gone")
        reverseController.bind(null)
        // Цикл раскладки принадлежит соединению и обязан кончиться вместе с
        // ним: иначе он пережил бы SessionGeneration и продолжил читать
        // отпущенный интерфейс (ADR-0003 §2, ADR-0004 §4).
        connection?.close()
        connection = null
        mutableCommand.value = AdbCommandState.None
        mutableState.value = AdbLinkState.Idle
    }

    private fun runHandshake(generation: SessionGeneration, claim: UsbClaimResult.Claimed) {
        val connection = AdbConnection(
            handle = claim.handle,
            keyStore = keyStore,
            apiLevel = apiLevel,
            diagnostics = diagnostics,
            onPublicKeySent = {
                val current = mutableState.value
                if (current is AdbLinkState.Connecting && current.generation == generation) {
                    mutableState.value = AdbLinkState.WaitingForAuthorization(generation)
                }
            },
        )

        val outcome = runCatching { connection.connect() }
        mutableState.value = when (val result = outcome.getOrNull()) {
            is AdbHandshakeOutcome.Connected -> {
                this.connection = connection
                // Право принимать потоки отдаётся сразу после рукопожатия, а не
                // при первом запросе: устройство может слушать с прошлого раза —
                // `reverse` переживает переподключение, — и тогда поток придёт
                // раньше, чем оператор о чём-нибудь попросит.
                reverseController.bind(connection.reverseSource(diagnostics))
                connection.acceptInboundStreams(reverseController)
                AdbLinkState.Connected(
                    generation = generation,
                    peerMode = result.banner.peerMode,
                    banner = result.banner.banner,
                    features = result.banner.features,
                )
            }

            is AdbHandshakeOutcome.Failed -> {
                releaseAfterFailure(generation)
                AdbLinkState.Failed(generation, result.reason, result.detail)
            }

            null -> {
                releaseAfterFailure(generation)
                AdbLinkState.Failed(
                    generation = generation,
                    reason = AdbHandshakeFailure.TRANSPORT_CLOSED,
                    detail = outcome.exceptionOrNull()?.let { error ->
                        error.message ?: error.javaClass.simpleName
                    } ?: "unknown",
                )
            }
        }
    }

    /**
     * Сверяет соединение с действительным состоянием сессий USB.
     *
     * Соединение существует ровно столько, сколько удерживается интерфейс.
     * Отпустить его можно не только кнопкой «Отключиться»: устройство могли
     * выдернуть, сессию — закрыть, интерфейс — освободить другим путём. Во всех
     * этих случаях `UsbTransportHandle` уже закрыт, и оставлять на экране
     * «подключено» значит выдавать несуществующее за существующее.
     *
     * Прогон 2026-09-03 показал это ровно так: после освобождения интерфейса
     * экран продолжал утверждать, что ADB подключён (`07` §6.12).
     */
    public fun onUsbSessionsChanged(sessions: List<UsbSession>) {
        dropLinkIfInterfaceNoLongerHeld(sessions)
        connectToNewlyReadyDevice(sessions)
    }

    private fun dropLinkIfInterfaceNoLongerHeld(sessions: List<UsbSession>) {
        when (val state = mutableState.value) {
            AdbLinkState.Idle -> Unit

            // Живое соединение существует ровно столько, сколько удерживается
            // интерфейс.
            is AdbLinkState.Connecting -> dropUnless(state.generation, sessions, ::isHeld)
            is AdbLinkState.WaitingForAuthorization -> dropUnless(state.generation, sessions, ::isHeld)
            is AdbLinkState.Connected -> dropUnless(state.generation, sessions, ::isHeld)

            // Причина отказа остаётся на экране до тех пор, пока устройство то
            // же самое: она единственное, что есть у пользователя для разбора.
            // Но пережить это устройство она не должна — иначе одна неудача
            // отменила бы автоподключение до перезапуска приложения.
            is AdbLinkState.Failed -> dropUnless(state.generation, sessions, ::isPresent)
        }
    }

    private fun dropUnless(
        generation: SessionGeneration,
        sessions: List<UsbSession>,
        alive: (UsbSession) -> Boolean,
    ) {
        if (sessions.none { it.generation == generation && alive(it) }) {
            forgetConnection()
        }
    }

    /**
     * Подключается к устройству, которое только что стало готовым.
     *
     * Занятость проверяется по состоянию, а не по флагу: пока идёт одно
     * рукопожатие, второе начинать нельзя — физический читатель один.
     */
    private fun connectToNewlyReadyDevice(sessions: List<UsbSession>) {
        if (mutableState.value !is AdbLinkState.Idle) return
        val candidate = sessions.firstOrNull { session ->
            !session.closed &&
                session.state == UsbSessionState.READY &&
                session.candidate.kind == UsbInterfaceKind.ADB &&
                UsbAutoConnectPolicy.allowsAutomaticConnect(session.candidate) &&
                !handled.contains(session.generation.value)
        } ?: return
        connect(candidate.generation)
    }

    /**
     * Освобождает интерфейс после неудачи.
     *
     * Держать его дальше нельзя: рукопожатие на этом транспорте больше не
     * повторить, а исключительный захват мешал бы другим владельцам USB.
     */
    private fun releaseAfterFailure(generation: SessionGeneration) {
        coordinator.release(generation)
    }
}
