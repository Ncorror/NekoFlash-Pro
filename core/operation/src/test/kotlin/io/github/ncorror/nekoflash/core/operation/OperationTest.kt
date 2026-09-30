package io.github.ncorror.nekoflash.core.operation

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OperationTest {
    @Test
    fun operationIdRejectsBlankValue() {
        assertThrows(IllegalArgumentException::class.java) {
            OperationId("")
        }
    }

    @Test
    fun unknownIsAFirstClassOutcome() {
        assertEquals("UNKNOWN", OperationOutcome.UNKNOWN.name)
    }

    @Test
    fun mutationBoundaryRecordsIrreversibleTransition() {
        val boundary = MutationBoundary.Crossed(
            at = Instant.EPOCH,
            detail = "first mutating wire command accepted",
        )

        assertEquals("first mutating wire command accepted", boundary.detail)
    }
}
