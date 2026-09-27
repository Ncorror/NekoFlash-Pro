package io.github.ncorror.nekoflash

import android.Manifest
import android.app.Application
import android.net.Uri
import android.os.Build
import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticBundle
import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticBundleResult
import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticEvent
import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticRedaction
import io.github.ncorror.nekoflash.core.diagnostics.InMemoryDiagnosticSink
import io.github.ncorror.nekoflash.core.model.SessionGeneration
import io.github.ncorror.nekoflash.adb.AdbLinkController
import io.github.ncorror.nekoflash.fastboot.FastbootLinkController
import io.github.ncorror.nekoflash.diagnostics.HostFacts
import io.github.ncorror.nekoflash.protocol.adb.AdbKeyStore
import io.github.ncorror.nekoflash.usb.android.AndroidUsbHost
import io.github.ncorror.nekoflash.usb.api.UsbDiagnosticReport
import io.github.ncorror.nekoflash.usb.api.UsbPermissionCallbackIdentity
import io.github.ncorror.nekoflash.usb.api.UsbPermissionPolicy
import io.github.ncorror.nekoflash.core.operation.FileOperationJournal
import io.github.ncorror.nekoflash.operation.OperationEngine
import io.github.ncorror.nekoflash.operation.OperationService
import io.github.ncorror.nekoflash.usb.api.UsbSessionCoordinator
import io.github.ncorror.nekoflash.usb.api.UsbSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Владелец USB на уровне приложения.
 *
 * Владение живёт здесь, а не на экране: подключённое устройство не должно
 * теряться при повороте экрана или пересоздании активности. Граф зависимостей
 * собирается явно, без библиотеки внедрения — так решено в ADR-0003, пока
 * масштаб это позволяет.
 */
public class NekoFlashApplication : Application() {
    /**
     * Область для отложенных задач владельца USB.
     *
     * [Dispatchers.Default], а не главный поток: главный диспетчер требует
     * отдельного артефакта корутин, а здесь он и не нужен. Реестр сессий
     * потокобезопасен, наблюдаемое состояние тоже, а на экран его доставляет
     * Compose.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val host by lazy {
        AndroidUsbHost(
            context = this,
            callbackIdentity = UsbPermissionCallbackIdentity(
                actionPrefix = "$packageName.USB_PERMISSION",
                processToken = UUID.randomUUID().toString(),
            ),
        )
    }

    private val events = InMemoryDiagnosticSink()

    /**
     * Недавние события для экрана.
     *
     * Отдаётся снимком по запросу, а не потоком: журнал потока не даёт, а
     * заводить его пришлось бы с ограничением частоты — `logcat` даёт тысячи
     * событий в секунду, и публикация на каждое утопила бы экран ровно тем,
     * что он показывает. Спрашивает экран, и только пока панель открыта.
     */
    public val recentDiagnostics: () -> List<DiagnosticEvent> = { events.snapshot() }

    /** Состояние сессий USB. Экран подписывается на него и ничего не опрашивает. */
    public val usbSessions: UsbSessionCoordinator by lazy {
        UsbSessionCoordinator(
            host = host,
            onPermissionRequested = ::schedulePermissionTimeout,
            diagnostics = events,
        )
    }

    /**
     * Ключ хоста ADB.
     *
     * Лежит в приватном каталоге приложения: устройство помнит хост по
     * отпечатку публичного ключа, и терять его между запусками нельзя.
     */
    private val adbKeys by lazy { AdbKeyStore(File(filesDir, ADB_KEY_FOLDER)) }

    /**
     * Потоки операций ADB.
     *
     * Раньше здесь был **один** поток, и это был способ соблюсти инвариант
     * единственного физического читателя: кто читает — тот и в очереди. После
     * `docs/adr/0004_CONCURRENT_ADB_DISPATCHER_RU.md` транспорт читает цикл
     * раскладки внутри `AdbConnection`, а операции ждут каждая на своём ящике
     * и потому идут одновременно. Держать их в одной очереди значило бы
     * сохранить ограничение, у которого больше нет причины.
     *
     * Рукопожатие по-прежнему единственное, но не из-за очереди: до его
     * окончания соединения нет, а без соединения ни одна операция не начнётся.
     */
    private val adbOperationThreads by lazy {
        Executors.newCachedThreadPool { runnable -> Thread(runnable, "nekoflash-adb-operation") }
    }

    /**
     * Последовательный поток записи интерактивной ADB-сессии.
     *
     * Он не читает транспорт и не нарушает single-reader invariant. Нужен,
     * чтобы блокирующий Android `bulkTransfer` никогда не выполнялся из Compose
     * callback на главном потоке.
     */
    private val adbWriterThread by lazy {
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "nekoflash-adb-writer") }
    }

    /**
     * Владелец длительных операций.
     *
     * Живёт на уровне приложения, а не экрана: `06` §1 требует, чтобы
     * физическое время жизни передачи не принадлежало Activity — пользователь
     * вправе свернуть приложение посреди прошивки.
     *
     * Хранилище — каталог приложения. Записи нужны для истории, evidence и
     * честного чтения после смерти процесса, а **не** для продолжения
     * оборванной транзакции: продолжать нечего, транспорт отпущен вместе с
     * процессом.
     */
    public val operations: OperationEngine by lazy {
        OperationEngine(FileOperationJournal(File(filesDir, OPERATIONS_FOLDER).toPath()))
    }

    /** Состояние ADB-соединения. Экран подписывается на него. */
    public val adbLink: AdbLinkController by lazy {
        AdbLinkController(
            coordinator = usbSessions,
            keyStore = adbKeys,
            apiLevel = Build.VERSION.SDK_INT,
            executor = adbOperationThreads,
            terminalWriterExecutor = adbWriterThread,
            diagnostics = events,
            // Факт, а не догадка: спрашивается у платформы в момент отказа.
            networkPermission = { HostFacts.permissionState(this, Manifest.permission.INTERNET) },
            operations = operations,
            holdProcess = { OperationService.start(this) },
        )
    }

    /**
     * Состояние Fastboot-соединения.
     *
     * Своё, а не общее с ADB: в загрузчике устройство перечисляется другим
     * интерфейсом, и ADB там не отвечает вовсе (`07` §6.46).
     */
    public val fastbootLink: FastbootLinkController by lazy {
        FastbootLinkController(
            claim = usbSessions::claim,
            executor = adbOperationThreads,
            diagnostics = events,
        )
    }

    /** Имя файла, предлагаемое системному диалогу сохранения. */
    public fun suggestedDiagnosticsFileName(): String =
        DiagnosticBundle.suggestedFileName(Instant.now())

    /**
     * Записывает диагностический архив в выбранный пользователем файл.
     *
     * Поток открывает и закрывает вызывающий этого метода владелец: провайдер
     * документов может оказаться медленным, а сборщик архива намеренно не
     * закрывает чужой поток.
     */
    public fun writeDiagnostics(destination: Uri): DiagnosticBundleResult {
        // Незавершённые и недавно завершённые вместе: отключение устройства
        // перед выгрузкой — обычное дело, и без закрытых сессий отчёт был бы
        // пустым именно в самом интересном случае.
        val sessions = usbSessions.sessions.value + usbSessions.recentlyClosedSessions()
        val snapshot = events.snapshot()
        val host = HostFacts.collect(this)
        // Серийные номера целей и Android ID хоста заменяются меткой перед
        // выгрузкой: отчёт пересылают, а эти значения связывают его с
        // конкретными устройствами (docs/02_INVARIANTS.md §3). Метка одна для
        // одного значения, поэтому отчёты по-прежнему можно сопоставить.
        val identifiers = sessions.mapNotNull { it.identity.serialNumber } +
            snapshot.mapNotNull { it.targetId?.value }
                .filter { it.startsWith(SERIAL_TARGET_PREFIX) }
                .map { it.removePrefix(SERIAL_TARGET_PREFIX) } +
            // Недоступный ID записан словом, а не значением: его заменять нельзя.
            listOfNotNull(host[HOST_ANDROID_ID]?.takeUnless { it.startsWith(UNAVAILABLE_PREFIX) })
        val sections = DiagnosticRedaction.redact(
            UsbDiagnosticReport.sections(
                host = host,
                sessions = sessions,
                events = snapshot,
                droppedEvents = events.droppedCount(),
            ),
            identifiers.toSet(),
        )
        return contentResolver.openOutputStream(destination).use { output ->
            requireNotNull(output) { "Document provider returned no stream" }
            DiagnosticBundle.write(output, sections, Instant.now())
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Операции, застигнутые гибелью процесса, читаются и закрываются здесь
        // — до того, как экран успеет показать их идущими. Продолжения среди
        // исходов нет: транспорт ушёл вместе с процессом, и «сейчас дожмём»
        // было бы обещанием того, чего не существует (`06` §3).
        //
        // Поколение сессии на этот момент ещё не известно и передаётся как
        // `null`: устройство не захвачено, а совет «подключите устройство и
        // проверьте» от этого только точнее.
        operations.restoreAll(currentGeneration = null)
        usbSessions.start()
        // Соединения ADB и Fastboot живы ровно пока удерживается интерфейс. Наблюдение
        // заведено здесь, а не внутри контроллера: владелец USB живёт на уровне
        // приложения, и подписываться на него должен тот, кто им владеет.
        scope.launch {
            usbSessions.sessions.collect { sessions ->
                adbLink.onUsbSessionsChanged(sessions)
                fastbootLink.onUsbSessionsChanged(sessions)
            }
        }
    }

    private companion object {
        /** Каталог ключа ADB внутри приватного хранилища приложения. */
        /** Каталог записей операций внутри приватного хранилища приложения. */
        private const val OPERATIONS_FOLDER = "operations"

        const val ADB_KEY_FOLDER = "adb"

        /** Префикс личности цели, построенной по серийному номеру (`UsbTargetIdentity`). */
        const val SERIAL_TARGET_PREFIX = "serial:"

        /** Ключ Android ID хоста в [HostFacts]. */
        const val HOST_ANDROID_ID = "host.androidId"

        /** Так [HostFacts] пишет значение, которого нет. */
        const val UNAVAILABLE_PREFIX = "unavailable"
    }

    /**
     * Заводит отсчёт ожидания ответа на запрос разрешения.
     *
     * Координатор время не планирует намеренно, поэтому отсчёт живёт здесь. По
     * истечении решение принимает та же политика, что и в остальных случаях:
     * если разрешение к этому моменту уже выдано, сессия закрывается молча и
     * подключение не происходит неявно.
     */
    private fun schedulePermissionTimeout(generation: SessionGeneration) {
        scope.launch {
            delay(UsbPermissionPolicy.RESPONSE_TIMEOUT_MS)
            // Будить координатор имеет смысл только если сессия всё ещё ждёт
            // ответа. Иначе таймер опоздал: ответ давно получен, интерфейс мог
            // быть уже захвачен, и запись «истекло ожидание разрешения»
            // читалась бы в отчёте как происшествие, которым она не является.
            // Гонку между этой проверкой и вызовом разрешает сам координатор,
            // возвращая IGNORE.
            val session = usbSessions.sessions.value
                .firstOrNull { it.generation == generation }
                ?.takeIf { it.state == UsbSessionState.PERMISSION_PENDING }
                ?: return@launch
            usbSessions.onPermissionTimeout(
                generation = generation,
                permissionGrantedNow = host.hasPermission(session.candidate.device),
            )
        }
    }
}
