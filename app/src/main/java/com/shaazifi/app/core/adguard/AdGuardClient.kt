package com.shaazifi.app.core.adguard

import com.shaazifi.app.feature.adguard.AdGuardDashboard
import com.shaazifi.app.feature.adguard.AdGuardFilteringStatus
import com.shaazifi.app.feature.adguard.AdGuardLogDevice
import com.shaazifi.app.feature.adguard.AdGuardParsers
import com.shaazifi.app.feature.adguard.AdGuardQueryLog
import com.shaazifi.app.feature.adguard.AdGuardQueryLogSettings
import com.shaazifi.app.feature.adguard.AdGuardSafeSearchStatus
import com.shaazifi.app.feature.adguard.AdGuardStats
import com.shaazifi.app.feature.adguard.AdGuardStatsConfig
import com.shaazifi.app.feature.adguard.AdGuardStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import java.io.IOException

/**
 * Diagnostic result of probing an AdGuard Home endpoint.
 * Contains no credentials or secrets.
 */
data class AdGuardProbeDiagnostic(
    val targetHostPort: String,
    val httpCodeOrException: String,
    val socketConnected: Boolean
)

data class AdGuardProbeResult(
    val isAdGuard: Boolean,
    /** Null when the source has no network detail to report (demo data, fakes). */
    val diagnostic: AdGuardProbeDiagnostic?
)

/**
 * AdGuardClient handles REST API calls to an AdGuard Home instance.
 *
 * Security Constraints:
 * 1. An unauthenticated probe of GET /control/status is ALWAYS performed before sending Basic Auth.
 * 2. Credentials are never sent to unverified endpoints (e.g. router web server on port 80).
 * 3. Network calls pass through AddressGuard (private/Tailscale addresses only).
 * 4. Passwords are held in memory only for the request and never logged.
 * 5. State-changing empty-body POSTs (safebrowsing) omit Content-Type header [UNVERIFIED: no source was read].
 */
class AdGuardClient(
    private val client: OkHttpClient
) {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Probes the endpoint unauthenticated with detailed diagnostics.
     * Never sends credentials in this check.
     */
    suspend fun probeEndpointDetailed(baseUrl: String): AdGuardProbeResult = withContext(Dispatchers.IO) {
        val parsed = baseUrl.toHttpUrlOrNull()
        val targetHostPort = if (parsed != null) {
            "${parsed.host}:${parsed.port}"
        } else {
            baseUrl.removePrefix("http://").removePrefix("https://").substringBefore("/")
        }

        val url = "$baseUrl/control/status".toHttpUrlOrNull()
        if (url == null) {
            return@withContext AdGuardProbeResult(
                isAdGuard = false,
                diagnostic = AdGuardProbeDiagnostic(
                    targetHostPort = targetHostPort,
                    httpCodeOrException = "Malformed URL",
                    socketConnected = false
                )
            )
        }

        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> {
                        val body = response.body.string()
                        val isJsonAdGuard = body.contains("\"version\"") &&
                                (body.contains("\"protection_enabled\"") || body.contains("\"dns_addresses\""))
                        AdGuardProbeResult(
                            isAdGuard = isJsonAdGuard,
                            diagnostic = AdGuardProbeDiagnostic(
                                targetHostPort = targetHostPort,
                                httpCodeOrException = if (isJsonAdGuard) "HTTP 200" else "HTTP 200 (Non-AdGuard content)",
                                socketConnected = true
                            )
                        )
                    }
                    401 -> {
                        // AdGuard Home answers an unauthenticated /control/* request with a bare 401:
                        // no WWW-Authenticate header, no body.
                        // Source: AdguardTeam/AdGuardHome master 5a6053ab47f1, internal/home/authhttp.go,
                        // authMiddlewareDefault.Wrap. Read 2026-11-16.
                        // A 401 alone cannot prove the server is AdGuard, so it means "present, sign-in needed";
                        // the signed-in /control/status reply is still parsed and must look like AdGuard.
                        AdGuardProbeResult(
                            isAdGuard = true,
                            diagnostic = AdGuardProbeDiagnostic(
                                targetHostPort = targetHostPort,
                                httpCodeOrException = "HTTP 401",
                                socketConnected = true
                            )
                        )
                    }
                    else -> {
                        AdGuardProbeResult(
                            isAdGuard = false,
                            diagnostic = AdGuardProbeDiagnostic(
                                targetHostPort = targetHostPort,
                                httpCodeOrException = "HTTP ${response.code}",
                                socketConnected = true
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            val socketConnected = e !is java.net.ConnectException &&
                    e !is java.net.UnknownHostException &&
                    !e.message.orEmpty().contains("failed to connect", ignoreCase = true)
            AdGuardProbeResult(
                isAdGuard = false,
                diagnostic = AdGuardProbeDiagnostic(
                    targetHostPort = targetHostPort,
                    httpCodeOrException = e::class.simpleName ?: "Exception",
                    socketConnected = socketConnected
                )
            )
        }
    }

    /**
     * Probes the endpoint unauthenticated to verify it is recognisably AdGuard Home.
     * Never sends credentials in this check.
     */
    suspend fun probeEndpoint(baseUrl: String): Boolean = probeEndpointDetailed(baseUrl).isAdGuard

    private suspend fun executeGet(
        urlStr: String,
        authHeader: String
    ): String = suspendCancellableCoroutine { continuation ->
        val url = urlStr.toHttpUrlOrNull() ?: throw AdGuardError.HttpError(400, "Malformed URL")
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", authHeader)
            .header("Accept", "application/json")
            .build()

        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWith(Result.failure(AdGuardError.Unreachable(cause = e)))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        when (response.code) {
                            200 -> response.body.string()
                            401 -> throw AdGuardError.Unauthorized()
                            429 -> throw AdGuardError.RateLimited(
                                retryAfterSeconds = response.header("Retry-After")?.trim()?.toIntOrNull()
                            )
                            else -> throw AdGuardError.HttpError(response.code, response.message)
                        }
                    }
                }
                continuation.resumeWith(result.recoverCatching { error ->
                    throw if (error is IOException) AdGuardError.Unreachable(cause = error) else error
                })
            }
        })
    }

    suspend fun loadDashboard(
        baseUrl: String,
        username: String,
        password: String
    ): AdGuardDashboard = withContext(Dispatchers.IO) {
        // Enforce unauthenticated discovery gate
        if (!probeEndpoint(baseUrl)) {
            throw AdGuardError.NotAdGuardHome("Endpoint $baseUrl is not recognisably AdGuard Home")
        }

        val authHeader = Credentials.basic(username, password)
        val isCleartext = baseUrl.startsWith("http://")

        val statusStr = executeGet("$baseUrl/control/status", authHeader)
        val status = AdGuardParsers.parseStatus(statusStr)

        val statsStr = executeGet("$baseUrl/control/stats", authHeader)
        val stats = AdGuardParsers.parseStats(statsStr)

        val statsConfig = try {
            val configStr = executeGet("$baseUrl/control/stats/config", authHeader)
            AdGuardParsers.parseStatsConfig(configStr)
        } catch (_: Exception) {
            AdGuardStatsConfig(intervalKnown = false)
        }

        val filtering = try {
            val filteringStr = executeGet("$baseUrl/control/filtering/status", authHeader)
            AdGuardParsers.parseFilteringStatus(filteringStr)
        } catch (_: Exception) {
            AdGuardFilteringStatus()
        }

        val safeSearch = try {
            val safeSearchStr = executeGet("$baseUrl/control/safesearch/status", authHeader)
            AdGuardParsers.parseSafeSearchStatus(safeSearchStr)
        } catch (_: Exception) {
            AdGuardSafeSearchStatus()
        }

        // Read-only status calls. A failure leaves the value unknown (null), never guessed.
        val safeBrowsingEnabled = try {
            AdGuardParsers.parseEnabledFlag(executeGet("$baseUrl/control/safebrowsing/status", authHeader))
        } catch (_: Exception) {
            null
        }
        val parentalEnabled = try {
            AdGuardParsers.parseEnabledFlag(executeGet("$baseUrl/control/parental/status", authHeader))
        } catch (_: Exception) {
            null
        }

        AdGuardDashboard(
            status = status,
            stats = stats,
            statsConfig = statsConfig,
            filtering = filtering,
            safeSearch = safeSearch,
            isCleartextHttp = isCleartext,
            safeBrowsingEnabled = safeBrowsingEnabled,
            parentalEnabled = parentalEnabled
        )
    }

    suspend fun loadQueryLog(
        baseUrl: String,
        username: String,
        password: String,
        limit: Int = 50,
        offset: Int = 0,
        search: String? = null,
        olderThan: String? = null,
        /** AdGuard Home internal/querylog/deprecated.go values; "all" or null means no filter. */
        responseStatus: String? = null
    ): AdGuardQueryLog = withContext(Dispatchers.IO) {
        val authHeader = Credentials.basic(username, password)
        val urlBuilder = "$baseUrl/control/querylog".toHttpUrlOrNull()?.newBuilder()
            ?: throw AdGuardError.HttpError(400, "Malformed URL")

        urlBuilder.addQueryParameter("limit", limit.toString())
        if (olderThan.isNullOrBlank() && offset > 0) urlBuilder.addQueryParameter("offset", offset.toString())
        if (!search.isNullOrBlank()) {
            urlBuilder.addQueryParameter("search", search)
        }
        if (!olderThan.isNullOrBlank()) {
            urlBuilder.addQueryParameter("older_than", olderThan)
        }
        if (!responseStatus.isNullOrBlank() && responseStatus != "all") {
            urlBuilder.addQueryParameter("response_status", responseStatus)
        }

        val jsonStr = executeGet(urlBuilder.build().toString(), authHeader)
        AdGuardParsers.parseQueryLog(jsonStr)
    }

    suspend fun loadQueryLogSettings(baseUrl: String, username: String, password: String): AdGuardQueryLogSettings =
        withContext(Dispatchers.IO) {
            AdGuardParsers.parseQueryLogSettings(executeGet("$baseUrl/control/querylog/config", Credentials.basic(username, password)))
        }

    suspend fun loadQueryLogClients(baseUrl: String, username: String, password: String): List<AdGuardLogDevice> =
        withContext(Dispatchers.IO) {
            AdGuardParsers.parseQueryLogClients(executeGet("$baseUrl/control/clients", Credentials.basic(username, password)))
        }

    suspend fun setProtection(
        baseUrl: String,
        username: String,
        password: String,
        enabled: Boolean,
        durationMs: Long? = null
    ): Unit = withContext(Dispatchers.IO) {
        val authHeader = Credentials.basic(username, password)
        val url = "$baseUrl/control/protection".toHttpUrlOrNull()
            ?: throw AdGuardError.HttpError(400, "Malformed URL")

        val payload = if (durationMs != null && !enabled) {
            """{"enabled":false,"duration":$durationMs}"""
        } else {
            """{"enabled":$enabled}"""
        }

        val body = payload.toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url(url)
            .post(body)
            .header("Authorization", authHeader)
            .header("Accept", "application/json")
            .build()

        executeWriteRequest(request)
    }

    suspend fun setSafeBrowsing(
        baseUrl: String,
        username: String,
        password: String,
        enabled: Boolean
    ): Unit = withContext(Dispatchers.IO) {
        val authHeader = Credentials.basic(username, password)
        val endpoint = if (enabled) "enable" else "disable"
        val url = "$baseUrl/control/safebrowsing/$endpoint".toHttpUrlOrNull()
            ?: throw AdGuardError.HttpError(400, "Malformed URL")

        // Note: The empty-body Content-Type rule is [UNVERIFIED] (no source was read). We omit Content-Type for empty bodies.
        val emptyBody = object : RequestBody() {
            override fun contentType() = null // No Content-Type header
            override fun writeTo(sink: okio.BufferedSink) {}
        }

        val request = Request.Builder()
            .url(url)
            .post(emptyBody)
            .header("Authorization", authHeader)
            .header("Accept", "application/json")
            .build()

        executeWriteRequest(request)
    }

    private fun executeWriteRequest(request: Request) {
        try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> Unit
                    401 -> throw AdGuardError.Unauthorized()
                    429 -> {
                        val retryAfter = response.header("Retry-After")?.trim()?.toIntOrNull()
                        throw AdGuardError.RateLimited(retryAfterSeconds = retryAfter)
                    }
                    else -> throw AdGuardError.HttpError(response.code, response.message)
                }
            }
        } catch (e: AdGuardError) {
            throw e
        } catch (e: Exception) {
            throw AdGuardError.Unreachable("Could not execute write request to AdGuard Home", e)
        }
    }
}
