package com.shaazifi.app.feature.home

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hands out prepared readings or failures, one per load, and counts the loads. */
private class ScriptedRepository(private val script: List<Any>) : HomeRepository {
    var loads = 0

    override suspend fun load(): HomeReading {
        val step = script[minOf(loads, script.size - 1)]
        loads++
        if (step is Exception) throw step
        return step as HomeReading
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class HomeStateHolderTest {

    private fun reading(
        cpu: CpuSample? = CpuSample(100, 1000),
        rx: Long = 1_000_000,
        tx: Long = 100_000,
        uptime: Long = 68100
    ) = HomeReading(
        board = BoardInfo("Example Router X1", "Device-1", "OpenWrt SNAPSHOT r1"),
        uptimeSec = uptime,
        memUsedPct = 86,
        cpu = cpu,
        wan = WanStatus(true, "static", "wan", "198.51.100.2", "198.51.100.3", 64384),
        clients = ClientSummary(17, 0, 17, true),
        wanCounters = TrafficCounters(rx, tx)
    )

    private fun dashboardOf(state: HomeUiState): HomeDashboard = when (state) {
        is HomeUiState.Loaded -> state.dashboard
        is HomeUiState.DemoData -> state.dashboard
        else -> throw AssertionError("No dashboard in $state")
    }

    @Test
    fun testInitialLoadingState() {
        val holder = HomeStateHolder(ScriptedRepository(listOf(reading())))
        assertEquals(HomeUiState.Loading, holder.state.value)
    }

    @Test
    fun testSuccessfulLoadEmitsLoadedState() = runTest {
        val holder = HomeStateHolder(ScriptedRepository(listOf(reading())), nowMs = { testScheduler.currentTime })
        holder.refresh()
        val d = dashboardOf(holder.state.value)
        assertTrue(holder.state.value is HomeUiState.Loaded)
        assertEquals("Example Router X1", d.routerName)
        assertEquals("18h 55m", d.uptimeText)
        assertEquals(86, d.memUsedPct)
        assertEquals(ClientSummary(17, 0, 17, true), d.clients)
        assertEquals(1_100_000L, d.totalBytesSinceStart)
        assertTrue(d.internetUp)
        assertFalse(d.stale)
    }

    @Test
    fun testCpuAndSpeedAreEmptyOnFirstSampleThenCalculated() = runTest {
        val repo = ScriptedRepository(
            listOf(
                reading(cpu = CpuSample(100, 1000), rx = 1_000_000, tx = 100_000),
                // 5 s later: 2,500,000 bytes down and 250,000 up; 20 of 1000 CPU ticks busy.
                reading(cpu = CpuSample(120, 2000), rx = 3_500_000, tx = 350_000)
            )
        )
        val holder = HomeStateHolder(repo, nowMs = { testScheduler.currentTime })
        holder.refresh()
        val first = dashboardOf(holder.state.value)
        assertNull(first.cpuUsedPct)
        assertNull(first.downloadBps)
        assertNull(first.uploadBps)
        assertTrue(first.downloadHistoryBps.isEmpty())

        advanceTimeBy(5_000)
        holder.refresh()
        val second = dashboardOf(holder.state.value)
        assertEquals(2, second.cpuUsedPct)
        assertEquals(4_000_000.0, second.downloadBps!!, 0.001)   // 2.5 MB in 5 s = 4 Mbps
        assertEquals(400_000.0, second.uploadBps!!, 0.001)
        assertEquals(listOf(4_000_000.0), second.downloadHistoryBps)
    }

    @Test
    fun testCounterResetShowsNoSpeedInsteadOfANegativeOne() = runTest {
        val repo = ScriptedRepository(listOf(reading(rx = 9_000_000), reading(rx = 1_000)))
        val holder = HomeStateHolder(repo, nowMs = { testScheduler.currentTime })
        holder.refresh()
        advanceTimeBy(5_000)
        holder.refresh()
        assertNull(dashboardOf(holder.state.value).downloadBps)
    }

    @Test
    fun testDemoModeEmitsDemoStateWithFakeData() = runTest {
        val holder = HomeStateHolder(DemoHomeRepository(), demo = true, nowMs = { testScheduler.currentTime })
        holder.refresh()
        advanceTimeBy(5_000)
        holder.refresh()
        val state = holder.state.value
        assertTrue(state is HomeUiState.DemoData)
        val d = dashboardOf(state)
        assertEquals("Demo router", d.routerName)
        assertEquals(ClientSummary(9, 7, 2, true), d.clients)
        assertEquals(3, d.cpuUsedPct)
        assertTrue(d.downloadBps!! > 0.0)
    }

    @Test
    fun testUnreachableRouterEmitsRouterUnreachableState() = runTest {
        val holder = HomeStateHolder(ScriptedRepository(listOf(HomeLoadException.Unreachable())))
        holder.refresh()
        assertEquals(HomeUiState.RouterUnreachable, holder.state.value)
    }

    @Test
    fun testUnexpectedErrorIsShownAsUnreachableNotACrash() = runTest {
        val holder = HomeStateHolder(ScriptedRepository(listOf(IllegalStateException("bad reply"))))
        holder.refresh()
        assertEquals(HomeUiState.RouterUnreachable, holder.state.value)
    }

    @Test
    fun testSessionExpiredEmitsSessionExpiredState() = runTest {
        val holder = HomeStateHolder(ScriptedRepository(listOf(reading(), HomeLoadException.SessionExpired())))
        holder.refresh()
        holder.refresh()
        assertEquals(HomeUiState.SessionExpired, holder.state.value)
    }

    @Test
    fun testAccessDeniedEmitsNotPermittedState() = runTest {
        val holder = HomeStateHolder(ScriptedRepository(listOf(HomeLoadException.NotPermitted())))
        holder.refresh()
        assertEquals(HomeUiState.NotPermitted, holder.state.value)
    }

    @Test
    fun testMissingUbusModuleEmitsFeatureNotInstalledState() = runTest {
        val holder = HomeStateHolder(ScriptedRepository(listOf(HomeLoadException.FeatureNotInstalled())))
        holder.refresh()
        assertEquals(HomeUiState.FeatureNotInstalled, holder.state.value)
    }

    @Test
    fun testKeepsLastValuesBrieflyThenReportsUnreachable() = runTest {
        val repo = ScriptedRepository(listOf(reading(), HomeLoadException.Unreachable()))
        val holder = HomeStateHolder(repo, nowMs = { testScheduler.currentTime })
        holder.refresh()
        holder.refresh()
        assertTrue(dashboardOf(holder.state.value).stale)
        assertEquals(86, dashboardOf(holder.state.value).memUsedPct)
        holder.refresh()
        assertTrue(holder.state.value is HomeUiState.Loaded)
        holder.refresh()
        assertEquals(HomeUiState.RouterUnreachable, holder.state.value)
    }

    @Test
    fun testRecoversAfterTheRouterComesBack() = runTest {
        val repo = ScriptedRepository(listOf(HomeLoadException.Unreachable(), reading()))
        val holder = HomeStateHolder(repo, nowMs = { testScheduler.currentTime })
        holder.refresh()
        assertEquals(HomeUiState.RouterUnreachable, holder.state.value)
        holder.refresh()
        assertFalse(dashboardOf(holder.state.value).stale)
    }

    @Test
    fun testPollingRunsWhileActive() = runTest {
        val repo = ScriptedRepository(listOf(reading()))
        val holder = HomeStateHolder(repo, nowMs = { testScheduler.currentTime })
        val job = launch { holder.pollWhileActive() }
        runCurrent()
        assertEquals(1, repo.loads)
        advanceTimeBy(HomeStateHolder.SECOND_SAMPLE_DELAY_MS)
        runCurrent()
        assertEquals(2, repo.loads)
        advanceTimeBy(HomeStateHolder.REFRESH_INTERVAL_MS)
        runCurrent()
        assertEquals(3, repo.loads)
        advanceTimeBy(HomeStateHolder.REFRESH_INTERVAL_MS - 1)
        runCurrent()
        assertEquals(3, repo.loads)
        job.cancel()
    }

    @Test
    fun testPollingStopsOnBackground() = runTest {
        val repo = ScriptedRepository(listOf(reading()))
        val holder = HomeStateHolder(repo, nowMs = { testScheduler.currentTime })
        // The screen cancels this coroutine when it leaves the screen or the app goes to the background.
        val job = launch { holder.pollWhileActive() }
        runCurrent()
        advanceTimeBy(HomeStateHolder.SECOND_SAMPLE_DELAY_MS)
        runCurrent()
        assertEquals(2, repo.loads)
        job.cancel()
        runCurrent()
        advanceTimeBy(10 * HomeStateHolder.REFRESH_INTERVAL_MS)
        runCurrent()
        assertEquals(2, repo.loads)
    }

    @Test
    fun testPollingStopsWhenTheSessionHasExpired() = runTest {
        val repo = ScriptedRepository(listOf(reading(), HomeLoadException.SessionExpired()))
        val holder = HomeStateHolder(repo, nowMs = { testScheduler.currentTime })
        val job = launch { holder.pollWhileActive() }
        runCurrent()
        advanceTimeBy(10 * HomeStateHolder.REFRESH_INTERVAL_MS)
        runCurrent()
        assertEquals(2, repo.loads)
        assertEquals(HomeUiState.SessionExpired, holder.state.value)
        assertTrue(job.isCompleted)
    }
}
