package com.shaazifi.app.feature.web

import com.shaazifi.app.core.net.AddressGuard
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest

object SecureWebNavigation {

    fun isInternalNavigation(url: String, routerOrigin: String): Boolean {
        val targetUrl = url.toHttpUrlOrNull() ?: return false
        val originUrl = routerOrigin.toHttpUrlOrNull() ?: return false

        if (targetUrl.scheme != originUrl.scheme) return false
        if (!targetUrl.host.equals(originUrl.host, ignoreCase = true)) return false
        if (targetUrl.port != originUrl.port) return false

        return true
    }

    fun isNavigationalAllowed(url: String, routerOrigin: String): Boolean {
        val targetUrl = url.toHttpUrlOrNull() ?: return false
        val host = targetUrl.host

        // AddressGuard enforces strict validation (no user@host, strict IP ranges, dotted-quad)
        return AddressGuard.isAllowed(host)
    }

    private val TOKEN_REGEX = Regex("^[0-9a-f]{32}$")

    fun isValidToken(token: String): Boolean {
        return TOKEN_REGEX.matches(token)
    }

    fun verifyFingerprint(certBytes: ByteArray?, pinnedFingerprint: String?): Boolean {
        if (certBytes == null || certBytes.isEmpty()) return false
        if (pinnedFingerprint.isNullOrBlank()) return false
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val hash = md.digest(certBytes).joinToString(":") { "%02X".format(it) }
            hash.equals(pinnedFingerprint, ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    fun buildSessionInjectionScript(token: String): String {
        require(isValidToken(token)) { "Malformed or unsafe ubus session token" }
        return """
            (function() {
                try {
                    sessionStorage.setItem('untangled-session', '$token');
                } catch(e) {}
            })();
        """.trimIndent()
    }
}
