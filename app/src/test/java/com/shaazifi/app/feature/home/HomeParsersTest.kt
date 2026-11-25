package com.shaazifi.app.feature.home

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The formulas must match UnTangled's own (reference/untangled/src/lib/net.ts).
 * All data here is made up; reply shapes follow the test router's scrubbed replies.
 */
class HomeParsersTest {

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun testMemoryPercentageFormulaMatchesUntangled() {
        // round((total - available) / total * 100) -> 86.09 -> 86
        val info = obj("""{"uptime":71426,"memory":{"total":452972544,"free":59871232,"available":63025152}}""")
        assertEquals(86, HomeParsers.parseMemUsedPct(info))
        assertEquals(71426L, HomeParsers.parseUptimeSec(info))
        // No "available": fall back to "free" -> (1000-250)/1000 -> 75
        assertEquals(75, HomeParsers.parseMemUsedPct(obj("""{"memory":{"total":1000,"free":250}}""")))
        // 82.6 -> 83
        assertEquals(83, HomeParsers.parseMemUsedPct(obj("""{"memory":{"total":1000,"available":174}}""")))
        assertEquals(0, HomeParsers.parseMemUsedPct(obj("""{"memory":{"total":0,"available":0}}""")))
        assertEquals(0, HomeParsers.parseMemUsedPct(obj("""{}""")))
    }

    @Test
    fun testCpuPercentageTwoSampleFormulaMatchesUntangled() {
        val first = HomeParsers.parseCpuSample(
            "cpu  43003 2 25534 28420565 979 0 14589 0 0 0\ncpu0 10399 0 6894 7076919 161 0 14304 0 0 0\nintr 1 2 3\n"
        )
        // total = first 8 fields; idle = idle + iowait
        assertEquals(CpuSample(busy = 83128, total = 28504672), first)
        val second = HomeParsers.parseCpuSample("cpu  43023 2 25534 28421545 979 0 14589 0 0 0\n")
        // 20 busy ticks out of 1000 -> 2 %
        assertEquals(2, HomeParsers.cpuUsedPct(first, second))
        // No value until two samples exist, or when the counter did not move.
        assertNull(HomeParsers.cpuUsedPct(null, second))
        assertNull(HomeParsers.cpuUsedPct(first, null))
        assertNull(HomeParsers.cpuUsedPct(second, second))
        assertNull(HomeParsers.cpuUsedPct(second, first))
        // Unusable text gives no sample instead of a crash.
        assertNull(HomeParsers.parseCpuSample("nothing here"))
        assertNull(HomeParsers.parseCpuSample("cpu  1 2 x 4"))
        assertNull(HomeParsers.parseCpuSample("cpu  1 2"))
        assertEquals(100, HomeParsers.cpuUsedPct(CpuSample(0, 100), CpuSample(500, 200)))
    }

    @Test
    fun testUptimeFormattingMatchesUntangled() {
        assertEquals("18h 55m", HomeParsers.formatUptime(68100))
        assertEquals("12d 3h 4m", HomeParsers.formatUptime(12L * 86400 + 3 * 3600 + 4 * 60 + 59))
        assertEquals("5m", HomeParsers.formatUptime(359))
        assertEquals("0m", HomeParsers.formatUptime(40))
        assertEquals("1d 0h 0m", HomeParsers.formatUptime(86400))
        assertEquals("—", HomeParsers.formatUptime(0))
    }

    @Test
    fun testClientCountAndClassificationMatchesUntangled() {
        val hints = obj(
            """{
              "02:00:00:00:00:01": {"ipaddrs": ["198.51.100.11"], "ip6addrs": []},
              "02:00:00:00:00:02": {"ipaddrs": [], "ip6addrs": ["2001:db8::2"]},
              "02:00:00:00:00:03": {"ipv4": "198.51.100.13"},
              "02:00:00:00:00:0a": {"ipaddrs": ["198.51.100.20"]},
              "not-a-mac": {"ipaddrs": ["198.51.100.99"]}
            }"""
        )
        val leases = obj(
            """{"dhcp_leases": [
              {"macaddr": "02:00:00:00:00:01", "hostname": "Device-1", "ipaddr": "198.51.100.11"},
              {"macaddr": "02:00:00:00:00:04", "hostname": "*", "ipaddr": "198.51.100.14"},
              {"macaddr": "02:00:00:00:00:02", "hostname": "Device-2", "ipaddr": ""},
              {"hostname": "no-mac", "ipaddr": "198.51.100.50"}
            ]}"""
        )
        // Devices with an IPv4 address: 01, 03, 0A, 04. Device 02 has IPv6 only and is not counted.
        val wifi = setOf("02:00:00:00:00:0A", "02:00:00:00:00:02")
        assertEquals(
            ClientSummary(total = 4, wifi = 1, wired = 3, wifiKnown = true),
            HomeParsers.summarizeClients(hints, leases, wifi, wifiOk = true)
        )
        // Access points answered with no clients: everything is wired.
        assertEquals(
            ClientSummary(total = 4, wifi = 0, wired = 4, wifiKnown = true),
            HomeParsers.summarizeClients(hints, leases, emptySet(), wifiOk = true)
        )
        // Wi-Fi did not answer: do not claim "wired".
        assertEquals(
            ClientSummary(total = 4, wifi = 0, wired = 0, wifiKnown = false),
            HomeParsers.summarizeClients(hints, leases, emptySet(), wifiOk = false)
        )
        assertEquals(
            ClientSummary(total = 0, wifi = 0, wired = 0, wifiKnown = true),
            HomeParsers.summarizeClients(obj("{}"), obj("{}"), emptySet(), wifiOk = true)
        )
    }

    @Test
    fun testParsesBoard() {
        val board = HomeParsers.parseBoard(
            obj(
                """{"kernel":"6.18.39","hostname":"Device-1","model":"Example Router X1","board_name":"example,x1",
                "release":{"distribution":"OpenWrt","version":"SNAPSHOT","description":"OpenWrt SNAPSHOT r1+2-abc"}}"""
            )
        )
        assertEquals(BoardInfo("Example Router X1", "Device-1", "OpenWrt SNAPSHOT r1+2-abc"), board)
        assertEquals(
            BoardInfo("example,x1", "openwrt", "OpenWrt 24.10.0"),
            HomeParsers.parseBoard(obj("""{"board_name":"example,x1","release":{"distribution":"OpenWrt","version":"24.10.0"}}"""))
        )
        assertEquals(BoardInfo("Router", "openwrt", "OpenWRT"), HomeParsers.parseBoard(obj("{}")))
    }

    @Test
    fun testParsesWanInterface() {
        val dump = obj(
            """{"interface":[
              {"interface":"lan","up":true,"proto":"static","l3_device":"br-lan","device":"br-lan",
               "ipv4-address":[{"address":"198.51.100.1","mask":24}],"route":[],"uptime":69634},
              {"interface":"loopback","up":true,"proto":"static","l3_device":"lo","device":"lo","uptime":69633},
              {"interface":"wan","up":true,"proto":"static","l3_device":"wan","device":"wan",
               "ipv4-address":[{"address":"198.51.100.2","mask":24}],
               "route":[{"target":"0.0.0.0","mask":0,"nexthop":"198.51.100.3"}],"uptime":64384}
            ]}"""
        )
        assertEquals(
            WanStatus(up = true, proto = "static", device = "wan", ipv4 = "198.51.100.2", gateway = "198.51.100.3", uptimeSec = 64384),
            HomeParsers.parseWan(dump)
        )
        // No interface named "wan": use the one that holds the default route.
        val pppoe = obj(
            """{"interface":[
              {"interface":"lan","up":true,"proto":"static","device":"br-lan"},
              {"interface":"internet","up":false,"proto":"pppoe","device":"eth1","route":[{"target":"0.0.0.0","nexthop":"203.0.113.1"}]}
            ]}"""
        )
        val wan = HomeParsers.parseWan(pppoe)
        assertEquals("eth1", wan.device)
        assertFalse(wan.up)
        assertNull(wan.ipv4)
        assertEquals("203.0.113.1", wan.gateway)
        assertEquals(
            WanStatus(up = false, proto = "?", device = "", ipv4 = null, gateway = null, uptimeSec = 0),
            HomeParsers.parseWan(obj("{}"))
        )
    }

    @Test
    fun testParsesTrafficCounters() {
        val status = obj("""{"wan":{"up":true,"statistics":{"rx_bytes":12005628864,"tx_bytes":313103537}},"lo":{"up":true}}""")
        assertEquals(TrafficCounters(12005628864L, 313103537L), HomeParsers.parseDeviceCounters(status, "wan"))
        assertNull(HomeParsers.parseDeviceCounters(status, "lo"))
        assertNull(HomeParsers.parseDeviceCounters(status, "missing"))
        assertNull(HomeParsers.parseDeviceCounters(status, ""))
    }

    @Test
    fun testWirelessPlanListsOnlySafeAccessPoints() {
        val status = obj(
            """{
              "radio0":{"up":true,"config":{"band":"2g"},"interfaces":[
                {"section":"a","ifname":"phy0-ap0","config":{"mode":"ap","ssid":"FakeWiFi-Network"}},
                {"section":"b","ifname":"phy0-sta0","config":{"mode":"sta"}}]},
              "radio1":{"up":true,"config":{"band":"5g"},"interfaces":[
                {"section":"c","config":{"ifname":"phy1-ap0"}},
                {"section":"d","ifname":"bad name; reboot","config":{"mode":"ap"}}]}
            }"""
        )
        val plan = HomeParsers.parseWirelessPlan(status)
        assertFalse(plan.wifiAbsent)
        assertEquals(3, plan.accessPoints)
        assertEquals(listOf("phy0-ap0", "phy1-ap0"), plan.ifnames)
        assertTrue(HomeParsers.parseWirelessPlan(obj("{}")).wifiAbsent)
        assertTrue(HomeParsers.isSafeIfname("wlan0.1"))
        assertFalse(HomeParsers.isSafeIfname(""))
        assertFalse(HomeParsers.isSafeIfname("a b"))
        assertFalse(HomeParsers.isSafeIfname("x".repeat(33)))
    }

    @Test
    fun testParsesWifiClientLists() {
        val hostapd = obj(
            """{"freq":5745,"clients":{
              "02:00:00:00:00:0a":{"assoc":true,"signal":-51},
              "02:00:00:00:00:0b":{"assoc":false},
              "02:00:00:00:00:0c":{}
            }}"""
        )
        assertEquals(setOf("02:00:00:00:00:0A", "02:00:00:00:00:0C"), HomeParsers.parseHostapdClients(hostapd))
        assertEquals(emptySet<String>(), HomeParsers.parseHostapdClients(obj("""{"freq":2412,"clients":{}}""")))
        assertNull(HomeParsers.parseHostapdClients(obj("{}")))
        assertEquals(
            setOf("02:00:00:00:00:0D"),
            HomeParsers.parseIwinfoAssoc(obj("""{"results":[{"mac":"02:00:00:00:00:0d","signal":-60},{"signal":-70}]}"""))
        )
    }

    @Test
    fun testFormatsBytesAndRates() {
        assertEquals("12.32 GB", HomeParsers.formatBytes(12_318_732_401L))
        assertEquals("1.50 TB", HomeParsers.formatBytes(1_500_000_000_000L))
        assertEquals("313.1 MB", HomeParsers.formatBytes(313_103_537L))
        assertEquals("999 B", HomeParsers.formatBytes(999L))
        assertEquals("42.6" to "Mbps", HomeParsers.formatRate(42_600_000.0))
        assertEquals("850" to "Kbps", HomeParsers.formatRate(850_000.0))
        assertEquals("0" to "bps", HomeParsers.formatRate(0.0))
        assertEquals("1.20" to "Gbps", HomeParsers.formatRate(1_200_000_000.0))
    }
}
