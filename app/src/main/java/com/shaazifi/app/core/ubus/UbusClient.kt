package com.shaazifi.app.core.ubus

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable
data class UbusSession(
    val token: String,
    val timeoutSeconds: Int,
    val username: String = "root"
)

@Serializable
private data class UbusRpcRequest(
    val jsonrpc: String = "2.0",
    val id: Int,
    val method: String = "call",
    val params: JsonArray
)

/**
 * OpenWrt JSON-RPC client targeting `/ubus`.
 *
 * Note: for embedded OpenWrt routers (`uhttpd`/`rpcd`) that struggle with concurrent HTTP
 * requests, configure `Dispatcher().apply { maxRequestsPerHost = 1 }` on the supplied [client]
 * so RPC calls are serialized per host.
 */
class UbusClient(
    private val client: OkHttpClient
) {

    companion object {
        /** `session login` arguments (rpcd session.c: username, password, optional timeout in seconds). */
        fun loginArgs(username: String, password: String, timeoutSeconds: Int?) = buildJsonObject {
            put("username", username)
            put("password", password)
            if (timeoutSeconds != null) put("timeout", timeoutSeconds)
        }
    }

    private val requestId = AtomicInteger(1)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val rpcClient: OkHttpClient = client.newBuilder()
        .retryOnConnectionFailure(false)
        .build()

    private val refreshMutex = Mutex()
    private val refreshedTokens = HashMap<Pair<String, String>, String>()

    /**
     * Executes session.login with OpenWrt ubus.
     * Uses the all-zero token '00000000000000000000000000000000'.
     */
    suspend fun login(
        endpointUrl: String,
        username: String = "root",
        password: String,
        /** Seconds the router keeps the sign-in without use (rpcd default 300). */
        timeoutSeconds: Int? = null
    ): UbusSession = withContext(Dispatchers.IO) {
        val args = loginArgs(username, password, timeoutSeconds)

        val data = call(
            endpointUrl = endpointUrl,
            token = "00000000000000000000000000000000",
            ubusObject = "session",
            method = "login",
            args = args
        )

        val token = (data["ubus_rpc_session"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
        if (token.isNullOrBlank()) {
            throw UbusError.InvalidJson("Missing 'ubus_rpc_session' in login response: $data")
        }

        val timeout = (data["timeout"] as? JsonPrimitive)?.intOrNull ?: 300
        val userData = data["data"] as? JsonObject
        val user = (userData?.get("username") as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content ?: username

        UbusSession(token = token, timeoutSeconds = timeout, username = user)
    }

    /**
     * Invalids active session via session.destroy.
     */
    suspend fun destroySession(
        endpointUrl: String,
        token: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            call(
                endpointUrl = endpointUrl,
                token = token,
                ubusObject = "session",
                method = "destroy",
                args = buildJsonObject {}
            )
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Executes generic JSON-RPC call against /ubus.
     * If onSessionExpired is provided, automatically coordinates single-flight refresh via [refreshMutex]
     * and retries strictly ONCE with the refreshed token upon session expiry or access denied
     * (OpenWrt rpcd returns AccessDenied when a session has expired).
     * If the retry after refresh still receives AccessDenied, it throws [UbusError.AccessDenied]
     * with `isAclDenied = true` to distinguish missing rpcd ACL permissions from an expired session.
     */
    suspend fun call(
        endpointUrl: String,
        token: String,
        ubusObject: String,
        method: String,
        args: JsonElement = buildJsonObject {},
        onSessionExpired: (suspend () -> String)? = null
    ): JsonObject = withContext(Dispatchers.IO) {
        try {
            executeCall(endpointUrl, token, ubusObject, method, args)
        } catch (e: UbusError) {
            if (onSessionExpired == null || (e !is UbusError.SessionExpired && e !is UbusError.AccessDenied)) {
                throw e
            }

            val refreshKey = normalizeUbusUrl(endpointUrl) to token
            val newToken = refreshMutex.withLock {
                refreshedTokens[refreshKey] ?: onSessionExpired().also { fresh ->
                    if (fresh.isNotBlank() && fresh != token) {
                        if (refreshedTokens.size >= 32) refreshedTokens.clear()
                        refreshedTokens[refreshKey] = fresh
                    }
                }
            }

            try {
                // Retry strictly once with the newly refreshed token
                executeCall(endpointUrl, newToken, ubusObject, method, args)
            } catch (retryError: UbusError) {
                if (retryError is UbusError.SessionExpired || retryError is UbusError.AccessDenied) {
                    refreshMutex.withLock {
                        if (refreshedTokens[refreshKey] == newToken) {
                            refreshedTokens.remove(refreshKey)
                        }
                    }
                }
                if (retryError is UbusError.AccessDenied) {
                    throw UbusError.AccessDenied(
                        code = retryError.code,
                        message = "ACL permission denied for '$ubusObject.$method' (code: ${retryError.code})",
                        isAclDenied = true,
                        ubusObject = ubusObject,
                        method = method
                    )
                }
                throw retryError
            }
        }
    }

    private data class RawResponse(val code: Int, val body: String)

    /**
     * Reads the whole response inside OkHttp's callback so call cancellation can abort a slow
     * body (e.g. a router streaming its reply): `Call.cancel()` closes the exchange and the
     * blocked read fails promptly. A coroutine cancelled while `response.body.string()` runs on
     * the IO dispatcher could not otherwise be interrupted.
     */
    private suspend fun Call.awaitBody(): RawResponse = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!continuation.isActive) return@use
                    try {
                        val body = resp.body.string()
                        if (continuation.isActive) continuation.resume(RawResponse(resp.code, body))
                    } catch (e: Exception) {
                        // Cancellation closes the exchange underneath the read; it is not a call failure.
                        if (continuation.isActive) {
                            continuation.resumeWithException(e as? IOException ?: IOException(e))
                        }
                    }
                }
            }
        })
    }

    private suspend fun executeCall(
        endpointUrl: String,
        token: String,
        ubusObject: String,
        method: String,
        args: JsonElement
    ): JsonObject {
        val targetUrl = normalizeUbusUrl(endpointUrl)
        val id = requestId.getAndIncrement()

        val params = buildJsonArray {
            add(token)
            add(ubusObject)
            add(method)
            add(args)
        }

        val rpcRequest = UbusRpcRequest(id = id, params = params)
        val requestBody = json.encodeToString(rpcRequest)

        val request = Request.Builder()
            .url(targetUrl)
            .post(requestBody.toRequestBody(jsonMediaType))
            .build()

        val responseBody = try {
            val raw = rpcClient.newCall(request).awaitBody()
            if (raw.code !in 200..299) throw UbusError.HttpError(raw.code, raw.body)
            raw.body
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            if (e is UbusError) throw e
            throw UbusError.NetworkError(e)
        }

        return parseUbusResponse(responseBody, ubusObject, method)
    }

    private fun parseUbusResponse(
        rawJson: String,
        ubusObject: String? = null,
        method: String? = null
    ): JsonObject {
        val root = try {
            json.parseToJsonElement(rawJson).jsonObject
        } catch (_: Exception) {
            throw UbusError.InvalidJson(rawJson)
        }

        // Check top-level JSON-RPC error (treat "error": null / JsonNull as no error)
        val errElement = root["error"]
        if (errElement != null && errElement !is JsonNull) {
            val errObj = (errElement as? JsonObject)
                ?: throw UbusError.InvalidJson("Malformed 'error' field in response: $rawJson")
            val code = (errObj["code"] as? JsonPrimitive)?.intOrNull ?: -1
            val msg = (errObj["message"] as? JsonPrimitive)?.contentOrNull ?: "Unknown RPC error"
            if (code == -32002) {
                throw UbusError.AccessDenied(
                    code = code,
                    message = msg,
                    ubusObject = ubusObject,
                    method = method
                )
            }
            throw UbusError.RpcError(code, msg)
        }

        // OpenWrt returns result: [ status_code, optional_data_object ]
        val resultArray = root["result"] as? JsonArray
            ?: throw UbusError.InvalidJson("Response missing 'result' array: $rawJson")

        if (resultArray.isEmpty()) {
            throw UbusError.InvalidJson("Empty 'result' array: $rawJson")
        }

        val statusCode = try {
            (resultArray[0] as? JsonPrimitive)?.intOrNull
        } catch (_: Exception) {
            null
        } ?: throw UbusError.InvalidJson("Invalid non-integer status code in 'result' array: $rawJson")

        when (statusCode) {
            0 -> {
                val data = resultArray.getOrNull(1)
                return if (data is JsonObject) data else buildJsonObject {}
            }
            4 -> throw UbusError.SessionExpired(4)
            5 -> throw UbusError.ObjectNotFound(5)
            6 -> throw UbusError.AccessDenied(
                code = 6,
                ubusObject = ubusObject,
                method = method
            )
            else -> throw UbusError.RpcError(statusCode, "Ubus call failed with code $statusCode")
        }
    }

    fun normalizeUbusUrl(url: String): String {
        val trimmed = url.trim()
        val parsed = trimmed.toHttpUrlOrNull()
        if (parsed != null) {
            var path = parsed.encodedPath.trimEnd('/')
            if (path.endsWith("/untangled")) {
                path = path.removeSuffix("/untangled")
            }
            val finalPath = when {
                path.isEmpty() -> "/ubus"
                path == "/ubus" || path.endsWith("/ubus") -> path
                else -> "$path/ubus"
            }
            return parsed.newBuilder()
                .encodedPath(finalPath)
                .query(null)
                .fragment(null)
                .build()
                .toString()
        }
        var withoutSlash = trimmed.removeSuffix("/")
        if (withoutSlash.endsWith("/untangled")) {
            withoutSlash = withoutSlash.removeSuffix("/untangled")
        }
        return if (withoutSlash.endsWith("/ubus")) withoutSlash else "$withoutSlash/ubus"
    }
}
