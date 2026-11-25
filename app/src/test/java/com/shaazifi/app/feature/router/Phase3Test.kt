package com.shaazifi.app.feature.router

import com.shaazifi.app.ui.theme.PillTone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3 (T-20261117-C0594015). Sources (read 2026-11-17): openwrt/luci master
 * libs/rpcd-mod-luci/src/luci.c (getNetworkDevices: stats, flags, link), luci-base rpcd ucode
 * `luci` (getRealtimeStats via luci-bwc: rows [time, rx bytes, rx packets, tx bytes, tx packets]),
 * status/bandwidth.js (rate = byte difference / time difference), luci-proto-wireguard rpcd
 * `luci.wireguard` (getWgInstances), status/dmesg.js (`/bin/dmesg -r`), openwrt/rpcd session.c
 * (`session access` without object/function dumps the login's ACL by scope).
 */
class Phase3Test {

    private fun j(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun interfacesShowAddressesTrafficAndLinks() {
        val dump = FeatureMappersTest.fixture("m7_network_interface_dump.json")
        val devices = j("""{"eth0":{"name":"eth0","up":true,"mac":"02:00:00:00:00:01","stats":{"rx_bytes":2000000000,"tx_bytes":500000000},"link":{"speed":1000,"duplex":"full","carrier":true},"flags":{"up":true}},
            "lo":{"name":"lo","up":true,"flags":{"loopback":true},"stats":{"rx_bytes":1,"tx_bytes":1}}}""")
        val v = FeatureMappers.interfaces(devices, dump)
        val names = v.sections.flatMap { it.rows }.map { it.label }
        assertTrue("logical interfaces from the dump", names.any { it.equals("wan", true) || it.equals("lan", true) })
        val ports = v.sections.first { it.heading == "Ports" }.rows
        assertEquals(listOf("eth0"), ports.map { it.label }) // loopback hidden
        assertTrue(ports[0].value.contains("1000"))
        assertTrue(ports[0].sub!!.contains("2.0 GB"))
    }

    @Test
    fun liveTrafficIsTheChangeOverTime() {
        val rows = Json.parseToJsonElement("""{"result":[[100,1000,10,500,5],[101,126000,20,1500,8],[103,376000,30,3500,9]]}""").jsonObject
        val r = RealtimeStats.rates(rows)!!
        assertEquals(125_000.0, r.currentRx, 0.1) // (376000-126000)/2
        assertEquals(1_000.0, r.currentTx, 0.1)
        assertEquals(125_000.0, r.peakRx, 0.1)
        assertEquals((125_000.0 + 125_000.0) / 2, r.averageRx, 0.1)
        assertNull("one row is not enough", RealtimeStats.rates(Json.parseToJsonElement("""{"result":[[1,2,3,4,5]]}""").jsonObject))
        assertEquals("1.0 Mbit/s", RealtimeStats.rateText(125_000.0))
    }

    @Test
    fun wireGuardPeersShowHandshakeAndTraffic() {
        val wg = j("""{"wg0":{"name":"wg0","public_key":"PUB","listen_port":"51820","peers":[
            {"name":"Phone","public_key":"KEY1","endpoint":"203.0.113.5:4500","allowed_ips":["10.0.0.2/32"],"latest_handshake":"1000","transfer_rx":"2048","transfer_tx":"4096"},
            {"name":"","public_key":"KEY2ABCDEFGHIJ","endpoint":"(none)","allowed_ips":[],"latest_handshake":"0","transfer_rx":"0","transfer_tx":"0"}]}}""")
        val rows = WireGuardPeers.rows(wg, nowSeconds = 1120)
        assertEquals("Phone", rows[0].label)
        assertTrue(rows[0].sub!!.contains("2 min ago"))
        assertEquals(PillTone.Ok, rows[0].pill?.tone)
        assertEquals("Peer KEY2ABCD…", rows[1].label)
        assertEquals("Never", rows[1].value)
    }

    @Test
    fun kernelLogLinesAreParsed() {
        val out = "<6>[    0.000000] Booting Linux on physical CPU 0x0\n<3>[   12.500000] ath11k: firmware crashed\nnot a kernel line\n"
        val v = FeatureMappers.kernelLog(out)
        val rows = v.sections.first().rows
        assertEquals("ath11k: firmware crashed", rows.first().label) // newest first
        assertEquals(PillTone.Bad, rows.first().pill?.tone)
        assertEquals("12.5 s after start", rows.first().sub)
        assertEquals(2, rows.size)
        assertTrue(v.pill!!.text.contains("1"))
    }

    @Test
    fun theLoginsPermissionsDecideWhatIsShown() {
        val root = Capabilities.parse(j("""{"ubus":{"*":["*"]},"uci":{"*":["read","write"]}}"""))
        assertTrue(root.ubus("rc", "list"))
        val limited = Capabilities.parse(j("""{"ubus":{"luci-rpc":["getHostHints","getWirelessDevices"],"hostapd.*":["get_clients"],"system":["board","info"]},"uci":{"network":["read"]}}"""))
        assertTrue(limited.ubus("luci-rpc", "getWirelessDevices"))
        assertTrue(limited.ubus("hostapd.phy0-ap0", "get_clients"))
        assertFalse(limited.ubus("rc", "list"))
        assertTrue(limited.uciRead("network"))
        assertFalse(limited.uciRead("firewall"))
        assertFalse("services need rc list", limited.feature("services"))
        assertTrue("unknown features are not hidden", limited.feature("something-new"))
        assertTrue("no answer: hide nothing", Capabilities.UNKNOWN.feature("services"))
    }

    @Test
    fun onlyTheseExactReadsAreAllowed() {
        fun ok(o: String, m: String, a: JsonObject = JsonObject(emptyMap())) = RouterReader.isAllowedRead(o, m, a)
        assertTrue(ok("luci-rpc", "getNetworkDevices"))
        assertTrue(ok("luci.wireguard", "getWgInstances"))
        assertTrue(ok("session", "access"))
        assertTrue(ok("luci", "getRealtimeStats", buildJsonObject { put("mode", "interface"); put("device", "br-lan") }))
        assertFalse(ok("luci", "getRealtimeStats", buildJsonObject { put("mode", "interface"); put("device", "eth0; reboot") }))
        assertFalse(ok("luci", "getRealtimeStats", buildJsonObject { put("mode", "conntrack") }))
        assertTrue(ok("file", "exec", buildJsonObject { put("command", "/bin/dmesg"); put("params", Json.parseToJsonElement("""["-r"]""")) }))
        assertFalse(ok("luci", "setPassword"))
    }
}
