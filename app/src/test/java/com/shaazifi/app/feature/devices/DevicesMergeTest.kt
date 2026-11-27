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
package com.shaazifi.app.feature.devices

import com.shaazifi.app.feature.router.UciConfig
import com.shaazifi.app.feature.router.UciSection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DevicesMergeTest {

    @Test
    fun customUciHostNameOverridesDhcpLeaseHostname() {
        val hints = Json.parseToJsonElement(
            """{"02:00:00:00:00:10":{"ipv4":"192.168.1.10","name":"hint-name"}}"""
        ).jsonObject
        val leases = Json.parseToJsonElement(
            """{"dhcp_leases":[{"expires":3600,"hostname":"android-dhcp","macaddr":"02:00:00:00:00:10","ipaddr":"192.168.1.10"}]}"""
        ).jsonObject
        val dhcp = UciConfig(
            listOf(
                UciSection("cfg1", "host", mapOf("mac" to listOf("02:00:00:00:00:10"), "ip" to listOf("192.168.1.10"), "name" to listOf("LivingRoom-TV")))
            )
        )
        val merged = DevicesParsers.merge(hints, leases, emptyMap(), wifiKnown = true, dhcp = dhcp, firewall = null)
        val device = merged.devices.single()
        assertEquals("LivingRoom-TV", device.name)
        assertEquals("LivingRoom-TV", device.customName)
        assertEquals("192.168.1.10", device.fixedIp)
        assertTrue(device.online)
    }

    @Test
    fun inactiveOrExpiredDhcpLeasesAreNotMarkedOnlineWithoutHintsOrWifi() {
        val leases = Json.parseToJsonElement(
            """
            {"dhcp_leases":[
                {"expires":3600,"hostname":"Sleeping-Phone","macaddr":"02:00:00:00:00:21","ipaddr":"192.168.1.21"},
                {"expires":0,"hostname":"Expired-Box","macaddr":"02:00:00:00:00:22","ipaddr":"192.168.1.22"},
                {"expires":3600,"hostname":"Wifi-Only","macaddr":"02:00:00:00:00:23","ipaddr":"192.168.1.23"}
            ]}
            """
        ).jsonObject
        val merged = DevicesParsers.merge(
            hostHints = null,
            dhcpLeases = leases,
            wifiByMac = mapOf("02:00:00:00:00:23" to Connection.WIFI_5),
            wifiKnown = true,
            dhcp = null,
            firewall = null
        )

        val sleeping = merged.devices.first { it.mac == "02:00:00:00:00:21" }
        assertFalse(sleeping.online)
        assertEquals("Sleeping-Phone", sleeping.name)
        assertEquals(Connection.UNKNOWN, sleeping.connection)

        assertNull(merged.devices.firstOrNull { it.mac == "02:00:00:00:00:22" })

        val wifi = merged.devices.first { it.mac == "02:00:00:00:00:23" }
        assertTrue(wifi.online)
        assertEquals("192.168.1.23", wifi.ip)
        assertEquals(Connection.WIFI_5, wifi.connection)
    }

    @Test
    fun extraEgressZonesAreDetectedFromFirewallConfig() {
        val fw = UciConfig(
            listOf(
                UciSection("z1", "zone", mapOf("name" to listOf("lan"))),
                UciSection("z2", "zone", mapOf("name" to listOf("wan"), "masq" to listOf("1"))),
                UciSection("z3", "zone", mapOf("name" to listOf("wan2"), "masq" to listOf("1"))),
                UciSection("z4", "zone", mapOf("name" to listOf("wg_vpn"), "masq" to listOf("1")))
            )
        )
        val merged = DevicesParsers.merge(null, null, emptyMap(), wifiKnown = true, dhcp = null, firewall = fw)
        assertEquals(listOf("wan2", "wg_vpn"), merged.extraEgressZones)
    }
}
