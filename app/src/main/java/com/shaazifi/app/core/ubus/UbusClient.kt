package com.shaazifi.app.core.ubus

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

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

        val token = data["ubus_rpc_session"]?.jsonPrimitive?.content
        if (token.isNullOrBlank()) {
            throw UbusError.InvalidJson("Missing 'ubus_rpc_session' in login response: $data")
        }

        val timeout = data["timeout"]?.jsonPrimitive?.intOrNull ?: 300
        val userData = data["data"]?.jsonObject
        val user = userData?.get("username")?.jsonPrimitive?.content ?: username

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
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Executes generic JSON-RPC call against /ubus.
     * If onSessionExpired is provided, automatically retries strictly ONCE with the refreshed token
     * upon session expiry or access denied (OpenWrt rpcd returns AccessDenied when a session has expired).
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
            if (onSessionExpired != null && (e is UbusError.SessionExpired || e is UbusError.AccessDenied)) {
                val newToken = onSessionExpired()
                // Retry strictly once with the newly refreshed token
                executeCall(endpointUrl, newToken, ubusObject, method, args)
            } else {
                throw e
            }
        }
    }

    private fun executeCall(
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
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body.string()
                    throw UbusError.HttpError(response.code, body)
                }
                response.body.string()
            }
        } catch (e: IOException) {
            if (e is UbusError) throw e
            throw UbusError.NetworkError(e)
        }

        return parseUbusResponse(responseBody)
    }

    private fun parseUbusResponse(rawJson: String): JsonObject {
        val root = try {
            json.parseToJsonElement(rawJson).jsonObject
        } catch (_: Exception) {
            throw UbusError.InvalidJson(rawJson)
        }

        // Check top-level JSON-RPC error
        if (root.containsKey("error") && root["error"] != null) {
            val errObj = root["error"]?.jsonObject
            val code = errObj?.get("code")?.jsonPrimitive?.intOrNull ?: -1
            val msg = errObj?.get("message")?.jsonPrimitive?.content ?: "Unknown RPC error"
            if (code == -32002) {
                throw UbusError.AccessDenied(code, msg)
            }
            throw UbusError.RpcError(code, msg)
        }

        // OpenWrt returns result: [ status_code, optional_data_object ]
        val resultArray = root["result"] as? JsonArray
            ?: throw UbusError.InvalidJson("Response missing 'result' array: $rawJson")

        if (resultArray.isEmpty()) {
            throw UbusError.InvalidJson("Empty 'result' array: $rawJson")
        }

        val statusCode = resultArray[0].jsonPrimitive.int
        when (statusCode) {
            0 -> {
                val data = resultArray.getOrNull(1)
                return if (data is JsonObject) data else buildJsonObject {}
            }
            4 -> throw UbusError.SessionExpired(4)
            5 -> throw UbusError.ObjectNotFound(5)
            6 -> throw UbusError.AccessDenied(6)
            else -> throw UbusError.RpcError(statusCode, "Ubus call failed with code $statusCode")
        }
    }

    fun normalizeUbusUrl(url: String): String {
        val trimmed = url.trim()
        val parsed = trimmed.toHttpUrlOrNull()
        return if (parsed != null) {
            parsed.newBuilder().encodedPath("/ubus").build().toString()
        } else {
            val withoutSlash = trimmed.removeSuffix("/")
            if (withoutSlash.endsWith("/ubus")) withoutSlash else "$withoutSlash/ubus"
        }
    }
}
