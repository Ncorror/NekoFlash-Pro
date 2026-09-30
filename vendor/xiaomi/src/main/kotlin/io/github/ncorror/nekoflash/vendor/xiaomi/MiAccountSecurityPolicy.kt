package io.github.ncorror.nekoflash.vendor.xiaomi

import java.io.IOException
import java.net.IDN
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Pure security policy for Xiaomi Account authentication.
 *
 * Interactive login remains confined to HTTPS endpoints under account.xiaomi.com.
 * The non-interactive clientSign exchange may additionally call only the exact
 * official Mi Unlock `/sts` endpoints used by Xiaomi's regional unlock service.
 * Cookies keep RFC6265 host/domain/path/secure scope, so account credentials are
 * never copied to an unlock-service host.
 */
object MiAccountSecurityPolicy {
    private const val ACCOUNT_ROOT = "account.xiaomi.com"
    private const val UNLOCK_CALLBACK_HOST = "unlock.update.miui.com"
    private const val UNLOCK_CALLBACK_PATH = "/sts"

    private val unlockServiceHosts = setOf(
        "unlock.update.miui.com",
        "unlock.update.intl.miui.com",
        "in-unlock.update.intl.miui.com",
        "ru-unlock.update.intl.miui.com",
        "eu-unlock.update.intl.miui.com"
    )
    private val cookieNamePattern = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")

    fun normalizeHost(raw: String?): String? {
        val trimmed = raw?.trim()?.trimEnd('.')?.lowercase(Locale.US).orEmpty()
        if (trimmed.isEmpty()) return null
        return runCatching { IDN.toASCII(trimmed, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.US) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    fun isAllowedAccountHost(rawHost: String?): Boolean {
        val host = normalizeHost(rawHost) ?: return false
        return host == ACCOUNT_ROOT || host.endsWith(".$ACCOUNT_ROOT")
    }

    fun isAllowedUnlockServiceHost(rawHost: String?): Boolean {
        val host = normalizeHost(rawHost) ?: return false
        return host in unlockServiceHosts
    }

    fun isAllowedAccountUrl(raw: String): Boolean =
        runCatching { isAllowedAccountUrl(URI(raw).toURL()) }.getOrDefault(false)

    fun isAllowedAccountUrl(url: URL): Boolean {
        if (!hasSafeHttpsOrigin(url)) return false
        return isAllowedAccountHost(url.host)
    }

    fun isAllowedUnlockServiceUrl(raw: String): Boolean =
        runCatching { isAllowedUnlockServiceUrl(URI(raw).toURL()) }.getOrDefault(false)

    fun isAllowedUnlockServiceUrl(url: URL): Boolean {
        if (!hasSafeHttpsOrigin(url)) return false
        if (!isAllowedUnlockServiceHost(url.host)) return false
        return url.path == UNLOCK_CALLBACK_PATH
    }

    fun isAllowedAuthFlowUrl(raw: String): Boolean =
        runCatching { isAllowedAuthFlowUrl(URI(raw).toURL()) }.getOrDefault(false)

    fun isAllowedAuthFlowUrl(url: URL): Boolean =
        isAllowedAccountUrl(url) || isAllowedUnlockServiceUrl(url)

    fun isOfficialUnlockCallbackUrl(raw: String): Boolean =
        runCatching { isOfficialUnlockCallbackUrl(URI(raw).toURL()) }.getOrDefault(false)

    /**
     * Browser completion is intentionally narrower than the HTTP exchange:
     * WebView may finish only at the exact China unlockApi callback returned by
     * Xiaomi Account. Regional `/sts` hosts are used only by the background
     * clientSign exchange and are never opened as top-level login pages.
     */
    fun isOfficialUnlockCallbackUrl(url: URL): Boolean {
        if (!hasSafeHttpsOrigin(url)) return false
        if (normalizeHost(url.host) != UNLOCK_CALLBACK_HOST) return false
        return url.path == UNLOCK_CALLBACK_PATH
    }

    @Throws(IOException::class)
    fun requireAllowedAccountUrl(raw: String, context: String): URL {
        val url = parseUrl(raw, context)
        if (!isAllowedAccountUrl(url)) {
            throw IOException("Blocked Xiaomi $context URL: ${safeDescription(url)}")
        }
        return url
    }

    @Throws(IOException::class)
    fun requireAllowedUnlockApiUrl(raw: String, context: String): URL {
        val url = parseUrl(raw, context)
        if (!hasSafeHttpsOrigin(url) || !isAllowedUnlockServiceHost(url.host) ||
            url.path !in setOf("/api/v2/nonce", "/api/v2/unlock/device/clear", "/api/v3/ahaUnlock")) {
            throw IOException("Blocked Xiaomi $context URL: ${safeDescription(url)}")
        }
        return url
    }

    @Throws(IOException::class)
    fun requireAllowedAuthFlowUrl(raw: String, context: String): URL {
        val url = parseUrl(raw, context)
        if (!isAllowedAuthFlowUrl(url)) {
            throw IOException("Blocked Xiaomi $context URL: ${safeDescription(url)}")
        }
        return url
    }

    /** Account-only redirect resolver used by interactive/login-only checks. */
    @Throws(IOException::class)
    fun resolveAllowedRedirect(current: URL, location: String): URL {
        if (!isAllowedAccountUrl(current)) {
            throw IOException("Redirect source is outside Xiaomi account allowlist")
        }
        val resolved = resolve(current, location)
        if (!isAllowedAccountUrl(resolved)) {
            throw IOException("Blocked Xiaomi redirect: ${safeDescription(resolved)}")
        }
        return resolved
    }

    /** Redirect resolver for the bounded account -> official `/sts` exchange. */
    @Throws(IOException::class)
    fun resolveAllowedAuthRedirect(current: URL, location: String): URL {
        if (!isAllowedAuthFlowUrl(current)) {
            throw IOException("Redirect source is outside Xiaomi authentication allowlist")
        }
        val resolved = resolve(current, location)
        if (!isAllowedAuthFlowUrl(resolved)) {
            throw IOException("Blocked Xiaomi authentication redirect: ${safeDescription(resolved)}")
        }
        return resolved
    }

    @Throws(IOException::class)
    fun appendQueryParameter(raw: String, name: String, value: String): String {
        val url = requireAllowedAuthFlowUrl(raw, "service location")
        if (!url.ref.isNullOrEmpty()) {
            throw IOException("Xiaomi service location must not contain a fragment")
        }
        val separator = if (raw.contains('?')) "&" else "?"
        val encodedName = URLEncoder.encode(name, "UTF-8")
        val encodedValue = URLEncoder.encode(value, "UTF-8")
        val result = raw + separator + encodedName + "=" + encodedValue
        requireAllowedAuthFlowUrl(result, "signed service location")
        return result
    }

    private fun parseUrl(raw: String, context: String): URL = try {
        URI(raw).toURL()
    } catch (e: Exception) {
        throw IOException("Invalid Xiaomi $context URL", e)
    }

    private fun resolve(current: URL, location: String): URL = try {
        current.toURI().resolve(location).toURL()
    } catch (e: Exception) {
        throw IOException("Invalid Xiaomi redirect Location", e)
    }

    private fun hasSafeHttpsOrigin(url: URL): Boolean {
        if (!url.protocol.equals("https", ignoreCase = true)) return false
        if (!url.userInfo.isNullOrEmpty()) return false
        if (url.port != -1 && url.port != 443) return false
        return true
    }

    private fun safeDescription(url: URL): String {
        val host = normalizeHost(url.host) ?: "<invalid-host>"
        val port = if (url.port == -1) "" else ":${url.port}"
        return "${url.protocol}://$host$port${url.path}"
    }

    internal fun isValidCookieName(name: String): Boolean = cookieNamePattern.matches(name)

    internal fun isValidCookieValue(value: String): Boolean =
        value.none { it == ';' || it == '\r' || it == '\n' || it.code < 0x20 || it.code == 0x7f }

    internal fun isAllowedUnlockServiceCookieName(name: String): Boolean =
        name == "serviceToken" ||
            name == "userId" ||
            name == "cUserId" ||
            name.startsWith("unlockApi_")

    internal fun isAllowedCookieDomain(requestUrl: URL, candidate: String, cookieName: String): Boolean {
        val requestHost = normalizeHost(requestUrl.host) ?: return false
        if (!domainMatches(requestHost, candidate)) return false

        if (isAllowedAccountUrl(requestUrl)) {
            return candidate == "xiaomi.com" || isAllowedAccountHost(candidate)
        }

        if (isAllowedUnlockServiceUrl(requestUrl)) {
            // A regional official /sts response may scope serviceToken to
            // .miui.com, .intl.miui.com, or an intermediate parent such as
            // .update.intl.miui.com. domainMatches() limits the candidate to
            // a real parent of the exact allowlisted request host.
            val officialParent = candidate == "miui.com" || candidate.endsWith(".miui.com")
            return officialParent && isAllowedUnlockServiceCookieName(cookieName)
        }

        return false
    }

    internal fun domainMatches(host: String, domain: String): Boolean =
        host == domain || host.endsWith(".$domain")

    internal fun pathMatches(requestPath: String, cookiePath: String): Boolean {
        if (requestPath == cookiePath) return true
        if (!requestPath.startsWith(cookiePath)) return false
        if (cookiePath.endsWith('/')) return true
        return requestPath.length > cookiePath.length && requestPath[cookiePath.length] == '/'
    }

    internal fun defaultCookiePath(url: URL): String {
        val path = url.path.takeIf { it.startsWith('/') } ?: return "/"
        if (path.count { it == '/' } <= 1) return "/"
        return path.substringBeforeLast('/') + "/"
    }
}

/** RFC6265-style cookie storage limited to the bounded Xiaomi auth flow. */
internal class MiAccountCookieJar(initialUrl: URL, initial: Map<String, String>) {
    private data class Key(val name: String, val domain: String, val path: String)
    private data class StoredCookie(
        val name: String,
        val value: String,
        val domain: String,
        val path: String,
        val secure: Boolean,
        val hostOnly: Boolean
    )

    private val cookies = linkedMapOf<Key, StoredCookie>()

    init {
        require(MiAccountSecurityPolicy.isAllowedAccountUrl(initialUrl)) {
            "Initial cookie URL must be an allowed Xiaomi Account URL"
        }
        val host = MiAccountSecurityPolicy.normalizeHost(initialUrl.host)
            ?: throw IllegalArgumentException("Initial cookie host is invalid")
        initial.forEach { (name, value) ->
            if (
                MiAccountSecurityPolicy.isValidCookieName(name) &&
                MiAccountSecurityPolicy.isValidCookieValue(value)
            ) {
                val cookie = StoredCookie(name, value, host, "/", secure = true, hostOnly = true)
                cookies[Key(name, host, "/")] = cookie
            }
        }
    }

    fun headerFor(url: URL): String {
        require(MiAccountSecurityPolicy.isAllowedAuthFlowUrl(url)) {
            "Cookie header requested for non-allowlisted URL"
        }
        return matching(url)
            .sortedWith(compareByDescending<StoredCookie> { it.path.length }.thenBy { it.name })
            .joinToString("; ") { "${it.name}=${it.value}" }
    }

    /** Known unlockApi cookies across the bounded redirect chain. */
    fun serviceEntries(): List<Pair<String, String>> = cookies.values
        .filter { MiAccountSecurityPolicy.isAllowedUnlockServiceCookieName(it.name) }
        .associate { it.name to it.value }
        .toList()

    fun names(): Set<String> = cookies.values.mapTo(sortedSetOf()) { it.name }

    fun capture(requestUrl: URL, headers: Map<String?, List<String>>) {
        require(MiAccountSecurityPolicy.isAllowedAuthFlowUrl(requestUrl)) {
            "Cookies captured from non-allowlisted URL"
        }
        headers.forEach { (headerName, values) ->
            if (headerName != null && headerName.equals("Set-Cookie", ignoreCase = true)) {
                values.forEach { captureOne(requestUrl, it) }
            }
        }
    }

    private fun captureOne(requestUrl: URL, raw: String) {
        val segments = raw.split(';')
        val pair = segments.firstOrNull()?.trim().orEmpty()
        val idx = pair.indexOf('=')
        if (idx <= 0) return

        val name = pair.substring(0, idx).trim()
        val value = pair.substring(idx + 1).trim()
        if (!MiAccountSecurityPolicy.isValidCookieName(name)) return
        if (!MiAccountSecurityPolicy.isValidCookieValue(value)) return

        val requestHost = MiAccountSecurityPolicy.normalizeHost(requestUrl.host) ?: return
        var domain = requestHost
        var hostOnly = true
        var path = MiAccountSecurityPolicy.defaultCookiePath(requestUrl)
        var secure = false
        var delete = value.isEmpty() || value.equals("null", ignoreCase = true)

        for (attribute in segments.drop(1)) {
            val attr = attribute.trim()
            val attrName = attr.substringBefore('=').trim().lowercase(Locale.US)
            val attrValue = attr.substringAfter('=', "").trim()
            when (attrName) {
                "domain" -> {
                    val candidate = MiAccountSecurityPolicy.normalizeHost(attrValue.trimStart('.')) ?: return
                    if (!MiAccountSecurityPolicy.isAllowedCookieDomain(requestUrl, candidate, name)) return
                    domain = candidate
                    hostOnly = false
                }
                "path" -> if (attrValue.startsWith('/')) path = attrValue
                "secure" -> secure = true
                "max-age" -> if (attrValue.toLongOrNull()?.let { it <= 0 } == true) delete = true
            }
        }

        val key = Key(name, domain, path)
        if (delete) {
            cookies.remove(key)
            return
        }
        cookies[key] = StoredCookie(name, value, domain, path, secure, hostOnly)
    }

    private fun matching(url: URL): List<StoredCookie> {
        val host = MiAccountSecurityPolicy.normalizeHost(url.host) ?: return emptyList()
        val requestPath = url.path.takeIf { it.startsWith('/') && it.isNotEmpty() } ?: "/"
        val https = url.protocol.equals("https", ignoreCase = true)
        return cookies.values.filter { cookie ->
            val domainOk = if (cookie.hostOnly) {
                host == cookie.domain
            } else {
                MiAccountSecurityPolicy.domainMatches(host, cookie.domain)
            }
            domainOk &&
                (!cookie.secure || https) &&
                MiAccountSecurityPolicy.pathMatches(requestPath, cookie.path)
        }
    }
}
