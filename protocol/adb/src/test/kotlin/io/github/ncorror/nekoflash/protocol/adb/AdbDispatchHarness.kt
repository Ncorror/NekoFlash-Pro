package io.github.ncorror.nekoflash.protocol.adb

import io.github.ncorror.nekoflash.usb.api.UsbTransportHandle
import org.junit.Assert.assertTrue
import kotlin.concurrent.thread

/**
 * Подставной транспорт с **настоящим** циклом раскладки.
 *
 * После шага 5 `docs/adr/0004_CONCURRENT_ADB_DISPATCHER_RU.md` потребители
 * транспорт не читают: ящик наполняет [AdbDispatchLoop]. Поэтому и в тестах его
 * крутит он же, на своём потоке — подменять цикл ручной подкачкой значило бы
 * проверять не тот механизм, который работает в production.
 *
 * Поток здесь неизбежен и с ADR-0003 §2 не спорит: правило описывает
 * конкурентную модель продукта, а цикл блокирующий по устройству, и разбудить
 * ждущего на ящике потребителя может только кто-то, работающий не на его
 * потоке.
 *
 * Каждый заведённый экземпляр обязан быть остановлен: цикл на исчерпанной
 * очереди крутится без пауз, и брошенные потоки съели бы процессор ещё до
 * середины сюиты. За этим следит [AdbDispatchHarnesses].
 */
internal class AdbDispatchHarness<H : UsbTransportHandle>(
    val handle: H,
    maxPayload: Int = AdbInboundFraming.MODERN_MAX_PAYLOAD_BYTES,
) {
    val writer: AdbPacketWriter = AdbPacketWriter(handle)
    val dispatcher: AdbStreamDispatcher = AdbStreamDispatcher()

    private val loop = AdbDispatchLoop(
        reader = AdbPacketReader(handle, maxPayload),
        writer = writer,
        dispatcher = dispatcher,
        readSliceMillis = SLICE_MS,
    )

    private val worker = thread(name = "adb-dispatch-test", isDaemon = true, start = false) {
        loop.run()
    }

    fun start(): AdbDispatchHarness<H> {
        worker.start()
        return this
    }

    /** Останавливает цикл и убеждается, что он действительно кончился. */
    fun stop() {
        loop.stop()
        worker.join(JOIN_MS)
        assertTrue("цикл раскладки не остановился", !worker.isAlive)
    }

    private companion object {
        /** Короткий кусок ожидания: тесту не на что ждать по четверти секунды. */
        const val SLICE_MS = 5

        const val JOIN_MS = 5_000L
    }
}

/**
 * Учёт заведённых циклов внутри одного теста.
 *
 * Сюиты держат его полем и останавливают в `@After`: иначе каждый тест оставлял
 * бы за собой крутящийся поток.
 */
internal class AdbDispatchHarnesses {
    private val started = mutableListOf<AdbDispatchHarness<*>>()

    fun <H : UsbTransportHandle> start(
        handle: H,
        maxPayload: Int = AdbInboundFraming.MODERN_MAX_PAYLOAD_BYTES,
    ): AdbDispatchHarness<H> =
        AdbDispatchHarness(handle, maxPayload).start().also { harness -> started += harness }

    fun stopAll() {
        started.forEach(AdbDispatchHarness<*>::stop)
        started.clear()
    }
}
