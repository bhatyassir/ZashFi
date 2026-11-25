package com.shaazifi.app.core.adguard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

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
        "/status", "/stats", "/stats/config", "/querylog", "/querylog/config",
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
        "PUT" to "/safesearch/settings",
        "POST" to "/filtering/config", "POST" to "/filtering/add_url", "POST" to "/filtering/remove_url",
        "POST" to "/filtering/set_url", "POST" to "/filtering/refresh", "POST" to "/filtering/set_rules",
        "PUT" to "/blocked_services/update",
        "POST" to "/clients/add", "POST" to "/clients/delete",
        "POST" to "/access/set",
        "POST" to "/rewrite/add", "POST" to "/rewrite/delete", "PUT" to "/rewrite/settings/update",
        "POST" to "/cache_clear", "POST" to "/test_upstream_dns",
        "PUT" to "/querylog/config/update", "POST" to "/querylog_clear",
        "PUT" to "/stats/config/update", "POST" to "/stats_reset"
    )

    fun isAllowedRead(path: String) = path in READS
    fun isAllowedWrite(method: String, path: String) = (method to path) in WRITES
}

/** HTTP implementation over the app's guarded client (certificate pins, private addresses only). */
class HttpAdGuardCaller(
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val username: String,
    private val passwordProvider: () -> String?
) : AdGuardCaller {

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json".toMediaType()

    private fun auth(): String = Credentials.basic(username, passwordProvider() ?: throw AdGuardError.Unauthorized("Password required"))

    override suspend fun get(path: String, query: Map<String, String>): JsonElement = withContext(Dispatchers.IO) {
        check(AdGuardEndpoints.isAllowedRead(path)) { "AdGuard read not allowed: $path" }
        val url = "$baseUrl/control$path".toHttpUrlOrNull()?.newBuilder()?.apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }?.build() ?: throw AdGuardError.HttpError(400, "Malformed URL")
        val text = execute(Request.Builder().url(url).get().header("Authorization", auth()).header("Accept", "application/json").build())
        json.parseToJsonElement(text.ifBlank { "null" })
    }

    override suspend fun send(method: String, path: String, body: JsonElement?): JsonElement? = withContext(Dispatchers.IO) {
        check(AdGuardEndpoints.isAllowedWrite(method, path)) { "AdGuard write not allowed: $method $path" }
        val url = "$baseUrl/control$path".toHttpUrlOrNull() ?: throw AdGuardError.HttpError(400, "Malformed URL")
        val requestBody: RequestBody = body?.toString()?.toRequestBody(jsonType) ?: object : RequestBody() {
            override fun contentType() = null
            override fun writeTo(sink: okio.BufferedSink) {}
        }
        val text = execute(Request.Builder().url(url).method(method, requestBody).header("Authorization", auth()).header("Accept", "application/json").build())
        if (text.isBlank()) null else runCatching { json.parseToJsonElement(text) }.getOrNull()
    }

    private fun execute(request: Request): String = try {
        http.newCall(request).execute().use { response ->
            when (response.code) {
                200 -> response.body.string()
                401 -> throw AdGuardError.Unauthorized()
                429 -> throw AdGuardError.RateLimited(retryAfterSeconds = response.header("Retry-After")?.trim()?.toIntOrNull())
                else -> throw AdGuardError.HttpError(response.code, response.body.string().take(200).ifBlank { response.message })
            }
        }
    } catch (e: AdGuardError) {
        throw e
    } catch (e: Exception) {
        throw AdGuardError.Unreachable("Could not reach AdGuard Home", e)
    }
}
