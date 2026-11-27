package com.shaazifi.app.feature.web

import com.shaazifi.app.core.net.AddressGuard
import com.shaazifi.app.core.net.AddressGuardDns
import com.shaazifi.app.core.net.SystemAddressGuardDns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest

enum class NavigationDecision {
    /** Load inside the WebView (matches the router origin and passes AddressGuard). */
    ALLOW_INTERNAL,

    /** Safe public internet link: open in external browser via ACTION_VIEW (not on redirects). */
    OPEN_EXTERNAL,

    /** Blocked: dangerous scheme, userinfo spoofing, obfuscated IP, or another private host. */
    BLOCK
}

object SecureWebNavigation {

    @JvmOverloads
    fun isInternalNavigation(
        url: String,
        routerOrigin: String,
        dns: AddressGuardDns = SystemAddressGuardDns
    ): Boolean {
        val targetUrl = url.toHttpUrlOrNull() ?: return false
        val originUrl = routerOrigin.toHttpUrlOrNull() ?: return false

        if (targetUrl.scheme != originUrl.scheme) return false
        if (!targetUrl.host.equals(originUrl.host, ignoreCase = true)) return false
        if (targetUrl.port != originUrl.port) return false
        if (targetUrl.username.isNotEmpty() || targetUrl.password.isNotEmpty()) return false

        return AddressGuard.isAllowed(targetUrl.host, dns)
    }

    /**
     * True when [url] is allowed to load inside the router WebView (internal to [routerOrigin]
     * and permitted by [AddressGuard]).
     */
    @JvmOverloads
    fun isNavigationalAllowed(
        url: String,
        routerOrigin: String,
        dns: AddressGuardDns = SystemAddressGuardDns
    ): Boolean = isInternalNavigation(url, routerOrigin, dns)

    /**
     * True when [url] is a safe, non-local HTTP(S) link that should open in the system browser
     * rather than inside the router WebView. Arbitrary private/local hosts, obfuscated IPs,
     * userinfo-spoofed URLs, and non-HTTP(S) schemes are refused.
     */
    @JvmOverloads
    fun isExternalBrowserUrl(
        url: String,
        routerOrigin: String,
        dns: AddressGuardDns = SystemAddressGuardDns
    ): Boolean {
        val targetUrl = url.toHttpUrlOrNull() ?: return false
        if (targetUrl.username.isNotEmpty() || targetUrl.password.isNotEmpty()) return false
        if (isInternalNavigation(url, routerOrigin, dns)) return false
        val host = targetUrl.host
        if (AddressGuard.isNonCanonicalIp(host)) return false
        return !AddressGuard.isAllowed(host, dns)
    }

    /** Decides how WebView navigation to [url] should be handled. */
    @JvmOverloads
    fun decideNavigation(
        url: String,
        routerOrigin: String,
        isRedirect: Boolean = false,
        dns: AddressGuardDns = SystemAddressGuardDns
    ): NavigationDecision {
        if (isNavigationalAllowed(url, routerOrigin, dns)) {
            return NavigationDecision.ALLOW_INTERNAL
        }
        if (!isRedirect && isExternalBrowserUrl(url, routerOrigin, dns)) {
            return NavigationDecision.OPEN_EXTERNAL
        }
        return NavigationDecision.BLOCK
    }

    /**
     * Enforces [AddressGuard] on WebView subresources (<script>, fetch, <img>, etc.).
     * In-memory `data:`, `blob:`, and `about:` resources are permitted; network schemes
     * must be valid HTTP(S) URLs without userinfo whose host passes [AddressGuard.isAllowed].
     */
    @JvmOverloads
    fun isSubresourceAllowed(
        url: String,
        routerOrigin: String,
        dns: AddressGuardDns = SystemAddressGuardDns
    ): Boolean {
        val trimmed = url.trim()
        val scheme = trimmed.substringBefore(':', "").lowercase()
        if (scheme == "data" || scheme == "blob" || scheme == "about") return true
        val targetUrl = trimmed.toHttpUrlOrNull() ?: return false
        if (targetUrl.username.isNotEmpty() || targetUrl.password.isNotEmpty()) return false
        return AddressGuard.isAllowed(targetUrl.host, dns)
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
