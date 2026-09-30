package io.github.ncorror.nekoflash.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Сервисы установки — те же строки, что отправляет настольный `adb`. */
class InstallCommandsTest {
    @Test
    fun singleInstallDeclaresTheSizeAfterTheFlags() {
        assertEquals("exec:cmd package install -r -g -S 1234", InstallCommands.install(listOf("-r", "-g"), 1234))
    }

    @Test
    fun sessionCommandsCarryTheSessionAndSafeNames() {
        assertEquals("exec:cmd package install-create -S 30", InstallCommands.create(emptyList(), 30))
        assertEquals(
            "exec:cmd package install-write -S 10 42 1_split_config.arm64 v8a.apk -".replace(" v8a", "_v8a"),
            InstallCommands.write("42", 1, "split_config.arm64 v8a.apk", 10),
        )
        assertEquals("exec:cmd package install-commit 42", InstallCommands.commit("42"))
        assertEquals("exec:cmd package install-abandon 42", InstallCommands.abandon("42"))
    }

    @Test
    fun answersAreReadAsPackageWritesThem() {
        assertEquals("1234", InstallCommands.session("Success: created install session [1234]\n"))
        assertNull(InstallCommands.session("Error: something"))
        assertTrue(InstallCommands.accepted("Success\n"))
        assertFalse(InstallCommands.accepted("Failure [INSTALL_FAILED_VERSION_DOWNGRADE]"))
    }
}
