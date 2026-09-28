package io.github.ncorror.nekoflash.vendor.xiaomi

import android.util.Base64
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLEncoder
import java.net.UnknownHostException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Клиент официального Mi Unlock API (разблокировка загрузчика Xiaomi).
 *
 * Реализует подписанные POST-запросы к unlock-серверу Xiaomi:
 * nonce → clear → ahaUnlock. Параметры передаются в query string, как в
 * актуальном MiUnlockTool; тело POST остаётся пустым.
 */
class MiUnlockClient(
    private val host: String,
    private val ssecurity: String,
    private val serviceCookies: String,
    private val userId: String,
    private val deviceId: String
) {
    private val key = Base64.decode(ssecurity, Base64.DEFAULT)
    private val iv = "0102030405060708".toByteArray(Charsets.UTF_8)
    private val pcId: String = md5Hex(deviceId)

    companion object {
        // Xiaomi unlock endpoint ожидает UA официального PC-клиента.
        private const val UA = "XiaomiPCSuite"
        private const val SID = "miui_unlocktool_client"
        private const val HMAC_KEY =
            "2tBeoEyJTunmWUGq7bQH2Abn0k2NhhurOaqBfyxCuLVgn4AVj7swcawe53uDUno"
        private const val TIMEOUT_MS = 30000
        private const val SAFE_READ_MAX_ATTEMPTS = 3
        private val SAFE_READ_RETRY_DELAYS_MS = longArrayOf(400L, 1_200L)
    }

    /** Шаг 1: получить nonce от сервера. */
    fun getNonce(): String {
        val r = (1..16).map { "abcdefghijklmnopqrstuvwxyz".random() }.joinToString("")
        val resp = sendReadWithRetry("/api/v2/nonce") {
            send(
                "/api/v2/nonce",
                mapOf("r" to r)
            )
        }
        return resp.optString("nonce").also {
            if (it.isNullOrEmpty()) throw IOException("nonce not received")
        }
    }

    /** Шаг 2: проверка устройства (чистятся ли данные при разблокировке). */
    data class ClearInfo(val notice: String, val clearsData: Boolean)

    fun checkClear(product: String, nonce: String): ClearInfo {
        val resp = sendReadWithRetry("/api/v2/unlock/device/clear") {
            send(
                "/api/v2/unlock/device/clear",
                mapOf(
                    "appId" to "1",
                    "data" to JSONObject().put("product", product).toString(),
                    "nonce" to nonce
                )
            )
        }
        val notice = resp.optString("notice", "")
        val clean = resp.optInt("cleanOrNot", -1)
        return ClearInfo(notice, clean == 1)
    }

    /**
     * Шаг 3: запрос разблокировки. Возвращает encryptData (hex) для прошивки в
     * устройство, либо бросает с описанием ошибки от сервера.
     */
    fun requestUnlock(product: String, deviceToken: String, nonce: String): String {
        val data = JSONObject().apply {
            put("clientId", "2")
            put("clientVersion", "7.6.727.43")
            put("deviceInfo", JSONObject().apply {
                put("boardVersion", "")
                put("deviceName", "")
                put("product", product)
                put("socId", "")
            })
            put("deviceToken", deviceToken)
            put("language", "en")
            put("operate", "unlock")
            put("pcId", pcId)
            put("region", "")
            put("uid", userId)
        }.toString()

        val resp = send(
            "/api/v3/ahaUnlock",
            mapOf("appId" to "1", "data" to data, "nonce" to nonce)
        )

        val code = resp.optInt("code", -1)
        if (code != 0) {
            throw BusinessException(code, describeXiaomiJson(resp))
        }
        return resp.optString("encryptData", "").also {
            if (it.isEmpty()) throw IOException("encryptData empty in server response")
        }
    }

    /**
     * Retry разрешён только для безопасных read/check endpoint-ов и только для
     * явно временных транспортных/HTTP ошибок. ahaUnlock всегда отправляется один раз.
     */
    private fun <T> sendReadWithRetry(endpoint: String, block: () -> T): T {
        var lastFailure: Exception? = null
        for (attempt in 1..SAFE_READ_MAX_ATTEMPTS) {
            try {
                return block()
            } catch (e: Exception) {
                lastFailure = e
                if (!isRetryableReadFailure(e) || attempt >= SAFE_READ_MAX_ATTEMPTS) throw e
                val delayMs = SAFE_READ_RETRY_DELAYS_MS.getOrElse(attempt - 1) { SAFE_READ_RETRY_DELAYS_MS.last() }
                try {
                    Thread.sleep(delayMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Retry interrupted for $endpoint", e)
                }
            }
        }
        throw lastFailure ?: IOException("Request failed: $endpoint")
    }

    private fun isRetryableReadFailure(error: Exception): Boolean = when (error) {
        is SocketTimeoutException,
        is ConnectException,
        is UnknownHostException,
        is NoRouteToHostException -> true
        is HttpStatusException -> error.statusCode == 408 ||
            error.statusCode == 429 ||
            error.statusCode in 500..599
        else -> false
    }

    // ─── Подписанный запрос ──────────────────────────────────────────────

    private fun send(path: String, paramsRawInput: Map<String, String>): JSONObject {
        val secretKey = SecretKeySpec(key, "AES")
        val ivSpec = IvParameterSpec(iv)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")

        val params = paramsRawInput.toMutableMap()
        params["data"]?.let {
            params["data"] = Base64.encodeToString(
                it.toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP
            )
        }
        params["sid"] = SID

        // Актуальный клиент сортирует ключи перед подписью.
        val paramOrder = params.keys.sorted()

        val encParam: (String) -> String = { input ->
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec)
            Base64.encodeToString(
                cipher.doFinal(input.toByteArray(Charsets.UTF_8)),
                Base64.NO_WRAP
            )
        }

        // sign = AES( hex( HmacSHA1( "POST\npath\nk=v&..." ) ) )
        val signParams = paramOrder.joinToString("&") { k -> "$k=${params[k]}" }
        val signStr = "POST\n$path\n$signParams"
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(HMAC_KEY.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        val hmacDigest = mac.doFinal(signStr.toByteArray(Charsets.UTF_8))
        val hexHmac = hmacDigest.joinToString("") { "%02x".format(it) }
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec)
        val currentSign = Base64.encodeToString(
            cipher.doFinal(hexHmac.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP
        )

        // signature = base64( SHA1( "POST&path&k=ENC(v)&...&sign=...&ssecurity" ) )
        val encodedParams = paramOrder.map { k -> "$k=${encParam(params[k]!!)}" }
        val sha1Input =
            "POST&$path&${encodedParams.joinToString("&")}&sign=$currentSign&$ssecurity"
        val signature = Base64.encodeToString(
            MessageDigest.getInstance("SHA1").digest(sha1Input.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP
        )

        // ВАЖНО: актуальный Mi Unlock protocol отправляет эти параметры в URL
        // (POST ?appId=...&data=...&sign=...), а не в application/x-www-form-urlencoded body.
        val query = buildString {
            paramOrder.forEach { k ->
                if (isNotEmpty()) append("&")
                append(URLEncoder.encode(k, "UTF-8"))
                    .append("=")
                    .append(URLEncoder.encode(encParam(params[k]!!), "UTF-8"))
            }
            append("&sign=").append(URLEncoder.encode(currentSign, "UTF-8"))
            append("&signature=").append(URLEncoder.encode(signature, "UTF-8"))
        }

        val cookie = serviceCookies.split(";").map { it.trim() }
            .filter { it.isNotEmpty() && it.contains("=") }
            .joinToString("; ")

        val endpoint = MiAccountSecurityPolicy.requireAllowedUnlockApiUrl("$host$path", "Mi Unlock")
        val response = httpPost(endpoint.toExternalForm(), query, cookie)
        val decodedJson = decodeXiaomiResponse(response.body)

        if (response.code !in 200..299) {
            val detail = when {
                decodedJson != null -> describeXiaomiJson(decodedJson)
                response.body.isNotBlank() -> describeRawError(response.body)
                else -> "server did not return a response body"
            }
            val message = "HTTP ${response.code}: $detail [endpoint=$path]"
            if (response.code == HttpURLConnection.HTTP_UNAUTHORIZED) {
                throw SessionExpiredException(message)
            }
            throw HttpStatusException(response.code, message)
        }

        return decodedJson ?: throw IOException(
            "Server returned HTTP ${response.code}, but the response could not be decrypted/read: " +
                describeRawError(response.body)
        )
    }

    class SessionExpiredException(message: String) : IOException(message)

    class BusinessException(
        val code: Int,
        message: String
    ) : IOException(message)

    private class HttpStatusException(
        val statusCode: Int,
        message: String
    ) : IOException(message)

    private data class HttpResponse(
        val code: Int,
        val body: String
    )

    /** POST с параметрами в query string и пустым телом. */
    private fun httpPost(urlStr: String, query: String, cookie: String): HttpResponse {
        val fullUrl = "$urlStr?$query"
        val conn = (URI.create(fullUrl).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = false
            doInput = true
            doOutput = false
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Cookie", cookie)
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Connection", "close")
        }
        try {
            conn.connect()
            val code = conn.responseCode
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
            return HttpResponse(code, body)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Ответ Xiaomi обычно имеет вид base64(AES-CBC(base64(JSON))). На ошибках
     * отдельные узлы могут присылать обычный JSON — поддерживаем оба формата.
     */
    private fun decodeXiaomiResponse(raw: String): JSONObject? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        parsePlainJson(text)?.let { return it }

        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                IvParameterSpec(iv)
            )
            val encrypted = Base64.decode(text, Base64.DEFAULT)
            val decrypted = cipher.doFinal(encrypted)
            val innerBase64 = String(decrypted, Charsets.UTF_8).trim()
            val jsonBytes = Base64.decode(innerBase64, Base64.DEFAULT)
            parsePlainJson(String(jsonBytes, Charsets.UTF_8).trim())
        } catch (_: Exception) {
            null
        }
    }

    private fun parsePlainJson(raw: String): JSONObject? {
        val cleaned = raw
            .removePrefix("&&&START&&&")
            .trim()
        return try {
            if (cleaned.startsWith("{")) JSONObject(cleaned) else null
        } catch (_: Exception) {
            null
        }
    }

    private fun describeRawError(raw: String): String {
        return if (raw.isBlank()) "empty response" else "could not parse server response"
    }

    /** Человекочитаемая причина на основе фактического JSON ответа Xiaomi. */
    private fun describeXiaomiJson(json: JSONObject): String {
        val code = if (json.has("code")) json.optInt("code", Int.MIN_VALUE) else Int.MIN_VALUE
        val fallback = when (code) {
            10000 -> "invalid deviceToken or product"
            10013 -> "device is not activated or the activation period is too short"
            10023 -> "unlockable-device limit reached for the account"
            20030 -> "monthly unlock-data request limit reached for the account"
            20031 -> "account/device is not linked in Mi Unlock status"
            20033 -> "account is not authorized for unlocking"
            20035 -> "unlock client version is outdated"
            20036 -> "server assigned a waiting period before unlocking"
            20038 -> "device is locked through Find Device"
            20039 -> "basic device-data check failed"
            20041 -> "no phone number is bound to the Mi Account"
            20045 -> "invalid server region or account/device region mismatch"
            30002 -> "approval/binding through official Xiaomi mechanisms is required"
            else -> ""
        }

        return when {
            code != Int.MIN_VALUE && fallback.isNotEmpty() ->
                "Xiaomi code $code: $fallback"
            code != Int.MIN_VALUE ->
                "Xiaomi code $code"
            else ->
                "Xiaomi returned an error without a code"
        }
    }

    private fun md5Hex(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
