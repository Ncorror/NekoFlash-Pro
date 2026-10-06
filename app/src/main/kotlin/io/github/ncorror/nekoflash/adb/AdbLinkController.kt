package io.github.ncorror.nekoflash.adb

import java.io.File
import io.github.ncorror.nekoflash.artifact.LocalFolder
import io.github.ncorror.nekoflash.core.artifact.ArtifactSink
import io.github.ncorror.nekoflash.core.model.TargetId
import io.github.ncorror.nekoflash.operation.OperationEngine
import io.github.ncorror.nekoflash.core.artifact.ArtifactSource
import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticSink
import io.github.ncorror.nekoflash.core.model.SessionGeneration
import io.github.ncorror.nekoflash.protocol.adb.AdbConnection
import io.github.ncorror.nekoflash.protocol.adb.AdbKeyStore
import io.github.ncorror.nekoflash.protocol.adb.AdbShellMode
import io.github.ncorror.nekoflash.usb.api.UsbSession
import io.github.ncorror.nekoflash.usb.api.UsbSessionCoordinator
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.StateFlow

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
    private val diagnostics: DiagnosticSink,
    /** Platform facts and resources are provided by the application without Android dependencies here. */
    private val hostServices: AdbHostServices,
    /**
     * Владелец записей длительных операций.
     *
     * На уровне приложения журнал обязателен: молчаливо отключить след
     * длительной операции здесь нельзя.
     */
    private val operations: OperationEngine,
) {
    /**
     * Владелец интерактивных сессий.
     *
     * Отдельный класс: этот следит за жизнью транспорта, тот — за жизнью одной
     * сессии оболочки, и заканчиваются они по разным причинам.
     */
    private val shellTabs = AdbTerminalTabs(executor, terminalWriterExecutor, diagnostics)

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
    private val sideloads = AdbSideloadController(
        executor, diagnostics, operations, hostServices.holdProcess, hostServices.stagingSpace,
    )

    private val installs = AdbInstallController(executor, diagnostics)
    private val transfers = AdbTransferController(executor, diagnostics)

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
        networkPermission = hostServices.networkPermission,
    )

    private var operationTarget: TargetId? = null

    private val lifecycle = AdbLinkLifecycle(
        coordinator, keyStore, apiLevel, executor, diagnostics,
        onReplacing = { generation ->
            operationTarget = targetOf(generation)
            bindOperations(null, discard = true)
        },
        onConnected = { live ->
            bindOperations(live)
            // Bind the inbound handler before reverseSource starts the reader.
            live.acceptInboundStreams(reverseController)
            reverseController.bind(live.reverseSource(diagnostics))
        },
        onDisconnected = {
            bindOperations(null)
            shellTabs.stopAll()
            forwardController.stopAll("transport is gone")
            reverseController.bind(null)
        },
    )

    private fun bindOperations(live: AdbConnection?, discard: Boolean = false) {
        fileOperations.bind(live, operationTarget, discard)
        reboots.bind(live, discard)
        rawServices.bind(live, discard)
        sideloads.bind(live, discard)
        installs.bind(live, discard)
        transfers.bind(live, discard)
    }

    private val connection: AdbConnection?
        get() = lifecycle.connection

    /** Состояние соединения. Экран подписывается и ничего не опрашивает. */
    public val state: StateFlow<AdbLinkState> = lifecycle.state

    /** Состояние последней команды. */
    public val command: StateFlow<AdbCommandState> = lifecycle.command

    /** Состояние интерактивной оболочки. */
    public val terminals: StateFlow<TerminalTabs> = shellTabs.state

    /** Состояние последней файловой операции. */
    public val files: StateFlow<AdbFileState> = fileOperations.state

    /** Состояние передачи пакета в Recovery. */
    public val sideload: StateFlow<AdbSideloadState> = sideloads.state

    /** Состояние установки пакетов. */
    public val install: StateFlow<AdbInstallState> = installs.state

    /** Последний `adb push`/`pull` из поля команды: несколько файлов и каталоги. */
    public val transfer: StateFlow<AdbTransferState> = transfers.state

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

    /** Установка пакетов — `adb install` и `adb install-multiple`. */
    public val packages: PackageActions = PackageActions()

    /** Установка пакетов: одна грань, чтобы не раздувать сам контроллер. */
    public inner class PackageActions internal constructor() {
        /** Устанавливает выбранные файлы; [several] — сессией, как `install-multiple`. */
        public fun install(origins: List<() -> ArtifactSource>, options: List<String>, several: Boolean) {
            val live = connection ?: return
            installs.install(live, origins, options, several)
        }
    }

    /** Проверка пути, чтение, запись — и то же самое через выбор файла. */
    public inner class StorageActions internal constructor() {

        /** `adb push`: файлы и папки, выбранные через Android, по правилам настольного клиента. */
        internal fun push(sources: () -> List<PushSource>, destination: String, sync: Boolean, mode: AdbPushMode) {
            val live = connection ?: return
            transfers.push(live, sources, destination, sync, mode)
        }

        /** `adb sync`: выбранная папка — каталог сборки. */
        internal fun syncBuild(productOut: () -> LocalFolder, partition: String, listOnly: Boolean, mode: AdbPushMode) {
            val live = connection ?: return
            transfers.syncBuild(live, productOut, partition, listOnly, mode)
        }

        /** `adb pull`: пути устройства в папку, выбранную через Android; [compression] — имя из `-z`. */
        internal fun pull(sources: List<String>, compression: String, target: () -> PullTarget) {
            val live = connection ?: return
            transfers.pull(live, sources, compression, target)
        }

        /** Спрашивает сведения о пути на устройстве. */
        public fun describe(path: String) {
            val live = connection ?: return
            if (fileOperations.active) return
            fileOperations.describe(live, path)
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
         * Отдаёт Recovery пакет, выбранный пользователем.
         *
         * [stagingDirectory] — куда класть копию, если источник читается только
         * подряд. Каталог приходит снаружи: у контроллера `Context` нет, и
         * заводить его здесь ради одного пути значило бы протащить Android в
         * слой, который без него обходится.
         */
        public fun sideloadFrom(stagingDirectory: File, origin: () -> ArtifactSource) {
            val live = connection ?: return
            val shown = state.value
            if (shown !is AdbLinkState.Connected || sideloads.active) return
            val target = targetOf(shown.generation) ?: return
            sideloads.startFrom(live, shown.peerMode, stagingDirectory, target, shown.generation, origin)
        }

        /** Просит отменить передачу. После границы мутации сессия откажет. */
        public fun cancelSideload() {
            sideloads.cancel()
        }
    }

    /** Оболочки во вкладках: одна грань, чтобы не раздувать сам контроллер. */
    public val shells: ShellActions = ShellActions()

    /** Интерактивные оболочки и их вкладки. */
    public inner class ShellActions internal constructor() {
        /**
         * Открывает интерактивную оболочку в выбранной вкладке.
         *
         * [fromCommand] — оболочку просит команда настольного клиента: если во
         * вкладке уже идёт своя, открывается новая вкладка, а не ввод в чужую.
         * Одноразовые команды и файловые операции при этом остаются доступны:
         * каждая работает на своём логическом потоке и ждёт на своём ящике.
         */
        public fun start(
            firstLine: String? = null,
            mode: AdbShellMode = AdbShellMode.PTY,
            fromCommand: Boolean = false,
        ) {
            val live = connection ?: return
            shellTabs.start(live, firstLine, mode, newIfBusy = fromCommand)
        }

        /** Передаёт строку в оболочку выбранной вкладки; без [enter] — без перевода строки. */
        public fun send(text: String, enter: Boolean = true) {
            shellTabs.current.sendInput(text, enter)
        }

        /** Прерывает текущую команду в оболочке, не закрывая её. */
        public fun interrupt() {
            shellTabs.current.interrupt()
        }

        /** Размер окна терминала, как его измерил экран. */
        public fun resize(columns: Int, rows: Int) {
            shellTabs.current.resize(columns, rows)
        }

        /** Клавиша, которой нет в строке ввода. */
        public fun key(key: TerminalKey) {
            shellTabs.current.sendKey(key)
        }

        /** Закрывает оболочку выбранной вкладки. */
        public fun stop() {
            shellTabs.current.stop()
        }

        public fun addTab() {
            shellTabs.add()
        }

        public fun selectTab(index: Int) {
            shellTabs.select(index)
        }

        public fun closeTab(index: Int) {
            shellTabs.close(index)
        }
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
        lifecycle.runCommand(command)
    }

    /**
     * Захватывает интерфейс сессии и проводит рукопожатие.
     *
     * Возвращается сразу: рукопожатие уходит на исполнитель, потому что
     * ожидание подтверждения на устройстве длится до минуты и заморозило бы
     * экран.
     */
    public fun connect(generation: SessionGeneration) {
        lifecycle.connect(generation)
    }

    /**
     * Разрывает соединение и освобождает интерфейс.
     *
     * Отдельного «закрыть только ADB» нет: соединение и захват начинаются
     * вместе и заканчиваются вместе. Повторное подключение — это новый захват,
     * а не второй `CNXN` в том же соединении.
     */
    public fun disconnect(generation: SessionGeneration) {
        lifecycle.disconnect(generation)
    }

    /**
     * Цель этого поколения.
     *
     * `null` означает, что сессии больше нет: устройство отключили между
     * нажатием и этим вызовом. Заводить запись операции на цель, которой нет,
     * незачем — операция всё равно не начнётся.
     */
    private fun targetOf(generation: SessionGeneration): TargetId? =
        coordinator.sessions.value.firstOrNull { session -> session.generation == generation }?.targetId

    /** Reconciles only the selected USB session supplied by the application. */
    public fun onUsbSessionsChanged(sessions: List<UsbSession>) {
        lifecycle.onUsbSessionsChanged(sessions)
    }
}
