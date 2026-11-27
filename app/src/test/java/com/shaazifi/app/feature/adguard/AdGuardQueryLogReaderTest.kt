package com.shaazifi.app.feature.adguard

import com.shaazifi.app.core.adguard.AdGuardError
import com.shaazifi.app.data.AdGuardRepository
import com.shaazifi.app.feature.adguard.manage.AgQueryFilter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class AdGuardQueryLogReaderTest {
    @Test
    fun liveStartsImmediatelyAndMergesOverlappingReads() = runTest {
        val repository = FakeLog { read ->
            val newest = if (read == 1) 100 else 101
            page(newest downTo newest - 63)
        }
        val states = AdGuardQueryLogReader(repository).live(LiveQueryLog()).take(2).toList()

        assertEquals(listOf(100, 101).map(::timestamp), states.map { it.items.first().time })
        assertEquals(65, states.last().items.size)
        assertEquals(65, states.last().items.distinctBy { it.key }.size)
        assertEquals(listOf(null, null), repository.requests.map { it.olderThan })
        assertEquals(AdGuardQueryLogReader.LIVE_POLL_MS, currentTime)
        assertTrue(currentTime in 1_000L..1_500L)
    }

    @Test
    fun liveCatchesUpWithTimestampPaginationWhenTheLatestPageDoesNotOverlap() = runTest {
        val head = page(200 downTo 137)
        val earlier = page(136 downTo 73)
        val previous = LiveQueryLog(listOf(row(78), row(77)), LiveLogStatus.LIVE)
        val repository = FakeLog { read -> if (read == 1) head else earlier }

        val state = AdGuardQueryLogReader(repository).live(previous).first()

        assertEquals(listOf(null, timestamp(137)), repository.requests.map { it.olderThan })
        assertEquals((200 downTo 73).map(::timestamp), state.items.map { it.time })
        assertFalse(state.hasGap)
    }

    @Test
    fun liveCancelsAnInFlightRequestAndStopsPolling() = runTest {
        var cancelled = false
        val repository = FakeLog {
            try { awaitCancellation() } finally { cancelled = true }
        }
        val feed = launch { AdGuardQueryLogReader(repository).live(LiveQueryLog()).collect {} }
        runCurrent()
        assertEquals(1, repository.requests.size)

        feed.cancelAndJoin()
        advanceTimeBy(10_000)

        assertTrue(cancelled)
        assertEquals(1, repository.requests.size)
    }

    @Test
    fun liveBacksOffAfterRateLimitAndKeepsVisibleItems() = runTest {
        val repository = FakeLog { read ->
            if (read == 1) throw AdGuardError.RateLimited(retryAfterSeconds = 3)
            page(12 downTo 10)
        }
        val previous = LiveQueryLog(listOf(row(10)), LiveLogStatus.LIVE)
        val states = AdGuardQueryLogReader(repository).live(previous).take(2).toList()

        assertEquals(listOf(LiveLogStatus.RETRYING, LiveLogStatus.LIVE), states.map { it.status })
        assertEquals(previous.items, states.first().items)
        assertEquals(3_000L, currentTime)
    }

    @Test
    fun rejectedCredentialsStopTheFeed() = runTest {
        val repository = FakeLog { throw AdGuardError.Unauthorized() }

        val states = AdGuardQueryLogReader(repository).live(LiveQueryLog()).toList()

        assertEquals(LiveLogStatus.UNAVAILABLE, states.single().status)
        assertTrue(states.single().needsSignIn)
        assertEquals(1, repository.requests.size)
    }

    @Test
    fun deviceHistoryUsesExactClientSearchAndChecksIdentityAndDomain() = runTest {
        val address = "192.0.2.20"
        val selected = AdGuardLogDevice.fromIdentifier(address, "Tablet")!!
        val matching = row(9).copy(client = address, questionName = "news.example.test")
        val unrelatedDevice = row(8).copy(client = "192.0.2.201", questionName = "news.example.test")
        val unrelatedDomain = row(7).copy(client = address, questionName = "other.example.test")
        val domainCoincidence = row(6).copy(client = "192.0.2.201", questionName = address)
        val repository = FakeLog { AdGuardQueryLog(timestamp(6), listOf(matching, unrelatedDevice, unrelatedDomain, domainCoincidence)) }

        val result = AdGuardQueryLogReader(repository).history(QueryLogFilter(selected, "news", AgQueryFilter.BLOCKED), timestamp(10))

        assertEquals(Request("\"$address\"", "blocked", timestamp(10)), repository.requests.single())
        assertEquals(listOf(matching), result.items)
        assertEquals(timestamp(6), result.olderThan)
    }

    @Test
    fun anEmptyHistoryBatchCanStillAdvanceButARepeatedCursorCannot() = runTest {
        val repository = FakeLog { AdGuardQueryLog(oldest = timestamp(1)) }
        val reader = AdGuardQueryLogReader(repository)
        val filter = QueryLogFilter(null, "example", AgQueryFilter.ALL)

        val first = reader.history(filter, null)
        assertTrue(first.items.isEmpty())
        assertEquals(timestamp(1), first.olderThan)
        assertNull(reader.history(filter, first.olderThan).olderThan)
    }

    @Test
    fun encryptedClientIdentitySurvivesAddressChangesAndCanBeUsedWithAnonymization() {
        val lookup = row(1).copy(clientId = "tablet-1", client = "192.0.2.0")
        val selected = lookup.logDevice(allowAddress = false)!!
        val filter = QueryLogFilter(selected, "", AgQueryFilter.ALL)

        assertEquals("tablet-1", selected.identifier)
        assertTrue(filter.matches(lookup.copy(client = "192.0.3.0")))
        assertFalse(filter.matches(lookup.copy(clientId = "tablet-10")))
        assertNull(lookup.copy(clientId = null).logDevice(allowAddress = false))
        assertEquals("2001:db8::2", AdGuardLogDevice.fromIdentifier("2001:db8:0:0:0:0:0:2")?.identifier)
        assertNull(AdGuardLogDevice.fromIdentifier("AA:BB:CC:DD:EE:FF"))
        assertNull(AdGuardLogDevice.fromIdentifier("192.0.2.0/24"))
    }

    @Test
    fun livePreservesIdenticalTimestampRowsInSinglePage() = runTest {
        val dup1 = row(50)
        val dup2 = row(50)
        val repository = FakeLog { AdGuardQueryLog(timestamp(50), listOf(dup1, dup2)) }

        val state = AdGuardQueryLogReader(repository).live(LiveQueryLog()).first()

        assertEquals(2, state.items.size)
        assertEquals(2, state.items.distinctBy { it.key }.size)
    }

    @Test
    fun historyAutoAdvancesEmptyClientFilteredPagesWithBound() = runTest {
        val selected = AdGuardLogDevice.fromIdentifier("tablet-1")!!
        val repository = FakeLog { read ->
            when (read) {
                1 -> AdGuardQueryLog(timestamp(20), listOf(row(20).copy(clientId = "tablet-10")))
                2 -> AdGuardQueryLog(timestamp(10), listOf(row(10).copy(clientId = "tablet-10")))
                else -> AdGuardQueryLog(timestamp(5), listOf(row(5).copy(clientId = "tablet-1")))
            }
        }
        val page = AdGuardQueryLogReader(repository).history(QueryLogFilter(selected, "", AgQueryFilter.ALL), null)

        assertEquals(3, repository.requests.size)
        assertEquals(listOf(row(5).copy(clientId = "tablet-1")), page.items)
        assertEquals(timestamp(5), page.olderThan)
    }

    @Test
    fun forEachRetainedPageDoesNotDuplicateNullTimestampsAndRetries429() = runTest {
        val nullRow = AdGuardQueryLogItem("not-a-timestamp", "null-ts.example.test", "A", "192.0.2.1")
        val p1 = AdGuardQueryLog(timestamp(10), listOf(row(11), nullRow))
        val p2 = AdGuardQueryLog("", listOf(nullRow, row(9)))
        var attempts = 0
        val repository = FakeLog {
            attempts++
            if (attempts == 2) throw AdGuardError.RateLimited(retryAfterSeconds = 2)
            if (attempts == 1) p1 else p2
        }
        val exported = mutableListOf<AdGuardQueryLogItem>()

        AdGuardQueryLogReader(repository).forEachRetainedPage(QueryLogFilter(null, "", AgQueryFilter.ALL)) {
            exported.addAll(it)
        }

        assertEquals(3, attempts)
        assertEquals(2_000L, currentTime)
        assertEquals(listOf("example.test", "null-ts.example.test", "example.test"), exported.map { it.questionName })
        assertEquals(1, exported.count { it.questionName == "null-ts.example.test" })
    }

    private data class Request(val search: String?, val responseStatus: String?, val olderThan: String?)

    private class FakeLog(private val read: suspend (Int) -> AdGuardQueryLog) : AdGuardRepository by DemoAdGuardRepository() {
        val requests = mutableListOf<Request>()
        override suspend fun loadQueryLogFiltered(
            limit: Int, search: String?, responseStatus: String?, olderThan: String?
        ): AdGuardQueryLog {
            requests += Request(search, responseStatus, olderThan)
            return read(requests.size)
        }
    }

    private fun timestamp(second: Int) = Instant.ofEpochSecond(1_700_000_000L + second).toString()

    private fun row(second: Int) = AdGuardQueryLogItem(timestamp(second), "example.test", "A", "192.0.2.42")

    private fun page(seconds: IntProgression) = AdGuardQueryLog(timestamp(seconds.last), seconds.map(::row))
}
