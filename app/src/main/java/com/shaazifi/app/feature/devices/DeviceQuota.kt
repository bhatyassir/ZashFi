/*
 * Copyright (C) 2026 ZashFi contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.shaazifi.app.feature.devices

import java.util.Locale

private val quotaMacPattern = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")
private const val QUOTA_DAY_MS = 86_400_000L
private const val MAX_LIMIT_MB = 1_000_000
private const val WARNING_PCT = 80
private const val MAX_QUOTAS = 100

/** One device's daily data allowance. [windowStartMs] is an epoch timestamp on the UTC day grid. */
data class Quota(
    val mac: String,
    val name: String,
    val limitMb: Int,
    val windowStartMs: Long = 0L
) {
    init {
        require(quotaMacPattern.matches(mac)) { "Invalid device MAC address." }
        require(limitMb in 1..MAX_LIMIT_MB) { "Data limit must be between 1 and 1,000,000 MB." }
        require(windowStartMs >= 0L) { "The quota window cannot start before the epoch." }
    }
}

/** Snapshot of a quota at one instant; [pct] is capped at 999. */
data class QuotaStatus(
    val quota: Quota,
    val usedMb: Int,
    val pct: Int,
    val over: Boolean,
    val warning: Boolean
)

/** True when the quota's window started on the same UTC day as [nowMs]. */
fun isCurrentWindow(quota: Quota, nowMs: Long): Boolean =
    Math.floorDiv(quota.windowStartMs, QUOTA_DAY_MS) == Math.floorDiv(nowMs, QUOTA_DAY_MS)

/** Usage outside today's UTC window is deliberately forgotten. */
fun evaluate(quota: Quota, usedBytes: Long, nowMs: Long): QuotaStatus {
    val usedMb = if (!isCurrentWindow(quota, nowMs) || usedBytes <= 0L) 0
    else minOf(Int.MAX_VALUE.toLong(), usedBytes / 1_000_000L).toInt()
    val pct = minOf(999L, usedMb.toLong() * 100L / quota.limitMb.toLong()).toInt()
    return QuotaStatus(
        quota = quota,
        usedMb = usedMb,
        pct = pct,
        over = pct >= 100,
        warning = pct in WARNING_PCT until 100
    )
}

/** Protect the separators and the escape marker before splitting. */
private fun escapeQuotaField(value: String): String =
    value.replace("%", "%25").replace("|", "%7C").replace(";", "%3B")

private fun unescapeQuotaField(value: String): String =
    value.replace("%3B", ";").replace("%7C", "|").replace("%25", "%")

/** Compact form: one `mac|name|limitMb|windowStartMs` entry per `;`. */
fun encode(quotas: List<Quota>): String = quotas.joinToString(";") { quota ->
    listOf(
        quota.mac,
        escapeQuotaField(quota.name),
        quota.limitMb.toString(),
        quota.windowStartMs.toString()
    ).joinToString("|")
}

private fun parseQuotaEntry(entry: String): Quota? {
    val parts = entry.split('|')
    if (parts.size != 4) return null
    val (rawMac, rawName, rawLimit, rawWindow) = parts
    if (!quotaMacPattern.matches(rawMac)) return null
    val limitMb = rawLimit.toIntOrNull()?.takeIf { it in 1..MAX_LIMIT_MB } ?: return null
    val windowStartMs = rawWindow.toLongOrNull()?.takeIf { it >= 0L } ?: return null
    return runCatching { Quota(rawMac, unescapeQuotaField(rawName), limitMb, windowStartMs) }.getOrNull()
}

/** Skips corrupt entries, keeps the last quota per MAC, and caps the result at 100 entries. */
fun decode(value: String?): List<Quota> {
    if (value.isNullOrEmpty()) return emptyList()
    val latest = LinkedHashMap<String, Quota>()
    value.split(';').mapNotNull(::parseQuotaEntry).forEach { quota ->
        val key = quota.mac.uppercase(Locale.ROOT)
        latest.remove(key) // re-insert so the newest entry also occupies the newest position
        latest[key] = quota
    }
    return latest.values.toList().takeLast(MAX_QUOTAS)
}

/** Short notification line: device name and usage only, never an address. */
fun alertText(status: QuotaStatus): String {
    val usage = "${status.usedMb} MB of ${status.quota.limitMb} MB today (${status.pct}%)"
    return when {
        status.over -> "Data limit reached: ${status.quota.name} used $usage."
        status.warning -> "Data warning: ${status.quota.name} used $usage."
        else -> "${status.quota.name} used $usage."
    }
}

/** Epoch milliseconds of the next UTC midnight, strictly after [nowMs]. */
fun dailyResetAtMs(nowMs: Long): Long {
    val startOfToday = Math.floorDiv(nowMs, QUOTA_DAY_MS) * QUOTA_DAY_MS
    return startOfToday + QUOTA_DAY_MS
}
