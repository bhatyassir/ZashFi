package com.shaazifi.app.feature.home

import com.shaazifi.app.feature.router.FrequentFeatures
import com.shaazifi.app.feature.router.SectionLook
import com.shaazifi.app.feature.router.ViewPill
import com.shaazifi.app.feature.router.ViewRow
import com.shaazifi.app.feature.router.ViewSection
import com.shaazifi.app.feature.web.MainTab
import com.shaazifi.app.feature.web.ShellNavState
import com.shaazifi.app.ui.theme.PillTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner feedback, 2026-11-18: AdGuard in the bottom bar; a "Frequently used" group in More;
 * richer pages; the Home graph must not jump back every second.
 */
class ThirdFeedbackTest {

    @Test
    fun adGuardIsABottomTab() {
        assertEquals(listOf("Home", "Devices", "Wi-Fi", "AdGuard", "Parental", "More"), MainTab.entries.map { it.label })
        val log = ShellNavState().select(MainTab.AdGuard).openQueryLog()
        assertEquals(MainTab.AdGuard, log.tab)
        assertTrue(log.queryLogOpen)
        assertFalse(log.back().queryLogOpen)
        assertEquals(MainTab.AdGuard, log.back().tab)
        val page = ShellNavState().select(MainTab.AdGuard).openAdGuardPage("filters")
        assertEquals("filters", page.adGuardPage)
        assertNull("tapping the tab again returns to its first screen", page.select(MainTab.AdGuard).adGuardPage)
    }

    @Test
    fun frequentlyUsedShowsTheMostOpenedFeatures() {
        val known = setOf("health", "bandwidth", "interfaces", "usage", "log")
        val top = FrequentFeatures.top(mapOf("health" to 2, "bandwidth" to 9, "interfaces" to 5, "gone" to 50, "log" to 1), known::contains, 3)
        assertEquals(listOf("bandwidth", "interfaces", "health"), top)
        assertEquals("unknown or hidden features are left out", listOf("bandwidth"), FrequentFeatures.top(mapOf("bandwidth" to 1, "gone" to 9), known::contains, 3))
        val starters = FrequentFeatures.top(emptyMap(), known::contains, 4)
        assertTrue("before anything is opened, useful starters", starters.isNotEmpty() && starters.all(known::contains))
    }

    @Test
    fun shortFactsAreShownAsTiles() {
        val facts = ViewSection("Now", listOf(ViewRow("Processor", "8%"), ViewRow("Memory", "42%"), ViewRow("Uptime", "3 d 4 h", pill = ViewPill("OK", PillTone.Ok))))
        assertTrue(SectionLook.asTiles(facts))
        val long = ViewSection("Messages", listOf(ViewRow("A very long message from the kernel about a driver", "x"), ViewRow("b", "y")))
        assertFalse(SectionLook.asTiles(long))
        val withSwitch = ViewSection("Wi-Fi", listOf(ViewRow("Guest", "On", toggle = true), ViewRow("Main", "On")))
        assertFalse("rows you can change stay rows", SectionLook.asTiles(withSwitch))
        assertFalse("one fact alone stays a row", SectionLook.asTiles(ViewSection("x", listOf(ViewRow("a", "1")))))
    }

    @Test
    fun theGraphKnowsWhenEachPointWasMeasured() {
        var now = 0L
        val repo = object : HomeRepository {
            var rx = 1_000_000L
            override suspend fun load(): HomeReading {
                rx += 125_000
                return HomeReading(
                    BoardInfo("R", "r", "OpenWrt"), 100, 40, CpuSample(1, 10),
                    WanStatus(true, "dhcp", "wan", null, null, 1), null, TrafficCounters(rx, 0)
                )
            }
        }
        val holder = HomeStateHolder(repo, nowMs = { now })
        kotlinx.coroutines.runBlocking {
            holder.refresh(); now = 1_000; holder.refresh(); now = 2_000; holder.refresh()
        }
        val d = (holder.state.value as HomeUiState.Loaded).dashboard
        assertEquals(listOf(1_000L, 2_000L), d.historyTimesMs)
        assertEquals(d.downloadHistoryBps.size, d.historyTimesMs.size)
    }
}
