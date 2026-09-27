package io.github.ncorror.nekoflash.protocol.fastboot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Инвентарь разделов и команды управления динамическими разделами.
 *
 * Проверяется не «разделы собрались», а что инвентарь не выдумывает: упоминание
 * имени — ещё не доказательство раздела, а неполный ответ даёт неполный
 * инвентарь и говорит об этом.
 */
class FastbootPartitionsTest {
    /**
     * `has-slot` доказательством существования раздела не является.
     *
     * Взято из A2 `hasConcreteEvidence`: устройства отвечают на `has-slot:` и
     * про имена, которых у них нет. Собрать инвентарь по нему значило бы
     * выдумать разделы и показать их оператору как настоящие.
     */
    @Test
    fun aSlotAnswerAloneIsNotEvidenceOfAPartition() {
        val inventory = FastbootPartitionIndex.of(
            snapshot("has-slot:phantom" to "yes", "partition-size:boot" to "65536"),
        )

        assertEquals(2, inventory.partitions.size)
        assertEquals(listOf("boot"), inventory.concrete.map { it.name })
        assertFalse("раздел, известный только по слоту, не подтверждён", inventory.partition("phantom")!!.concrete)
    }

    /** Поля раздела складываются из разных переменных в одну запись. */
    @Test
    fun theFieldsOfOnePartitionAreMergedIntoOneEntry() {
        val inventory = FastbootPartitionIndex.of(
            snapshot(
                "partition-size:system" to "0x40000000",
                "partition-type:system" to "ext4",
                "is-logical:system" to "yes",
                "has-slot:system" to "no",
            ),
        )

        val system = inventory.partition("system")!!
        assertEquals(1073741824L, system.sizeBytes)
        assertEquals("ext4", system.type)
        assertEquals(true, system.logical)
        assertEquals(false, system.hasSlot)
        assertTrue(system.concrete)
    }

    /**
     * Неполный ответ даёт неполный инвентарь, и это видно.
     *
     * Иначе оборванный `getvar:all` превратился бы в уверенный список, а
     * отсутствие раздела в нём читалось бы как его отсутствие на устройстве.
     */
    @Test
    fun anIncompleteAnswerYieldsAnIncompleteInventory() {
        val snapshot = FastbootVariables.parse(
            lines = listOf("partition-size:boot: 65536"),
            complete = false,
            finalReply = FastbootReply.UNKNOWN,
        )

        assertFalse(FastbootPartitionIndex.of(snapshot).complete)
    }

    /** Переменная, не относящаяся к разделам, в инвентарь не попадает. */
    @Test
    fun aVariableThatIsNotAboutPartitionsIsLeftAlone() {
        val inventory = FastbootPartitionIndex.of(
            snapshot("product" to "vayu", "max-download-size" to "805306368"),
        )

        assertTrue(inventory.partitions.isEmpty())
    }

    /** Незнакомое двухсоставное имя разделом не объявляется. */
    @Test
    fun anUnfamiliarTwoPartNameIsNotTreatedAsAPartition() {
        val inventory = FastbootPartitionIndex.of(snapshot("slot-retry-count:a" to "7"))

        assertTrue("догадываться, что это про раздел, нечем", inventory.partitions.isEmpty())
    }

    /** Нечитаемое значение не подменяется нулём: неизвестно — значит `null`. */
    @Test
    fun anUnreadableValueStaysUnknownRatherThanBecomingZero() {
        val inventory = FastbootPartitionIndex.of(
            snapshot("partition-size:boot" to "неизвестно", "is-logical:boot" to "может быть"),
        )

        val boot = inventory.partition("boot")!!
        assertNull(boot.sizeBytes)
        assertNull(boot.logical)
        assertTrue("поле всё равно названо устройством", boot.concrete)
    }

    /** Четыре команды управления разметкой super — из Legacy, и других там нет. */
    @Test
    fun theSuperManagementCommandsComeFromTheArchive() {
        assertTrue(FastbootLogicalPartitions.manages("create-logical-partition:x:1024"))
        assertTrue(FastbootLogicalPartitions.manages("DELETE-LOGICAL-PARTITION:x"))
        assertTrue(FastbootLogicalPartitions.manages("resize-logical-partition:x:2048"))
        assertTrue(FastbootLogicalPartitions.manages("update-super:super"))
        assertFalse(FastbootLogicalPartitions.manages("flash:boot"))
        assertFalse(FastbootLogicalPartitions.manages("getvar:is-logical:system"))
    }

    /**
     * Разметка super — свой класс, а не запись в раздел.
     *
     * Оборванный `resize-logical-partition:` оставляет неизвестной таблицу
     * разделов, а не байты внутри одного из них, и сказать про него «раздел мог
     * остаться записанным наполовину» было бы просто неверно.
     */
    @Test
    fun managingSuperIsItsOwnMutationClass() {
        assertEquals(FastbootMutationClass.SUPER, FastbootMutation.classify("delete-logical-partition:product_a"))
        assertEquals(FastbootMutationClass.SUPER, FastbootMutation.classify("update-super:super"))
        assertEquals(FastbootMutationClass.PARTITION, FastbootMutation.classify("flash:product_a"))
    }

    /** Осмотр раздела спрашивает ровно те четыре переменные, что и Legacy. */
    @Test
    fun describingAPartitionAsksTheSameFourVariables() {
        assertEquals(
            listOf("partition-size:boot", "partition-type:boot", "is-logical:boot", "has-slot:boot"),
            FastbootLogicalPartitions.describe(" boot "),
        )
    }

    private fun snapshot(vararg pairs: Pair<String, String>): FastbootVariableSnapshot =
        FastbootVariables.parse(pairs.map { (name, value) -> "$name: $value" })
}
