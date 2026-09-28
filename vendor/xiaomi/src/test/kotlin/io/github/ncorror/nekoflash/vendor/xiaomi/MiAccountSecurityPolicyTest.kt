package io.github.ncorror.nekoflash.vendor.xiaomi

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiAccountSecurityPolicyTest {
    @Test
    fun onlyOfficialHttpsHostsMayReceiveSignedUnlockRequests() {
        assertEquals(
            "unlock.update.intl.miui.com",
            MiAccountSecurityPolicy.requireAllowedUnlockApiUrl(
                "https://unlock.update.intl.miui.com/api/v3/ahaUnlock", "test"
            ).host,
        )
        listOf(
            "http://unlock.update.intl.miui.com/api/v3/ahaUnlock",
            "https://unlock.update.intl.miui.com.attacker.example/api/v3/ahaUnlock",
            "https://unlock.update.intl.miui.com:444/api/v3/ahaUnlock",
            "https://unlock.update.intl.miui.com/sts",
        ).forEach { url ->
            assertTrue(runCatching { MiAccountSecurityPolicy.requireAllowedUnlockApiUrl(url, "test") }
                .exceptionOrNull() is IOException)
        }
    }

    @Test
    fun accountCookiesCannotBeRedirectedToUntrustedHost() {
        val source = MiAccountSecurityPolicy.requireAllowedAccountUrl(
            "https://account.xiaomi.com/pass/serviceLogin", "test"
        )
        assertTrue(MiAccountSecurityPolicy.isAllowedAccountUrl(source))
        assertFalse(MiAccountSecurityPolicy.isAllowedAccountUrl("https://account.xiaomi.com.evil.example/"))
        assertTrue(runCatching {
            MiAccountSecurityPolicy.resolveAllowedAuthRedirect(source, "https://evil.example/steal")
        }.exceptionOrNull() is IOException)
    }
}
