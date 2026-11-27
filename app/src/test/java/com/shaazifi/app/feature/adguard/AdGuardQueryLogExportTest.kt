package com.shaazifi.app.feature.adguard

import com.shaazifi.app.data.AdGuardRepository
import com.shaazifi.app.feature.adguard.manage.AgQueryFilter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

class AdGuardQueryLogExportTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val allQueries = QueryLogFilter(null, "", AgQueryFilter.ALL)

    @Test
    fun csvExportsAllPagesAndTreatsSpreadsheetInputAsText() = runBlocking {
        val newest = query(3, "bø.example.test").copy(clientName = "=2+2, \"lab\"")
        val boundary = query(2, "second.example.test")
        val oldest = query(1, "first.example.test")
        val repository = FakeLog { cursor ->
            if (cursor == null) AdGuardQueryLog(boundary.time, listOf(newest, boundary))
            else AdGuardQueryLog(items = listOf(boundary, oldest))
        }
        val progress = mutableListOf<Long>()

        val export = AdGuardQueryLogReader(repository).export(folder.root, allQueries, QueryLogExportFormat.CSV) {
            progress.add(it)
        }

        val csv = export.file.readText()
        assertEquals("csv", export.file.extension)
        assertEquals(3L, export.count)
        assertEquals(listOf(2L, 3L), progress)
        assertEquals(listOf(null, boundary.time), repository.requests.map { it.cursor })
        assertTrue(csv.startsWith("\uFEFF\"Timestamp\",\"Domain\",\"Record type\""))
        assertTrue(csv.contains("\"'=2+2, \"\"lab\"\"\""))
        assertTrue(csv.contains("bø.example.test"))
        assertEquals(1, Regex("second\\.example\\.test").findAll(csv).count())
        assertTrue(csv.contains("first.example.test"))
    }

    @Test
    fun textExportKeepsExactDeviceAndSearchAcrossAnEmptyBatch() = runBlocking {
        val selected = AdGuardLogDevice.fromIdentifier("tablet-1")!!
        val filter = QueryLogFilter(selected, "news", AgQueryFilter.BLOCKED)
        val requested = query(1, "news.example.test", clientId = "tablet-1")
        val repository = FakeLog { cursor ->
            when (cursor) {
                null -> AdGuardQueryLog(timestamp(4), listOf(query(4, "news.example.test", clientId = "tablet-10")))
                timestamp(4) -> AdGuardQueryLog(timestamp(3))
                else -> AdGuardQueryLog(items = listOf(query(2, "other.example.test", clientId = "tablet-1"), requested))
            }
        }

        val export = AdGuardQueryLogReader(repository).export(folder.root, filter, QueryLogExportFormat.TEXT) {}

        val text = export.file.readText()
        assertEquals("txt", export.file.extension)
        assertEquals(1L, export.count)
        assertEquals(listOf(null, timestamp(4), timestamp(3)), repository.requests.map { it.cursor })
        assertTrue(repository.requests.all { it.search == "\"tablet-1\"" && it.responseStatus == "blocked" })
        assertTrue(text.contains("Domain: news.example.test"))
        assertTrue(text.contains("Client ID: tablet-1"))
        assertFalse(text.contains("tablet-10"))
        assertFalse(text.contains("other.example.test"))
    }

    @Test
    fun nonAdvancingPageRemovesTheIncompleteFile() = runBlocking {
        val query = query(5, "sample.example.test")
        val repository = FakeLog { AdGuardQueryLog(query.time, listOf(query)) }
        val result = runCatching {
            AdGuardQueryLogReader(repository).export(folder.root, allQueries, QueryLogExportFormat.CSV) {}
        }

        assertTrue(result.exceptionOrNull() is QueryLogExportException)
        assertEquals(2, repository.requests.size)
        assertTrue(folder.root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun csvSanitizesControlCharsNbspZwspTabsCrAndMultilineFormulas() {
        assertEquals("'=1+1", sanitizeCsvCell("\u0007=1+1"))
        assertEquals("'\u00A0+2+2", sanitizeCsvCell("\u00A0+2+2"))
        assertEquals("'\u200B@SUM(A1)", sanitizeCsvCell("\u200B@SUM(A1)"))
        assertEquals("'\tfoo", sanitizeCsvCell("\tfoo"))
        assertEquals("'\rbar", sanitizeCsvCell("\rbar"))
        assertEquals("safe\n'=cmd|'/C calc'!A0\n'\tmore", sanitizeCsvCell("safe\n=cmd|'/C calc'!A0\n\tmore"))
        assertEquals("safe\n'=1+1\n'\tindented", sanitizeCsvCell("safe\n=1+1\n\tindented"))
        assertEquals("example.org", sanitizeCsvCell("example.org"))
    }

    @Test
    fun cleanupOldExportsRemovesExpiredAndExcessFilesOnly() {
        val now = 10_000_000_000L
        val expired = folder.newFile("zashfi-dns-old.csv").apply { setLastModified(now - 7_200_000L) }
        val keepOther = folder.newFile("unrelated.txt").apply { setLastModified(now - 7_200_000L) }
        val recent = (1..6).map { idx ->
            folder.newFile("zashfi-dns-r$idx.csv").apply { setLastModified(now - (6 - idx) * 1_000L) }
        }

        cleanupOldExports(folder.root, nowMs = now)

        assertFalse(expired.exists())
        assertTrue(keepOther.exists())
        assertFalse(recent.first().exists())
        assertTrue(recent.drop(1).all { it.exists() })
    }

    @Test
    fun cancellationDuringAnOlderPageRemovesTheIncompleteFile() = runBlocking {
        val nextRead = CompletableDeferred<Unit>()
        val query = query(2, "sample.example.test")
        val repository = FakeLog { cursor ->
            if (cursor == null) AdGuardQueryLog(query.time, listOf(query))
            else {
                nextRead.complete(Unit)
                awaitCancellation()
            }
        }

        val export = launch {
            AdGuardQueryLogReader(repository).export(folder.root, allQueries, QueryLogExportFormat.TEXT) {}
        }
        withTimeout(5_000) { nextRead.await() }
        export.cancelAndJoin()

        assertTrue(folder.root.listFiles().orEmpty().isEmpty())
    }

    private data class Request(val search: String?, val responseStatus: String?, val cursor: String?)

    private class FakeLog(private val read: suspend (String?) -> AdGuardQueryLog) :
        AdGuardRepository by DemoAdGuardRepository() {
        val requests = mutableListOf<Request>()

        override suspend fun loadQueryLogFiltered(
            limit: Int, search: String?, responseStatus: String?, olderThan: String?
        ): AdGuardQueryLog {
            requests += Request(search, responseStatus, olderThan)
            return read(olderThan)
        }
    }

    private fun timestamp(seconds: Long) = Instant.ofEpochSecond(1_700_000_000L + seconds).toString()

    private fun query(seconds: Long, domain: String, clientId: String? = null) =
        AdGuardQueryLogItem(timestamp(seconds), domain, "A", "192.0.2.20", clientId = clientId)
}
