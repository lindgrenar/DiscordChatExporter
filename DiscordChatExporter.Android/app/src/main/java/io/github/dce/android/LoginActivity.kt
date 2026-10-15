package io.github.dce.android

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowInsets
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray

/**
 * Lets the user sign in on discord.com inside a WebView, then picks up the session token:
 * either from the Authorization header of the web client's own API calls, or from its localStorage.
 * The web session is wiped afterwards, so nothing but the token is kept.
 */
class LoginActivity : Activity() {
    companion object {
        const val EXTRA_TOKEN = "token"

        fun clearWebSession() {
            CookieManager.getInstance().removeAllCookies(null)
            WebStorage.getInstance().deleteAllData()
        }

        // Discord removes window.localStorage from its own page; a fresh iframe still exposes it
        private const val READ_TOKEN_JS = """
            (function () {
                try {
                    var frame = document.createElement('iframe');
                    frame.style.display = 'none';
                    document.body.appendChild(frame);
                    var token = frame.contentWindow.localStorage.getItem('token');
                    frame.remove();
                    return token;
                } catch (e) {
                    return null;
                }
            })();
        """
    }

    private lateinit var webView: WebView
    private val handler = Handler(Looper.getMainLooper())
    private var finished = false

    private val poller = object : Runnable {
        override fun run() {
            if (finished) return
            webView.evaluateJavascript(READ_TOKEN_JS) { result -> parseJsResult(result)?.let(::deliver) }
            handler.postDelayed(this, 1500)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        clearWebSession()

        webView = WebView(this)
        setContentView(webView)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            webView.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                WindowInsets.CONSUMED
            }
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            // Present as regular mobile Chrome rather than an embedded WebView
            userAgentString = userAgentString.replace("; wv", "")
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url
                if (url.host?.endsWith("discord.com") == true && url.path?.startsWith("/api/") == true) {
                    val auth = request.requestHeaders.entries.firstOrNull { it.key.equals("Authorization", ignoreCase = true) }?.value
                    if (!auth.isNullOrBlank() && auth != "undefined" && auth != "null") handler.post { deliver(auth) }
                }
                return null
            }
        }
        webView.loadUrl("https://discord.com/login")
        handler.postDelayed(poller, 1500)
    }

    private fun parseJsResult(result: String?): String? {
        if (result.isNullOrBlank() || result == "null") return null
        // evaluateJavascript returns a JSON value; the stored token is itself a JSON string
        return runCatching {
            val outer = JSONArray("[$result]").getString(0)
            if (outer.startsWith("\"")) JSONArray("[$outer]").getString(0) else outer
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun deliver(token: String) {
        if (finished) return
        finished = true
        handler.removeCallbacks(poller)
        setResult(RESULT_OK, Intent().putExtra(EXTRA_TOKEN, token))
        finish()
    }

    override fun onDestroy() {
        finished = true
        handler.removeCallbacks(poller)
        webView.stopLoading()
        webView.destroy()
        clearWebSession()
        super.onDestroy()
    }

    @Deprecated("Framework Activity API")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
