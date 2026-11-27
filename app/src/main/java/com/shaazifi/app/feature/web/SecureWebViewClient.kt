package com.shaazifi.app.feature.web

import android.content.Context
import android.content.Intent
import android.net.http.SslCertificate
import android.net.http.SslError
import android.os.Build
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.security.MessageDigest

open class SecureWebViewClient(
    private val context: Context,
    private val routerOrigin: String,
    private val pinnedFingerprint: String,
    private val onCertMismatch: (presentedFingerprint: String) -> Unit = {},
    private val onConnectionError: () -> Unit = {}
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        if (request == null) return false
        val url = request.url?.toString() ?: return true

        return when (SecureWebNavigation.decideNavigation(url, routerOrigin, request.isRedirect)) {
            NavigationDecision.ALLOW_INTERNAL -> false
            NavigationDecision.OPEN_EXTERNAL -> {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, request.url).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {
                    // Block if no handler found
                }
                true
            }
            NavigationDecision.BLOCK -> true
        }
    }

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        val url = request?.url?.toString() ?: return null
        if (!SecureWebNavigation.isSubresourceAllowed(url, routerOrigin)) {
            return WebResourceResponse(
                "text/plain",
                "utf-8",
                403,
                "Blocked by AddressGuard",
                emptyMap(),
                ByteArrayInputStream(ByteArray(0))
            )
        }
        return null
    }

    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
        super.onReceivedError(view, request, error)
        if (request?.isForMainFrame == true) {
            onConnectionError()
        }
    }

    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
        if (handler == null) return

        val certBytes = extractCertificateBytes(error?.certificate)
        if (certBytes != null && SecureWebNavigation.verifyFingerprint(certBytes, pinnedFingerprint)) {
            // Certificate SHA-256 strictly matches pinned router fingerprint
            handler.proceed()
        } else {
            // Certificate mismatch or missing: hard stop
            handler.cancel()
            val presentedHash = if (certBytes != null) {
                MessageDigest.getInstance("SHA-256")
                    .digest(certBytes)
                    .joinToString(":") { "%02X".format(it) }
            } else {
                "UNKNOWN"
            }
            onCertMismatch(presentedHash)
        }
    }

    private fun extractCertificateBytes(sslCert: SslCertificate?): ByteArray? {
        if (sslCert == null) return null
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                sslCert.x509Certificate?.encoded?.let { return it }
            }
            val bundle = SslCertificate.saveState(sslCert)
            bundle.getByteArray("x509-certificate")
        } catch (_: Exception) {
            null
        }
    }
}
