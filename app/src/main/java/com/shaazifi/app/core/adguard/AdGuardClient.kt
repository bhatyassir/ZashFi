package com.shaazifi.app.core.adguard

import com.shaazifi.app.core.adguard.AdGuardHttp.await
import com.shaazifi.app.feature.adguard.AdGuardDashboard
import com.shaazifi.app.feature.adguard.AdGuardFilteringStatus
import com.shaazifi.app.feature.adguard.AdGuardLogDevice
import com.shaazifi.app.feature.adguard.AdGuardParsers
import com.shaazifi.app.feature.adguard.AdGuardQueryLog
import com.shaazifi.app.feature.adguard.AdGuardQueryLogSettings
import com.shaazifi.app.feature.adguard.AdGuardSafeSearchStatus
import com.shaazifi.app.feature.adguard.AdGuardStatsConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

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
 * 1. An unauthenticated probe of GET /control/status is performed before sending Basic Auth (unless [alreadyProbed]).
 * 2. Credentials are never sent to unverified endpoints (e.g. router web server on port 80).
 * 3. Network calls pass through AddressGuard (private/Tailscale addresses only).
 * 4. Passwords are held in memory only for the request and never logged.
 * 5. State-changing empty-body POSTs (safebrowsing/parental) omit Content-Type header.
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
        val normalizedBase = AdGuardHttp.normalizeBaseUrl(baseUrl)
        val parsed = normalizedBase.toHttpUrlOrNull()
        val targetHostPort = if (parsed != null) {
            "${parsed.host}:${parsed.port}"
        } else {
            normalizedBase.removePrefix("http://").removePrefix("https://").substringBefore("/")
        }

        val url = "$normalizedBase/control/status".toHttpUrlOrNull()
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
            client.newCall(request).await().use { response ->
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
                        // AdGuard Home answers an unauthenticated /control/* request with a bare 401
                        // (no WWW-Authenticate header, empty body) or Basic realm="AdGuard Home".
                        // Reject 401s that carry non-AdGuard indicators (non-AdGuard WWW-Authenticate
                        // realm, HTML/XML Content-Type, known router Server headers, or non-JSON text).
                        val isPlausibleAdGuard = isPlausibleAdGuard401(response)
                        AdGuardProbeResult(
                            isAdGuard = isPlausibleAdGuard,
                            diagnostic = AdGuardProbeDiagnostic(
                                targetHostPort = targetHostPort,
                                httpCodeOrException = if (isPlausibleAdGuard) "HTTP 401" else "HTTP 401 (Non-AdGuard)",
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
        } catch (e: CancellationException) {
            throw e
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

    private fun isPlausibleAdGuard401(response: Response): Boolean {
        val wwwAuth = response.header("WWW-Authenticate")?.trim()
        if (!wwwAuth.isNullOrEmpty() && !wwwAuth.contains("adguard", ignoreCase = true)) {
            return false
        }

        val serverHeader = response.header("Server")?.trim().orEmpty()
        if (NON_ADGUARD_SERVERS.any { serverHeader.contains(it, ignoreCase = true) }) {
            return false
        }

        val contentType = response.header("Content-Type")?.trim().orEmpty()
        if (contentType.contains("html", ignoreCase = true) || contentType.contains("xml", ignoreCase = true)) {
            return false
        }

        val bodyPreview = runCatching { response.peekBody(1024).string().trim() }.getOrDefault("")
        if (bodyPreview.isEmpty()) {
            return true
        }
        if (bodyPreview.startsWith("<") || !bodyPreview.startsWith("{") || !bodyPreview.endsWith("}")) {
            return false
        }
        return true
    }

    /**
     * Probes the endpoint unauthenticated to verify it is recognisably AdGuard Home.
     * Never sends credentials in this check.
     */
    suspend fun probeEndpoint(baseUrl: String): Boolean = probeEndpointDetailed(baseUrl).isAdGuard

    private suspend fun executeGet(
        urlStr: String,
        authHeader: String
    ): String {
        val url = urlStr.toHttpUrlOrNull() ?: throw AdGuardError.HttpError(400, "Malformed URL")
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", authHeader)
            .header("Accept", "application/json")
            .build()
        return AdGuardHttp.execute(client, request)
    }

    private suspend inline fun <T> optionalDashboardCall(
        fallback: T,
        block: () -> T
    ): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: AdGuardError.Unauthorized) {
        throw e
    } catch (e: AdGuardError.RateLimited) {
        throw e
    } catch (_: Exception) {
        fallback
    }

    suspend fun loadDashboard(
        baseUrl: String,
        username: String,
        password: String,
        alreadyProbed: Boolean = false
    ): AdGuardDashboard = withContext(Dispatchers.IO) {
        val normalizedBase = AdGuardHttp.normalizeBaseUrl(baseUrl)

        // Enforce unauthenticated discovery gate unless caller already probed this endpoint
        if (!alreadyProbed && !probeEndpoint(normalizedBase)) {
            throw AdGuardError.NotAdGuardHome("Endpoint $normalizedBase is not recognisably AdGuard Home")
        }

        val authHeader = AdGuardHttp.basicAuth(username, password)
        val isCleartext = AdGuardHttp.isCleartextHttp(normalizedBase)

        // Verify credentials with /control/status first before fanning out parallel GETs
        val status = AdGuardParsers.parseStatus(executeGet("$normalizedBase/control/status", authHeader))

        coroutineScope {
            val statsDeferred = async {
                AdGuardParsers.parseStats(executeGet("$normalizedBase/control/stats", authHeader))
            }
            val statsConfigDeferred = async {
                optionalDashboardCall(AdGuardStatsConfig(intervalKnown = false)) {
                    AdGuardParsers.parseStatsConfig(executeGet("$normalizedBase/control/stats/config", authHeader))
                }
            }
            val filteringDeferred = async {
                optionalDashboardCall(AdGuardFilteringStatus()) {
                    AdGuardParsers.parseFilteringStatus(executeGet("$normalizedBase/control/filtering/status", authHeader))
                }
            }
            val safeSearchDeferred = async {
                optionalDashboardCall(AdGuardSafeSearchStatus()) {
                    AdGuardParsers.parseSafeSearchStatus(executeGet("$normalizedBase/control/safesearch/status", authHeader))
                }
            }
            val safeBrowsingDeferred = async {
                optionalDashboardCall<Boolean?>(null) {
                    AdGuardParsers.parseEnabledFlag(executeGet("$normalizedBase/control/safebrowsing/status", authHeader))
                }
            }
            val parentalDeferred = async {
                optionalDashboardCall<Boolean?>(null) {
                    AdGuardParsers.parseEnabledFlag(executeGet("$normalizedBase/control/parental/status", authHeader))
                }
            }

            AdGuardDashboard(
                status = status,
                stats = statsDeferred.await(),
                statsConfig = statsConfigDeferred.await(),
                filtering = filteringDeferred.await(),
                safeSearch = safeSearchDeferred.await(),
                isCleartextHttp = isCleartext,
                safeBrowsingEnabled = safeBrowsingDeferred.await(),
                parentalEnabled = parentalDeferred.await()
            )
        }
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
        val normalizedBase = AdGuardHttp.normalizeBaseUrl(baseUrl)
        val authHeader = AdGuardHttp.basicAuth(username, password)
        val urlBuilder = "$normalizedBase/control/querylog".toHttpUrlOrNull()?.newBuilder()
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
            val normalizedBase = AdGuardHttp.normalizeBaseUrl(baseUrl)
            val authHeader = AdGuardHttp.basicAuth(username, password)
            val jsonStr = try {
                executeGet("$normalizedBase/control/querylog/config", authHeader)
            } catch (e: AdGuardError.HttpError) {
                // Legacy AdGuard Home v0.107.0 - v0.107.29 fallback
                if (e.statusCode == 404 || e.statusCode == 405) {
                    executeGet("$normalizedBase/control/querylog_info", authHeader)
                } else {
                    throw e
                }
            }
            AdGuardParsers.parseQueryLogSettings(jsonStr)
        }

    suspend fun loadQueryLogClients(baseUrl: String, username: String, password: String): List<AdGuardLogDevice> =
        withContext(Dispatchers.IO) {
            val normalizedBase = AdGuardHttp.normalizeBaseUrl(baseUrl)
            val authHeader = AdGuardHttp.basicAuth(username, password)
            AdGuardParsers.parseQueryLogClients(executeGet("$normalizedBase/control/clients", authHeader))
        }

    suspend fun setProtection(
        baseUrl: String,
        username: String,
        password: String,
        enabled: Boolean,
        durationMs: Long? = null
    ): Unit = withContext(Dispatchers.IO) {
        val normalizedBase = AdGuardHttp.normalizeBaseUrl(baseUrl)
        val authHeader = AdGuardHttp.basicAuth(username, password)
        val url = "$normalizedBase/control/protection".toHttpUrlOrNull()
            ?: throw AdGuardError.HttpError(400, "Malformed URL")

        val payload = if (durationMs != null && !enabled) {
            """{"enabled":false,"duration":$durationMs}"""
        } else {
            """{"enabled":$enabled}"""
        }

        val request = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(jsonMediaType))
            .header("Authorization", authHeader)
            .header("Accept", "application/json")
            .build()

        try {
            AdGuardHttp.execute(client, request)
        } catch (e: AdGuardError.HttpError) {
            // Legacy AdGuard Home v0.107.0 - v0.107.29 fallback
            if (e.statusCode == 404 || e.statusCode == 405) {
                val legacyUrl = "$normalizedBase/control/dns_config".toHttpUrlOrNull()
                    ?: throw AdGuardError.HttpError(400, "Malformed URL")
                val legacyPayload = """{"protection_enabled":$enabled}"""
                val legacyRequest = Request.Builder()
                    .url(legacyUrl)
                    .post(legacyPayload.toRequestBody(jsonMediaType))
                    .header("Authorization", authHeader)
                    .header("Accept", "application/json")
                    .build()
                AdGuardHttp.execute(client, legacyRequest)
            } else {
                throw e
            }
        }
    }

    suspend fun setSafeBrowsing(
        baseUrl: String,
        username: String,
        password: String,
        enabled: Boolean
    ): Unit = setToggleFeature(baseUrl, username, password, "safebrowsing", enabled)

    suspend fun setParental(
        baseUrl: String,
        username: String,
        password: String,
        enabled: Boolean
    ): Unit = setToggleFeature(baseUrl, username, password, "parental", enabled)

    private suspend fun setToggleFeature(
        baseUrl: String,
        username: String,
        password: String,
        feature: String,
        enabled: Boolean
    ): Unit = withContext(Dispatchers.IO) {
        val normalizedBase = AdGuardHttp.normalizeBaseUrl(baseUrl)
        val authHeader = AdGuardHttp.basicAuth(username, password)
        val endpoint = if (enabled) "enable" else "disable"
        val url = "$normalizedBase/control/$feature/$endpoint".toHttpUrlOrNull()
            ?: throw AdGuardError.HttpError(400, "Malformed URL")

        val request = Request.Builder()
            .url(url)
            .post(AdGuardHttp.emptyBodyWithoutContentType())
            .header("Authorization", authHeader)
            .header("Accept", "application/json")
            .build()

        AdGuardHttp.execute(client, request)
    }

    private companion object {
        val NON_ADGUARD_SERVERS = listOf("uhttpd", "boa", "luci", "lighttpd", "mikrotik")
    }
}
