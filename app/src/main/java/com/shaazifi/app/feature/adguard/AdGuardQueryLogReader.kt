package com.shaazifi.app.feature.adguard

import com.shaazifi.app.core.adguard.AdGuardError
import com.shaazifi.app.data.AdGuardRepository
import com.shaazifi.app.feature.adguard.manage.AgQueryFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@ConsistentCopyVisibility
data class AdGuardLogDevice private constructor(val identifier: String, val isAddress: Boolean, val name: String?) {
    val label: String get() = name ?: identifier

    internal fun matches(item: AdGuardQueryLogItem): Boolean =
        identifier.equals(if (isAddress) item.client else item.clientId, ignoreCase = true)

    companion object {
        private val clientIdPattern = Regex("[a-zA-Z0-9-]{1,64}")
        private val ipv6Pattern = Regex("[a-fA-F0-9:.]+")

        fun fromIdentifier(input: String, name: String? = null): AdGuardLogDevice? {
            val id = input.trim()
            val label = name?.takeIf { it.isNotBlank() && !it.equals(id, ignoreCase = true) }
            if (':' in id) {
                val literal = id.removeSurrounding("[", "]").takeIf(ipv6Pattern::matches) ?: return null
                val ip = "http://[$literal]/".toHttpUrlOrNull()?.host
                return ip?.let { AdGuardLogDevice(it, true, label) }
            }
            val octets = id.split('.')
            if (octets.size == 4 && octets.all { (it.toIntOrNull() ?: -1) in 0..255 && it.all(Char::isDigit) }) {
                return AdGuardLogDevice(octets.joinToString(".") { it.toInt().toString() }, true, label)
            }
            return id.takeIf(clientIdPattern::matches)?.let { AdGuardLogDevice(it.lowercase(), false, label) }
        }
    }
}

internal fun AdGuardQueryLogItem.logDevice(allowAddress: Boolean): AdGuardLogDevice? =
    clientId?.let { AdGuardLogDevice.fromIdentifier(it, clientName) }
        ?: if (allowAddress) AdGuardLogDevice.fromIdentifier(client, clientName) else null

internal fun AgQueryFilter.matchesLive(item: AdGuardQueryLogItem): Boolean {
    val reason = item.reason.lowercase()
    return when (this) {
        AgQueryFilter.ALL -> true
        AgQueryFilter.FILTERED -> item.isBlocked || reason.startsWith("filtered") || reason.startsWith("rewrit")
        AgQueryFilter.BLOCKED -> item.isBlocked
        AgQueryFilter.WHITELISTED -> reason == "notfilteredwhitelist" || reason == "notfilteredallowlist" ||
            reason == "whitelisted" || reason == "allowed"
        AgQueryFilter.REWRITTEN -> reason.startsWith("rewrit")
        AgQueryFilter.PROCESSED -> reason == "notfilterednotfound" || reason == "notfilterederror" || reason == "processed"
    }
}

internal val AdGuardQueryLogItem.outcomeLabel: String
    get() = when {
        isBlocked -> "Blocked"
        AgQueryFilter.REWRITTEN.matchesLive(this) -> "Rewritten"
        AgQueryFilter.WHITELISTED.matchesLive(this) -> "Allowed"
        AgQueryFilter.FILTERED.matchesLive(this) -> "Filtered"
        AgQueryFilter.PROCESSED.matchesLive(this) -> "Not filtered"
        else -> "Unknown"
    }

internal data class QueryLogFilter(
    val device: AdGuardLogDevice?, val search: String, val result: AgQueryFilter
) {
    val serverSearch: String? get() = device?.let { "\"${it.identifier}\"" } ?: search.takeIf(String::isNotBlank)

    fun matches(item: AdGuardQueryLogItem): Boolean =
        (device == null || device.matches(item)) && matchesSearch(item)

    private fun matchesSearch(item: AdGuardQueryLogItem): Boolean {
        if (search.isBlank()) return true
        val exact = search.length >= 2 && search.first() == '"' && search.last() == '"'
        val term = if (exact) search.removeSurrounding("\"") else search
        fun String?.matchesTerm(): Boolean = if (exact) this.equals(term, ignoreCase = true)
            else this?.contains(term, ignoreCase = true) == true
        return item.questionName.matchesTerm() || item.unicodeName.matchesTerm() ||
            (device == null && (item.client.matchesTerm() || item.clientId.matchesTerm() || item.clientName.matchesTerm()))
    }
}

internal enum class LiveLogStatus { CONNECTING, LIVE, RETRYING, UNAVAILABLE }

internal data class LiveQueryLog(
    val items: List<AdGuardQueryLogItem> = emptyList(),
    val status: LiveLogStatus = LiveLogStatus.CONNECTING,
    val error: String? = null,
    val hasGap: Boolean = false,
    val needsSignIn: Boolean = false
)

internal data class QueryHistoryPage(val items: List<AdGuardQueryLogItem>, val olderThan: String?)

internal class AdGuardQueryLogReader(private val repository: AdGuardRepository) {
    suspend fun history(filter: QueryLogFilter, olderThan: String?): QueryHistoryPage {
        var cursor = olderThan
        for (attempt in 0 until MAX_EMPTY_HISTORY_PAGES) {
            currentCoroutineContext().ensureActive()
            val page = readHistoryPage(filter, cursor, HISTORY_PAGE_SIZE)
            val next = page.nextCursor(cursor)
            val matches = page.matchingItems(filter)
            if (matches.isNotEmpty() || next == null || page.items.isEmpty()) {
                return QueryHistoryPage(matches, next)
            }
            cursor = next
        }
        return QueryHistoryPage(emptyList(), cursor)
    }

    suspend fun forEachRetainedPage(filter: QueryLogFilter, consume: suspend (List<AdGuardQueryLogItem>) -> Unit) {
        var olderThan: String? = null
        val seenKeys = HashSet<String>()
        do {
            currentCoroutineContext().ensureActive()
            val page = readExportPageWithRetry(filter, olderThan, EXPORT_PAGE_SIZE)
            val next = page.nextCursor(olderThan)
            if (next == null && page.oldest.isNotBlank()) {
                throw QueryLogExportException("AdGuard Home stopped paging through the log. No complete export was created.")
            }
            val boundary = olderThan?.let(::queryTimestamp)
            val matches = page.matchingItems(filter).filter { item ->
                val beforeBoundary = boundary == null || item.timestamp == null || item.timestamp.isBefore(boundary)
                beforeBoundary && seenKeys.add(item.key)
            }
            if (matches.isNotEmpty()) {
                consume(matches)
            }
            olderThan = next
        } while (olderThan != null)
    }

    private suspend fun readExportPageWithRetry(
        filter: QueryLogFilter,
        olderThan: String?,
        limit: Int
    ): AdGuardQueryLog {
        var backoffSeconds = 2
        for (attempt in 0..MAX_EXPORT_RATE_LIMIT_RETRIES) {
            currentCoroutineContext().ensureActive()
            try {
                return readHistoryPage(filter, olderThan, limit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AdGuardError.RateLimited) {
                if (attempt == MAX_EXPORT_RATE_LIMIT_RETRIES) throw e
                val waitSeconds = (e.retryAfterSeconds ?: backoffSeconds).coerceIn(1, 60)
                delay(waitSeconds * 1_000L)
                backoffSeconds = (backoffSeconds * 2).coerceAtMost(60)
            }
        }
        error("Unreachable")
    }

    private suspend fun readHistoryPage(filter: QueryLogFilter, olderThan: String?, limit: Int): AdGuardQueryLog =
        repository.loadQueryLogFiltered(limit, filter.serverSearch, filter.result.value, olderThan)

    private fun AdGuardQueryLog.matchingItems(filter: QueryLogFilter): List<AdGuardQueryLogItem> =
        items.withPageOccurrences().filter(filter::matches).distinctBy { it.key }

    fun live(from: LiveQueryLog): Flow<LiveQueryLog> = flow {
        var last = from
        var retryMs = 1_000L
        while (true) {
            val batch = try {
                withTimeoutOrNull(5_000) { latest(last) } ?: throw AdGuardError.Unreachable()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val canRetry = e !is AdGuardError.Unauthorized && e !is AdGuardError.NotAdGuardHome &&
                    !(e is AdGuardError.HttpError && e.statusCode in 400..499)
                val failed = last.copy(
                    status = if (canRetry) LiveLogStatus.RETRYING else LiveLogStatus.UNAVAILABLE,
                    error = e.queryLogMessage(),
                    needsSignIn = e is AdGuardError.Unauthorized
                )
                if (failed != last) emit(failed)
                last = failed
                if (!canRetry) break
                val waitMs = if (e is AdGuardError.RateLimited) (e.retryAfterSeconds ?: 30).coerceAtLeast(1) * 1_000L
                    else retryMs
                delay(waitMs)
                retryMs = (retryMs * 2).coerceAtMost(8_000L)
                continue
            }
            if (batch != last) emit(batch)
            last = batch
            retryMs = 1_000L
            // A fixed cadence keeps the poll interval deterministic (including under virtual-time
            // test dispatchers, where a real monotonic clock does not advance with the test clock).
            delay(LIVE_POLL_MS)
        }
    }

    private suspend fun latest(last: LiveQueryLog): LiveQueryLog {
        val head = repository.loadQueryLogFiltered(LIVE_PAGE_SIZE)
        val headItems = head.items.withPageOccurrences()
        val previous = last.items
        // A short unfiltered head contains every retained row. It can also reflect a log clear.
        if (headItems.size < LIVE_PAGE_SIZE || previous.isEmpty()) {
            return LiveQueryLog(headItems.distinctBy { it.key }, LiveLogStatus.LIVE)
        }
        val previousKeys = previous.mapTo(HashSet()) { it.key }
        val fetched = headItems.toMutableList()
        var page = head
        var pageItems = headItems
        var requestCursor: String? = null
        var overlaps = pageItems.any { it.key in previousKeys }
        for (attempt in 0 until MAX_CATCH_UP_PAGES) {
            if (overlaps || pageItems.size < LIVE_PAGE_SIZE) break
            val cursor = page.nextCursor(requestCursor) ?: break
            page = repository.loadQueryLogFiltered(LIVE_PAGE_SIZE, olderThan = cursor)
            pageItems = page.items.withPageOccurrences()
            requestCursor = cursor
            overlaps = pageItems.any { it.key in previousKeys }
            fetched.addAll(pageItems)
        }
        val combined = if (overlaps || pageItems.size >= LIVE_PAGE_SIZE) fetched + previous else fetched
        return LiveQueryLog(
            items = combined.distinctBy { it.key }.sortedByDescending { it.timestamp }.take(MAX_LIVE_ROWS),
            status = LiveLogStatus.LIVE,
            hasGap = last.hasGap || (!overlaps && pageItems.size >= LIVE_PAGE_SIZE),
            needsSignIn = false
        )
    }

    private fun AdGuardQueryLog.nextCursor(current: String?): String? {
        val cursor = queryTimestamp(oldest) ?: return null
        return oldest.takeIf { current == null || queryTimestamp(current)?.let { cursor < it } == true }
    }

    companion object {
        const val LIVE_POLL_MS = 1_200L
        const val LIVE_PAGE_SIZE = 64
        private const val MAX_LIVE_ROWS = 256
        private const val MAX_CATCH_UP_PAGES = 3
        private const val MAX_EMPTY_HISTORY_PAGES = 5
        private const val MAX_EXPORT_RATE_LIMIT_RETRIES = 3
        private const val HISTORY_PAGE_SIZE = 50
        private const val EXPORT_PAGE_SIZE = 500
    }
}

internal fun Exception.queryLogMessage(): String = when (this) {
    is AdGuardError.Unauthorized -> "Sign in to AdGuard Home again."
    is AdGuardError.RateLimited -> "AdGuard Home is rate limiting requests."
    is AdGuardError.Unreachable -> "Can't reach AdGuard Home."
    is AdGuardError.HttpError -> "AdGuard Home returned HTTP $statusCode."
    else -> "Couldn't read the query log."
}
