package com.shaazifi.app.core.ubus

import java.io.IOException

sealed class UbusError(message: String? = null, cause: Throwable? = null) : IOException(message, cause) {

    class NetworkError(cause: Throwable) : UbusError("Network failure communicating with router: ${cause.message}", cause)

    class HttpError(val statusCode: Int, val body: String) : UbusError("HTTP error $statusCode from router: $body")

    class InvalidJson(val rawResponse: String) : UbusError("Malformed JSON response from router: $rawResponse")

    /**
     * Code 6: UBUS_STATUS_PERMISSION_DENIED
     */
    class AccessDenied(val code: Int = 6, message: String = "Access denied / invalid credentials (code: $code)") : UbusError(message)

    /**
     * Code 4: UBUS_STATUS_NOT_FOUND or expired session
     */
    class SessionExpired(val code: Int = 4, message: String = "Session expired or invalid (code: $code)") : UbusError(message)

    /**
     * Code 5: UBUS_STATUS_INVALID_ARGUMENT or object not found
     */
    class ObjectNotFound(val code: Int = 5, message: String = "Ubus object or method not found (code: $code)") : UbusError(message)

    class RpcError(val code: Int, message: String) : UbusError("Ubus RPC error (code: $code): $message")
}
