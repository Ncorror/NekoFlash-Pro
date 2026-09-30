package io.github.ncorror.nekoflash.protocol.adb

import io.github.ncorror.nekoflash.core.diagnostics.InMemoryDiagnosticSink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

/**
 * Sideload на настоящем транспорте: машина состояний, цикл раскладки и провод.
 *
 * Тесты машины состояний (`AdbSideloadSessionTest`) проверяют правила; эти —
 * что правила действительно доходят до провода. Разница не формальная: машина
 * была написана как самостоятельный пакетный автомат, а поток открывает
 * диспетчер, и стыковка этих двух вещей и есть то, что здесь проверяется.
 */
class AdbSideloadDriverTest {
    private val harnesses = AdbDispatchHarnesses()

    @After
    fun stopLoops() {
        harnesses.stopAll()
    }

    /**
     * Полная передача: блоки дошли, `DONEDONE` кончил передачу.
     *
     * И сразу главное про исход: это `TransferComplete`, а не успех установки.
     * Вердикт даёт Recovery, а не наш счётчик (`03` §6, инвариант 1).
     */
    @Test
    fun aWholePayloadReachesRecoveryAndDoneDoneEndsTheTransfer() {
        val device = FakeRecoveryDevice(requests = listOf(0, 1, AdbSideloadContract.END_OF_REQUESTS))
        val run = send(device, totalBytes = BLOCK.toLong() * 2)

        assertEquals(AdbSideloadOutcome.TransferComplete, run.outcome)
        assertEquals("два блока", 2, device.receivedBlocks.size)
        assertEquals(BLOCK, device.receivedBlocks[0].size)
        assertArrayEqualsAt(offset = 0L, actual = device.receivedBlocks[0])
        assertArrayEqualsAt(offset = BLOCK.toLong(), actual = device.receivedBlocks[1])
    }

    /**
     * Покрытие двигают **подтверждения устройства**, а не отправленные байты.
     *
     * Ради этого поток и открывается с подтверждениями: маршрутизатор `OKAY`
     * открытого потока никуда не отдавал, и считать было бы нечем. Проверяется
     * это не счётчиком внутри, а прогрессом, который увидит оператор.
     */
    @Test
    fun coverageIsDrivenByTheDeviceAcknowledgements() {
        val device = FakeRecoveryDevice(requests = listOf(0, 1, AdbSideloadContract.END_OF_REQUESTS))

        val run = send(device, totalBytes = BLOCK.toLong() * 2)

        val covered = run.progress.map { it.uniqueBytes }
        assertEquals(listOf(BLOCK.toLong(), BLOCK.toLong() * 2), covered)
        assertEquals("оба блока подтверждены", 2, run.progress.last().uniqueBlocks)
    }

    /**
     * Повторный запрос блока раздувает отданное и **не** двигает покрытие.
     *
     * Recovery вправе попросить блок не раз, и на проводе это видно: тот же
     * блок уходит дважды. Считать это прогрессом значило бы показать больше ста
     * процентов на исправной передаче.
     */
    @Test
    fun aRepeatedRequestGrowsTrafficButNotCoverage() {
        val device = FakeRecoveryDevice(requests = listOf(0, 0, 1, AdbSideloadContract.END_OF_REQUESTS))

        val run = send(device, totalBytes = BLOCK.toLong() * 2)

        assertEquals("блок ушёл трижды", 3, device.receivedBlocks.size)
        val last = run.progress.last()
        assertEquals(BLOCK.toLong() * 3, last.servedBytes)
        assertEquals(BLOCK.toLong() * 2, last.uniqueBytes)
    }

    /**
     * Источник читается **с произвольного доступа**, а не по порядку.
     *
     * Повторный запрос обязан принести тот же блок. Источник-поток отдал бы
     * следующий кусок, и образ уехал бы перемешанным при исправной с виду
     * передаче.
     */
    @Test
    fun aRepeatedRequestBringsTheSameBytes() {
        val device = FakeRecoveryDevice(requests = listOf(1, 1, AdbSideloadContract.END_OF_REQUESTS))

        send(device, totalBytes = BLOCK.toLong() * 2)

        assertTrue(device.receivedBlocks[0].contentEquals(device.receivedBlocks[1]))
        assertArrayEqualsAt(offset = BLOCK.toLong(), actual = device.receivedBlocks[0])
    }

    /**
     * Закрытие Recovery **после** нагрузки — не сбой, а неизвестное состояние.
     *
     * Устройство начинает меняться, как только у него появились данные, и
     * назвать обрыв обычной неудачей значило бы пообещать, что ничего не
     * произошло.
     */
    @Test
    fun aCloseAfterThePayloadIsNeverAnOrdinaryFailure() {
        val device = FakeRecoveryDevice(
            requests = listOf(0),
            closeInsteadOfDoneDone = true,
        )

        val run = send(device, totalBytes = BLOCK.toLong() * 10)

        val outcome = run.outcome as AdbSideloadOutcome.InterruptedAfterPayload
        assertEquals(10, outcome.percent)
        assertTrue(AdbSideloadContract.requiresVerification(outcome))
    }

    /** Граница мутации попадает в журнал: без записи её не доказать на прогоне. */
    @Test
    fun theMutationBoundaryIsRecorded() {
        val device = FakeRecoveryDevice(requests = listOf(0, AdbSideloadContract.END_OF_REQUESTS))

        val run = send(device, totalBytes = BLOCK.toLong())

        val boundary = run.diagnostics.filter { it.message == "sideload_mutation_boundary" }
        assertEquals("отметка ровно одна", 1, boundary.size)
        assertEquals("0", boundary.single().fields["block"])
        // Вызывающему она объявляется тоже и ровно один раз: по ней UI перестаёт
        // предлагать отмену, а вывести её из прогресса нельзя — прогресс
        // приходит на подтверждение, то есть уже после границы.
        assertEquals(1, run.boundaries)
    }

    /** Сбой чтения на хосте — сбой **файла**, и границы мутации он не трогает. */
    @Test
    fun aSourceThatCannotReadIsAFileFailureAndNotAMutation() {
        val device = FakeRecoveryDevice(requests = listOf(0, AdbSideloadContract.END_OF_REQUESTS))
        val run = send(
            device,
            totalBytes = BLOCK.toLong(),
            source = { _, _ -> error("носитель отвалился") },
        )

        val outcome = run.outcome as AdbSideloadOutcome.Failed
        assertEquals(AdbSideloadFailure.FILE, outcome.kind)
        assertFalse("устройство байт не видело", AdbSideloadContract.requiresVerification(outcome))
        assertTrue(device.receivedBlocks.isEmpty())
    }

    /** Не тот режим — обмен не начинается, и ни одного байта не уходит. */
    @Test
    fun aPeerThatIsNotInSideloadIsRefusedBeforeTheFirstByte() {
        val device = FakeRecoveryDevice(requests = emptyList())

        val run = send(device, totalBytes = BLOCK.toLong(), peerIsSideload = false)

        assertTrue(run.outcome is AdbSideloadOutcome.NotInSideloadMode)
        assertTrue(device.receivedBlocks.isEmpty())
    }

    /** Пустая нагрузка — отказ, а не пустая передача. */
    @Test
    fun anEmptyPayloadIsRefusedRatherThanSent() {
        val run = send(FakeRecoveryDevice(requests = emptyList()), totalBytes = 0L)

        assertEquals(AdbSideloadFailure.FILE, (run.outcome as AdbSideloadOutcome.Failed).kind)
    }

    /** Отмена до границы мутации законна, и устройство остаётся нетронутым. */
    @Test
    fun cancellingBeforeTheBoundaryIsHonoured() {
        val device = FakeRecoveryDevice(requests = listOf(0, AdbSideloadContract.END_OF_REQUESTS))

        val run = send(device, totalBytes = BLOCK.toLong(), cancelRequested = { true })

        assertEquals(AdbSideloadOutcome.Cancelled, run.outcome)
        assertTrue(device.receivedBlocks.isEmpty())
        assertEquals("границу не переходили", 0, run.boundaries)
    }

    /** Ровно один исход передачи попадает в журнал: прогон должен читаться однозначно. */
    @Test
    fun theOutcomeIsRecordedOnce() {
        val device = FakeRecoveryDevice(requests = listOf(0, AdbSideloadContract.END_OF_REQUESTS))

        val run = send(device, totalBytes = BLOCK.toLong())

        val finished = run.diagnostics.filter { it.message == "sideload_finished" }
        assertEquals(1, finished.size)
        assertEquals("TransferComplete", finished.single().fields["outcome"])
    }

    /** Что вышло из одной передачи. */
    private class Run(
        val outcome: AdbSideloadOutcome,
        val progress: List<AdbSideloadProgress>,
        /** Сколько раз объявлена граница мутации. Больше одного — уже неправда. */
        val boundaries: Int,
        val diagnostics: List<io.github.ncorror.nekoflash.core.diagnostics.DiagnosticEvent>,
    )

    /**
     * Гоняет передачу на своём потоке.
     *
     * Драйвер блокируется на ящике, а наполняет ящик цикл раскладки — то есть
     * ждать их на одном потоке нельзя, как и в production.
     */
    private fun send(
        device: FakeRecoveryDevice,
        totalBytes: Long,
        peerIsSideload: Boolean = true,
        source: AdbSideloadSource = AdbSideloadSource { offset, length -> pattern(offset, length) },
        cancelRequested: () -> Boolean = { false },
    ): Run {
        val harness = harnesses.start(device)
        val sink = InMemoryDiagnosticSink()
        val driver = AdbSideloadDriver(harness.writer, harness.dispatcher, sink)
        val steps = mutableListOf<AdbSideloadProgress>()
        val boundaries = java.util.concurrent.atomic.AtomicInteger()
        val listener = object : AdbSideloadListener {
            override fun onProgress(progress: AdbSideloadProgress) {
                synchronized(steps) { steps += progress }
            }

            override fun onMutationBoundary() {
                boundaries.incrementAndGet()
            }
        }
        var outcome: AdbSideloadOutcome? = null
        val worker = thread(name = "sideload-test", isDaemon = true) {
            outcome = driver.send(
                source = source,
                totalBytes = totalBytes,
                transportConnected = true,
                peerIsSideload = peerIsSideload,
                listener = listener,
                timeoutMillis = TEST_TIMEOUT_MS,
                cancelRequested = cancelRequested,
            )
        }
        worker.join(JOIN_MS)
        assertFalse("передача не закончилась", worker.isAlive)
        return Run(
            outcome = requireNotNull(outcome),
            progress = synchronized(steps) { steps.toList() },
            boundaries = boundaries.get(),
            diagnostics = sink.snapshot(),
        )
    }

    private fun assertArrayEqualsAt(offset: Long, actual: ByteArray) {
        assertTrue(
            "блок со смещения $offset не совпал",
            pattern(offset, actual.size).contentEquals(actual),
        )
    }

    private companion object {
        const val BLOCK = AdbSideloadContract.BLOCK_SIZE_BYTES
        const val TEST_TIMEOUT_MS = 10_000
        const val JOIN_MS = 20_000L
        const val PATTERN_PERIOD = 251L

        /** Детерминированный узор: перепутанные блоки на нём видно. */
        fun pattern(offset: Long, length: Int): ByteArray =
            ByteArray(length) { index -> ((offset + index) % PATTERN_PERIOD).toByte() }
    }
}
