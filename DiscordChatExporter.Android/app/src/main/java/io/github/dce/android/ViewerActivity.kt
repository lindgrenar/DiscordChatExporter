package io.github.dce.android

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Shows a single-file HTML export. The file is streamed from its content URI through a virtual
 * https origin, so even very large exports open without being loaded into memory first.
 */
class ViewerActivity : Activity() {
    companion object {
        const val EXTRA_TITLE = "title"
        private const val VIRTUAL_URL = "https://export.dce.invalid/index.html"
    }

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val source = intent.data ?: return finish()
        title = intent.getStringExtra(EXTRA_TITLE)

        webView = WebView(this)
        setContentView(webView)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            webView.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                WindowInsets.CONSUMED
            }
        }

        webView.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            builtInZoomControls = true
            displayZoomControls = false
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (request.url.toString().substringBefore('#') != VIRTUAL_URL) return null
                val stream = runCatching { contentResolver.openInputStream(source) }.getOrNull() ?: return null
                return WebResourceResponse("text/html", "utf-8", stream)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.toString().substringBefore('#') == VIRTUAL_URL) return false
                // Links in messages open in the browser
                if (url.scheme == "http" || url.scheme == "https") {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, url)) }
                }
                return true
            }
        }
        webView.loadUrl(VIRTUAL_URL)
    }

    override fun onDestroy() {
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }

    @Deprecated("Framework Activity API")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
