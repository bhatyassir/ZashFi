package com.shaazifi.app.core.adguard

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Calls AdGuard Home's `/control` API. Only the paths in [AdGuardEndpoints] are allowed. */
interface AdGuardCaller {
    suspend fun get(path: String, query: Map<String, String> = emptyMap()): JsonElement
    suspend fun send(method: String, path: String, body: JsonElement? = null): JsonElement?
}

/**
 * The AdGuard Home calls the app may make. Paths and methods from AdGuard Home's
 * openapi/openapi.yaml (master, read 2026-11-17). Deliberately absent: software update, DHCP,
 * encryption (TLS), DNS server configuration, install, login/logout, profile.
 */
object AdGuardEndpoints {
    val READS: Set<String> = setOf(
        "/status", "/stats", "/stats/config", "/stats_info", "/querylog", "/querylog/config", "/querylog_info",
        "/filtering/status", "/filtering/check_host",
        "/safebrowsing/status", "/parental/status", "/safesearch/status",
        "/blocked_services/all", "/blocked_services/get",
        "/clients", "/access/list",
        "/rewrite/list", "/rewrite/settings",
        "/dns_info"
    )

    val WRITES: Set<Pair<String, String>> = setOf(
        "POST" to "/protection",
        "POST" to "/safebrowsing/enable", "POST" to "/safebrowsing/disable",
        "POST" to "/parental/enable", "POST" to "/parental/disable",
        "PUT" to "/safesearch/settings", "POST" to "/safesearch/enable", "POST" to "/safesearch/disable",
        "POST" to "/filtering/config", "POST" to "/filtering/add_url", "POST" to "/filtering/remove_url",
        "POST" to "/filtering/set_url", "POST" to "/filtering/refresh", "POST" to "/filtering/set_rules",
        "PUT" to "/blocked_services/update",
        "POST" to "/clients/add", "POST" to "/clients/delete",
        "POST" to "/access/set",
        "POST" to "/rewrite/add", "POST" to "/rewrite/delete", "PUT" to "/rewrite/settings/update",
        "POST" to "/cache_clear", "POST" to "/test_upstream_dns",
        "PUT" to "/querylog/config/update", "POST" to "/querylog_config", "POST" to "/querylog_clear",
        "PUT" to "/stats/config/update", "POST" to "/stats_config", "POST" to "/stats_reset"
    )

    fun isAllowedRead(path: String) = path in READS
    fun isAllowedWrite(method: String, path: String) = (method to path) in WRITES
}

/** Shared HTTP execution helpers for [HttpAdGuardCaller] and [AdGuardClient]. */
internal object AdGuardHttp {

    private const val ERROR_BODY_PEEK_BYTES = 2048L
    private const val ERROR_BODY_MAX_CHARS = 200

    fun normalizeBaseUrl(baseUrl: String): String = baseUrl.trim().trimEnd('/')

    fun basicAuth(username: String, password: String): String =
        Credentials.basic(username, password, Charsets.UTF_8)

    fun isCleartextHttp(baseUrl: String): Boolean =
        baseUrl.trim().startsWith("http://", ignoreCase = true)

    fun emptyBodyWithoutContentType(): RequestBody = object : RequestBody() {
        override fun contentType() = null
        override fun writeTo(sink: okio.BufferedSink) {}
    }

    suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) {
                    continuation.resume(response)
                } else {
                    response.close()
                }
            }
        })
    }

    suspend fun execute(client: OkHttpClient, request: Request): String {
        try {
            return client.newCall(request).await().use { response ->
                when (response.code) {
                    in 200..204 -> response.body.string()
                    401 -> throw AdGuardError.Unauthorized()
                    429 -> throw AdGuardError.RateLimited(
                        retryAfterSeconds = AdGuardError.parseRetryAfterSeconds(response.header("Retry-After"))
                    )
                    else -> {
                        val detail = runCatching {
                            response.peekBody(ERROR_BODY_PEEK_BYTES).string().trim().take(ERROR_BODY_MAX_CHARS)
                        }.getOrDefault("").ifBlank { response.message }
                        throw AdGuardError.HttpError(response.code, detail)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AdGuardError) {
            throw e
        } catch (e: IOException) {
            throw AdGuardError.Unreachable("Could not reach AdGuard Home", e)
        } catch (e: Exception) {
            throw AdGuardError.Unreachable("Could not reach AdGuard Home", e)
        }
    }
}

/** HTTP implementation over the app's guarded client (certificate pins, private addresses only). */
class HttpAdGuardCaller(
    private val http: OkHttpClient,
    baseUrl: String,
    private val username: String,
    private val passwordProvider: () -> String?
) : AdGuardCaller {

    private val normalizedBase = AdGuardHttp.normalizeBaseUrl(baseUrl)
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json".toMediaType()

    private fun auth(): String = AdGuardHttp.basicAuth(
        username,
        passwordProvider() ?: throw AdGuardError.Unauthorized("Password required")
    )

    override suspend fun get(path: String, query: Map<String, String>): JsonElement = withContext(Dispatchers.IO) {
        check(AdGuardEndpoints.isAllowedRead(path)) { "AdGuard read not allowed: $path" }
        val url = "$normalizedBase/control$path".toHttpUrlOrNull()?.newBuilder()?.apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }?.build() ?: throw AdGuardError.HttpError(400, "Malformed URL")
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", auth())
            .header("Accept", "application/json")
            .build()
        val text = AdGuardHttp.execute(http, request)
        json.parseToJsonElement(text.ifBlank { "null" })
    }

    override suspend fun send(method: String, path: String, body: JsonElement?): JsonElement? = withContext(Dispatchers.IO) {
        check(AdGuardEndpoints.isAllowedWrite(method, path)) { "AdGuard write not allowed: $method $path" }
        val url = "$normalizedBase/control$path".toHttpUrlOrNull() ?: throw AdGuardError.HttpError(400, "Malformed URL")
        val requestBody: RequestBody = body?.toString()?.toRequestBody(jsonType)
            ?: AdGuardHttp.emptyBodyWithoutContentType()
        val request = Request.Builder()
            .url(url)
            .method(method, requestBody)
            .header("Authorization", auth())
            .header("Accept", "application/json")
            .build()
        val text = AdGuardHttp.execute(http, request)
        if (text.isBlank()) null else runCatching { json.parseToJsonElement(text) }.getOrNull()
    }
}
