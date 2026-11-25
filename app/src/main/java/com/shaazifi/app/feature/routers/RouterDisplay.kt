package com.shaazifi.app.feature.routers

import com.shaazifi.app.TestStatus
import com.shaazifi.app.core.net.AddressNotAllowedException
import com.shaazifi.app.core.net.CertificateChangedException
import com.shaazifi.app.core.ubus.UbusError
import com.shaazifi.app.data.RouterProfile
import com.shaazifi.app.ui.theme.PillTone
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Plain helpers for the start screens (router list, add router, sign in). Unit-tested. */
object RouterDisplay {

    const val HTTP_NO_PIN = "HTTP_NO_PIN"

    /** True when the router is reached over plain HTTP (no certificate to pin). */
    fun isPlainHttp(profile: RouterProfile): Boolean =
        profile.certificateFingerprint == HTTP_NO_PIN || profile.address.startsWith("http://", ignoreCase = true)

    /** "192.168.1.1" or "192.168.1.1:8443": host plus any non-default port; the input itself if it is not a URL. */
    fun hostLabel(address: String): String {
        val url = address.trim().toHttpUrlOrNull() ?: return address
        val defaultPort = if (url.scheme == "https") 443 else 80
        val host = if (url.host.contains(':')) "[${url.host}]" else url.host
        return if (url.port == defaultPort) host else "$host:${url.port}"
    }

    /** Splits "AA:BB:..." into lines of [pairsPerLine] pairs so a fingerprint can be read and compared. */
    fun fingerprintLines(fingerprint: String, pairsPerLine: Int = 8): List<String> =
        fingerprint.split(':').chunked(pairsPerLine).map { it.joinToString(":") }

    /** Sign-in failure in plain English. Never includes the password. */
    fun signInError(error: Throwable): String {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is CertificateChangedException) {
                return "This router's security certificate has changed since you added it. " +
                    "The app stopped to protect your password. If you reset or replaced the router, forget it and add it again."
            }
            if (cause is AddressNotAllowedException) {
                return "This address is not on a private network, so the app will not contact it."
            }
            cause = cause.cause
        }
        return when (error) {
            is UbusError.AccessDenied -> "Wrong user name or password. Please try again."
            is UbusError.NetworkError -> "Can't reach the router. Check that this phone is on the router's Wi-Fi or VPN."
            else -> "Sign-in failed: ${error.message ?: error::class.simpleName}"
        }
    }

    /** Label and pill tone for a connection self-test result (test builds only). */
    fun testStatusPill(status: TestStatus): Pair<String, PillTone> = when (status) {
        TestStatus.PENDING -> "Not run" to PillTone.Off
        TestStatus.RUNNING -> "Running" to PillTone.Off
        TestStatus.SUCCESS -> "OK (200)" to PillTone.Ok
        TestStatus.REDIRECT -> "Redirect (307)" to PillTone.Warn
        TestStatus.REFUSED_EXPECTED -> "Blocked, as expected" to PillTone.Ok
        TestStatus.HARD_STOP_EXPECTED -> "Stopped, as expected" to PillTone.Ok
        TestStatus.FAILED -> "Failed" to PillTone.Bad
    }
}
