package com.shaazifi.app.feature.reconnect

import com.shaazifi.app.core.net.AddressGuard
import com.shaazifi.app.core.net.CertificateChangedException
import com.shaazifi.app.core.net.TrustStore
import com.shaazifi.app.core.net.UntrustedCertificateException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed interface ReconnectState {
    data class Probing(val remainingSeconds: Int, val elapsedSeconds: Int) : ReconnectState
    data class ReconnectedKept(val newAddress: String) : ReconnectState
    data class ReconnectedRolledBack(val oldAddress: String) : ReconnectState
    data class CertificateChanged(val presentedFingerprint: String) : ReconnectState
    data object TimeoutUnreachable : ReconnectState
}

class ReconnectProber(
    private val totalDurationSeconds: Int = DEFAULT_DURATION_SECONDS,
    private val probeIntervalMs: Long = DEFAULT_INTERVAL_MS
) {
    companion object {
        const val DEFAULT_DURATION_SECONDS = 130 // Exceeds 120s OpenWrt rollback timer
        const val DEFAULT_INTERVAL_MS = 2000L    // 2s probing interval per blueprint
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

        val client = buildProbeClient(pinnedFingerprint)
        val startTime = System.currentTimeMillis()
        val totalMs = totalDurationSeconds * 1000L

        while (true) {
            val elapsedMs = System.currentTimeMillis() - startTime
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

            // 2. Try Current Address (checking if changes rolled back)
            val currentResult = probeEndpoint(client, currentAddress)
            when (currentResult) {
                is ProbeOutcome.Success -> {
                    // Router responded at current address
                    emit(ReconnectState.ReconnectedRolledBack(currentAddress))
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

            if (elapsedMs >= totalMs) {
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
        val host = address.toHttpUrlOrNull()?.host ?: return ProbeOutcome.Unreachable
        if (!AddressGuard.isAllowed(host)) return ProbeOutcome.Unreachable

        val url = address.toHttpUrlOrNull()?.newBuilder()?.encodedPath("/ubus")?.build()
            ?: return ProbeOutcome.Unreachable

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                // Response received and TLS certificate verified against pin
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

    private fun buildProbeClient(pinnedFingerprint: String): OkHttpClient {
        val trustStore = TrustStore().apply {
            pin("router", pinnedFingerprint)
        }

        return OkHttpClient.Builder()
            .connectTimeout(1500, TimeUnit.MILLISECONDS)
            .readTimeout(1500, TimeUnit.MILLISECONDS)
            .writeTimeout(1500, TimeUnit.MILLISECONDS)
            .sslSocketFactory(trustStore.sslSocketFactory, trustStore.trustManager)
            .hostnameVerifier(trustStore.hostnameVerifier)
            .retryOnConnectionFailure(false)
            .build()
    }

    private fun getRootCause(throwable: Throwable): Throwable {
        var cause: Throwable = throwable
        while (cause.cause != null && cause.cause !== cause) {
            cause = cause.cause!!
        }
        return cause
    }
}
