package com.shaazifi.app.feature.web

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.http.SslCertificate
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

open class SecureWebViewClient(
    private val context: Context,
    private val routerOrigin: String,
    private val pinnedFingerprint: String,
    private val onCertMismatch: (presentedFingerprint: String) -> Unit = {},
    private val onConnectionError: () -> Unit = {}
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        if (request == null) return false
        val url = request.url.toString()

        // 1. Enforce AddressGuard security rules
        if (!SecureWebNavigation.isNavigationalAllowed(url, routerOrigin)) {
            return true // Refuse navigation to dangerous or invalid destinations
        }

        // 2. Check if destination is internal to the router origin
        if (SecureWebNavigation.isInternalNavigation(url, routerOrigin)) {
            return false // Allow WebView to proceed internally
        }

        // 3. External links open in the system browser
        return try {
            val intent = Intent(Intent.ACTION_VIEW, request.url).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            true // Block if no handler found
        }
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
                java.security.MessageDigest.getInstance("SHA-256")
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
            val bundle = SslCertificate.saveState(sslCert)
            val bytes = bundle.getByteArray("x509-certificate")
            if (bytes != null) {
                bytes
            } else {
                // Fallback: reflection on mX509Certificate field if bundle is empty
                val field = sslCert.javaClass.getDeclaredField("mX509Certificate")
                field.isAccessible = true
                val x509 = field.get(sslCert) as? X509Certificate
                x509?.encoded
            }
        } catch (_: Exception) {
            null
        }
    }
}

