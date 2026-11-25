package com.shaazifi.app.feature.home

import com.shaazifi.app.core.session.SavedSession
import com.shaazifi.app.core.ubus.UbusClient
import com.shaazifi.app.feature.router.FeatureMappers
import com.shaazifi.app.feature.router.FeatureMappersTest
import com.shaazifi.app.feature.router.RouterReader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner feedback, 2026-11-17 (Document.docx): stay signed in for a week; Home denser and
 * interactive like LuCI Mobile's; the speed card opens per-device bandwidth.
 * Sources (read 2026-11-17): openwrt/rpcd session.c (login `timeout` in seconds, default 300, no
 * upper limit; idle timeout), openwrt/luci luci-base ucode sys.uc conntrack_list (per connection:
 * src, dst, sport, dport, layer4, bytes = both directions).
 */
class OwnerFeedbackTest {

    @Test
    fun signInAsksForAWeekAndTheSessionIsKeptForAWeek() {
        assertEquals(604_800, SavedSession.TIMEOUT_SECONDS)
        val args = UbusClient.loginArgs("root", "pw", SavedSession.TIMEOUT_SECONDS)
        assertEquals("604800", args["timeout"].toString())
        assertFalse("no timeout: router default", UbusClient.loginArgs("root", "pw", null).containsKey("timeout"))

        val s = SavedSession(profileId = "r1", token = "tok", endpoint = "https://192.168.1.1", viaVpn = false, savedAt = 1_000_000)
        assertEquals(s, SavedSession.decode(s.encode()))
        assertNull(SavedSession.decode("nope"))
        assertTrue(s.isFresh(now = 1_000_000 + 6L * 86_400_000))
        assertFalse(s.isFresh(now = 1_000_000 + 8L * 86_400_000))
        assertFalse("the token never appears in toString", s.toString().contains("tok"))
    }

    private fun conn(src: String, sport: Int, dst: String, bytes: Long) =
        """{"layer3":"ipv4","layer4":"tcp","src":"$src","sport":$sport,"dst":"$dst","dport":443,"bytes":$bytes}"""

    @Test
    fun bandwidthPerDeviceFromTwoConnectionReadings() {
        val first = Json.parseToJsonElement("""{"result":[${conn("192.168.1.5", 50000, "203.0.113.1", 1_000_000)},${conn("192.168.1.9", 50001, "203.0.113.2", 500)}]}""").jsonObject
        val second = Json.parseToJsonElement("""{"result":[${conn("192.168.1.5", 50000, "203.0.113.1", 3_500_000)},${conn("192.168.1.9", 50001, "203.0.113.2", 600)},
            ${conn("192.168.1.9", 50002, "203.0.113.3", 2000)},${conn("203.0.113.9", 6000, "192.168.1.1", 99999)}]}""").jsonObject
        val v = FeatureMappers.bandwidth(first, second, seconds = 2.0, names = mapOf("192.168.1.5" to "Laptop"))
        val rows = v.sections.first().rows
        assertEquals("Laptop", rows[0].label) // busiest first: (3,500,000 - 1,000,000) / 2 s
        assertEquals("10.0 Mbit/s", rows[0].value)
        assertEquals("192.168.1.9", rows[1].label) // (600-500 + 2000 new) / 2 s
        assertFalse("outside senders are not devices", rows.any { it.label.startsWith("203.") })
    }

    @Test
    fun homeShowsInterfacesAndRadios() {
        val dump = FeatureMappersTest.fixture("m7_network_interface_dump.json")
        val ifaces = HomeParsers.parseInterfaces(dump)
        assertTrue(ifaces.none { it.first == "loopback" })
        assertTrue(ifaces.any { it.first == "lan" && it.second })
        val radios = HomeParsers.parseRadios(FeatureMappersTest.fixture("m7_luci_rpc_wirelessdevices.json"), mapOf("phy0-ap0" to 3))
        assertTrue(radios.isNotEmpty())
        assertTrue(radios.any { it.channel.isNotBlank() && it.ssid.isNotBlank() })
    }

    @Test
    fun theConnectionListIsAnAllowedRead() {
        assertTrue(RouterReader.isAllowedRead("luci", "getConntrackList", JsonObject(emptyMap())))
    }
}
