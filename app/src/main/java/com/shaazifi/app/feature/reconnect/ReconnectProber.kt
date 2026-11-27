package com.shaazifi.app.feature.reconnect

import android.os.SystemClock
import com.shaazifi.app.core.net.AddressGuard
import com.shaazifi.app.core.net.CertificateChangedException
import com.shaazifi.app.core.net.HttpFactory
import com.shaazifi.app.core.net.TrustStore
import com.shaazifi.app.core.net.UntrustedCertificateException
import com.shaazifi.app.feature.routers.RouterDisplay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

sealed interface ReconnectState {
    data class Probing(val remainingSeconds: Int, val elapsedSeconds: Int) : ReconnectState
    data class ReconnectedKept(val newAddress: String) : ReconnectState
    data class ReconnectedSameAddress(val address: String) : ReconnectState
    data class ReconnectedRolledBack(val oldAddress: String) : ReconnectState
    data class CertificateChanged(val presentedFingerprint: String) : ReconnectState
    data object TimeoutUnreachable : ReconnectState
}

class ReconnectProber(
    private val totalDurationSeconds: Int = DEFAULT_DURATION_SECONDS,
    private val probeIntervalMs: Long = DEFAULT_INTERVAL_MS,
    private val elapsedRealtimeMs: () -> Long = ::defaultElapsedRealtime
) {
    companion object {
        const val DEFAULT_DURATION_SECONDS = 130 // Exceeds 120s OpenWrt rollback timer
        const val DEFAULT_INTERVAL_MS = 2000L    // 2s probing interval per blueprint
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val UBUS_PROBE_BODY = """{"jsonrpc":"2.0","id":1,"method":"list"}"""

        private fun defaultElapsedRealtime(): Long = try {
            SystemClock.elapsedRealtime()
        } catch (_: RuntimeException) {
            // Pure JVM unit tests do not mock android.os.SystemClock; fall back to monotonic nanoTime.
            System.nanoTime() / 1_000_000L
        }
    }

    fun probe(
        currentAddress: String,
        candidateAddress: String?,
        pinnedFingerprint: String
    ): Flow<ReconnectState> = flow {
        // Validate candidate address against AddressGuard before using it
        val validCandidate = candidateAddress?.takeIf { candidate ->
            val host = candidate.toHttpUrlOrNull()?.host
            host != null && AddressGuard.isAllowed(host)
        }

        val client = buildProbeClient(pinnedFingerprint, currentAddress, validCandidate)
        val startTime = elapsedRealtimeMs()
        val totalMs = totalDurationSeconds * 1000L

        while (true) {
            val elapsedMs = (elapsedRealtimeMs() - startTime).coerceAtLeast(0L)
            val elapsedSeconds = (elapsedMs / 1000).toInt()
            val remainingSeconds = (totalDurationSeconds - elapsedSeconds).coerceAtLeast(0)

            emit(ReconnectState.Probing(remainingSeconds, elapsedSeconds))

            // 1. Try Candidate Address first (checking if changes were kept)
            if (validCandidate != null) {
                val candidateResult = probeEndpoint(client, validCandidate)
                when (candidateResult) {
                    is ProbeOutcome.Success -> {
                        emit(ReconnectState.ReconnectedKept(validCandidate))
                        return@flow
                    }
                    is ProbeOutcome.CertChanged -> {
                        emit(ReconnectState.CertificateChanged(candidateResult.presentedFingerprint))
                        return@flow // Hard stop! Never auto-trust
                    }
                    is ProbeOutcome.Unreachable -> {
                        // Candidate not answering
                    }
                }
            }

            // 2. Try Current Address (either normal reconnect or rollback when a candidate was given)
            val currentResult = probeEndpoint(client, currentAddress)
            when (currentResult) {
                is ProbeOutcome.Success -> {
                    val outcome = if (candidateAddress == null) {
                        ReconnectState.ReconnectedSameAddress(currentAddress)
                    } else {
                        ReconnectState.ReconnectedRolledBack(currentAddress)
                    }
                    emit(outcome)
                    return@flow
                }
                is ProbeOutcome.CertChanged -> {
                    emit(ReconnectState.CertificateChanged(currentResult.presentedFingerprint))
                    return@flow // Hard stop! Never auto-trust
                }
                is ProbeOutcome.Unreachable -> {
                    // Current address still unreachable
                }
            }

            val afterProbeElapsedMs = (elapsedRealtimeMs() - startTime).coerceAtLeast(0L)
            if (afterProbeElapsedMs >= totalMs) {
                emit(ReconnectState.TimeoutUnreachable)
                return@flow
            }

            delay(probeIntervalMs)
        }
    }.flowOn(Dispatchers.IO)

    private sealed interface ProbeOutcome {
        data object Success : ProbeOutcome
        data class CertChanged(val presentedFingerprint: String) : ProbeOutcome
        data object Unreachable : ProbeOutcome
    }

    private fun probeEndpoint(
        client: OkHttpClient,
        address: String
    ): ProbeOutcome {
        val parsed = address.toHttpUrlOrNull() ?: return ProbeOutcome.Unreachable
        if (!AddressGuard.isAllowed(parsed.host)) return ProbeOutcome.Unreachable

        val url = parsed.newBuilder().encodedPath("/ubus").build()

        val request = Request.Builder()
            .url(url)
            .post(UBUS_PROBE_BODY.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                // Require an HTTP 200 response from /ubus; reject 3xx captive-portal redirects, 404s, and HTML pages.
                if (response.code != 200) {
                    return ProbeOutcome.Unreachable
                }
                val contentType = response.header("Content-Type").orEmpty()
                if (contentType.contains("html", ignoreCase = true)) {
                    return ProbeOutcome.Unreachable
                }
                val body = response.body.string().trimStart()
                if (body.startsWith("<")) {
                    return ProbeOutcome.Unreachable
                }
                ProbeOutcome.Success
            }
        } catch (e: Exception) {
            val mismatchedCert = extractCertificateMismatch(e)
            if (mismatchedCert != null) {
                ProbeOutcome.CertChanged(mismatchedCert)
            } else {
                ProbeOutcome.Unreachable
            }
        }
    }

    private fun extractCertificateMismatch(throwable: Throwable): String? {
        var current: Throwable? = throwable
        val seen = mutableSetOf<Throwable>()
        while (current != null && seen.add(current)) {
            if (current is CertificateChangedException) {
                return current.actualFingerprint
            }
            if (current is UntrustedCertificateException) {
                return current.presentedFingerprint
            }
            val msg = current.message
            if (msg != null && msg.contains("Certificate fingerprint mismatch")) {
                val match = Regex("Got:\\s*([0-9A-Fa-f:]+)").find(msg)
                if (match != null) return match.groupValues[1]
            }
            for (suppressed in current.suppressed) {
                val fromSuppressed = extractCertificateMismatch(suppressed)
                if (fromSuppressed != null) return fromSuppressed
            }
            current = current.cause
        }
        return null
    }

    private fun buildProbeClient(
        pinnedFingerprint: String,
        currentAddress: String,
        candidateAddress: String?
    ): OkHttpClient {
        val trustStore = TrustStore().apply {
            if (pinnedFingerprint != RouterDisplay.HTTP_NO_PIN && pinnedFingerprint.isNotBlank()) {
                pin(currentAddress, pinnedFingerprint)
                candidateAddress?.let { pin(it, pinnedFingerprint) }
                pin("router", pinnedFingerprint)
            }
        }

        return HttpFactory.createClient(trustStore)
            .newBuilder()
            .connectTimeout(1500, TimeUnit.MILLISECONDS)
            .readTimeout(1500, TimeUnit.MILLISECONDS)
            .writeTimeout(1500, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }
}
