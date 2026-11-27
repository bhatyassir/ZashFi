package com.shaazifi.app.core.adguard

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

sealed class AdGuardError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Unauthorized(message: String = "AdGuard username or password incorrect") : AdGuardError(message)
    class RateLimited(
        val retryAfterSeconds: Int? = null,
        message: String = when {
            retryAfterSeconds == null -> "Too many failed login attempts. Please wait a while."
            retryAfterSeconds <= 0 -> "Too many failed login attempts. Please try again shortly."
            else -> {
                val mins = (retryAfterSeconds + 59) / 60
                if (mins > 1) {
                    "Too many failed login attempts. Please wait $mins minutes."
                } else {
                    "Too many failed login attempts. Please wait $retryAfterSeconds seconds."
                }
            }
        }
    ) : AdGuardError(message)
    class NotAdGuardHome(message: String = "Target endpoint is not an AdGuard Home instance") : AdGuardError(message)
    class Unreachable(message: String = "Could not reach AdGuard Home", cause: Throwable? = null) : AdGuardError(message, cause)
    class HttpError(val statusCode: Int, message: String) : AdGuardError("HTTP $statusCode: $message")

    companion object {
        /**
         * Parses an HTTP `Retry-After` header containing either non-negative integer delay-seconds
         * or an RFC 1123 HTTP-date, returning delay in seconds (0 if already elapsed) or null.
         */
        fun parseRetryAfterSeconds(
            headerValue: String?,
            nowEpochMillis: Long = System.currentTimeMillis()
        ): Int? {
            val trimmed = headerValue?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            trimmed.toLongOrNull()?.let { seconds ->
                return seconds.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            }
            return try {
                val targetMillis = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant()
                    .toEpochMilli()
                val deltaMillis = targetMillis - nowEpochMillis
                if (deltaMillis <= 0L) 0
                else ((deltaMillis + 999L) / 1000L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            } catch (_: Exception) {
                null
            }
        }
    }
}
