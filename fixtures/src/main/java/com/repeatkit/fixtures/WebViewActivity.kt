package com.repeatkit.fixtures

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView

class WebViewActivity : BaseViewsActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webview)

        FixtureLog.event("webview", "open", "webview")

        val webView = findViewById<WebView>(R.id.web_view)
        webView.settings.javaScriptEnabled = true
        webView.addJavascriptInterface(FixtureBridge(), "FixtureBridge")
        webView.loadUrl("file:///android_asset/fixture.html")
    }

    private class FixtureBridge {
        // WebView matches JavaScript calls to interface methods by argument count, and Kotlin
        // default arguments do not create a visible overload, so the 2-argument form the page
        // uses for button taps needs its own method or every tap fails silently.
        @JavascriptInterface
        fun event(event: String, target: String) {
            FixtureLog.event("webview", event, target, null)
        }

        @JavascriptInterface
        fun event(event: String, target: String, value: String?) {
            FixtureLog.event("webview", event, target, if (value.isNullOrEmpty()) null else value)
        }
    }
}
