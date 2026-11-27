package com.shaazifi.app.feature.home

import com.shaazifi.app.feature.devices.DeviceKind
import com.shaazifi.app.feature.router.FeatureMappers
import com.shaazifi.app.feature.router.FeatureMappersTest
import com.shaazifi.app.feature.router.PortKind
import com.shaazifi.app.feature.web.MainTab
import com.shaazifi.app.feature.web.MorePage
import com.shaazifi.app.feature.web.ShellNavState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner feedback, 2026-11-17 (Notes PDF): back goes to the page you came from; the live chart is
 * smooth and near real time; device page and interfaces/ports page get a richer look.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MoreOwnerFeedbackTest {

    @Test
    fun backReturnsToThePageYouCameFrom() {
        val home = ShellNavState()
        assertFalse(home.canGoBack)

        // Home -> speed card -> "Who is using the internet" -> back: Home again (not the More list).
        val bw = home.openMore(MorePage.Feature("bandwidth"))
        assertTrue(bw.canGoBack)
        val back = bw.back()
        assertEquals(MainTab.Home, back.tab)
        assertFalse(back.canGoBack)

        // Switching tabs never builds cross-tab Back history; Back walks pages inside a tab.
        val wifi = home.select(MainTab.Devices).select(MainTab.Wifi)
        assertEquals(MainTab.Wifi, wifi.back().tab)
        assertFalse(wifi.canGoBack)

        // Deep: a More page and an AdGuard page each pop back to their own tab root.
        val deep = home.select(MainTab.More).openMore(MorePage.Feature("log"))
            .select(MainTab.AdGuard).openAdGuardPage("filters")
        assertNull(deep.back().adGuardPage)
        assertEquals(MainTab.AdGuard, deep.back().tab)
        assertFalse(deep.back().canGoBack)

        // The page you left behind is still there when you switch back to its tab.
        val more = deep.select(MainTab.More)
        assertEquals(MorePage.Feature("log"), more.morePage)
        assertNull(more.back().morePage)
        assertEquals(MainTab.More, more.back().tab)

        // A screen opened straight from a shortcut still goes up one level.
        assertNull(ShellNavState().openWifiFeature("guest").back().wifiPage)
    }

    private fun reading(rx: Long, tx: Long) = HomeReading(
        board = BoardInfo("Example Router X1", "Device-1", "OpenWrt"),
        uptimeSec = 100, memUsedPct = 40, cpu = CpuSample(100, 1000),
        wan = WanStatus(true, "dhcp", "wan", "198.51.100.2", "198.51.100.1", 50),
        clients = null, wanCounters = TrafficCounters(rx, tx)
    )

    private class FastRepo(val base: HomeReading) : HomeRepository {
        var loads = 0
        var counterReads = 0
        override suspend fun load(): HomeReading { loads++; return base }
        override suspend fun loadCounters(): TrafficCounters {
            counterReads++
            return TrafficCounters(base.wanCounters!!.rxBytes + counterReads * 125_000L, base.wanCounters!!.txBytes + counterReads * 12_500L)
        }
    }

    @Test
    fun theChartGetsAPointEverySecondWithoutFullReloads() = runTest {
        val repo = FastRepo(reading(1_000_000, 100_000))
        val holder = HomeStateHolder(repo, nowMs = { testScheduler.currentTime })
        val job = launch { holder.pollWhileActive() }
        runCurrent()
        advanceTimeBy(HomeStateHolder.SECOND_SAMPLE_DELAY_MS)
        runCurrent()
        assertEquals(2, repo.loads)
        advanceTimeBy(HomeStateHolder.FAST_SAMPLE_MS)
        runCurrent()
        assertEquals("no extra full reload", 2, repo.loads)
        assertEquals(1, repo.counterReads)
        val d = (holder.state.value as HomeUiState.Loaded).dashboard
        assertEquals(1_000_000.0, d.downloadBps!!, 0.1) // 125,000 bytes in 1 s
        assertEquals(100_000.0, d.uploadBps!!, 0.1)
        assertEquals(d.downloadHistoryBps.size, d.uploadHistoryBps.size)
        job.cancel()
    }

    @Test
    fun devicesGetAKindFromTheirNameOrMaker() {
        assertEquals(DeviceKind.PHONE, DeviceKind.guess("Galaxy-S24", null))
        assertEquals(DeviceKind.PHONE, DeviceKind.guess("iPhone", null))
        assertEquals(DeviceKind.COMPUTER, DeviceKind.guess("DESKTOP-4KQ1", null))
        assertEquals(DeviceKind.COMPUTER, DeviceKind.guess("Office-MacBook-Air", null))
        assertEquals(DeviceKind.TV, DeviceKind.guess("LGwebOSTV", null))
        assertEquals(DeviceKind.CONSOLE, DeviceKind.guess("PS5-123", null))
        assertEquals(DeviceKind.PRINTER, DeviceKind.guess("HP-LaserJet", null))
        assertEquals(DeviceKind.SMART_HOME, DeviceKind.guess("192.168.1.40", "Espressif Inc."))
        assertEquals(DeviceKind.OTHER, DeviceKind.guess("192.168.1.121", null))
    }

    @Test
    fun interfacesPageKnowsWhichPortIsWhich() {
        val dump = FeatureMappersTest.fixture("m7_network_interface_dump.json")
        val devices = Json.parseToJsonElement("""{
            "wan":{"name":"wan","up":true,"stats":{"rx_bytes":9000,"tx_bytes":100},"link":{"speed":1000,"duplex":"full","carrier":true}},
            "lan1":{"name":"lan1","up":true,"stats":{"rx_bytes":5,"tx_bytes":5},"link":{"speed":100,"duplex":"full","carrier":true}},
            "lan2":{"name":"lan2","up":true,"stats":{"rx_bytes":0,"tx_bytes":0},"link":{"carrier":false}},
            "br-lan":{"name":"br-lan","up":true,"stats":{"rx_bytes":7,"tx_bytes":7},"link":{"speed":-1,"carrier":true}},
            "phy0-ap0":{"name":"phy0-ap0","up":true,"stats":{"rx_bytes":1,"tx_bytes":1},"link":{"speed":0,"carrier":true}},
            "lo":{"name":"lo","up":true,"flags":{"loopback":true},"stats":{"rx_bytes":1,"tx_bytes":1}}}""").jsonObject
        val pic = FeatureMappers.interfaces(devices, dump).network
        assertNotNull(pic)
        val byName = pic!!.ports.associateBy { it.name }
        assertEquals(PortKind.INTERNET, byName.getValue("wan").kind)
        assertEquals(PortKind.LAN, byName.getValue("lan1").kind)
        assertFalse(byName.getValue("lan2").connected)
        assertEquals(PortKind.BRIDGE, byName.getValue("br-lan").kind)
        assertEquals(PortKind.WIRELESS, byName.getValue("phy0-ap0").kind)
        assertFalse("loopback hidden", byName.containsKey("lo"))
        assertEquals(1000, byName.getValue("wan").speedMbps)
        assertNull("no speed for virtual ports", byName.getValue("br-lan").speedMbps)
        assertTrue(pic.links.any { it.name == "lan" && it.up })
        assertTrue(pic.links.none { it.name == "loopback" })
    }
}
