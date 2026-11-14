
package com.example.ilbbangandroid

import android.app.Activity
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.content.Intent
import android.net.Uri
import android.view.View
import android.webkit.WebSettings

class MainActivity : Activity() {

    private lateinit var webView: WebView

    private val appUrl =
        "https://creep1020.github.io/1-book/"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false

            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }

        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {

                val uri = request.url

                val isAppPage =
                    uri.scheme == "https" &&
                    uri.host == "creep1020.github.io" &&
                    uri.path?.startsWith("/1-book") == true

                if (isAppPage) {
                    return false
                }

                if (uri.scheme == "https" || uri.scheme == "http") {
                    try {
                        startActivity(
                            Intent(Intent.ACTION_VIEW, uri)
                        )
                    } catch (_: Exception) {
                    }
                }

                return true
            }
        }

        webView.webChromeClient = WebChromeClient()

        webView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE

        setContentView(webView)

        if (savedInstanceState == null) {
            webView.loadUrl(appUrl)
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
