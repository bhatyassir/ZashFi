package com.shaazifi.app.core.net

import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * AddressGuard enforces that all network connections from ShaaziFi are strictly
 * confined to private RFC 1918 networks, Tailscale (100.64.0.0/10), loopback,
 * and local IPv6 ranges.
 */
class AddressNotAllowedException(
    val host: String,
    message: String = "Access to address '$host' is refused. Only private LAN, Tailscale (100.64.0.0/10), loopback, and local IPv6 addresses are permitted."
) : IOException(message)

fun interface AddressGuardDns {
    fun lookup(hostname: String): List<InetAddress>
}

object SystemAddressGuardDns : AddressGuardDns {
    override fun lookup(hostname: String): List<InetAddress> =
        InetAddress.getAllByName(hostname).toList()
}

object AddressGuard {

    /**
     * Checks if a host string is an obfuscated or non-canonical IP format
     * (e.g., DWORD 2130706433, hex 0x7f.1, or dotted octal 0177.0.0.1).
     */
    fun isNonCanonicalIp(host: String): Boolean {
        val clean = host.trim().removeSurrounding("[", "]")
        // Single integer / DWORD (e.g. 2130706433)
        if (clean.isNotEmpty() && clean.all { it.isDigit() }) return true
        // Dotted parts
        val parts = clean.split(".")
        // Hex IP notation (e.g. 0x7f.1 or 0x7f000001) without rejecting valid hostnames like box0x1.lan
        if (parts.any { it.startsWith("0x", ignoreCase = true) }) return true
        if (parts.size in 2..3 && parts.all { it.isNotEmpty() && it.all { c -> c.isDigit() } }) {
            // Shorthand 2-part or 3-part IP (e.g. 127.1 or 10.0.1)
            return true
        }
        if (parts.size in 2..4 && parts.any { it.length > 1 && it.startsWith("0") && it.all { c -> c.isDigit() } }) {
            // Dotted octal notation (e.g. 0177.0.0.1 or 127.0.0.01)
            return true
        }
        return false
    }

    /**
     * Checks whether an IP literal or hostname is within permitted address ranges.
     */
    fun isAllowed(hostOrIp: String, dns: AddressGuardDns = SystemAddressGuardDns): Boolean {
        val cleanHost = extractHost(hostOrIp).trim().removeSurrounding("[", "]")

        if (cleanHost.isEmpty() || isNonCanonicalIp(cleanHost)) {
            return false
        }

        if (cleanHost.equals("localhost", ignoreCase = true)) {
            return true
        }

        // IPv4-mapped IPv6 string literal handling: ::ffff:w.x.y.z
        if (cleanHost.startsWith("::ffff:", ignoreCase = true)) {
            val mappedIpv4 = cleanHost.substring(7)
            return isAllowed(mappedIpv4, dns)
        }

        return try {
            val addresses = dns.lookup(cleanHost)
            if (addresses.isEmpty()) return false
            // Every resolved address must be within permitted ranges
            addresses.all { isAddressAllowed(it) }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Asserts that a URL or hostname is allowed, throwing [AddressNotAllowedException] if not.
     */
    fun requireAllowed(urlOrHost: String, dns: AddressGuardDns = SystemAddressGuardDns) {
        val host = extractHost(urlOrHost)
        if (!isAllowed(host, dns)) {
            throw AddressNotAllowedException(host)
        }
    }

    fun extractHost(urlOrHost: String): String {
        val trimmed = urlOrHost.trim()
        val withoutScheme = if (trimmed.startsWith("http://", ignoreCase = true)) {
            trimmed.substring(7)
        } else if (trimmed.startsWith("https://", ignoreCase = true)) {
            trimmed.substring(8)
        } else {
            trimmed
        }

        // Split off path/query/fragment
        val authority = withoutScheme.substringBefore("/").substringBefore("?").substringBefore("#")
        // Strip userinfo if present: e.g. "192.168.1.1@evil.com" -> host is "evil.com"
        val hostAndPort = if (authority.contains("@")) {
            authority.substringAfterLast("@")
        } else {
            authority
        }

        // Handle IPv6 literal bracket: [::1]:80 -> ::1, or [fe80::1%wlan0] -> fe80::1
        val rawHost = if (hostAndPort.startsWith("[")) {
            hostAndPort.substringAfter("[").substringBefore("]")
        } else if (hostAndPort.count { it == ':' } == 1) {
            // Standard hostname:port or ipv4:port
            hostAndPort.substringBefore(":")
        } else {
            // Unbracketed IPv6 literal or hostname without port
            hostAndPort
        }
        return rawHost.substringBefore("%")
    }

    fun isAddressAllowed(addr: InetAddress): Boolean {
        if (addr.isLoopbackAddress) return true

        return when (addr) {
            is Inet4Address -> isAllowedIpv4(addr)
            is Inet6Address -> isAllowedIpv6(addr)
            else -> false
        }
    }

    private fun isAllowedIpv4(addr: Inet4Address): Boolean {
        val bytes = addr.address
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        return isAllowedIpv4Bytes(b0, b1)
    }

    fun isAllowedIpv4Bytes(b0: Int, b1: Int): Boolean {
        // 10.0.0.0/8 (10.0.0.0 - 10.255.255.255)
        if (b0 == 10) return true

        // 172.16.0.0/12 (172.16.0.0 - 172.31.255.255)
        if (b0 == 172 && (b1 in 16..31)) return true

        // 192.168.0.0/16 (192.168.0.0 - 192.168.255.255)
        if (b0 == 192 && b1 == 168) return true

        // 100.64.0.0/10 (100.64.0.0 - 100.127.255.255) - Carrier-grade NAT / Tailscale
        if (b0 == 100 && (b1 in 64..127)) return true

        // 127.0.0.0/8
        if (b0 == 127) return true

        return false
    }

    private fun isAllowedIpv6(addr: Inet6Address): Boolean {
        val bytes = addr.address

        // Check for IPv4-mapped IPv6: ::ffff:w.x.y.z
        val isIpv4Mapped = (0..9).all { bytes[it] == 0.toByte() } &&
                           bytes[10] == 0xFF.toByte() &&
                           bytes[11] == 0xFF.toByte()
        if (isIpv4Mapped) {
            val b0 = bytes[12].toInt() and 0xFF
            val b1 = bytes[13].toInt() and 0xFF
            return isAllowedIpv4Bytes(b0, b1)
        }

        if (addr.isLoopbackAddress) return true
        if (addr.isLinkLocalAddress) return true

        // ULA fc00::/7 (fc00... or fd00...)
        val firstByte = bytes[0].toInt() and 0xFF
        if ((firstByte and 0xFE) == 0xFC) return true

        return false
    }
}

/**
 * OkHttp Interceptor that guards every outbound HTTP request and any redirect locations.
 *
 * OkHttp's `RetryAndFollowUpInterceptor` resolves and connects to redirect targets between
 * application and network interceptors, so an application interceptor that only inspects the
 * final response never sees an unsafe redirect hop. This interceptor therefore owns the
 * follow-up loop itself (automatic redirects are disabled in [HttpFactory]): every Location
 * is validated with [AddressGuard.requireAllowed] *before* a second [Interceptor.Chain.proceed]
 * is issued. That keeps the guard on the post-redirect path without adding a network
 * interceptor (which would duplicate DNS lookups on every request).
 */
class AddressGuardInterceptor(
    private val dns: AddressGuardDns = SystemAddressGuardDns
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        AddressGuard.requireAllowed(request.url.host, dns)

        var redirectCount = 0
        while (true) {
            val response = chain.proceed(request)
            if (!response.isRedirect) return response

            val redirectUrl = response.header("Location")?.let { request.url.resolve(it) }
                ?: return response // no usable target: expose the redirect to the caller
            AddressGuard.requireAllowed(redirectUrl.host, dns)

            if (++redirectCount > MAX_REDIRECTS) {
                response.close()
                throw IOException("Too many redirects (max $MAX_REDIRECTS)")
            }
            val followUp = request.followRedirectTo(redirectUrl, response.code)
            response.close()
            request = followUp
        }
    }

    /** Mirrors OkHttp's follow-up rules without forwarding credentials to a different host. */
    private fun Request.followRedirectTo(url: HttpUrl, statusCode: Int): Request {
        val builder = newBuilder().url(url)
        if (!url.host.equals(this.url.host, ignoreCase = true)) builder.removeHeader("Authorization")
        // 303 (and POST/PUT/PATCH 301/302) become GET; 307/308 keep the original method.
        val becomesGet = when {
            statusCode == 303 && !method.equals("HEAD", ignoreCase = true) -> true
            (statusCode == 301 || statusCode == 302) && !method.equals("GET", ignoreCase = true) &&
                !method.equals("HEAD", ignoreCase = true) -> true
            else -> false
        }
        if (becomesGet) builder.method("GET", null)
        return builder.build()
    }

    companion object {
        private const val MAX_REDIRECTS = 20
    }
}

/**
 * OkHttp Dns implementation enforcing that resolved addresses must be allowed.
 */
class OkHttpAddressGuardDns(
    private val dns: AddressGuardDns = SystemAddressGuardDns
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val cleanHost = hostname.trim().removeSurrounding("[", "]").substringBefore("%")
        if (AddressGuard.isNonCanonicalIp(cleanHost)) {
            throw AddressNotAllowedException(hostname)
        }
        val addresses = dns.lookup(cleanHost)
        val allowed = addresses.filter { AddressGuard.isAddressAllowed(it) }
        if (allowed.isEmpty()) {
            throw AddressNotAllowedException(hostname)
        }
        return allowed
    }
}
