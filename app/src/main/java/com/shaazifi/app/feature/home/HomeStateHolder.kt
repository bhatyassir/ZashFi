package com.shaazifi.app.feature.home

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the Home screen state and refreshes it.
 *
 * It owns no coroutine of its own. The screen runs [pollWhileActive] only while
 * it is visible; when the screen goes away the coroutine is cancelled and all
 * router calls stop.
 */
class HomeStateHolder(
    private val repository: HomeRepository,
    private val demo: Boolean = false,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    companion object {
        const val REFRESH_INTERVAL_MS = 5_000L
        /** CPU use needs two samples, so the second reading comes quickly (as UnTangled does). */
        const val SECOND_SAMPLE_DELAY_MS = 1_000L
        /** Failed refreshes in a row before the last good values are replaced by an error. */
        const val MAX_STALE_REFRESHES = 3
        /** Owner feedback: the chart gets a point every second between full readings. */
        const val FAST_SAMPLE_MS = 1_000L
        /** About three minutes of speed history at one point per second. */
        const val MAX_HISTORY_POINTS = 310
    }

    private val _state = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private var previousCpu: CpuSample? = null
    private var previousCounters: TrafficCounters? = null
    private var previousCountersAtMs = 0L
    private val history = ArrayDeque<Pair<Long, Double>>()
    private val uploadHistory = ArrayDeque<Double>()
    private var lastDashboard: HomeDashboard? = null
    private var failuresInARow = 0

    /** True when refreshing again cannot help until the user does something. */
    private fun needsUser(): Boolean = when (_state.value) {
        HomeUiState.SessionExpired, HomeUiState.NotPermitted, HomeUiState.FeatureNotInstalled -> true
        else -> false
    }

    suspend fun pollWhileActive() {
        refresh()
        if (needsUser()) return
        delay(SECOND_SAMPLE_DELAY_MS)
        while (true) {
            refresh()
            if (needsUser()) return
            // Between full readings, only the internet counters, once a second.
            var waited = 0L
            while (waited + FAST_SAMPLE_MS < REFRESH_INTERVAL_MS) {
                delay(FAST_SAMPLE_MS)
                waited += FAST_SAMPLE_MS
                sampleCounters()
            }
            delay(REFRESH_INTERVAL_MS - waited)
        }
    }

    /** One quick reading of the internet counters; updates speed and chart only. */
    private suspend fun sampleCounters() {
        val counters = try {
            repository.loadCounters()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return
        val last = lastDashboard ?: return
        if (last.stale) return
        val (down, up) = addCounters(counters, nowMs())
        if (down == null) return
        val d = last.copy(
            downloadBps = down,
            uploadBps = up,
            downloadHistoryBps = history.map { it.second },
            uploadHistoryBps = uploadHistory.toList(),
            historyTimesMs = history.map { it.first },
            historySpanSec = spanSec(),
            totalBytesSinceStart = counters.rxBytes + counters.txBytes
        )
        lastDashboard = d
        _state.value = if (demo) HomeUiState.DemoData(d) else HomeUiState.Loaded(d)
    }

    private fun spanSec(): Long = if (history.size >= 2) (history.last().first - history.first().first) / 1000 else 0L

    /** Works out the speed since the previous counters and adds it to the chart. */
    private fun addCounters(current: TrafficCounters?, now: Long): Pair<Double?, Double?> {
        var downloadBps: Double? = null
        var uploadBps: Double? = null
        val previous = previousCounters
        if (current != null && previous != null) {
            val seconds = (now - previousCountersAtMs) / 1000.0
            val rx = current.rxBytes - previous.rxBytes
            val tx = current.txBytes - previous.txBytes
            // Counters go backwards after a router restart; show nothing for that reading.
            if (seconds > 0.0 && rx >= 0 && tx >= 0) {
                downloadBps = rx * 8.0 / seconds
                uploadBps = tx * 8.0 / seconds
            }
        }
        previousCounters = current
        previousCountersAtMs = now
        if (downloadBps != null) {
            history.addLast(now to downloadBps)
            while (history.size > MAX_HISTORY_POINTS) history.removeFirst()
            uploadHistory.addLast(uploadBps ?: 0.0)
            while (uploadHistory.size > MAX_HISTORY_POINTS) uploadHistory.removeFirst()
        }
        return downloadBps to uploadBps
    }

    suspend fun refresh() {
        val reading = try {
            repository.load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: HomeLoadException.SessionExpired) {
            _state.value = HomeUiState.SessionExpired
            return
        } catch (e: HomeLoadException.NotPermitted) {
            _state.value = HomeUiState.NotPermitted
            return
        } catch (e: HomeLoadException.FeatureNotInstalled) {
            _state.value = HomeUiState.FeatureNotInstalled
            return
        } catch (e: Exception) {
            onUnreachable()
            return
        }
        failuresInARow = 0
        val dashboard = buildDashboard(reading)
        lastDashboard = dashboard
        _state.value = if (demo) HomeUiState.DemoData(dashboard) else HomeUiState.Loaded(dashboard)
    }

    private fun onUnreachable() {
        failuresInARow++
        // A rate across a gap would be wrong, so start measuring again afterwards.
        previousCpu = null
        previousCounters = null
        val last = lastDashboard
        if (last == null || failuresInARow >= MAX_STALE_REFRESHES) {
            _state.value = HomeUiState.RouterUnreachable
        } else {
            val stale = last.copy(stale = true)
            _state.value = if (demo) HomeUiState.DemoData(stale) else HomeUiState.Loaded(stale)
        }
    }

    private fun buildDashboard(reading: HomeReading): HomeDashboard {
        val now = nowMs()

        val cpuPct = HomeParsers.cpuUsedPct(previousCpu, reading.cpu)
        previousCpu = reading.cpu

        val current = reading.wanCounters
        val (downloadBps, uploadBps) = addCounters(current, now)
        val spanSec = spanSec()

        return HomeDashboard(
            routerName = reading.board.model,
            firmware = reading.board.firmware,
            internetUp = reading.wan.up,
            wanIp = reading.wan.ipv4,
            uptimeText = HomeParsers.formatUptime(reading.uptimeSec),
            memUsedPct = reading.memUsedPct,
            cpuUsedPct = cpuPct,
            clients = reading.clients,
            downloadBps = downloadBps,
            uploadBps = uploadBps,
            downloadHistoryBps = history.map { it.second },
            uploadHistoryBps = uploadHistory.toList(),
            historyTimesMs = history.map { it.first },
            historySpanSec = spanSec,
            totalBytesSinceStart = current?.let { it.rxBytes + it.txBytes },
            notices = reading.notices,
            stale = false,
            interfaces = reading.interfaces,
            radios = reading.radios
        )
    }
}
