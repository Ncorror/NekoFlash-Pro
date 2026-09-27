package io.github.ncorror.nekoflash.usb.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbPermissionCallbackIdentityTest {
    @Test
    fun theFirstActivationIsNumberOneAndCarriesEveryIdentityField() {
        val identity = UsbPermissionCallbackIdentity("io.github.ncorror.nekoflash.USB", "p42")

        val action = identity.nextAction()

        assertEquals("io.github.ncorror.nekoflash.USB.p42.1", action)
        assertEquals(1L, identity.currentActivation)
    }

    @Test
    fun successiveActivationsCannotShareOneAction() {
        val identity = UsbPermissionCallbackIdentity("prefix", "p42")

        val first = identity.nextAction()
        val second = identity.nextAction()

        assertNotEquals(first, second)
        assertFalse(identity.matchesCurrent(first))
        assertTrue(identity.matchesCurrent(second))
    }

    @Test
    fun aNewProcessTokenCannotReproduceAnOldAction() {
        val oldProcess = UsbPermissionCallbackIdentity("prefix", "p1")
        val newProcess = UsbPermissionCallbackIdentity("prefix", "p2")

        val oldAction = oldProcess.nextAction()
        newProcess.nextAction()

        assertFalse(newProcess.matchesCurrent(oldAction))
    }

    @Test
    fun nothingMatchesBeforeTheFirstActivation() {
        val identity = UsbPermissionCallbackIdentity("prefix", "p42")

        assertFalse(identity.matchesCurrent("prefix.p42.1"))
        assertFalse(identity.matchesCurrent(null))
        assertEquals(0L, identity.currentActivation)
    }

    @Test
    fun anUnrelatedActionNeverMatches() {
        val identity = UsbPermissionCallbackIdentity("prefix", "p42")
        identity.nextAction()

        assertFalse(identity.matchesCurrent("prefix.p42.2"))
        assertFalse(identity.matchesCurrent("other.p42.1"))
        assertFalse(identity.matchesCurrent(""))
    }

    @Test
    fun blankIdentityComponentsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            UsbPermissionCallbackIdentity(" ", "p42")
        }
        assertThrows(IllegalArgumentException::class.java) {
            UsbPermissionCallbackIdentity("prefix", "")
        }
    }
}
