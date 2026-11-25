package com.shaazifi.app.core.adguard

sealed class AdGuardError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Unauthorized(message: String = "AdGuard username or password incorrect") : AdGuardError(message)
    class RateLimited(
        val retryAfterSeconds: Int? = null,
        message: String = if (retryAfterSeconds != null) {
            val mins = (retryAfterSeconds + 59) / 60
            if (mins > 1) {
                "Too many failed login attempts. Please wait $mins minutes."
            } else {
                "Too many failed login attempts. Please wait $retryAfterSeconds seconds."
            }
        } else {
            "Too many failed login attempts. Please wait a while."
        }
    ) : AdGuardError(message)
    class NotAdGuardHome(message: String = "Target endpoint is not an AdGuard Home instance") : AdGuardError(message)
    class Unreachable(message: String = "Could not reach AdGuard Home", cause: Throwable? = null) : AdGuardError(message, cause)
    class HttpError(val statusCode: Int, message: String) : AdGuardError("HTTP $statusCode: $message")
}
