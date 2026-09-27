package io.github.ncorror.nekoflash.protocol.fastboot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * План как последовательность команд.
 *
 * Проверяется не «шаги прошли», а что план не врёт про себя: показывает весь
 * список заранее, останавливается на первом непринятом шаге и отдаёт **и**
 * сделанное, **и** место остановки.
 */
class FastbootPlanTest {
    /** Весь список виден до запуска — в этом и смысл плана. */
    @Test
    fun thePreviewShowsEveryCommandBeforeAnythingRuns() {
        val plan = FastbootPlans.flashBuffer("boot", slot = "a")

        assertEquals(listOf("flash:boot_a", "reboot"), plan.commands)
        assertEquals(
            listOf(FastbootMutationClass.PARTITION, FastbootMutationClass.REBOOT),
            plan.classes,
        )
        assertTrue("план меняет устройство, и врать об этом нельзя", plan.destructive)
    }

    /** План из одних чтений и перезагрузок разрушающим не называется. */
    @Test
    fun aPlanOfReadsAndRebootsIsNotCalledDestructive() {
        assertFalse(FastbootPlan(listOf("getvar:product", "reboot-bootloader")).destructive)
    }

    @Test
    fun everyStepReachesTheDeviceInOrder() {
        val transport = FakeFastbootTransport().willReply("OKAY", "OKAY")

        val outcome = FastbootPlans.eraseAndReboot("cache").run(mutation(transport))

        assertEquals(2, (outcome as FastbootPlanOutcome.Completed).applied.size)
        assertEquals(listOf("erase:cache", "reboot"), transport.sent)
    }

    /**
     * Отказ останавливает план, и дальше ничего не уходит.
     *
     * Продолжать после отказа неверно: он обычно означает, что предпосылки шага
     * не выполнены, и следующий шаг будет работать не с тем, на что рассчитан.
     */
    @Test
    fun aRefusalStopsThePlanAndNothingFurtherIsSent() {
        val transport = FakeFastbootTransport().willReply("FAILnot allowed in Lock State", "OKAY")

        val outcome = FastbootPlans.eraseAndReboot("cache").run(mutation(transport))

        val stopped = outcome as FastbootPlanOutcome.Stopped
        assertEquals(0, stopped.index)
        assertEquals("erase:cache", stopped.command)
        assertTrue(stopped.applied.isEmpty())
        assertEquals("второй шаг не должен был уйти", listOf("erase:cache"), transport.sent)
    }

    /**
     * Остановка посреди плана отдаёт **и** сделанное, **и** место обрыва.
     *
     * Устройство осталось между «до» и «после». Показать это как неудачу целиком
     * значило бы скрыть сделанное, а как успех — скрыть несделанное.
     */
    @Test
    fun stoppingMidwayReportsBothWhatWasDoneAndWhereItStopped() {
        val transport = FakeFastbootTransport().willReply("OKAY", "FAILno")
        val plan = FastbootPlan(listOf("erase:cache", "erase:metadata", "reboot"))

        val outcome = plan.run(mutation(transport))

        val stopped = outcome as FastbootPlanOutcome.Stopped
        assertEquals("первый шаг сделан", 1, stopped.applied.size)
        assertEquals(1, stopped.index)
        assertEquals("erase:metadata", stopped.command)
        assertEquals("третий шаг не уходил", 2, transport.sent.size)
    }

    /**
     * Неизвестность останавливает план тем более.
     *
     * Складывать неизвестность с неизвестностью нельзя: если не известно, что
     * стало с разделом, то неизвестно и с чем работает следующий шаг.
     */
    @Test
    fun anUnknownStopsThePlanAsWell() {
        val transport = FakeFastbootTransport().willBeSilent(200)
        val plan = FastbootPlan(listOf("erase:cache", "reboot"))

        val outcome = plan.run(mutation(transport), inactivityMillis = 100)

        val stopped = outcome as FastbootPlanOutcome.Stopped
        assertTrue(stopped.outcome is FastbootMutationOutcome.Unknown)
        assertEquals("не выполнены оба шага: оборвавшийся и следующий", 2, stopped.remaining)
        assertEquals("на устройство ушёл только первый", 1, transport.sent.size)
    }

    /**
     * `set_active:` в плане проверяется перечитыванием, как и поодиночке.
     *
     * Устройство может ответить `OKAY` и слот не переключить; план на этом
     * обязан остановиться, а не идти дальше с неверной посылкой.
     */
    @Test
    fun aSlotStepThatTheDeviceDidNotMakeStopsThePlan() {
        val transport = FakeFastbootTransport().willReply("OKAY", "OKAYa", "OKAY")

        val outcome = FastbootPlans.switchSlot("b").run(mutation(transport))

        val stopped = outcome as FastbootPlanOutcome.Stopped
        assertTrue(stopped.outcome is FastbootMutationOutcome.Unconfirmed)
        assertEquals("перезагрузка не должна была уйти", 2, transport.sent.size)
    }

    /** Пустой план — законный ноль шагов, а не ошибка. */
    @Test
    fun anEmptyPlanIsZeroStepsRatherThanAnError() {
        val outcome = FastbootPlan(emptyList()).run(mutation(FakeFastbootTransport()))

        assertTrue((outcome as FastbootPlanOutcome.Completed).applied.isEmpty())
    }

    private fun mutation(transport: FakeFastbootTransport): FastbootMutation {
        val lane = FastbootLane(transport)
        return FastbootMutation(lane, FastbootGetVar(lane))
    }
}
