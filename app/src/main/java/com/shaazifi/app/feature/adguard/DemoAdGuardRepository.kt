package com.shaazifi.app.feature.adguard

import com.shaazifi.app.data.AdGuardRepository
import com.shaazifi.app.feature.adguard.manage.AgQueryFilter
import java.time.Instant

/**
 * Offline demo repository providing AdGuard Home data matching docs/design/router-app-mock.html.
 * Functions with zero network calls.
 */
class DemoAdGuardRepository : AdGuardRepository {

    private var protectionEnabled = true
    private var protectionPausedUntilMs = 0L
    private var safeBrowsingEnabled = true
    private val queryHistoryStarts = Instant.now().epochSecond - 240
    private val sampleQueries = listOf(
        AdGuardQueryLogItem("", "ads.example.test", "A", "192.0.2.20", "Smart TV", "FilteredBlackList", "2.1",
            listOf("||ads.example.test^"), responseStatus = "NXDOMAIN"),
        AdGuardQueryLogItem("", "video.example.test", "A", "192.0.2.20", "Smart TV", elapsedMs = "8.4",
            responseStatus = "NOERROR", upstream = "https://resolver.example.test/dns-query",
            cached = false, answers = listOf(AdGuardDnsAnswer("A", "203.0.113.24", 60))),
        AdGuardQueryLogItem("", "tracker.example.test", "A", "192.0.2.42", "Pixel 8", "FilteredBlackList", "1.9", listOf("||tracker.example.test^")),
        AdGuardQueryLogItem("", "search.example.test", "AAAA", "192.0.2.42", "Pixel 8", elapsedMs = "9.1"),
        AdGuardQueryLogItem("", "telemetry.example.test", "A", "192.0.2.57", "Kid's tablet", "FilteredBlackList", "3.2", listOf("||telemetry.example.test^")),
        AdGuardQueryLogItem("", "news.example.test", "A", "192.0.2.31", "Office laptop", elapsedMs = "11.0", clientId = "office-laptop")
    )

    override suspend fun probe(): Boolean = true

    override suspend fun loadDashboard(): AdGuardDashboard {
        val now = System.currentTimeMillis()
        val isPaused = !protectionEnabled && protectionPausedUntilMs > now
        val remainingMs = if (isPaused) protectionPausedUntilMs - now else 0L

        return AdGuardDashboard(
            status = AdGuardStatus(
                version = "v0.107.54",
                protectionEnabled = protectionEnabled,
                protectionDisabledDurationMs = remainingMs,
                dnsAddresses = listOf("192.168.1.1"),
                dnsPort = 53,
                httpPort = 3000,
                running = true
            ),
            stats = AdGuardStats(
                timeUnits = "hours",
                numDnsQueries = 48213L,
                numBlockedFiltering = 6127L,
                numReplacedSafebrowsing = 42L,
                numReplacedSafesearch = 18L,
                numReplacedParental = 0L,
                avgProcessingTimeSeconds = 0.009, // 9 ms
                dnsQueries = listOf(18, 12, 10, 22, 48, 66, 58, 74, 100, 82, 61, 70),
                topQueriedDomains = listOf(
                    "example-1.test" to 3450L,
                    "example-2.test" to 2120L,
                    "example-3.test" to 1890L
                ),
                topBlockedDomains = listOf(
                    "ads.example.com" to 1902L,
                    "tracker.example.net" to 1266L,
                    "telemetry.example.org" to 781L
                ),
                topClients = listOf(
                    "Smart TV" to 14880L,
                    "Pixel 8" to 11204L,
                    "Kid's tablet" to 6530L
                )
            ),
            statsConfig = AdGuardStatsConfig(
                intervalDays = 1.0f,
                enabled = true
            ),
            filtering = AdGuardFilteringStatus(
                enabled = true,
                activeFilterCount = 4,
                userRuleCount = 7
            ),
            safeSearch = AdGuardSafeSearchStatus(
                enabled = true,
                google = true,
                youtube = true
            ),
            isCleartextHttp = false,
            safeBrowsingEnabled = safeBrowsingEnabled,
            parentalEnabled = false
        )
    }

    override suspend fun loadQueryLog(limit: Int, offset: Int, search: String?): AdGuardQueryLog =
        queryLog(limit, search, null, null, offset)

    override suspend fun loadQueryLogFiltered(limit: Int, search: String?, responseStatus: String?, olderThan: String?): AdGuardQueryLog =
        queryLog(limit, search, responseStatus, olderThan, offset = 0)

    private fun queryLog(limit: Int, search: String?, responseStatus: String?, olderThan: String?, offset: Int): AdGuardQueryLog {
        val now = Instant.now().epochSecond
        val start = minOf(now, olderThan?.let(::queryTimestamp)?.epochSecond?.minus(1) ?: now)
        val oldest = maxOf(queryHistoryStarts, now - 900)
        val status = AgQueryFilter.entries.firstOrNull { it.value == responseStatus } ?: AgQueryFilter.ALL
        val filter = QueryLogFilter(null, search.orEmpty(), status)
        val items = (start downTo oldest).asSequence()
            .map { stamp -> stamp to sampleQueries[(stamp % sampleQueries.size).toInt()] }
            .filter { (_, query) -> filter.matches(query) && status.matchesLive(query) }
            .drop(offset)
            .take(limit + 1)
            .map { (stamp, query) -> query.copy(time = Instant.ofEpochSecond(stamp).toString()) }
            .toList()
        return AdGuardQueryLog(
            oldest = if (items.size > limit && limit > 0) items[limit - 1].time else "",
            items = items.take(limit)
        )
    }

    override suspend fun loadQueryLogSettings() = AdGuardQueryLogSettings(enabled = true, anonymizeClientIp = false)

    override suspend fun loadQueryLogClients(): List<AdGuardLogDevice> =
        sampleQueries.mapNotNull { it.logDevice(allowAddress = true) }.distinctBy { it.identifier }

    override suspend fun setProtection(enabled: Boolean, durationMs: Long?) {
        protectionEnabled = enabled
        protectionPausedUntilMs = if (!enabled && durationMs != null) {
            System.currentTimeMillis() + durationMs
        } else {
            0L
        }
    }

    override suspend fun setSafeBrowsing(enabled: Boolean) {
        safeBrowsingEnabled = enabled
    }
}

