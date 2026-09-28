package io.github.ncorror.nekoflash.vendor.xiaomi

import android.util.Base64
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Аутентификация для официального Mi Unlock API.
 *
 * Поток синхронизирован с актуальной схемой MiUnlockTool/migate:
 * passToken/userId/deviceId -> serviceLogin(_json) -> nonce+ssecurity+location ->
 * clientSign -> полный набор unlockApi cookies.
 */
object MiAccountClient {

    data class AuthResult(
        val host: String,
        val ssecurity: String,
        val serviceCookies: String,
        val serviceCookieNames: List<String>,
        val region: String,
        val dataCenterZone: String,
        val zoneSource: String,
        val userId: String,
        val deviceId: String
    )

    private data class ServiceResult(
        val ssecurity: String,
        val cookies: LinkedHashMap<String, String>
    )

    private data class HttpResponse(
        val code: Int,
        val body: String,
        val finalUrl: URL
    )

    private const val ACCOUNT_UA = "NekoFlash"
    private const val TIMEOUT_MS = 30000
    private const val MAX_REDIRECTS = 8
    private const val SERVICE_LOGIN_URL = "https://account.xiaomi.com/pass/serviceLogin"
    private const val REGION_URL = "https://account.xiaomi.com/pass/user/login/region"
    private const val CONFIGURATION_URL = "https://api.account.xiaomi.com/pass/configuration"

    private val zones = linkedMapOf(
        "Singapore" to "https://unlock.update.intl.miui.com",
        "China" to "https://unlock.update.miui.com",
        "India" to "https://in-unlock.update.intl.miui.com",
        "Russia" to "https://ru-unlock.update.intl.miui.com",
        "Europe" to "https://eu-unlock.update.intl.miui.com"
    )

    private val europeRegions = setOf(
        "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE",
        "EL", "HU", "IS", "IE", "IT", "LV", "LI", "LT", "LU", "MT", "NL",
        "NO", "PL", "PT", "RO", "SK", "SI", "ES", "SE", "UK"
    )

    fun dataCenterZones(): List<String> = zones.keys.toList()

    fun hostForZone(zone: String): String = zones[zone]
        ?: throw IllegalArgumentException("Unknown dataCenterZone: $zone")

    fun withDataCenterZone(auth: AuthResult, zone: String): AuthResult =
        auth.copy(host = hostForZone(zone), dataCenterZone = zone)

    /**
     * Обменивает cookies веб-входа на ssecurity и service cookies unlockApi.
     */
    fun exchangeToken(passToken: String, userId: String, deviceId: String): AuthResult {
        val authCookies = linkedMapOf(
            "passToken" to passToken,
            "userId" to userId,
            "deviceId" to deviceId
        )

        // Текущий MiUnlockTool сначала пытается определить регион аккаунта,
        // а при неудаче — dataCenterZone по диапазону userId.
        val region = runCatching { getRegion(authCookies) }.getOrNull().orEmpty()
        val resolvedZone = resolveDataCenterZone(region, userId)
        val service = getService(authCookies, userId)

        val cookieHeader = service.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        return AuthResult(
            host = hostForZone(resolvedZone.first),
            ssecurity = service.ssecurity,
            serviceCookies = cookieHeader,
            serviceCookieNames = service.cookies.keys.sorted(),
            region = if (region.isBlank()) "UNKNOWN" else region,
            dataCenterZone = resolvedZone.first,
            zoneSource = resolvedZone.second,
            userId = userId,
            deviceId = deviceId
        )
    }

    private fun resolveDataCenterZone(region: String, userId: String): Pair<String, String> {
        if (region.isNotBlank()) {
            val zone = when (region.uppercase()) {
                "CN" -> "China"
                "IN" -> "India"
                "RU" -> "Russia"
                in europeRegions -> "Europe"
                else -> "Singapore"
            }
            return zone to "account-region"
        }

        val byId = runCatching { getDataCenterZoneByUserId(userId) }.getOrNull()
        if (!byId.isNullOrBlank() && zones.containsKey(byId)) {
            return byId to "userId-range"
        }

        // В CLI-эталоне здесь открывается ручной выбор. На Android возвращаем
        // безопасный стартовый вариант и даём сменить zone в интерфейсе.
        return "Singapore" to "manual-default"
    }

    private fun getRegion(authCookies: Map<String, String>): String {
        val jar = MiAccountCookieJar(MiAccountSecurityPolicy.requireAllowedAccountUrl(REGION_URL, "region"), authCookies)
        val response = httpGet(REGION_URL, jar, followRedirects = true)
        if (response.code !in 200..299) {
            throw IOException("Region request failed: HTTP ${response.code}")
        }
        val json = parseXiaomiJson(response.body)
        val region = json.optJSONObject("data")?.optString("region", "").orEmpty()
        if (region.isBlank()) {
            throw IOException("Account region missing in Xiaomi response")
        }
        return region
    }

    private fun getDataCenterZoneByUserId(userId: String): String? {
        val id = userId.toLongOrNull() ?: return null
        val jar = MiAccountCookieJar(MiAccountSecurityPolicy.requireAllowedAccountUrl(CONFIGURATION_URL, "configuration"), emptyMap())
        val url = "$CONFIGURATION_URL?keys=idc"
        val response = httpGet(url, jar, followRedirects = true)
        if (response.code !in 200..299) return null

        val root = JSONObject(response.body)
        val idc = root.optJSONObject("data")?.optJSONObject("idc") ?: return null
        for (name in idc.keys()) {
            val info = idc.optJSONObject(name) ?: continue
            val ranges = mutableListOf<Pair<Long, Long>>()
            val min = info.optLong("userId.min", Long.MIN_VALUE)
            val max = info.optLong("userId.max", Long.MIN_VALUE)
            if (min != Long.MIN_VALUE && max != Long.MIN_VALUE) ranges += min to max

            val ext = info.optJSONArray("extend.idRange")
            if (ext != null) {
                for (i in 0 until ext.length()) {
                    val item = ext.optJSONObject(i) ?: continue
                    val extMin = item.optLong("userId.min", Long.MIN_VALUE)
                    val extMax = item.optLong("userId.max", Long.MIN_VALUE)
                    if (extMin != Long.MIN_VALUE && extMax != Long.MIN_VALUE) {
                        ranges += extMin to extMax
                    }
                }
            }
            if (ranges.any { id in it.first..it.second }) return name
        }
        return null
    }

    /**
     * Реализация get_service из migate: первый serviceLogin возвращает nonce,
     * ssecurity и location; второй запрос с clientSign выдаёт service cookies.
     */
    private fun getService(authCookies: Map<String, String>, userId: String): ServiceResult {
        val jar = MiAccountCookieJar(MiAccountSecurityPolicy.requireAllowedAccountUrl(SERVICE_LOGIN_URL, "service login"), authCookies)
        val firstUrl = buildUrl(
            SERVICE_LOGIN_URL,
            linkedMapOf(
                "sid" to "unlockApi",
                "checkSafeAddress" to "True",
                "_json" to "True"
            )
        )
        val first = httpGet(firstUrl, jar, followRedirects = true)
        if (first.code !in 200..299) {
            throw IOException("Mi serviceLogin failed: HTTP ${first.code}")
        }

        val serviceJson = parseXiaomiJson(first.body)
        val nonce = serviceJson.optString("nonce", "")
        val ssecurity = serviceJson.optString("ssecurity", "")
        val location = serviceJson.optString("location", "")
        if (nonce.isBlank() || ssecurity.isBlank() || location.isBlank()) {
            val code = serviceJson.optInt("code", Int.MIN_VALUE)
            throw IOException(
                "Mi service data incomplete" +
                    (if (code != Int.MIN_VALUE) " (code=$code)" else "")
            )
        }

        val clientSign = Base64.encodeToString(
            MessageDigest.getInstance("SHA1")
                .digest("nonce=$nonce&$ssecurity".toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP
        )
        val signedUrl = MiAccountSecurityPolicy.appendQueryParameter(
            location,
            "clientSign",
            clientSign
        )

        val second = httpGet(signedUrl, jar, followRedirects = true)
        if (second.code !in 200..299) {
            throw IOException("Mi unlockApi service authorization failed: HTTP ${second.code}")
        }

        // В unlock API не отправляем passToken/deviceId. Оставляем только
        // service cookies, которые использует актуальный MiUnlockTool/migate.
        val serviceCookies = linkedMapOf<String, String>()
        jar.serviceEntries().forEach { (name, value) ->
            if (
                name == "serviceToken" ||
                name == "userId" ||
                name == "cUserId" ||
                name.startsWith("unlockApi_")
            ) {
                serviceCookies[name] = value
            }
        }
        if (!serviceCookies.containsKey("userId")) serviceCookies["userId"] = userId
        if (serviceCookies["serviceToken"].isNullOrBlank()) {
            throw IOException(
                "serviceToken not found after clientSign exchange; " +
                    "cookies=${jar.names().sorted().joinToString(",")}"
            )
        }

        return ServiceResult(ssecurity = ssecurity, cookies = serviceCookies)
    }

    private fun parseXiaomiJson(raw: String): JSONObject {
        val cleaned = raw.trim().removePrefix("&&&START&&&").trim()
        return try {
            JSONObject(cleaned)
        } catch (e: Exception) {
            throw IOException("Invalid Xiaomi JSON response", e)
        }
    }

    private fun buildUrl(base: String, params: Map<String, String>): String =
        base + "?" + params.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }

    private fun httpGet(
        urlStr: String,
        jar: MiAccountCookieJar,
        followRedirects: Boolean
    ): HttpResponse {
        var currentUrl = MiAccountSecurityPolicy.requireAllowedAuthFlowUrl(urlStr, "request")
        var redirects = 0

        while (true) {
            val conn = (currentUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = false
                doInput = true
                setRequestProperty("User-Agent", ACCOUNT_UA)
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("Accept", "application/json;charset=UTF-8")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                jar.headerFor(currentUrl).takeIf { it.isNotBlank() }
                    ?.let { setRequestProperty("Cookie", it) }
            }

            try {
                conn.connect()
                val code = conn.responseCode
                jar.capture(currentUrl, conn.headerFields)

                if (followRedirects && code in setOf(301, 302, 303, 307, 308)) {
                    val location = conn.getHeaderField("Location")
                        ?: throw IOException("Xiaomi redirect without Location")
                    redirects++
                    if (redirects > MAX_REDIRECTS) {
                        throw IOException("Too many redirects during Xiaomi service login")
                    }
                    currentUrl = MiAccountSecurityPolicy.resolveAllowedAuthRedirect(currentUrl, location)
                    continue
                }

                val stream = if (code in 200..299) {
                    conn.inputStream
                } else {
                    conn.errorStream ?: runCatching { conn.inputStream }.getOrNull()
                }
                val body = try {
                    stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                } catch (_: Exception) {
                    ""
                }
                return HttpResponse(code, body, currentUrl)
            } finally {
                conn.disconnect()
            }
        }
    }
}
