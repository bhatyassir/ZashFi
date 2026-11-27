package com.shaazifi.app.feature.router

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2 (T-20261117-C0594015): radio settings, Wi-Fi scan, join and leave a network as a client.
 * Calls as LuCI's wireless page makes them (openwrt/luci master, network/wireless.js and
 * network.js, Apache-2.0, read 2026-11-17): iwinfo freqlist/txpowerlist/countrylist/scan with the
 * radio name as `device`; join = new wifi-iface (mode sta) + network interface (proto dhcp) +
 * that network in the firewall's wan zone, with the radio's channel set to the network's.
 */
class WifiRadioTest {

    private val wifi = FeatureMappersTest.fixture("m7_luci_rpc_wirelessdevices.json")
    private val freq2g = Json.parseToJsonElement("""{"results":[{"channel":1,"mhz":2412,"band":2},{"channel":6,"mhz":2437,"band":2},{"channel":11,"mhz":2462,"band":2},{"channel":13,"mhz":2472,"band":2,"restricted":true,"flags":["no_ir"]}]}""").jsonObject
    private val power = Json.parseToJsonElement("""{"results":[{"dbm":10,"mw":10},{"dbm":20,"mw":100},{"dbm":30,"mw":1000}]}""").jsonObject
    private val countries = Json.parseToJsonElement("""{"results":[{"code":"IN","country":"India"},{"code":"US","country":"United States"}]}""").jsonObject

    private fun plan(r: PlanResult) = (r as PlanResult.Ready).plan
    private fun err(r: PlanResult) = (r as PlanResult.Invalid).message

    @Test
    fun radiosAreReadWithTheirChoices() {
        val radios = WifiRadio.radioInfos(wifi, mapOf("radio0" to freq2g), mapOf("radio0" to power), mapOf("radio0" to countries))
        val r0 = radios.first { it.id == "radio0" }
        assertEquals("2g", r0.band)
        assertEquals(listOf(1, 6, 11), r0.channels) // 13 is restricted (no_ir): not offered, as in LuCI
        assertEquals(listOf(10, 20, 30), r0.powers)
        assertEquals(listOf("IN", "US"), r0.countries)
        assertEquals(listOf("HT20", "HE20", "HT40", "HE40"), WifiRadio.widthChoices(r0))

        // Wi-Fi 6 radio currently set to VHT80 is not trapped in VHT-only choices.
        val r1 = radios.first { it.id == "radio1" }.copy(htmode = "VHT80")
        assertTrue(WifiRadio.widthChoices(r1).containsAll(listOf("VHT80", "HE80", "HE80+80", "HE160")))
        assertEquals(160, WifiRadio.mhz("HE80+80"))
        assertEquals(20, WifiRadio.mhz("NOHT"))
        assertEquals("160 MHz (HE80+80)", WifiRadio.widthLabel("HE80+80"))
        assertEquals("20 MHz (NOHT)", WifiRadio.widthLabel("NOHT"))

        // Missing config.band falls back to iwinfo.frequency.
        val noBand = Json.parseToJsonElement("""{"radio0":{"config":{"channel":"36"},"iwinfo":{"frequency":5180,"htmodes":["HE20","HE80"]}}}""").jsonObject
        val inferred = WifiRadio.radioInfos(noBand, emptyMap(), emptyMap()).single()
        assertEquals("5g", inferred.band)
        assertEquals("5 GHz", WifiRadio.bandLabel(inferred.band))
    }

    @Test
    fun radioSettingsChangeOnlyWhatChangedAndAreChecked() {
        val r0 = WifiRadio.radioInfos(wifi, mapOf("radio0" to freq2g), mapOf("radio0" to power)).first { it.id == "radio0" }
        // The fixture's radio has no txpower set: "default" means unchanged.
        val p = plan(WifiRadio.radioSettings(r0, channel = "6", width = r0.htmode, country = r0.country, power = "default"))
        assertEquals(listOf<UciOp>(UciOp.Set("wireless", "radio0", mapOf("channel" to "6"))), p.ops)
        assertTrue("the phone may drop off Wi-Fi", p.longWait)
        assertTrue(err(WifiRadio.radioSettings(r0, "14", r0.htmode, r0.country, "30")).contains("channel", true))
        assertTrue(err(WifiRadio.radioSettings(r0, "6", "VHT80", r0.country, "30")).contains("width", true))
        assertTrue(err(WifiRadio.radioSettings(r0, "6", r0.htmode, "india", "30")).contains("country", true))
        assertTrue(err(WifiRadio.radioSettings(r0, r0.channel, r0.htmode, r0.country, "default")).contains("Nothing", true))
        val auto = plan(WifiRadio.radioSettings(r0, "auto", r0.htmode, r0.country, "30"))
        assertEquals(listOf(UciOp.Set("wireless", "radio0", mapOf("channel" to "auto")), UciOp.Set("wireless", "radio0", mapOf("txpower" to "30"))), auto.ops)
        val back = plan(WifiRadio.radioSettings(r0.copy(txpower = "20"), r0.channel, r0.htmode, r0.country, "default"))
        assertEquals(listOf<UciOp>(UciOp.DeleteOption("wireless", "radio0", "txpower")), back.ops)
    }

    private val scanReply = Json.parseToJsonElement(
        """{"results":[
          {"ssid":"Cafe","bssid":"AA:BB:CC:00:00:01","mode":"Master","channel":6,"signal":-71,"quality":39,"quality_max":70,
           "encryption":{"enabled":true,"wpa":[2],"authentication":["psk"],"ciphers":["ccmp"]}},
          {"ssid":"Neighbour","bssid":"AA:BB:CC:00:00:02","mode":"Master","channel":1,"signal":-48,
           "encryption":{"enabled":true,"wpa":[3],"authentication":["sae"],"ciphers":["ccmp"]}},
          {"ssid":"Hybrid","bssid":"AA:BB:CC:00:00:05","mode":"Master","channel":1,"signal":-52,
           "encryption":{"enabled":true,"wpa":[2,3],"authentication":["psk","sae"],"ciphers":["ccmp"]}},
          {"ssid":"Free","bssid":"AA:BB:CC:00:00:03","mode":"Master","channel":11,"signal":-80,"encryption":{"enabled":false}},
          {"ssid":"Office","bssid":"AA:BB:CC:00:00:04","mode":"Master","channel":1,"signal":-60,
           "encryption":{"enabled":true,"wpa":[2],"authentication":["802.1x"],"ciphers":["ccmp"]}}]}"""
    ).jsonObject

    @Test
    fun scanResultsAreSortedAndLabelled() {
        val list = WifiRadio.parseScan(scanReply)
        assertEquals(listOf("Neighbour", "Hybrid", "Office", "Cafe", "Free"), list.map { it.ssid })
        assertEquals("sae", list.first { it.ssid == "Neighbour" }.encryption)
        assertEquals("sae-mixed", list.first { it.ssid == "Hybrid" }.encryption)
        assertEquals("psk2", list.first { it.ssid == "Cafe" }.encryption)
        assertEquals("none", list.first { it.ssid == "Free" }.encryption)
        assertEquals(null, list.first { it.ssid == "Office" }.encryption) // enterprise: not offered
    }

    private val firewall = FeatureMappersTest.uci("""{"cfg01":{".type":"zone","name":"lan","network":["lan"]},"cfg02":{".type":"zone","name":"wan","network":["wan","wan6"]}}""")
    private val network = FeatureMappersTest.uci("""{"lan":{".type":"interface","proto":"static"},"wan":{".type":"interface","proto":"dhcp"}}""")

    @Test
    fun joiningANetworkFollowsLuci() {
        val cafe = WifiRadio.parseScan(scanReply).first { it.ssid == "Cafe" }
        assertTrue(err(WifiRadio.join("radio0", cafe, "short", wifi, firewall, network)).contains("8"))
        val p = plan(WifiRadio.join("radio0", cafe, "coffee-please", wifi, firewall, network))
        assertEquals(
            listOf(
                UciOp.Set("wireless", "radio0", mapOf("channel" to "6")),
                UciOp.Add("wireless", "wifi-iface", mapOf("device" to "radio0", "mode" to "sta", "network" to "wwan", "ssid" to "Cafe", "encryption" to "psk2", "key" to "coffee-please")),
                UciOp.Add("network", "interface", mapOf("proto" to "dhcp"), name = "wwan"),
                UciOp.Set("firewall", "cfg02", mapOf("network" to listOf("wan", "wan6", "wwan")))
            ),
            p.ops
        )
        assertTrue(p.reloadFirewall)
        assertTrue(p.longWait)
        assertFalse("the password is never shown back", p.lines.any { it.after.contains("coffee-please") })
        assertTrue(WifiRadio.join("radio0", cafe, "café-wifi-123", wifi, firewall, network) is PlanResult.Ready)
        assertTrue(WifiRadio.join("radio0", cafe, "0123456789abcdef".repeat(4), wifi, firewall, network) is PlanResult.Ready)
        assertTrue(WifiRadio.join("radio0", cafe, "é".repeat(32), wifi, firewall, network) is PlanResult.Invalid)
        val office = WifiRadio.parseScan(scanReply).first { it.ssid == "Office" }
        assertTrue(err(WifiRadio.join("radio0", office, "x".repeat(10), wifi, firewall, network)).contains("enterprise", true))
    }

    @Test
    fun leavingRemovesOnlyTheTargetClientLink() {
        val withSta = FeatureMappersTest.uci("""{"lan":{".type":"interface","proto":"static"},"wwan":{".type":"interface","proto":"dhcp"}}""")
        val fw = FeatureMappersTest.uci("""{"cfg02":{".type":"zone","name":"wan","network":["wan","wan6","wwan"]}}""")
        val p = plan(WifiRadio.disconnect("wifinet2", "Cafe", fw, withSta))
        assertTrue(p.reloadFirewall)
        assertEquals(
            listOf(
                UciOp.Delete("wireless", "wifinet2"),
                UciOp.Delete("network", "wwan"),
                UciOp.Set("firewall", "cfg02", mapOf("network" to listOf("wan", "wan6")))
            ),
            p.ops
        )
    }

    private fun args(vararg pairs: Pair<String, Any>) = buildJsonObject {
        pairs.forEach { (k, v) ->
            when (v) {
                is JsonObject -> put(k, v)
                else -> put(k, v.toString())
            }
        }
    }

    @Test
    fun theWriterAllowsOnlyTheseShapes() {
        fun ok(m: String, a: JsonObject) = RouterWriter.isAllowedWrite("uci", m, a)
        fun values(vararg kv: Pair<String, String>) = buildJsonObject { kv.forEach { (k, v) -> put(k, v) } }
        assertTrue(ok("set", args("config" to "wireless", "section" to "radio0", "values" to values("channel" to "6", "htmode" to "HE40", "country" to "IN", "txpower" to "20"))))
        assertTrue(ok("add", args("config" to "wireless", "type" to "wifi-iface", "values" to values("device" to "radio0", "mode" to "sta", "network" to "wwan", "ssid" to "x", "encryption" to "psk2", "key" to "12345678"))))
        assertFalse("never a new access point", ok("add", args("config" to "wireless", "type" to "wifi-iface", "values" to values("device" to "radio0", "mode" to "ap", "ssid" to "x"))))
        assertTrue(ok("add", args("config" to "network", "type" to "interface", "name" to "wwan", "values" to values("proto" to "dhcp"))))
        assertFalse(ok("add", args("config" to "network", "type" to "interface", "name" to "lan", "values" to values("proto" to "dhcp"))))
        assertFalse(ok("add", args("config" to "network", "type" to "interface", "name" to "wwan", "values" to values("proto" to "static"))))
        assertTrue(ok("delete", args("config" to "network", "section" to "wwan")))
        assertFalse(ok("delete", args("config" to "network", "section" to "lan")))
        assertTrue(ok("delete", args("config" to "wireless", "section" to "radio0", "option" to "txpower")))
        assertTrue(ok("delete", args("config" to "wireless", "section" to "wifinet2")))
        assertTrue(ok("delete", args("config" to "wireless", "type" to "wifi-iface", "match" to values("mode" to "sta"))))
        assertFalse(ok("delete", args("config" to "wireless", "type" to "wifi-iface", "match" to values("mode" to "ap"))))
        assertFalse(ok("delete", args("config" to "wireless", "type" to "wifi-iface", "match" to values("ssid" to "Home"))))
        // Reads: radio names only.
        assertTrue(RouterReader.isAllowedRead("iwinfo", "scan", buildJsonObject { put("device", "radio0") }))
        assertTrue(RouterReader.isAllowedRead("iwinfo", "freqlist", buildJsonObject { put("device", "radio1") }))
        assertFalse(RouterReader.isAllowedRead("iwinfo", "scan", buildJsonObject { put("device", "radio0; reboot") }))
        assertTrue(JsonPrimitive("x").isString && JsonArray(emptyList()).isEmpty())
    }
}
