package io.github.ncorror.nekoflash.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TargetTest {
    @Test
    fun targetIdRejectsBlankValue() {
        assertThrows(IllegalArgumentException::class.java) {
            TargetId("   ")
        }
    }

    @Test
    fun sessionGenerationMustBePositive() {
        assertThrows(IllegalArgumentException::class.java) {
            SessionGeneration(0)
        }
    }

    @Test
    fun targetSnapshotKeepsLogicalIdentitySeparateFromSessionGeneration() {
        val snapshot = TargetSnapshot(
            id = TargetId("device-1"),
            mode = TargetMode.ADB,
            sessionGeneration = SessionGeneration(3),
        )

        assertEquals("device-1", snapshot.id.value)
        assertEquals(3L, snapshot.sessionGeneration?.value)
    }
}
