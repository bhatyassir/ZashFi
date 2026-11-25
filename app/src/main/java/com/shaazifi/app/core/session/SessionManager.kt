package com.shaazifi.app.core.session

import com.shaazifi.app.data.endpoints
import com.shaazifi.app.core.net.CertificateChangedException
import com.shaazifi.app.core.net.TrustStore
import com.shaazifi.app.core.net.UntrustedCertificateException
import com.shaazifi.app.core.ubus.UbusClient
import com.shaazifi.app.core.ubus.UbusError
import com.shaazifi.app.core.ubus.UbusSession
import com.shaazifi.app.data.RouterProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

data class ProbeDiagnostic(
    val exceptionClass: String?,
    val socketConnected: Boolean,
    val detail: String? = null
)

class ProbeException(
    val diagnostic: ProbeDiagnostic,
    message: String = diagnostic.detail ?: (diagnostic.exceptionClass ?: "Probe failed"),
    cause: Throwable? = null
) : Exception(message, cause)

sealed class SessionState {
    object LoggedOut : SessionState()
    object LoggingIn : SessionState()
    data class LoggedIn(
        val profile: RouterProfile,
        val token: String,
        val timeoutSeconds: Int,
        val loggedInAt: Long = System.currentTimeMillis(),
        /** The address this session talks to: the home address, or the VPN address (M9). */
        val endpoint: String = profile.address,
        val viaVpn: Boolean = false
    ) : SessionState()
    data class Error(val error: Throwable) : SessionState()
}

enum class ResumeResult { Restored, Rejected, Unreachable, Unavailable }

/** Manages active OpenWrt sessions. The app's persistence callback keeps the saved copy encrypted. */
class SessionManager(
    private val ubusClient: UbusClient,
    private val trustStore: TrustStore,
    private val client: OkHttpClient,
    /** Owner feedback: sign-ins last a week (rpcd login timeout); null keeps the router default. */
    private val loginTimeoutSeconds: Int? = null,
    /** Called after a successful sign-in, to keep the session on the phone. */
    private val onSignedIn: suspend (SessionState.LoggedIn) -> Unit = {},
    /** Called on sign out, to delete the kept session. */
    private val onSignedOut: suspend () -> Unit = {}
) {
    private val _sessionState = MutableStateFlow<SessionState>(SessionState.LoggedOut)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    val currentActiveToken: String?
        get() = (_sessionState.value as? SessionState.LoggedIn)?.token

    val currentProfile: RouterProfile?
        get() = (_sessionState.value as? SessionState.LoggedIn)?.profile

    /**
     * Probes an unknown router endpoint to extract its TLS certificate fingerprint (or confirm HTTP reachability).
     * The password is NEVER sent during this probe.
     * Returns the server's certificate SHA-256 fingerprint (or "HTTP_NO_PIN" for plain HTTP)
     * for explicit user approval.
     */
    suspend fun probeCertificate(address: String): Result<String> = withContext(Dispatchers.IO) {
        val trimmed = address.trim()
        val normalized = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }

        val isHttp = normalized.startsWith("http://")

        val existingPin = trustStore.getPinned(normalized)
        if (existingPin != null) {
            return@withContext Result.success(existingPin)
        }

        var socketConnected = false
        var capturedPeerCert: X509Certificate? = null
        val probeClient = client.newBuilder()
            .eventListener(object : EventListener() {
                override fun connectEnd(
                    call: okhttp3.Call,
                    inetSocketAddress: java.net.InetSocketAddress,
                    proxy: java.net.Proxy,
                    protocol: okhttp3.Protocol?
                ) {
                    socketConnected = true
                }

                override fun connectionAcquired(call: okhttp3.Call, connection: okhttp3.Connection) {
                    socketConnected = true
                    try {
                        val sslSocket = connection.socket() as? javax.net.ssl.SSLSocket
                        val certs = sslSocket?.session?.peerCertificates
                        println("DEBUG connectionAcquired: sslSocket=$sslSocket, certs=${certs?.size}")
                        if (!certs.isNullOrEmpty()) {
                            capturedPeerCert = certs[0] as? X509Certificate
                        }
                    } catch (t: Throwable) {
                        println("DEBUG connectionAcquired threw: $t")
                    }
                }
            })
            .build()

        try {
            val request = Request.Builder()
                .url(normalized)
                .build()

            probeClient.newCall(request).execute().use { response ->
                socketConnected = true
                val peerCert = (response.handshake?.peerCertificates?.firstOrNull() as? X509Certificate) ?: capturedPeerCert
                val fp = peerCert?.let { TrustStore.fingerprint(it) } ?: trustStore.getObserved(normalized)
                if (fp != null) {
                    Result.success(fp)
                } else if (response.request.url.isHttps.not() || isHttp) {
                    // Plain HTTP reached without certificate pinning per blueprint
                    Result.success("HTTP_NO_PIN")
                } else {
                    val diag = ProbeDiagnostic(
                        exceptionClass = "IllegalStateException",
                        socketConnected = true,
                        detail = "Connected over HTTPS but no peer certificate captured"
                    )
                    Result.failure(ProbeException(diag))
                }
            }
        } catch (e: Exception) {
            var foundUntrusted: UntrustedCertificateException? = null
            var foundChanged: CertificateChangedException? = null

            fun check(t: Throwable?) {
                if (t == null) return
                if (t is UntrustedCertificateException && foundUntrusted == null) {
                    foundUntrusted = t
                }
                if (t is CertificateChangedException && foundChanged == null) {
                    foundChanged = t
                }
                check(t.cause)
                for (s in t.suppressed) check(s)
            }
            check(e)

            if (foundUntrusted != null) {
                Result.success(foundUntrusted.presentedFingerprint)
            } else if (foundChanged != null) {
                // When probing an unpinned router endpoint, actualFingerprint is the server's certificate
                Result.success(foundChanged.actualFingerprint)
            } else {
                val isTlsFailure = e is SSLException || e is SSLPeerUnverifiedException
                val actualSocketConnected = socketConnected || isTlsFailure
                val diag = ProbeDiagnostic(
                    exceptionClass = e.javaClass.name,
                    socketConnected = actualSocketConnected,
                    detail = e.message ?: e.toString()
                )
                Result.failure(ProbeException(diag, cause = e))
            }
        }
    }

    /**
     * Pins the router's certificate fingerprint and executes session.login.
     * The app can keep an encrypted copy through [onSignedIn].
     */
    suspend fun login(
        profile: RouterProfile,
        password: String
    ): UbusSession {
        _sessionState.value = SessionState.LoggingIn
        val endpoints = profile.endpoints()
        endpoints.forEachIndexed { i, e ->
            try {
                // Pin the certificate approved for this address before anything is sent to it.
                trustStore.pin(e.address, e.fingerprint)

                val session = ubusClient.login(
                    endpointUrl = e.address,
                    username = "root",
                    password = password,
                    timeoutSeconds = loginTimeoutSeconds
                )

                val loggedIn = SessionState.LoggedIn(
                    profile = profile,
                    token = session.token,
                    timeoutSeconds = session.timeoutSeconds,
                    endpoint = e.address,
                    viaVpn = e.viaVpn
                )
                _sessionState.value = loggedIn
                runCatching { onSignedIn(loggedIn) }

                return session
            } catch (ex: Exception) {
                // Move to the VPN address only when the home address could not be reached at all.
                if (i < endpoints.lastIndex && RouteChooser.tryNextAddress(ex)) return@forEachIndexed
                _sessionState.value = SessionState.Error(ex)
                throw ex
            }
        }
        error("no address to sign in to")
    }

    /**
     * Invalids active session via session.destroy and clears memory.
     */
    suspend fun logout() {
        val current = _sessionState.value
        if (current is SessionState.LoggedIn) {
            try {
                ubusClient.destroySession(current.endpoint, current.token)
            } catch (_: Exception) {
                // Ignore logout network failure
            }
        }
        _sessionState.value = SessionState.LoggedOut
        runCatching { onSignedOut() }
    }

    /** Check the kept token using the current profile's approved addresses and pins. */
    suspend fun resume(profile: RouterProfile, saved: SavedSession): ResumeResult {
        require(profile.id == saved.profileId)
        val endpoints = profile.endpoints()
        val preferred = endpoints.firstOrNull { it.address == saved.endpoint && it.viaVpn == saved.viaVpn }
            ?: return ResumeResult.Unavailable
        val ordered = listOf(preferred) + endpoints.filter { it != preferred }
        for (endpoint in ordered) {
            try {
                trustStore.pin(endpoint.address, endpoint.fingerprint)
                ubusClient.call(endpoint.address, saved.token, "system", "board")
            } catch (e: CancellationException) {
                throw e
            } catch (e: UbusError.AccessDenied) {
                return ResumeResult.Rejected
            } catch (e: Exception) {
                if (RouteChooser.tryNextAddress(e)) continue
                return when {
                    e is UbusError.NetworkError && e.cause is SocketException -> ResumeResult.Unreachable
                    e is UbusError.HttpError && e.statusCode in 500..599 -> ResumeResult.Unreachable
                    else -> ResumeResult.Unavailable
                }
            }

            val session = SessionState.LoggedIn(
                profile = profile,
                token = saved.token,
                timeoutSeconds = loginTimeoutSeconds ?: SavedSession.TIMEOUT_SECONDS,
                endpoint = endpoint.address,
                viaVpn = endpoint.viaVpn
            )
            try {
                onSignedIn(session)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Resume still works in memory if the encrypted copy cannot be refreshed.
            }
            _sessionState.value = session
            return ResumeResult.Restored
        }
        return ResumeResult.Unreachable
    }
}
