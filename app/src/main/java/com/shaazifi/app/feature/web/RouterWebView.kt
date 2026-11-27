package com.shaazifi.app.feature.web

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.shaazifi.app.debug.DebugRouterConfig
import com.shaazifi.app.ui.theme.SfTheme

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
    val currentSessionToken by rememberUpdatedState(sessionToken)
    val currentOnCertMismatch by rememberUpdatedState(onCertMismatch)
    val currentOnConnectionError by rememberUpdatedState(onConnectionError)

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var lastLoadedTargetUrl by remember { mutableStateOf<String?>(null) }
    var lastScriptKey by remember { mutableStateOf<Pair<String, String>?>(null) }
    var scriptHandler by remember { mutableStateOf<ScriptHandler?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var loadingProgress by remember { mutableIntStateOf(0) }

    BackHandler(enabled = canGoBack) {
        val wv = webViewRef
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
            canGoBack = wv.canGoBack()
        } else {
            canGoBack = false
        }
    }

    val webViewClient = remember(routerOrigin, pinnedFingerprint) {
        object : SecureWebViewClient(
            context = context,
            routerOrigin = routerOrigin,
            pinnedFingerprint = pinnedFingerprint,
            onCertMismatch = { currentOnCertMismatch(it) },
            onConnectionError = { currentOnConnectionError() }
        ) {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                canGoBack = view?.canGoBack() == true
                // Fallback injection on page started strictly restricted to router origin
                if (url != null && SecureWebNavigation.isInternalNavigation(url, routerOrigin)) {
                    val script = SecureWebNavigation.buildSessionInjectionScript(currentSessionToken)
                    view?.evaluateJavascript(script, null)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                canGoBack = view?.canGoBack() == true
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                canGoBack = view?.canGoBack() == true
            }
        }
    }

    val webChromeClient = remember {
        object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                loadingProgress = newProgress
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { scriptHandler?.remove() }
            scriptHandler = null
            webViewRef?.apply {
                stopLoading()
                this.webChromeClient = null
                this.webViewClient = WebViewClient()
                destroy()
            }
            webViewRef = null
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
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
                    WebView.setWebContentsDebuggingEnabled(DebugRouterConfig.IS_DEBUG)

                    // 3. Document-start JavaScript injection restricted strictly to router origin
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                        val script = SecureWebNavigation.buildSessionInjectionScript(sessionToken)
                        scriptHandler = WebViewCompat.addDocumentStartJavaScript(this, script, setOf(routerOrigin))
                        lastScriptKey = routerOrigin to sessionToken
                    }

                    this.webViewClient = webViewClient
                    this.webChromeClient = webChromeClient
                    lastLoadedTargetUrl = targetUrl
                    loadUrl(targetUrl)
                    webViewRef = this
                }
            },
            update = { webView ->
                webView.webViewClient = webViewClient
                webView.webChromeClient = webChromeClient

                val desiredScriptKey = routerOrigin to sessionToken
                if (lastScriptKey != desiredScriptKey) {
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                        runCatching { scriptHandler?.remove() }
                        val script = SecureWebNavigation.buildSessionInjectionScript(sessionToken)
                        scriptHandler = WebViewCompat.addDocumentStartJavaScript(webView, script, setOf(routerOrigin))
                    }
                    lastScriptKey = desiredScriptKey
                    val currentUrl = webView.url
                    if (currentUrl != null && SecureWebNavigation.isInternalNavigation(currentUrl, routerOrigin)) {
                        webView.evaluateJavascript(SecureWebNavigation.buildSessionInjectionScript(sessionToken), null)
                    }
                }

                // Only reload when the targetUrl input itself changes, never on in-page LuCI/UnTangled sub-navigation.
                if (lastLoadedTargetUrl != targetUrl) {
                    lastLoadedTargetUrl = targetUrl
                    webView.loadUrl(targetUrl)
                }
                canGoBack = webView.canGoBack()
            },
            modifier = Modifier.fillMaxSize()
        )

        if (loadingProgress in 1..99) {
            LinearProgressIndicator(
                progress = { loadingProgress / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .align(Alignment.TopCenter),
                color = SfTheme.colors.accent,
                trackColor = SfTheme.colors.line
            )
        }
    }
}
