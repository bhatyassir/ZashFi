/*
 * Copyright (C) 2026 ZashFi contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.shaazifi.app.feature.router

import com.shaazifi.app.core.ubus.UbusError
import com.shaazifi.app.feature.home.UbusCaller
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Canned ubus replies for the read-only LuCI Realtime / Channel Analysis panels. */
class RealtimePanelsTest {

    private class FakeUbus(
        private val replies: Map<String, JsonObject>,
        private val denied: Set<String> = emptySet()
    ) : UbusCaller {
        val calls = mutableListOf<Triple<String, String, JsonObject>>()

        override suspend fun call(ubusObject: String, method: String, args: JsonObject): JsonObject {
            calls += Triple(ubusObject, method, args)
            val cacheKey = key(ubusObject, method, (args["device"] as? JsonPrimitive)?.content)
            if (cacheKey in denied) {
                throw UbusError.AccessDenied(
                    isAclDenied = true,
                    ubusObject = ubusObject,
                    method = method
                )
            }
            return replies[cacheKey] ?: JsonObject(emptyMap())
        }

        companion object {
            fun key(ubusObject: String, method: String, device: String? = null): String =
                listOfNotNull(ubusObject, method, device).joinToString("/")
        }
    }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text.trimIndent()).jsonObject

    private val wifi = json(
        """
        {
          "radio0": {
            "config": {"band": "2g"},
            "interfaces": [
              {"ifname": "wlan0", "section": "default_radio0", "config": {"mode": "ap", "ssid": "ZashFi-2G"}},
              {"ifname": "eth9", "section": "sta", "config": {"mode": "sta", "ssid": "uplink"}}
            ]
          },
          "radio1": {
            "config": {"band": "5g"},
            "interfaces": [
              {"ifname": "wlan1", "section": "default_radio1", "config": {"mode": "ap", "ssid": "ZashFi-5G"}}
            ]
          }
        }
        """
    )

    private val replies = mapOf(
        FakeUbus.key("luci-rpc", "getWirelessDevices") to wifi,
        FakeUbus.key("iwinfo", "freqlist", "radio0") to json(
            """
            {"results": [
              {"channel": 1, "band": 2, "frequency": 2412, "restricted": false, "flags": []},
              {"channel": 13, "band": 2, "frequency": 2472, "restricted": false, "flags": []},
              {"channel": 14, "band": 2, "frequency": 2484, "restricted": true, "flags": ["no_ir"]}
            ]}
            """
        ),
        FakeUbus.key("iwinfo", "freqlist", "radio1") to json(
            """
            {"results": [
              {"channel": 36, "band": 5, "frequency": 5180, "restricted": false, "flags": []},
              {"channel": 44, "band": 5, "frequency": 5220, "restricted": false, "flags": []}
            ]}
            """
        ),
        FakeUbus.key("iwinfo", "assoclist", "wlan0") to json(
            """
            {"results": [
              {"mac": "aa:bb:cc:dd:ee:01", "signal": -50},
              {"mac": "AA:BB:CC:DD:EE:02", "signal": -60}
            ]}
            """
        ),
        FakeUbus.key("iwinfo", "assoclist", "wlan1") to json(
            """{"results": [{"mac": "aa:bb:cc:dd:ee:03", "signal": -42}]}"""
        ),
        FakeUbus.key("network.device", "status") to json(
            """
            {
              "lan1": {"name": "lan1", "statistics": {"rx_bytes": 1500000, "tx_bytes": 250000}},
              "wan": {"name": "wan", "statistics": {"rx_bytes": 900000000, "tx_bytes": 30000000}},
              "lo": {"flags": {"loopback": true}, "statistics": {"rx_bytes": 9999, "tx_bytes": 9999}}
            }
            """
        ),
        FakeUbus.key("network.interface", "dump") to json(
            """
            {"interface": [
              {"interface": "loopback", "proto": "none", "up": true,
               "ipv4-address": [{"address": "127.0.0.1", "mask": 8}]},
              {"interface": "lan", "proto": "static", "device": "br-lan", "up": true,
               "ipv4-address": [{"address": "192.168.2.1", "mask": 24}]},
              {"interface": "wan", "proto": "dhcp", "device": "eth0", "up": true,
               "ipv4-address": [{"address": "203.0.113.10", "mask": 24}]}
            ]}
            """
        )
    )

    private fun section(view: FeatureView, heading: String) =
        view.sections.first { it.heading == heading }

    @Test
    fun cannedRepliesBuildAllFourReadOnlySections(): Unit = runTest {
        val router = FakeUbus(replies)
        val view = RealtimePanels.realtimeAndChannels(RouterReader(router))

        assertEquals(
            listOf("Channel analysis", "Wi-Fi clients per radio", "Port traffic", "Interfaces"),
            view.sections.map { it.heading }
        )
        assertTrue(view.summary.isNotBlank())
        assertEquals("2 radios", view.pill?.text)
        assertTrue(view.forms.isEmpty())
        assertTrue(view.sections.all { it.form == null && it.moreForms.isEmpty() })
        assertTrue(view.sections.all { rows -> rows.rows.all { it.toggle == null && it.onToggle == null && it.extra == null } })
        assertTrue(router.calls.all { (obj, method, args) -> RouterReader.isAllowedRead(obj, method, args) })
        assertTrue(router.calls.none { (_, method, _) -> method in setOf("set", "add", "delete", "apply", "commit") })

        val channels = section(view, "Channel analysis").rows
        assertEquals("radio0 (2.4 GHz)", channels.first().label)
        assertEquals("2 channels", channels.first().value)
        assertEquals("1, 13", channels.first().sub)
        assertTrue(channels.first().mono)
        val radio1Channels = channels.first { it.label.startsWith("radio1 (") }
        assertEquals("radio1 (5 GHz)", radio1Channels.label)
        assertEquals("2 channels", radio1Channels.value)
        val restricted = channels.first { it.label == "radio0 · ch 14" }
        assertTrue(restricted.detail)
        assertEquals("2484 MHz", restricted.value)
        assertEquals("Limited", restricted.pill?.text)
        assertEquals("Restricted (no IR)", restricted.sub)
        assertTrue(channels.any { it.label == "radio1 · ch 36" && it.value == "5180 MHz" && it.detail })

        val clients = section(view, "Wi-Fi clients per radio").rows
        assertEquals("radio0 (2.4 GHz)", clients.first().label)
        assertEquals("2 clients", clients.first().value)
        assertEquals("wlan0: 2", clients.first().sub)
        assertEquals("1 client", clients.first { it.label.startsWith("radio1 (") }.value)
        // The station interface is not an access point and is never queried.
        assertEquals(2, clients.count { it.detail })
        assertEquals("wlan0 · ZashFi-2G", clients.first { it.detail }.label)
        assertTrue(clients.none { it.label.contains("eth9") || it.label.contains("uplink") })

        val ports = section(view, "Port traffic").rows
        assertEquals(listOf("wan", "lan1"), ports.map { it.label })
        assertTrue(ports.all { it.mono })
        assertTrue(ports.first().sub?.startsWith("↓ ") == true)
        assertTrue(ports.none { it.label == "lo" })

        val interfaces = section(view, "Interfaces").rows
        assertEquals(listOf("lan (br-lan)", "wan (eth0)"), interfaces.map { it.label })
        assertEquals("192.168.2.1/24", interfaces.first().value)
        assertEquals("203.0.113.10/24", interfaces[1].value)
        assertTrue(interfaces.all { it.mono })
        assertTrue(interfaces.all { it.pill?.text == "Up" })
    }

    @Test
    fun oneRefusedCallLeavesTheRestOfThePanelUsable(): Unit = runTest {
        val router = FakeUbus(
            replies,
            denied = setOf(
                FakeUbus.key("iwinfo", "freqlist", "radio1"),
                FakeUbus.key("iwinfo", "assoclist", "wlan1")
            )
        )
        val view = RealtimePanels.realtimeAndChannels(RouterReader(router))

        assertEquals(4, view.sections.size)
        val channels = section(view, "Channel analysis").rows
        assertEquals("2 channels", channels.first { it.label.startsWith("radio0 (") }.value)
        assertEquals("No channel list", channels.first { it.label.startsWith("radio1 (") }.value)
        val clients = section(view, "Wi-Fi clients per radio").rows
        assertEquals("2 clients", clients.first { it.label.startsWith("radio0 (") }.value)
        assertEquals("0 clients", clients.first { it.label.startsWith("radio1 (") }.value)
        assertEquals(listOf("wan", "lan1"), section(view, "Port traffic").rows.map { it.label })
    }

    @Test
    fun emptyRouterShowsEmptyTextInsteadOfFailing(): Unit = runTest {
        val view = RealtimePanels.realtimeAndChannels(RouterReader(FakeUbus(emptyMap())))

        assertEquals(4, view.sections.size)
        assertTrue(view.sections.all { it.rows.isEmpty() })
        assertTrue(view.sections.all { !it.emptyText.isNullOrBlank() })
        assertEquals("0 radios", view.pill?.text)
        assertNull(view.network)
    }
}
