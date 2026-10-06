package io.github.ncorror.nekoflash.miunlock

import io.github.ncorror.nekoflash.LocaleAwareActivity
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.net.toUri
import androidx.activity.addCallback
import io.github.ncorror.nekoflash.R
import io.github.ncorror.nekoflash.vendor.xiaomi.MiAccountSecurityPolicy
import java.io.ByteArrayInputStream

/**
 * Xiaomi Account login isolated in a hardened WebView.
 *
 * Only HTTPS top-level navigation under account.xiaomi.com is accepted.
 * Third-party cookies and local file/content access are disabled. Authentication
 * cookies are preserved until explicit logout so an already authenticated user
 * can continue the official Mi Unlock flow without signing in again.
 */
class MiLoginActivity : LocaleAwareActivity() {

    private val initialUrl =
        "https://account.xiaomi.com/pass/serviceLogin?sid=unlockApi&checkSafeAddress=true"
    private val endPattern = "{\"R\":\"\",\"S\":\"OK\"}"
    private var monitoringEnded = false
    private var webViewDestroyed = false
    private var passToken: String? = null
    private var deviceId: String? = null
    private var userId: String? = null
    private var lastFailureMessage: String? = null
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var webView: WebView
    private lateinit var continueButton: Button
    private lateinit var logoutButton: Button
    private lateinit var loginMessage: TextView
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        WebView.setWebContentsDebuggingEnabled(false)

        setupLoginView()
        onBackPressedDispatcher.addCallback(this) {
            setResult(
                Activity.RESULT_CANCELED,
                Intent().putExtra(
                    EXTRA_LOGIN_ERROR,
                    lastFailureMessage ?: getString(R.string.mi_login_cancelled_by_user),
                ),
            )
            finish()
        }

        if (checkExistingCookies()) {
            webView.visibility = View.GONE
            progressBar.visibility = View.GONE
            continueButton.visibility = View.VISIBLE
            logoutButton.visibility = View.VISIBLE
            loginMessage.visibility = View.VISIBLE
            loginMessage.text = getString(R.string.mi_login_already, userId ?: "")
        } else {
            setupWebView()
        }
    }

    private fun setupLoginView() {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.window_background))
            setPadding(dp(ROOT_PADDING_DP), dp(ROOT_PADDING_DP), dp(ROOT_PADDING_DP), dp(ROOT_PADDING_DP))
        }
        loginMessage = TextView(this).apply {
            setTextColor(getColor(R.color.brand_text_primary))
            textSize = MESSAGE_TEXT_SP
            typeface = android.graphics.Typeface.MONOSPACE
            visibility = View.GONE
            setPadding(
                dp(MESSAGE_PADDING_DP), dp(MESSAGE_PADDING_DP),
                dp(MESSAGE_PADDING_DP), dp(MESSAGE_BOTTOM_DP),
            )
        }
        progressBar = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(getColor(R.color.brand_accent))
        }
        continueButton = Button(this).apply {
            text = getString(R.string.mi_login_continue)
            isAllCaps = false
            backgroundTintList = ColorStateList.valueOf(getColor(R.color.brand_accent))
            setTextColor(getColor(R.color.brand_on_accent))
            visibility = View.GONE
            setOnClickListener { returnResults() }
        }
        logoutButton = Button(this).apply {
            text = getString(R.string.mi_login_logout)
            isAllCaps = false
            backgroundTintList = ColorStateList.valueOf(getColor(R.color.brand_accent))
            setTextColor(getColor(R.color.brand_on_accent))
            visibility = View.GONE
            setOnClickListener { doLogout() }
        }
        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(loginMessage)
        root.addView(progressBar)
        root.addView(continueButton)
        root.addView(logoutButton)
        root.addView(webView)
        setContentView(root)
    }

    private fun checkExistingCookies(): Boolean {
        val cookieString = CookieManager.getInstance().getCookie("https://account.xiaomi.com")
            ?: return false
        passToken = extractCookie(cookieString, "passToken")
        deviceId = extractCookie(cookieString, "deviceId")
        userId = extractCookie(cookieString, "userId")
        return !userId.isNullOrEmpty() && !passToken.isNullOrEmpty() && !deviceId.isNullOrEmpty()
    }

    private fun extractCookie(cookieString: String, key: String): String? =
        cookieString.split(';')
            .asSequence()
            .map { it.trim() }
            .mapNotNull { item ->
                val separator = item.indexOf('=')
                if (separator <= 0) null else item.substring(0, separator) to item.substring(separator + 1)
            }
            .firstOrNull { it.first == key }
            ?.second
            ?.takeIf { it.isNotEmpty() }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.visibility = View.VISIBLE
        progressBar.visibility = View.VISIBLE
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = WebSettings.getDefaultUserAgent(this@MiLoginActivity)
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            // File/content schemes stay disabled explicitly. The deprecated
            // file-URL bridge flags are unnecessary when file access itself is blocked.
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = true
            safeBrowsingEnabled = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val raw = request.url.toString()
                return if (request.isForMainFrame) {
                    when {
                        MiAccountSecurityPolicy.isOfficialUnlockCallbackUrl(raw) -> {
                            handleOfficialCompletion(raw)
                            true
                        }
                        MiAccountSecurityPolicy.isAllowedAccountUrl(raw) -> false
                        else -> { blockUnsafeNavigation(raw); true }
                    }
                } else request.url.scheme?.equals("https", ignoreCase = true) != true
            }

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                if (MiAccountSecurityPolicy.isOfficialUnlockCallbackUrl(url)) {
                    handleOfficialCompletion(url)
                    return true
                }
                return if (MiAccountSecurityPolicy.isAllowedAccountUrl(url)) {
                    false
                } else {
                    blockUnsafeNavigation(url)
                    true
                }
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val scheme = request.url.scheme?.lowercase().orEmpty()
                return if (scheme in setOf("http", "file", "content")) {
                    emptyBlockedResponse()
                } else {
                    null
                }
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                if (MiAccountSecurityPolicy.isOfficialUnlockCallbackUrl(url)) {
                    view.stopLoading()
                    handleOfficialCompletion(url)
                    return
                }
                if (!MiAccountSecurityPolicy.isAllowedAccountUrl(url)) {
                    blockUnsafeNavigation(url)
                    return
                }
                progressBar.visibility = View.VISIBLE
                handler.removeCallbacksAndMessages(null)
            }

            override fun onPageFinished(view: WebView, url: String) {
                // WebView can deliver onPageFinished after shouldOverrideUrlLoading/onPageStarted
                // already consumed the official /sts completion. Never let that stale callback
                // overwrite a successful login with a blocked-host message.
                if (monitoringEnded || isFinishing || isDestroyed) return
                if (MiAccountSecurityPolicy.isOfficialUnlockCallbackUrl(url)) {
                    handleOfficialCompletion(url)
                    return
                }
                if (!MiAccountSecurityPolicy.isAllowedAccountUrl(url)) {
                    blockUnsafeNavigation(url)
                    return
                }
                progressBar.visibility = View.GONE
                extractCookies()
                checkForEndSignal(view)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (!request.isForMainFrame) return
                failLogin(getString(R.string.mi_login_network_error))
            }

            override fun onReceivedSslError(
                view: WebView,
                handler: SslErrorHandler,
                error: SslError
            ) {
                handler.cancel()
                failLogin(getString(R.string.mi_login_ssl_error))
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                failLogin(getString(R.string.mi_login_webview_error))
                return true
            }
        }

        CookieManager.getInstance().run {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, false)
            flush()
        }
        webView.loadUrl(initialUrl)
    }

    private fun emptyBlockedResponse(): WebResourceResponse =
        WebResourceResponse(
            "text/plain",
            "UTF-8",
            ByteArrayInputStream(ByteArray(0))
        )

    private fun blockUnsafeNavigation(rawUrl: String) {
        val host = runCatching { rawUrl.toUri().host }.getOrNull().orEmpty()
        failLogin(getString(R.string.mi_login_blocked_host, host.ifBlank { "?" }))
    }

    private fun failLogin(message: String) {
        // Once completion succeeded, late WebView callbacks are stale and must not
        // downgrade RESULT_OK into a visible/result cancellation state.
        if (monitoringEnded || isFinishing || isDestroyed) return
        monitoringEnded = true
        lastFailureMessage = message
        if (::webView.isInitialized && !webViewDestroyed) webView.stopLoading()
        progressBar.visibility = View.GONE
        logoutButton.visibility = View.GONE
        loginMessage.visibility = View.VISIBLE
        loginMessage.text = message
        continueButton.text = getString(R.string.mi_login_retry)
        continueButton.visibility = View.VISIBLE
        continueButton.setOnClickListener { retryLogin() }
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(EXTRA_LOGIN_ERROR, message))
        handler.removeCallbacksAndMessages(null)
    }

    private fun retryLogin() {
        if (webViewDestroyed || isFinishing || isDestroyed) return
        monitoringEnded = false
        lastFailureMessage = null
        loginMessage.visibility = View.GONE
        continueButton.visibility = View.GONE
        logoutButton.visibility = View.GONE
        continueButton.text = getString(R.string.mi_login_continue)
        continueButton.setOnClickListener { returnResults() }
        setupWebView()
    }

    private fun handleOfficialCompletion(rawUrl: String) {
        if (monitoringEnded || isFinishing || isDestroyed) return
        if (!MiAccountSecurityPolicy.isOfficialUnlockCallbackUrl(rawUrl)) {
            blockUnsafeNavigation(rawUrl)
            return
        }
        CookieManager.getInstance().flush()
        extractCookies()
        if (passToken.isNullOrEmpty() || deviceId.isNullOrEmpty() || userId.isNullOrEmpty()) {
            failLogin(getString(R.string.mi_login_missing_cookies))
            return
        }
        monitoringEnded = true
        loginMessage.text = getString(R.string.mi_login_success, userId ?: "")
        loginMessage.visibility = View.VISIBLE
        progressBar.visibility = View.GONE
        handler.postDelayed({ returnResults() }, LOGIN_RETURN_DELAY_MS)
    }

    private fun extractCookies() {
        CookieManager.getInstance().getCookie("https://account.xiaomi.com")?.let { cookieString ->
            passToken = passToken ?: extractCookie(cookieString, "passToken")
            deviceId = deviceId ?: extractCookie(cookieString, "deviceId")
            userId = userId ?: extractCookie(cookieString, "userId")
        }
    }

    private fun checkForEndSignal(view: WebView) {
        if (monitoringEnded || webViewDestroyed) return
        view.evaluateJavascript("document.documentElement.outerHTML") { html ->
            if (monitoringEnded || webViewDestroyed) return@evaluateJavascript
            val cleaned = html.replace("\\u003C", "<").replace("\\u003E", ">")
                .replace("\\\"", "\"").replace("\\\\", "\\")
            if (cleaned.contains(endPattern)) {
                if (passToken.isNullOrEmpty() || deviceId.isNullOrEmpty() || userId.isNullOrEmpty()) {
                    failLogin(getString(R.string.mi_login_missing_cookies))
                } else {
                    monitoringEnded = true
                    loginMessage.text = getString(R.string.mi_login_success, userId ?: "")
                    loginMessage.visibility = View.VISIBLE
                    handler.postDelayed({ returnResults() }, LOGIN_RETURN_DELAY_MS)
                }
            }
        }
    }

    private fun doLogout() {
        monitoringEnded = true
        clearSensitiveFields()
        CookieManager.getInstance().removeAllCookies {
            if (!isFinishing && !isDestroyed) {
                setResult(Activity.RESULT_CANCELED)
                finish()
            }
        }
        CookieManager.getInstance().flush()
    }

    private fun returnResults() {
        val token = passToken
        val device = deviceId
        val user = userId
        val ok = !token.isNullOrEmpty() && !device.isNullOrEmpty() && !user.isNullOrEmpty()
        if (ok) {
            setResult(Activity.RESULT_OK, Intent().apply {
                putExtra(EXTRA_PASS_TOKEN, token)
                putExtra(EXTRA_DEVICE_ID, device)
                putExtra(EXTRA_USER_ID, user)
            })
            clearSensitiveFields()
            finish()
        } else if (!webViewDestroyed) {
            failLogin(getString(R.string.mi_login_missing_cookies))
        }
    }

    private fun clearSensitiveFields() {
        passToken = null
        deviceId = null
        userId = null
    }

    private fun destroyWebViewSafely() {
        if (!::webView.isInitialized || webViewDestroyed) return
        webViewDestroyed = true
        runCatching { webView.stopLoading() }
        runCatching { webView.settings.javaScriptEnabled = false }
        runCatching { webView.webViewClient = WebViewClient() }
        runCatching { webView.clearHistory() }
        runCatching { webView.clearCache(true) }
        runCatching { webView.clearSslPreferences() }
        (webView.parent as? ViewGroup)?.removeView(webView)
        runCatching { webView.removeAllViews() }
        runCatching { webView.destroy() }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        destroyWebViewSafely()
        clearSensitiveFields()
        super.onDestroy()
    }

    companion object {
        private const val ROOT_PADDING_DP = 12
        private const val MESSAGE_PADDING_DP = 6
        private const val MESSAGE_BOTTOM_DP = 10
        private const val MESSAGE_TEXT_SP = 14f
        private const val LOGIN_RETURN_DELAY_MS = 350L
        /** Error message returned with [Activity.RESULT_CANCELED]. */
        const val EXTRA_LOGIN_ERROR = "nekoflash_mi_login_error"

        /** Short-lived Xiaomi pass token returned only to [MainActivity]. */
        const val EXTRA_PASS_TOKEN = "nekoflash_mi_pass_token"

        /** Xiaomi device identifier associated with the authenticated session. */
        const val EXTRA_DEVICE_ID = "nekoflash_mi_device_id"

        /** Xiaomi account identifier associated with the authenticated session. */
        const val EXTRA_USER_ID = "nekoflash_mi_user_id"
    }
}
