package com.example.ilbbangandroid

import android.app.Activity
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.content.Intent
import android.view.View
import android.widget.TextView
import android.widget.LinearLayout
import android.graphics.Color
import android.util.Log

class MainActivity : Activity() {

    private var webView: WebView? = null

    private val appUrl =
        "https://creep1020.github.io/I-ll-bang-1/"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            val browser = WebView(this)
            webView = browser

            browser.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode =
                    WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }

            browser.webViewClient = object : WebViewClient() {

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val uri = request.url

                    val isAppPage =
                        uri.scheme == "https" &&
                        uri.host == "creep1020.github.io" &&
                        uri.path?.startsWith("/I-ll-bang-1") == true

                    if (isAppPage) return false

                    if (uri.scheme == "https" || uri.scheme == "http") {
                        try {
                            startActivity(
                                Intent(Intent.ACTION_VIEW, uri)
                            )
                        } catch (e: Exception) {
                            Log.e("Ilbbang", "외부 링크 오류", e)
                        }
                    }

                    return true
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: android.webkit.WebResourceError
                ) {
                    super.onReceivedError(view, request, error)

                    if (request.isForMainFrame) {
                        showError(
                            "페이지를 불러오지 못했습니다.\n" +
                            error.description
                        )
                    }
                }
            }

            browser.webChromeClient = WebChromeClient()

            browser.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE

            setContentView(browser)

            if (savedInstanceState == null) {
                browser.loadUrl(appUrl)
            } else {
                val restored = browser.restoreState(savedInstanceState)
                if (restored == null) {
                    browser.loadUrl(appUrl)
                }
            }

        } catch (e: Exception) {
            Log.e("Ilbbang", "앱 실행 오류", e)
            showError(
                "앱 실행 오류\n\n" +
                (e.message ?: e.toString())
            )
        }
    }

    private fun showError(message: String) {
        val text = TextView(this).apply {
            this.text = message
            textSize = 16f
            setTextColor(Color.BLACK)
            setPadding(40, 60, 40, 40)
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(text)
        }

        setContentView(layout)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView?.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        val browser = webView

        if (browser?.canGoBack() == true) {
            browser.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}
