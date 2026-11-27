package com.shaazifi.app.feature.router

import com.shaazifi.app.ui.theme.PillTone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Parsers checked against scrubbed real replies (docs/fixtures, M7) and documented shapes. */
class FeatureMappersTest {

    companion object {
        /** Loads a scrubbed router reply and returns the result object (`result[1]`). */
        fun fixture(name: String): JsonObject {
            val file = listOf(File("../docs/fixtures/$name"), File("docs/fixtures/$name")).first { it.isFile }
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            return (root["result"] as JsonArray)[1].jsonObject
        }

        fun uci(json: String) = UciConfig.parse(Json.parseToJsonElement("""{"values":$json}""").jsonObject)

        fun allText(v: FeatureView): String =
            (listOf(v.summary, v.note.orEmpty(), v.pill?.text.orEmpty()) + v.sections.flatMap { s ->
                listOf(s.heading, s.emptyText.orEmpty()) + s.rows.flatMap { listOf(it.label, it.value, it.sub.orEmpty(), it.pill?.text.orEmpty()) }
            }).joinToString("\n")
    }

    @Test
    fun wirelessUsesTheRealReplyAndNeverShowsTheWifiKey() {
        val reply = fixture("m7_luci_rpc_wirelessdevices.json")
        assertTrue("fixture must contain a key to make this test meaningful", reply.toString().contains("REDACTED_SECRET"))
        val view = FeatureMappers.wireless(reply)
        val text = allText(view)
        assertFalse("Wi-Fi key leaked into the screen", text.contains("REDACTED_SECRET"))
        assertTrue(text.contains("FakeWiFi-Network"))
        assertTrue(text.contains("2.4 GHz"))
        assertTrue(text.contains("WPA2"))
        listOf(FeatureMappers.guest(reply), FeatureMappers.extendWifi(reply), FeatureMappers.doctor(null, null, reply, null)).forEach {
            assertFalse(allText(it).contains("REDACTED_SECRET"))
        }
    }

    @Test
    fun wirelessKeepsEachBandInOneCard() {
        // Owner, 2026-11-17: "make sure options are consolidated and not unnecessarily spread".
        val view = FeatureMappers.wireless(fixture("m7_luci_rpc_wirelessdevices.json"))
        assertTrue("no separate change cards below the bands", view.forms.isEmpty())
        val withNetwork = view.sections.filter { s -> s.rows.any { !it.detail && it.label == "FakeWiFi-Network" } }
        assertTrue(withNetwork.isNotEmpty())
        withNetwork.forEach { s ->
            assertTrue("each band carries its own change form", s.form != null)
            val main = s.rows.filterNot { it.detail }.map { it.label }
            assertFalse("no 'Password: Hidden' filler row", main.contains("Password"))
            assertFalse("technical details are folded away", main.any { it in setOf("Channel", "Channel width", "Country", "Transmit power") })
            assertTrue(s.rows.any { it.detail && it.label == "Channel" })
        }
    }

    @Test
    fun wanShowsTypeButNeverThePppoePassword() {
        val net = uci("""{"wan":{".type":"interface","proto":"pppoe","username":"isp-user","password":"isp-secret"}}""")
        val text = allText(FeatureMappers.wan(net, null))
        assertTrue(text.contains("PPPoE"))
        assertFalse(text.contains("isp-secret"))
        assertFalse(text.contains("isp-user"))
    }

    @Test
    fun wanLiveStatusComesFromTheRealInterfaceDump() {
        val net = uci("""{"wan":{".type":"interface","proto":"dhcp"}}""")
        val view = FeatureMappers.wan(net, fixture("m7_network_interface_dump.json"))
        assertTrue(allText(view).contains("Automatic (DHCP)"))
        assertTrue(view.pill!!.text == "Online" || view.pill!!.text == "Offline")
    }

    @Test
    fun lanReadsCidrAddressesAndThePool() {
        val net = uci("""{"lan":{".type":"interface","ipaddr":"192.168.1.1/24"}}""")
        val dhcp = uci("""{"lan":{".type":"dhcp","interface":"lan","start":"100","limit":"150","leasetime":"12h"},"h1":{".type":"host","mac":"AA:BB:CC:DD:EE:FF","ip":"192.168.1.20"}}""")
        val text = allText(FeatureMappers.lan(net, dhcp))
        assertTrue(text.contains("255.255.255.0"))
        assertTrue(text.contains(".100"))
        assertTrue(text.contains("Devices with a fixed address\n1"))
        assertEquals("255.255.255.0", FeatureMappers.prefixToMask(24))
        assertEquals("255.255.252.0", FeatureMappers.prefixToMask(22))
        assertEquals("0.0.0.0", FeatureMappers.prefixToMask(0))
    }

    @Test
    fun portForwardingSkipsTheDmzRuleAndDdnsHidesPasswords() {
        val fw = uci("""{
          "r1":{".type":"redirect","name":"Camera","src":"wan","src_dport":"8443","dest_ip":"192.168.1.70","proto":"tcp"},
          "dmz":{".type":"redirect","name":"DMZ","src":"wan","dest_ip":"192.168.1.9","proto":"all"}}""")
        val pf = FeatureMappers.portForwarding(fw)
        assertEquals(1, pf.sections[0].rows.size)
        assertTrue(pf.sections[0].rows[0].value.contains("8443"))
        val dmz = FeatureMappers.dmz(fw)
        assertEquals(PillTone.Bad, dmz.pill!!.tone)
        assertTrue(allText(dmz).contains("192.168.1.9"))

        val ddns = uci("""{"s":{".type":"service","enabled":"1","lookup_host":"home.example.net","service_name":"duckdns.org","password":"ddns-secret"}}""")
        assertFalse(allText(FeatureMappers.ddns(ddns)).contains("ddns-secret"))
    }

    @Test
    fun vpnNeverShowsKeys() {
        val net = uci("""{
          "wg0":{".type":"interface","proto":"wireguard","private_key":"PRIVATE-KEY-VALUE","listen_port":"51820","addresses":["10.9.0.1/24"]},
          "p1":{".type":"wireguard_wg0","description":"Phone","public_key":"PUB","preshared_key":"PSK-VALUE","allowed_ips":["10.9.0.2/32"]}}""")
        val text = allText(FeatureMappers.vpn(net, null))
        assertFalse(text.contains("PRIVATE-KEY-VALUE"))
        assertFalse(text.contains("PSK-VALUE"))
        assertTrue(text.contains("51820"))
        assertTrue(text.contains("Phone"))
    }

    @Test
    fun parentalModesFollowTheRule() {
        val fw = uci("""{
          "a":{".type":"rule","name":"Parental: tablet","src_mac":"aa:bb:cc:dd:ee:01","weekdays":["Mon","Tue"],"start_time":"21:30","stop_time":"06:30"},
          "b":{".type":"rule","name":"Parental: tv","src_mac":"AA:BB:CC:DD:EE:02"},
          "c":{".type":"rule","name":"Parental: off","src_mac":"AA:BB:CC:DD:EE:03","enabled":"0"},
          "d":{".type":"rule","name":"Allow-Ping"}}""")
        val rows = FeatureMappers.parental(fw, mapOf("AA:BB:CC:DD:EE:01" to "Tablet")).sections[0].rows
        assertEquals(3, rows.size)
        assertEquals("Tablet", rows[0].label)
        // The state is shown as text next to an on/off switch (the switch is the rule's "enabled").
        assertTrue(rows[0].sub!!.startsWith("Scheduled"))
        assertTrue(rows[1].sub!!.startsWith("Paused"))
        assertTrue(rows[2].sub!!.startsWith("Off"))
        assertEquals(listOf(true, true, false), rows.map { it.toggle })
    }

    @Test
    fun usageReadsVnstatAndNlbwmon() {
        val vn = """{"vnstatversion":"2.12","jsonversion":"2","interfaces":[{"name":"wan","traffic":{
            "day":[{"date":{"year":2026,"month":11,"day":15},"rx":1000,"tx":500},{"date":{"year":2026,"month":11,"day":16},"rx":2000000000,"tx":1000}],
            "month":[{"date":{"year":2026,"month":11},"rx":3000000000,"tx":2000}]}}]}"""
        val nl = """{"columns":["family","ip","mac","rx_bytes","tx_bytes"],"data":[[4,"192.168.1.5","aa:bb:cc:dd:ee:ff",5000,100],[4,"192.168.1.6","11:22:33:44:55:66",0,0]]}"""
        val view = FeatureMappers.usage(vn, nl, "wan", mapOf("AA:BB:CC:DD:EE:FF" to "Laptop"))
        val text = allText(view)
        assertTrue(text.contains("16/11"))
        assertTrue(text.contains("Laptop"))
        assertEquals(1, view.sections.last().rows.size)
        val missing = FeatureMappers.usage(null, null, "wan", emptyMap())
        assertTrue(allText(missing).contains("vnstat"))
        assertTrue(allText(missing).contains("nlbwmon"))

        // Dual-stack nlbw rows for the same MAC are grouped into one By device row.
        val dualStack = """{"columns":["ip","mac","rx_bytes","tx_bytes"],"data":[
            ["192.168.1.5","aa:bb:cc:dd:ee:ff",1000,500],
            ["fd00::5","AA:BB:CC:DD:EE:FF",2000,250]]}"""
        val grouped = FeatureMappers.usage(null, dualStack, null, mapOf("AA:BB:CC:DD:EE:FF" to "Laptop"))
        val byDevice = grouped.sections.first { it.heading == "By device" }.rows
        assertEquals(1, byDevice.size)
        assertTrue(byDevice[0].sub!!.startsWith("192.168.1.5"))

        // vnstat jsonversion 1 reports KiB and scales to bytes; stale day does not show as Today when todayDate is given.
        val vnV1 = """{"vnstatversion":"1.18","jsonversion":"1","interfaces":[{"name":"wan","traffic":{
            "day":[{"date":{"year":2026,"month":11,"day":16},"rx":1024,"tx":0}],
            "month":[{"date":{"year":2026,"month":11},"rx":2048,"tx":0}]}}]}"""
        val v1SameDay = FeatureMappers.usage(vnV1, null, "wan", emptyMap(), todayDate = Triple(2026, 11, 16))
        assertTrue(allText(v1SameDay).contains("1.0 MB"))
        val v1LaterDay = FeatureMappers.usage(vnV1, null, "wan", emptyMap(), todayDate = Triple(2026, 11, 20))
        assertEquals("Today 0 B", v1LaterDay.pill?.text)

        // Non-zero exit codes report an error instead of "Needs package".
        val errUsage = FeatureMappers.usage(
            null, null, "wan", emptyMap(),
            vnstatResult = ExecResult(1, "", "db not found"),
            nlbwResult = ExecResult(2, "", "socket failed")
        )
        assertTrue(allText(errUsage).contains("exit 1"))
        assertTrue(allText(errUsage).contains("exit 2"))
    }

    @Test
    fun packageListsFromApkAndOpkg() {
        val apk = "vnstat-2.12-r1 aarch64 {vnstat} (GPL-2.0) [installed]\nluci-app-sqm-1.2 noarch {luci} (Apache) [installed]\nsamba4-server-4.20.0-r1 aarch64 {samba4} (GPL-3.0) [installed]\nfoo-2-bar-1.0-r1 aarch64 {foo} (MIT) [installed]\n"
        assertEquals(listOf("vnstat", "luci-app-sqm", "samba4-server", "foo-2-bar"), FeatureMappers.parsePackages(apk))
        val apkInfo = "vnstat2\nsamba4-server\n"
        assertEquals(listOf("vnstat2", "samba4-server"), FeatureMappers.parsePackages(apkInfo))
        val soft = FeatureMappers.software(apkInfo)
        assertTrue(soft.sections.first().rows.any { it.label == "vnstat2" && it.pill?.text == "Installed" })
        assertTrue(soft.sections.first().rows.any { it.label == "samba4-server" && it.pill?.text == "Installed" })
        val opkg = "tailscale - 1.80.0-1\nsqm-scripts - 1.6.0-1\n"
        assertEquals(listOf("tailscale", "sqm-scripts"), FeatureMappers.parsePackages(opkg))
    }

    @Test
    fun logShowsNewestFirstAndCountsErrors() {
        val reply = Json.parseToJsonElement("""{"log":[
            {"time":1763280000,"priority":6,"source":1,"msg":"first"},
            {"time":1763280001,"priority":3,"source":1,"msg":"bad thing"}]}""").jsonObject
        val view = FeatureMappers.log(reply)
        assertEquals("bad thing", view.sections[0].rows[0].label)
        assertEquals("1 errors", view.pill!!.text)
    }

    @Test
    fun tailscaleStatus() {
        val out = """{"BackendState":"Running","Self":{"HostName":"router","TailscaleIPs":["100.64.0.7"],"PrimaryRoutes":["192.168.1.0/24","0.0.0.0/0"],"ExitNodeOption":true},
            "ExitNodeStatus":{"ID":"n1","TailscaleIPs":["100.64.0.99"],"Online":true},
            "Peer":{"k1":{"HostName":"phone","TailscaleIPs":["100.64.0.8"],"OS":"android","Online":true}}}"""
        val view = FeatureMappers.tailscale(out)
        assertEquals("Connected", view.pill!!.text)
        val text = allText(view)
        assertTrue(text.contains("phone"))
        assertTrue(text.contains("192.168.1.0/24"))
        assertTrue(text.contains("100.64.0.99"))
        assertEquals("Unknown", FeatureMappers.tailscale("not json").pill!!.text)
        assertEquals("Stopped", FeatureMappers.tailscale("", exitCode = 1, stderr = "tailscaled not running").pill!!.text)
        val loginView = FeatureMappers.tailscale("""{"BackendState":"NeedsLogin","AuthURL":"https://login.tailscale.com/a/123","Self":{"HostName":"router","TailscaleIPs":[]}}""")
        assertEquals("Sign in needed", loginView.pill!!.text)
        assertTrue(allText(loginView).contains("https://login.tailscale.com/a/123"))
    }

    @Test
    fun healthIncludesThermalsBoardAndOffloading() {
        val info = fixture("m7_system_info.json")
        val board = fixture("m7_system_board.json")
        val thermals = listOf(
            ThermalSensor("tz0", "nss-top", "NSS", 62.4, maxCelsius = 90.0),
            ThermalSensor("tz1", "cpu", "Processor", 78.0, critCelsius = 105.0),
            ThermalSensor("tz2", "wifi", "Wi-Fi radio", 86.5, critCelsius = 100.0)
        )
        val view = FeatureMappers.health(info, cpuPct = 15, thermals = thermals, board = board, offloading = FlowOffloadingStatus(software = true, hardware = true))
        val thermSec = view.sections.first { it.heading == "Thermals" }
        assertEquals(3, thermSec.rows.size)
        assertEquals("62.4°C", thermSec.rows[0].value)
        assertEquals(null, thermSec.rows[0].pill)
        assertEquals("Warm", thermSec.rows[1].pill?.text)
        assertEquals("Hot", thermSec.rows[2].pill?.text)
        assertEquals("Hot", view.pill?.text)
        val details = view.sections.first { it.heading == "Now" }.rows.filter { it.detail }
        assertTrue(details.any { it.label == "Firmware" && it.value.contains("OpenWrt") })
        assertTrue(details.any { it.label == "Kernel" && it.value == "6.18.39" })
        assertTrue(details.any { it.label == "Hardware flow offloading" && it.value.contains("PPE") })
    }

    @Test
    fun formsOfferChoiceChips() {
        val wan = FeatureMappers.wan(uci("""{"wan":{".type":"interface","proto":"dhcp"}}"""), null)
        assertEquals(listOf("dhcp", "static", "pppoe"), wan.forms.single().fields.first { it.key == "proto" }.choices)

        val fw = FeatureMappers.firewall(uci("""{"z1":{".type":"zone","name":"lan"},"z2":{".type":"zone","name":"wan"}}"""))
        assertEquals(listOf("ACCEPT", "REJECT", "DROP"), fw.sections[0].moreForms.first().fields.first { it.key == "input" }.choices)
        assertEquals(listOf("yes", "no"), fw.sections[0].moreForms.first().fields.first { it.key == "masq" }.choices)
        assertEquals(listOf("tcp", "udp", "icmp", "all"), fw.forms.single().fields.first { it.key == "proto" }.choices)

        val routes = FeatureMappers.routes(uci("""{"lan":{".type":"interface"},"wan":{".type":"interface"}}"""))
        assertEquals(listOf("lan", "wan"), routes.forms.single().fields.first { it.key == "interface" }.choices)
    }
}
