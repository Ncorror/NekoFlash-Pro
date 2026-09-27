package io.github.ncorror.nekoflash.protocol.fastboot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Граница мутации для команд, меняющих устройство.
 *
 * Проверяется не «команда ушла», а что про исход говорится правда: где
 * состояние устройства известно, где это его отказ, а где неизвестность —
 * `Unknown` из `03` §3. Формы команд и сверка слота взяты из архивов до кода
 * (`16` §3).
 */
class FastbootMutationTest {
    /** `OKAY` на команду без слота — исход известен и назван своим классом. */
    @Test
    fun aWriteThatTheDeviceAcceptsIsApplied() {
        val transport = FakeFastbootTransport().willReply("OKAY")
        val lane = FastbootLane(transport)

        val outcome = mutation(lane).run("flash:boot")

        assertEquals(
            FastbootMutationOutcome.Applied("flash:boot", FastbootMutationClass.PARTITION, "", emptyList()),
            outcome,
        )
        assertEquals(listOf("flash:boot"), transport.sent)
        assertEquals(FastbootLaneState.IDLE, lane.state)
    }

    /**
     * Отказ — слово устройства, и рамку он не портит.
     *
     * Целости раздела он при этом не доказывает, и поле, которое бы это
     * утверждало, здесь намеренно отсутствует: известно, что сказало
     * устройство, а не что оно успело сделать до отказа. Этим исход отличается
     * от `download:`, где отказ до фазы данных означает ноль отправленных байт.
     */
    @Test
    fun aRefusalIsTheDevicesWordAndKeepsTheFrame() {
        val lane = FastbootLane(FakeFastbootTransport().willReply("FAILnot allowed in locked state"))

        val outcome = mutation(lane).run("erase:userdata")

        val refused = outcome as FastbootMutationOutcome.Refused
        assertEquals("not allowed in locked state", refused.detail)
        assertEquals(FastbootMutationClass.PARTITION, refused.mutation)
        assertEquals(FastbootLaneState.IDLE, lane.state)
    }

    /** Молчание на запись в раздел — неизвестность, и раздел мог остаться половинным. */
    @Test
    fun silenceOnAPartitionWriteIsUnknownNotRefusal() {
        val lane = FastbootLane(FakeFastbootTransport().willBeSilent(100))

        val outcome = mutation(lane).run("flash:boot", inactivityMillis = 100)

        val unknown = outcome as FastbootMutationOutcome.Unknown
        assertEquals(FastbootMutationClass.PARTITION, unknown.mutation)
        assertEquals(FastbootLaneState.STALLED, lane.state)
    }

    /**
     * Молчание на перезагрузку — уход, а не неизвестность.
     *
     * Ни один архив этого не различает: Legacy на любом молчании помечает
     * сессию `BROKEN`. Для `reboot` это неверно по существу — мы попросили
     * устройство уйти, и оно ушло.
     */
    @Test
    fun silenceOnARebootIsADepartureNotUnknown() {
        val lane = FastbootLane(FakeFastbootTransport().willBeSilent(100))

        val outcome = mutation(lane).run("reboot-bootloader", inactivityMillis = 100)

        assertTrue("уход, а не неизвестность", outcome is FastbootMutationOutcome.Departed)
    }

    /** Та же тишина на той же полосе, но команда другая — и исход обязан отличаться. */
    @Test
    fun theSameSilenceIsReadDifferentlyForRebootAndForErase() {
        val reboot = mutation(FastbootLane(FakeFastbootTransport().willBeSilent(100)))
            .run("reboot", inactivityMillis = 100)
        val erase = mutation(FastbootLane(FakeFastbootTransport().willBeSilent(100)))
            .run("erase:boot", inactivityMillis = 100)

        assertTrue(reboot is FastbootMutationOutcome.Departed)
        assertTrue(erase is FastbootMutationOutcome.Unknown)
    }

    /**
     * `OKAY` на `set_active:` переключением не является, и это перечитывается.
     *
     * Прямо из Legacy `verifyTerminalMutation`: расхождение с `current-slot`
     * пишется как «not confirmed», а не как успех.
     */
    @Test
    fun anAcceptedSlotSwitchIsConfirmedByRereadingCurrentSlot() {
        val transport = FakeFastbootTransport().willReply("OKAY", "OKAYb")
        val lane = FastbootLane(transport)

        val outcome = mutation(lane).run("set_active:b")

        val applied = outcome as FastbootMutationOutcome.Applied
        assertEquals("current-slot=b", applied.confirmation)
        assertEquals(listOf("set_active:b", "getvar:current-slot"), transport.sent)
    }

    /** Устройство согласилось, а слот остался прежним — успехом это не считается. */
    @Test
    fun aSlotSwitchThatTheDeviceDidNotMakeIsUnconfirmed() {
        val lane = FastbootLane(FakeFastbootTransport().willReply("OKAY", "OKAYa"))

        val outcome = mutation(lane).run("set_active:b")

        val unconfirmed = outcome as FastbootMutationOutcome.Unconfirmed
        assertEquals("b", unconfirmed.expected)
        assertEquals("a", unconfirmed.observed)
    }

    /** Слот не прочитался — тоже не подтверждение, и молчание не выдаётся за согласие. */
    @Test
    fun aSlotSwitchIsUnconfirmedWhenCurrentSlotCannotBeRead() {
        val transport = FakeFastbootTransport().willReply("OKAY").willBeSilent(100)
        val lane = FastbootLane(transport)

        val outcome = mutation(lane).run("set_active:b", inactivityMillis = 100)

        val unconfirmed = outcome as FastbootMutationOutcome.Unconfirmed
        assertEquals("неизвестно", unconfirmed.observed)
    }

    /** Слот сравнивается канонично: `_b`, `b` и `B` — один слот, а не три. */
    @Test
    fun theSlotIsComparedCanonicallyRatherThanAsAString() {
        val lane = FastbootLane(FakeFastbootTransport().willReply("OKAY", "OKAY_B"))

        val outcome = mutation(lane).run("set_active:b")

        assertTrue("подчёркивание и регистр слот не меняют", outcome is FastbootMutationOutcome.Applied)
    }

    /**
     * Фаза данных на команде без нагрузки ломает рамку.
     *
     * Legacy помечает сессию `BROKEN` со словами «Terminal Fastboot command
     * entered DATA phase without a payload handler»: устройство осталось ждать
     * байты, о которых мы не договаривались.
     */
    @Test
    fun aDataPhaseOnACommandWithoutAPayloadBreaksTheFrame() {
        val lane = FastbootLane(FakeFastbootTransport().willReply("DATA00001000"))

        val outcome = mutation(lane).run("erase:boot")

        val unknown = outcome as FastbootMutationOutcome.Unknown
        assertTrue(unknown.detail.contains("фазу данных"))
        assertEquals(FastbootLaneState.STALLED, lane.state)
    }

    /** Не отправленная команда — не мутация: устройство её не видело. */
    @Test
    fun aCommandThatNeverLeftTheHostIsNotStarted() {
        val lane = FastbootLane(FakeFastbootTransport())
        lane.close()

        val outcome = mutation(lane).run("flash:boot")

        assertTrue(outcome is FastbootMutationOutcome.NotStarted)
    }

    /** Классификация называет класс состояния, а не разрешение. */
    @Test
    fun commandsAreClassifiedByWhatTheyChange() {
        assertEquals(FastbootMutationClass.PARTITION, FastbootMutation.classify("flash:boot_a"))
        assertEquals(FastbootMutationClass.PARTITION, FastbootMutation.classify("ERASE:userdata"))
        assertEquals(FastbootMutationClass.PARTITION, FastbootMutation.classify("format:cache"))
        assertEquals(FastbootMutationClass.SLOT, FastbootMutation.classify("set_active:a"))
        assertEquals(FastbootMutationClass.BOOT, FastbootMutation.classify("boot"))
        assertEquals(FastbootMutationClass.REBOOT, FastbootMutation.classify("reboot"))
        assertEquals(FastbootMutationClass.REBOOT, FastbootMutation.classify("reboot-fastboot"))
        assertEquals(FastbootMutationClass.NONE, FastbootMutation.classify("getvar:product"))
    }

    /**
     * Незнакомая команда — `NONE`, и это значит «не знаем», а не «безопасна».
     *
     * Отказывать по этой причине нельзя: в поле консоли набирают в том числе
     * `oem`-команды, которых не знает никто, кроме конкретного загрузчика
     * (`01` §3).
     */
    @Test
    fun anUnknownCommandIsNeitherClassifiedNorRefused() {
        val transport = FakeFastbootTransport().willReply("OKAY")

        val outcome = mutation(FastbootLane(transport)).run("oem device-info")

        assertEquals(FastbootMutationClass.NONE, (outcome as FastbootMutationOutcome.Applied).mutation)
        assertEquals("команда ушла как набрана", listOf("oem device-info"), transport.sent)
    }

    /** `boot` грузит из буфера и раздел не трогает — класс у него свой. */
    @Test
    fun bootingFromTheBufferIsNotAPartitionWrite() {
        val outcome = mutation(FastbootLane(FakeFastbootTransport().willReply("OKAY"))).run("boot")

        assertEquals(FastbootMutationClass.BOOT, (outcome as FastbootMutationOutcome.Applied).mutation)
    }

    /** `bootloader`-подобное имя не должно читаться как `boot`. */
    @Test
    fun aCommandThatMerelyStartsWithBootIsNotBoot() {
        assertEquals(FastbootMutationClass.NONE, FastbootMutation.classify("bootcontrol"))
        assertFalse(FastbootMutation.classify("rebooting") == FastbootMutationClass.REBOOT)
    }

    /**
     * Смена замка названа своим классом, а чтение о замке — нет.
     *
     * `flashing get_unlock_ability` ничего не меняет, и назвать его сменой
     * замка было бы неправдой. Ни Legacy, ни A2 семейства `flashing` не знают
     * вовсе — Legacy умеет только `oem unlock` от Xiaomi, — так что это наше
     * решение, помеченное как наше.
     */
    @Test
    fun changingTheLockIsItsOwnClassButReadingAboutItIsNot() {
        assertEquals(FastbootMutationClass.LOCK, FastbootMutation.classify("flashing unlock"))
        assertEquals(FastbootMutationClass.LOCK, FastbootMutation.classify("FLASHING LOCK"))
        assertEquals(FastbootMutationClass.LOCK, FastbootMutation.classify("flashing unlock_critical"))
        assertEquals(FastbootMutationClass.NONE, FastbootMutation.classify("flashing get_unlock_ability"))
    }

    /**
     * `oem` остаётся неклассифицированным намеренно.
     *
     * Что делает `oem <что-то>`, знает вендор, и на разных устройствах одно
     * слово значит разное. `NONE` здесь означает «не знаем», а не «безопасна»;
     * приписать классу догадку значило бы выдать предположение за знание.
     */
    @Test
    fun anOemCommandIsDeliberatelyLeftUnclassified() {
        assertEquals(FastbootMutationClass.NONE, FastbootMutation.classify("oem unlock"))
        assertEquals(FastbootMutationClass.NONE, FastbootMutation.classify("oem get_token"))
        assertEquals(FastbootMutationClass.NONE, FastbootMutation.classify("oem device-info"))
    }

    /**
     * Оборванная смена замка — худшее неизвестное состояние, и класс это несёт.
     *
     * Неизвестен и замок, и пользовательские данные: смена замка обычно стирает
     * устройство целиком.
     */
    @Test
    fun anInterruptedLockChangeCarriesItsOwnClass() {
        val lane = FastbootLane(FakeFastbootTransport().willBeSilent(100))

        val outcome = mutation(lane).run("flashing unlock", inactivityMillis = 100)

        assertEquals(FastbootMutationClass.LOCK, (outcome as FastbootMutationOutcome.Unknown).mutation)
        assertEquals(FastbootLaneState.STALLED, lane.state)
    }

    /**
     * Вендорские команды ждут дольше, и это терпение, а не разрешение.
     *
     * Legacy даёт `oem get_token` 30 секунд против обычных пяти, потому что
     * вендорские команды думают долго. Мерить их общей меркой значило бы
     * объявлять мёртвым то, что просто работает.
     */
    @Test
    fun vendorCommandsAreWaitedForLongerThanOrdinaryOnes() {
        assertEquals(FastbootMutation.VENDOR_INACTIVITY_MS, FastbootMutation.patienceFor("oem get_token"))
        assertEquals(FastbootMutation.VENDOR_INACTIVITY_MS, FastbootMutation.patienceFor("  FLASHING unlock "))
        assertEquals(FastbootLane.DEFAULT_INACTIVITY_MS, FastbootMutation.patienceFor("getvar:product"))
        assertEquals(FastbootLane.DEFAULT_INACTIVITY_MS, FastbootMutation.patienceFor("erase:boot"))
    }

    /**
     * Ответ вендорской команды приходит кусками `INFO`, и они доходят целиком.
     *
     * Legacy собирает токен разблокировки из нескольких фрагментов `INFO`
     * (`extractUnlockTokenPart`), то есть одна строка — это часть ответа, а не
     * ответ. Потерять хоть одну значило бы показать оператору обрывок.
     */
    @Test
    fun theInfoFragmentsOfAVendorAnswerAllArrive() {
        val transport = FakeFastbootTransport().willReply("INFOtoken: AAAA", "INFOBBBB", "OKAY")

        val outcome = mutation(FastbootLane(transport)).run("oem get_token")

        val applied = outcome as FastbootMutationOutcome.Applied
        assertEquals(listOf("token: AAAA", "BBBB"), applied.info)
    }

    private fun mutation(lane: FastbootLane) = FastbootMutation(lane, FastbootGetVar(lane))
}
