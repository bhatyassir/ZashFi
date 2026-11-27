package com.shaazifi.app.feature.adguard

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.time.OffsetDateTime
import kotlin.math.roundToInt

enum class AdGuardQueryEntry { LIVE, BLOCKED_HISTORY, DEVICES }

@Serializable
data class AdGuardStatus(
    val version: String = "unknown",
    val protectionEnabled: Boolean = false,
    val protectionDisabledDurationMs: Long = 0L,
    val dnsAddresses: List<String> = emptyList(),
    val dnsPort: Int = 53,
    val httpPort: Int = 3000,
    val running: Boolean = true
)

@Serializable
data class AdGuardStats(
    val timeUnits: String = "hours",
    val numDnsQueries: Long = 0L,
    val numBlockedFiltering: Long = 0L,
    val numReplacedSafebrowsing: Long = 0L,
    val numReplacedSafesearch: Long = 0L,
    val numReplacedParental: Long = 0L,
    val avgProcessingTimeSeconds: Double = 0.0,
    val dnsQueries: List<Int> = emptyList(),
    val topQueriedDomains: List<Pair<String, Long>> = emptyList(),
    val topBlockedDomains: List<Pair<String, Long>> = emptyList(),
    val topClients: List<Pair<String, Long>> = emptyList()
) {
    val blockedPercentage: Double
        get() = if (numDnsQueries > 0L) {
            (numBlockedFiltering.toDouble() / numDnsQueries.toDouble()) * 100.0
        } else {
            0.0
        }

    val avgProcessingTimeMs: Double
        get() = avgProcessingTimeSeconds * 1000.0
}

@Serializable
data class AdGuardStatsConfig(
    val intervalDays: Float = 1.0f,
    val enabled: Boolean = true,
    val intervalKnown: Boolean = true
) {
    val periodLabel: String
        get() {
            if (!intervalKnown) return "Statistics period unavailable"
            val hours = (intervalDays * 24).roundToInt()
            return when {
                hours == 1 -> "Last hour"
                hours <= 24 || hours % 24 != 0 -> "Last $hours hours"
                else -> "Last ${hours / 24} days"
            }
        }
}

@Serializable
data class AdGuardQueryLogItem(
    val time: String,
    val questionName: String,
    val questionType: String,
    val client: String,
    val clientName: String? = null,
    val reason: String = "NotFilteredNotFound",
    val elapsedMs: String = "0",
    val rules: List<String> = emptyList(),
    val clientId: String? = null,
    val unicodeName: String? = null,
    val responseStatus: String? = null,
    val upstream: String? = null,
    val cached: Boolean? = null,
    val answers: List<AdGuardDnsAnswer> = emptyList(),
    @Transient
    internal val occurrence: Int = 0
) {
    @Transient
    internal val timestamp = queryTimestamp(time)

    @Transient
    internal val key = listOf(
        time,
        client,
        clientId.orEmpty(),
        questionName,
        questionType,
        elapsedMs,
        reason,
        responseStatus.orEmpty(),
        occurrence.toString()
    ).joinToString("\u0000")

    val isBlocked: Boolean
        get() = reason.contains("block", ignoreCase = true) ||
            reason.contains("blacklist", ignoreCase = true) ||
            reason.contains("safebrowsing", ignoreCase = true) ||
            reason.contains("parental", ignoreCase = true) ||
            reason.equals("FilteredInvalid", ignoreCase = true)
}

internal fun List<AdGuardQueryLogItem>.withPageOccurrences(): List<AdGuardQueryLogItem> {
    if (size <= 1) return this
    val counts = HashMap<String, Int>(size)
    return map { item ->
        val base = if (item.occurrence == 0) item else item.copy(occurrence = 0)
        val seen = counts[base.key] ?: 0
        counts[base.key] = seen + 1
        if (seen == item.occurrence) item else item.copy(occurrence = seen)
    }
}

@Serializable
data class AdGuardDnsAnswer(val type: String, val value: String, val ttlSeconds: Long? = null)

internal fun queryTimestamp(time: String) = runCatching { OffsetDateTime.parse(time).toInstant() }.getOrNull()

@Serializable
data class AdGuardQueryLog(
    val oldest: String = "",
    val items: List<AdGuardQueryLogItem> = emptyList()
)

data class AdGuardQueryLogSettings(val enabled: Boolean? = null, val anonymizeClientIp: Boolean? = null)

@Serializable
data class AdGuardFilteringStatus(
    val enabled: Boolean = true,
    val activeFilterCount: Int = 0,
    val userRuleCount: Int = 0
)

@Serializable
data class AdGuardSafeSearchStatus(
    val enabled: Boolean = false,
    val google: Boolean = false,
    val bing: Boolean = false,
    val duckduckgo: Boolean = false,
    val ecosia: Boolean = false,
    val pixabay: Boolean = false,
    val yandex: Boolean = false,
    val youtube: Boolean = false
)

data class AdGuardDashboard(
    val status: AdGuardStatus,
    val stats: AdGuardStats,
    val statsConfig: AdGuardStatsConfig,
    val filtering: AdGuardFilteringStatus,
    val safeSearch: AdGuardSafeSearchStatus,
    val isCleartextHttp: Boolean = false,
    /** From GET /control/safebrowsing/status; null when it could not be read. */
    val safeBrowsingEnabled: Boolean? = null,
    /** From GET /control/parental/status; null when it could not be read. */
    val parentalEnabled: Boolean? = null
)

