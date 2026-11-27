package com.shaazifi.app.feature.adguard

import com.shaazifi.app.core.adguard.AdGuardError
import com.shaazifi.app.data.AdGuardRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AdGuardStateHolderTest {

    private lateinit var demoRepo: DemoAdGuardRepository
    private lateinit var holder: AdGuardStateHolder

    @Before
    fun setUp() {
        demoRepo = DemoAdGuardRepository()
        holder = AdGuardStateHolder(repository = demoRepo, isDemo = true)
    }

    @Test
    fun testDemoModeLoadsFullMockDataWithoutNetwork() = runBlocking {
        holder.refresh()
        val state = holder.state.value
        assertTrue(state is AdGuardUiState.DemoData)
        val data = (state as AdGuardUiState.DemoData).dashboard
        assertEquals(48213L, data.stats.numDnsQueries)
        assertEquals(6127L, data.stats.numBlockedFiltering)
        assertEquals(12.71, data.stats.blockedPercentage, 0.01)
        assertEquals("v0.107.54", data.status.version)
        assertTrue(data.status.protectionEnabled)
    }

    @Test
    fun testWrongPasswordMapsToWrongPasswordState() = runBlocking {
        val failingRepo = object : AdGuardRepository {
            override suspend fun probe() = true
            override suspend fun loadDashboard() = throw AdGuardError.Unauthorized()
            override suspend fun loadQueryLog(limit: Int, offset: Int, search: String?) = throw AdGuardError.Unauthorized()
            override suspend fun setProtection(enabled: Boolean, durationMs: Long?) {}
            override suspend fun setSafeBrowsing(enabled: Boolean) {}
        }

        val testHolder = AdGuardStateHolder(repository = failingRepo)
        testHolder.refresh()

        val state = testHolder.state.value
        assertTrue("HTTP 401 must map to WrongPassword state", state is AdGuardUiState.WrongPassword)
    }

    @Test
    fun testRateLimitedWithRetryAfterMapsToStateWithSeconds() = runBlocking {
        val failingRepo = object : AdGuardRepository {
            override suspend fun probe() = true
            override suspend fun loadDashboard() = throw AdGuardError.RateLimited(retryAfterSeconds = 120)
            override suspend fun loadQueryLog(limit: Int, offset: Int, search: String?) = throw AdGuardError.RateLimited(retryAfterSeconds = 120)
            override suspend fun setProtection(enabled: Boolean, durationMs: Long?) {}
            override suspend fun setSafeBrowsing(enabled: Boolean) {}
        }

        val testHolder = AdGuardStateHolder(repository = failingRepo)
        testHolder.refresh()

        val state = testHolder.state.value
        assertTrue("HTTP 429 must map to RateLimited state", state is AdGuardUiState.RateLimited)
        val rateLimited = state as AdGuardUiState.RateLimited
        assertEquals(120, rateLimited.retryAfterSeconds)
        assertTrue(rateLimited.message.contains("2 minutes"))
    }

    @Test
    fun testRateLimitedWithoutRetryAfterShowsWaitAWhile() = runBlocking {
        val failingRepo = object : AdGuardRepository {
            override suspend fun probe() = true
            override suspend fun loadDashboard() = throw AdGuardError.RateLimited(retryAfterSeconds = null)
            override suspend fun loadQueryLog(limit: Int, offset: Int, search: String?) = throw AdGuardError.RateLimited(retryAfterSeconds = null)
            override suspend fun setProtection(enabled: Boolean, durationMs: Long?) {}
            override suspend fun setSafeBrowsing(enabled: Boolean) {}
        }

        val testHolder = AdGuardStateHolder(repository = failingRepo)
        testHolder.refresh()

        val state = testHolder.state.value
        assertTrue("HTTP 429 without header must map to RateLimited state", state is AdGuardUiState.RateLimited)
        val rateLimited = state as AdGuardUiState.RateLimited
        assertNull("retryAfterSeconds must be null", rateLimited.retryAfterSeconds)
        assertTrue("Must tell user to wait a while", rateLimited.message.contains("wait a while"))
        assertFalse("Must NOT claim fixed 15 minutes", rateLimited.message.contains("15 minutes"))
    }

    @Test
    fun testFeatureNotInstalledWhenProbeReturnsFalse() = runBlocking {
        val notInstalledRepo = object : AdGuardRepository {
            override suspend fun probe() = false
            override suspend fun loadDashboard() = throw AdGuardError.NotAdGuardHome()
            override suspend fun loadQueryLog(limit: Int, offset: Int, search: String?) = throw AdGuardError.NotAdGuardHome()
            override suspend fun setProtection(enabled: Boolean, durationMs: Long?) {}
            override suspend fun setSafeBrowsing(enabled: Boolean) {}
        }

        val testHolder = AdGuardStateHolder(repository = notInstalledRepo)
        testHolder.refresh()

        val state = testHolder.state.value
        assertTrue("Non-AdGuard port must map to FeatureNotInstalled", state is AdGuardUiState.FeatureNotInstalled)
    }

    @Test
    fun testDefaultProbeDetailedCallsProbeOnceAndInventsNoDiagnostic() = runBlocking {
        var probeCalls = 0
        val repo = object : AdGuardRepository {
            override suspend fun probe(): Boolean { probeCalls++; return false }
            override suspend fun loadDashboard() = throw AdGuardError.NotAdGuardHome()
            override suspend fun loadQueryLog(limit: Int, offset: Int, search: String?) = throw AdGuardError.NotAdGuardHome()
            override suspend fun setProtection(enabled: Boolean, durationMs: Long?) {}
            override suspend fun setSafeBrowsing(enabled: Boolean) {}
        }

        val testHolder = AdGuardStateHolder(repository = repo)
        testHolder.refresh()

        assertEquals("Default probeDetailed must make exactly one probe call", 1, probeCalls)
        val state = testHolder.state.value as AdGuardUiState.FeatureNotInstalled
        assertNull("A repository that reports no diagnostic must not show a made-up one", state.diagnostic)
    }

    @Test
    fun testWriteProtectionRequiresConfirmationAndNoOptimisticUI() = runBlocking {
        var writesExecuted = 0
        val mockRepo = object : AdGuardRepository {
            var protection = true
            override suspend fun probe() = true
            override suspend fun loadDashboard(): AdGuardDashboard {
                return AdGuardDashboard(
                    status = AdGuardStatus(version = "v0.107.54", protectionEnabled = protection),
                    stats = AdGuardStats(numDnsQueries = 100, numBlockedFiltering = 10),
                    statsConfig = AdGuardStatsConfig(),
                    filtering = AdGuardFilteringStatus(),
                    safeSearch = AdGuardSafeSearchStatus()
                )
            }
            override suspend fun loadQueryLog(limit: Int, offset: Int, search: String?) = AdGuardQueryLog()
            override suspend fun setProtection(enabled: Boolean, durationMs: Long?) {
                writesExecuted++
                protection = enabled
            }
            override suspend fun setSafeBrowsing(enabled: Boolean) {}
        }

        val testHolder = AdGuardStateHolder(repository = mockRepo)
        testHolder.refresh()
        assertTrue((testHolder.state.value as AdGuardUiState.Loaded).dashboard.status.protectionEnabled)

        // 1. Propose change (user clicks switch) -> Dialog requested, NO network write sent yet!
        testHolder.requestProtectionToggle(enabled = false, durationMs = 600000L)
        assertNotNull(testHolder.pendingConfirmation.value)
        assertEquals(0, writesExecuted)
        assertTrue("Optimistic UI is forbidden: state must stay enabled until write completes",
            (testHolder.state.value as AdGuardUiState.Loaded).dashboard.status.protectionEnabled)

        // 2. User confirms dialog -> Write executed and state re-read from server
        testHolder.confirmPendingAction()
        assertEquals(1, writesExecuted)
        assertNull(testHolder.pendingConfirmation.value)
        assertFalse("State updated after server confirmation",
            (testHolder.state.value as AdGuardUiState.Loaded).dashboard.status.protectionEnabled)
    }

    @Test
    fun testDismissConfirmationDoesNotExecuteWrite() = runBlocking {
        var writesExecuted = 0
        val mockRepo = object : AdGuardRepository {
            override suspend fun probe() = true
            override suspend fun loadDashboard() = AdGuardDashboard(
                status = AdGuardStatus(protectionEnabled = true),
                stats = AdGuardStats(),
                statsConfig = AdGuardStatsConfig(),
                filtering = AdGuardFilteringStatus(),
                safeSearch = AdGuardSafeSearchStatus()
            )
            override suspend fun loadQueryLog(limit: Int, offset: Int, search: String?) = AdGuardQueryLog()
            override suspend fun setProtection(enabled: Boolean, durationMs: Long?) {
                writesExecuted++
            }
            override suspend fun setSafeBrowsing(enabled: Boolean) {}
        }

        val testHolder = AdGuardStateHolder(repository = mockRepo)
        testHolder.refresh()

        testHolder.requestProtectionToggle(enabled = false)
        assertNotNull(testHolder.pendingConfirmation.value)

        testHolder.dismissPendingAction()
        assertNull(testHolder.pendingConfirmation.value)
        assertEquals(0, writesExecuted)
    }

    @Test
    fun confirmPendingActionCatchesAuthAndNetworkErrorsWithoutCrashing() = runBlocking {
        var throwError: Exception = AdGuardError.Unauthorized("Bad creds")
        val repo = object : AdGuardRepository {
            override suspend fun probe() = true
            override suspend fun loadDashboard() = AdGuardDashboard(
                status = AdGuardStatus(protectionEnabled = true),
                stats = AdGuardStats(),
                statsConfig = AdGuardStatsConfig(),
                filtering = AdGuardFilteringStatus(),
                safeSearch = AdGuardSafeSearchStatus(),
                safeBrowsingEnabled = true,
                parentalEnabled = false
            )
            override suspend fun loadQueryLog(limit: Int, offset: Int, search: String?) = AdGuardQueryLog()
            override suspend fun setProtection(enabled: Boolean, durationMs: Long?) { throw throwError }
            override suspend fun setSafeBrowsing(enabled: Boolean) { throw throwError }
            override suspend fun setParental(enabled: Boolean) { throw throwError }
        }

        val testHolder = AdGuardStateHolder(repository = repo)
        testHolder.refresh()

        testHolder.requestProtectionToggle(enabled = false)
        testHolder.confirmPendingAction()
        assertTrue(testHolder.state.value is AdGuardUiState.WrongPassword)

        throwError = AdGuardError.RateLimited(retryAfterSeconds = 45)
        testHolder.requestSafeBrowsingToggle(enabled = false)
        testHolder.confirmPendingAction()
        assertTrue(testHolder.state.value is AdGuardUiState.RateLimited)

        throwError = AdGuardError.Unreachable("Timed out")
        testHolder.requestParentalToggle(enabled = true)
        testHolder.confirmPendingAction()
        assertTrue(testHolder.state.value is AdGuardUiState.RouterUnreachable)
    }

    @Test
    fun confirmPendingActionPropagatesCancellationException() = runBlocking {
        val repo = object : AdGuardRepository {
            override suspend fun probe() = true
            override suspend fun loadDashboard() = AdGuardDashboard(
                status = AdGuardStatus(protectionEnabled = true),
                stats = AdGuardStats(),
                statsConfig = AdGuardStatsConfig(),
                filtering = AdGuardFilteringStatus(),
                safeSearch = AdGuardSafeSearchStatus()
            )
            override suspend fun loadQueryLog(limit: Int, offset: Int, search: String?) = AdGuardQueryLog()
            override suspend fun setProtection(enabled: Boolean, durationMs: Long?) {
                throw CancellationException("Cancelled")
            }
            override suspend fun setSafeBrowsing(enabled: Boolean) {}
        }

        val testHolder = AdGuardStateHolder(repository = repo)
        testHolder.requestProtectionToggle(enabled = false)
        val result = runCatching { testHolder.confirmPendingAction() }
        assertTrue(result.exceptionOrNull() is CancellationException)
    }

    @Test
    fun parentalToggleUpdatesServerAndDemoRepository() = runBlocking {
        val demoHolder = AdGuardStateHolder(repository = demoRepo, isDemo = true)
        demoHolder.refresh()
        assertEquals(false, (demoHolder.state.value as AdGuardUiState.DemoData).dashboard.parentalEnabled)

        demoHolder.requestParentalToggle(enabled = true)
        assertTrue(demoHolder.pendingConfirmation.value is PendingConfirmation.ParentalToggle)
        demoHolder.confirmPendingAction()
        assertEquals(true, (demoHolder.state.value as AdGuardUiState.DemoData).dashboard.parentalEnabled)
    }
}
