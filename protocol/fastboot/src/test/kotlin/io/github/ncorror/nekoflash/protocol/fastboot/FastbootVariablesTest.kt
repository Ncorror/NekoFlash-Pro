package io.github.ncorror.nekoflash.protocol.fastboot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбор вывода `getvar:all`.
 *
 * Случаи взяты из архивов, а не придуманы: префиксы, «последнее значение
 * главное» и двухсоставные имена разделов сверены с Legacy
 * `FastbootGetVarAllParser.kt` и A2 одноимённым до написания кода (`16` §3).
 */
class FastbootVariablesTest {
    @Test
    fun aPlainVariableIsReadByItsFirstColon() {
        val snapshot = FastbootVariables.parse(listOf("product: vayu"))

        assertEquals("vayu", snapshot.value("product"))
    }

    @Test
    fun theNameIsCaseInsensitive() {
        val snapshot = FastbootVariables.parse(listOf("Product: vayu"))

        assertEquals("vayu", snapshot.value("PRODUCT"))
    }

    /** `(bootloader)` — настоящий префикс, его показывает `fastboot` в консоли. */
    @Test
    fun theBootloaderPrefixIsStripped() {
        val snapshot = FastbootVariables.parse(listOf("(bootloader) product: vayu"))

        assertEquals("vayu", snapshot.value("product"))
    }

    /**
     * Префиксы комбинируются, поэтому снимаются в цикле.
     *
     * Снять один раз означало бы оставить имя переменной с мусором и потерять её.
     */
    @Test
    fun combinedPrefixesAreStrippedRepeatedly() {
        val snapshot = FastbootVariables.parse(listOf("INFO(bootloader) secure: yes"))

        assertEquals("yes", snapshot.value("secure"))
    }

    @Test
    fun oneFrameMayCarryManyLines() {
        val snapshot = FastbootVariables.parse(listOf("product: vayu\nsecure: yes"))

        assertEquals("vayu", snapshot.value("product"))
        assertEquals("yes", snapshot.value("secure"))
    }

    /**
     * Имя с разделом содержит второе двоеточие, и разрезать по первому нельзя.
     *
     * Иначе все разделы слились бы в одну переменную `partition-size`, и разбор
     * выглядел бы успешным, потеряв почти всё.
     */
    @Test
    fun aPartitionScopedNameKeepsItsPartition() {
        val snapshot = FastbootVariables.parse(
            listOf("partition-size:boot: 0x4000000", "partition-size:system: 0x8000000"),
        )

        assertEquals("0x4000000", snapshot.value("partition-size:boot"))
        assertEquals("0x8000000", snapshot.value("partition-size:system"))
    }

    @Test
    fun everyKnownScopedFamilyIsRecognised() {
        val lines = listOf(
            "partition-type:boot: raw",
            "is-logical:system: yes",
            "has-slot:boot: yes",
            "slot-successful:a: yes",
            "slot-unbootable:b: no",
            "slot-retry-count:a: 7",
        )

        val snapshot = FastbootVariables.parse(lines)

        assertEquals("raw", snapshot.value("partition-type:boot"))
        assertEquals("yes", snapshot.value("is-logical:system"))
        assertEquals("yes", snapshot.value("has-slot:boot"))
        assertEquals("yes", snapshot.value("slot-successful:a"))
        assertEquals("no", snapshot.value("slot-unbootable:b"))
        assertEquals("7", snapshot.value("slot-retry-count:a"))
    }

    /** Незнакомое двухсоставное имя разбирается по первому двоеточию — и это видно. */
    @Test
    fun anUnknownTwoPartNameIsSplitVisiblyRatherThanGuessed() {
        val snapshot = FastbootVariables.parse(listOf("vendor-thing:target: value"))

        assertEquals("target: value", snapshot.value("vendor-thing"))
    }

    /** Последнее значение главное — так ведёт себя `fastboot` CLI. */
    @Test
    fun theLastValueWins() {
        val snapshot = FastbootVariables.parse(listOf("current-slot: a", "current-slot: b"))

        assertEquals("b", snapshot.value("current-slot"))
    }

    /**
     * Расхождение под одним именем сохраняется целиком.
     *
     * Это наблюдение об устройстве, а не шум: потеряв его, мы отдали бы один
     * ответ там, где устройство дало два разных.
     */
    @Test
    fun conflictingDuplicatesAreKeptAndMarked() {
        val snapshot = FastbootVariables.parse(listOf("current-slot: a", "current-slot: b"))

        val duplicate = snapshot.duplicates.single()
        assertEquals("current-slot", duplicate.name)
        assertEquals(listOf("a", "b"), duplicate.values)
        assertTrue("значения разные — это конфликт", duplicate.conflicting)
    }

    /** Повтор одного и того же значения конфликтом не считается. */
    @Test
    fun aRepeatedIdenticalValueIsNotAConflict() {
        val snapshot = FastbootVariables.parse(listOf("secure: yes", "secure: yes"))

        assertFalse(snapshot.duplicates.single().conflicting)
    }

    /** `all: done!` — конец вывода, а не переменная. */
    @Test
    fun theTerminatorLineIsNotAVariable() {
        val snapshot = FastbootVariables.parse(listOf("product: vayu", "all: done!"))

        assertNull(snapshot.value("all"))
        assertEquals(1, snapshot.variables.size)
    }

    /**
     * Неразобранная строка сохраняется, а не выбрасывается.
     *
     * Выбросить её значило бы выдать неполный разбор за полный.
     */
    @Test
    fun anUnparsedLineIsKept() {
        val snapshot = FastbootVariables.parse(listOf("что-то без двоеточия", "product: vayu"))

        assertEquals(listOf("что-то без двоеточия"), snapshot.ignored)
        assertEquals("vayu", snapshot.value("product"))
    }

    @Test
    fun anEmptyValueIsIgnoredRatherThanStoredAsBlank() {
        val snapshot = FastbootVariables.parse(listOf("product:"))

        assertNull(snapshot.value("product"))
        assertEquals(listOf("product:"), snapshot.ignored)
    }

    /**
     * Тот же случай, но именами, которые действительно назвало устройство.
     *
     * Пример выше придуман; эти две строки vayu шлёт на `getvar:all` в каждом
     * прогоне (`07` §6.74, `ignoredLines=version-baseband: | version-bootloader:`).
     * Сказать, что у `version-baseband` значение `""`, значило бы объявить
     * пустую строку версией baseband, а выбросить строку — потерять факт, что
     * переменную назвали. Она сохраняется целиком.
     */
    @Test
    fun theEmptyValuedVersionLinesOfARealBootloaderAreKeptVerbatim() {
        val snapshot = FastbootVariables.parse(
            listOf("(bootloader) version-baseband:", "(bootloader) version-bootloader:", "(bootloader) product: vayu"),
        )

        assertEquals(listOf("version-baseband:", "version-bootloader:"), snapshot.ignored)
        assertNull(snapshot.value("version-baseband"))
        assertEquals("vayu", snapshot.value("product"))
    }

    @Test
    fun blankLinesAreNeitherVariablesNorIgnored() {
        val snapshot = FastbootVariables.parse(listOf("", "   ", "product: vayu"))

        assertTrue(snapshot.ignored.isEmpty())
        assertEquals(1, snapshot.variables.size)
    }

    /** Оборванный обмен помечается неполным: отличить его от полного обязательно. */
    @Test
    fun anInterruptedExchangeIsMarkedIncomplete() {
        val snapshot = FastbootVariables.parse(
            listOf("product: vayu"),
            complete = false,
            finalReply = FastbootReply.UNKNOWN,
        )

        assertFalse(snapshot.complete)
        assertEquals(FastbootReply.UNKNOWN, snapshot.finalReply)
    }

    @Test
    fun theDeviceRefusalIsCarriedAlongWithWhateverWasRead() {
        val snapshot = FastbootVariables.parse(
            listOf("product: vayu"),
            finalReply = FastbootReply.FAIL,
            finalPayload = "unknown command",
        )

        assertEquals(FastbootReply.FAIL, snapshot.finalReply)
        assertEquals("unknown command", snapshot.finalPayload)
        assertEquals("прочитанное не теряется", "vayu", snapshot.value("product"))
    }

    @Test
    fun normalisationLeavesAnOrdinaryLineAlone() {
        assertEquals("product: vayu", FastbootVariables.normalize("  product: vayu  "))
    }
}
