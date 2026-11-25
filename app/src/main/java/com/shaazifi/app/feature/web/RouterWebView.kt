package com.shaazifi.app.feature.web

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.shaazifi.app.debug.DebugRouterConfig

object WebStorageManager {
    fun clearAll() {
        try {
            val cookieManager = CookieManager.getInstance()
            cookieManager.removeAllCookies(null)
            cookieManager.flush()
            WebStorage.getInstance().deleteAllData()
        } catch (_: Exception) {
            // Ignore storage clearing exceptions
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RouterWebView(
    routerOrigin: String,
    pinnedFingerprint: String,
    targetUrl: String,
    sessionToken: String,
    onCertMismatch: (String) -> Unit = {},
    onConnectionError: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val webViewClient = remember(routerOrigin, pinnedFingerprint, sessionToken) {
        object : SecureWebViewClient(context, routerOrigin, pinnedFingerprint, onCertMismatch, onConnectionError) {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // Fallback injection on page started strictly restricted to router origin
                if (url != null && SecureWebNavigation.isInternalNavigation(url, routerOrigin)) {
                    val script = SecureWebNavigation.buildSessionInjectionScript(sessionToken)
                    view?.evaluateJavascript(script, null)
                }
            }
        }
    }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                // 1. Enforce WebSettings security hardening
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                }

                // 2. Remote debugging strictly in debug builds only
                if (DebugRouterConfig.IS_DEBUG) {
                    WebView.setWebContentsDebuggingEnabled(true)
                } else {
                    WebView.setWebContentsDebuggingEnabled(false)
                }

                // 3. Document-start JavaScript injection restricted strictly to router origin
                if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    val script = SecureWebNavigation.buildSessionInjectionScript(sessionToken)
                    val allowedOriginRules = setOf(routerOrigin)
                    WebViewCompat.addDocumentStartJavaScript(this, script, allowedOriginRules)
                }

                this.webViewClient = webViewClient
                loadUrl(targetUrl)
            }
        },
        update = { webView ->
            if (webView.url != targetUrl) {
                webView.loadUrl(targetUrl)
            }
        },
        modifier = modifier.fillMaxSize()
    )
}
