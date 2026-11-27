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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guest Wi-Fi creation: the plan, the writer's narrow exception, and the screen form. */
class GuestCreateTest {

    private fun plan(r: PlanResult) = (r as PlanResult.Ready).plan
    private fun error(r: PlanResult) = (r as PlanResult.Invalid).message

    private fun allowed(op: UciOp): Boolean {
        val (method, args) = RouterWriter.argsFor(op)
        return RouterWriter.isAllowedWrite("uci", method, args)
    }

    private fun guestValues() = mapOf<String, Any>(
        "device" to "radio0",
        "mode" to "ap",
        "network" to "guest",
        "ssid" to "Home-Guest",
        "key" to "guest-pass-1",
        "encryption" to "sae-mixed"
    )

    private val guestReply = Json.parseToJsonElement(
        """{
          "radio0": {
            "up": true, "disabled": false,
            "config": {"band": "2g"},
            "interfaces": [
              {"section":"default_radio0","ifname":"phy0-ap0",
               "config":{"mode":"ap","network":["lan"],"ssid":"Home","encryption":"psk2"}},
              {"section":"guest_radio0","ifname":"phy0-ap1",
               "config":{"mode":"ap","network":["guest"],"ssid":"Home-Guest","encryption":"psk2","disabled":true}}
            ]
          }
        }"""
    ).jsonObject

    @Test
    fun createGuestBuildsOnlyTheNamedGuestAddOp() {
        val p = plan(FeatureChanges.createGuest("guest_radio0", "radio0", " Home-Guest ", "guest-pass-1", "SAE-MIXED"))
        assertEquals(1, p.ops.size)
        assertEquals(
            UciOp.Add("wireless", "wifi-iface", mapOf(
                "device" to "radio0", "mode" to "ap", "network" to "guest",
                "ssid" to "Home-Guest", "key" to "guest-pass-1", "encryption" to "sae-mixed"
            ), name = "guest_radio0"),
            p.ops.single()
        )
        assertTrue("the guest change must be sendable", allowed(p.ops.single()))
        assertTrue("Wi-Fi restarts by itself, no service reload", p.services.isEmpty())
        assertTrue(p.title.contains("guest", ignoreCase = true))

        assertTrue(error(FeatureChanges.createGuest("guest_radio0", "radio0; reboot", "Guest", "guest-pass-1", "psk2")).contains("radio"))
        assertTrue(error(FeatureChanges.createGuest("guest_radio0", "radio0", "  ", "guest-pass-1", "psk2")).contains("name"))
        assertTrue(error(FeatureChanges.createGuest("guest_radio0", "radio0", "Guest", "short", "psk2")).contains("password"))
        assertTrue(error(FeatureChanges.createGuest("guest_radio0", "radio0", "Guest", "x".repeat(64), "psk2")).contains("password"))
        assertTrue(error(FeatureChanges.createGuest("guest_radio0", "radio0", "Guest", "guest-pass-1", "wep")).contains("security"))
        assertTrue(error(FeatureChanges.createGuest("guest;radio0", "radio0", "Guest", "guest-pass-1", "psk2")).contains("name"))
    }

    @Test
    fun writerAcceptsGuestApOnlyNotSpoofedOrLan() {
        assertTrue(allowed(UciOp.Add("wireless", "wifi-iface", guestValues(), name = "guest_radio0")))
        assertTrue(allowed(UciOp.Add("wireless", "wifi-iface", guestValues())))

        // A client link with an extra option/pretending to be guest is still the old sta shape only.
        assertFalse(allowed(UciOp.Add("wireless", "wifi-iface", guestValues() + mapOf("mode" to "sta", "disabled" to "0"))))
        assertFalse(allowed(UciOp.Add("wireless", "wifi-iface", guestValues() + mapOf("extra" to "x"))))
        assertFalse(allowed(UciOp.Add("wireless", "wifi-iface", guestValues() + mapOf("mode" to "ap", "network" to "lan"))))
        assertFalse(allowed(UciOp.Add("wireless", "wifi-iface", guestValues() + mapOf("device" to "radio0; reboot"))))
        assertFalse(allowed(UciOp.Add("wireless", "wifi-iface", guestValues() + mapOf("encryption" to "none"))))
        assertFalse(allowed(UciOp.Add("wireless", "wifi-iface", guestValues() + mapOf("key" to "short"))))
        assertFalse(allowed(UciOp.Add("wireless", "wifi-iface", guestValues(), name = "guest;radio0")))
        assertTrue(allowed(UciOp.Add("wireless", "wifi-iface", guestValues())))

        // LAN and the main Wi-Fi interface are never added from a change plan.
        assertFalse(allowed(UciOp.Add("network", "interface", mapOf("proto" to "dhcp"), name = "lan")))
        assertFalse(allowed(UciOp.Add("network", "interface", mapOf("proto" to "static"), name = "wan")))
        assertFalse(allowed(UciOp.Add("wireless", "wifi-iface", mapOf(
            "device" to "radio0", "mode" to "ap", "network" to "lan", "ssid" to "Home", "key" to "home-pass-1", "encryption" to "psk2"
        ))))
    }

    @Test
    fun guestScreenOffersCreationWhenAbsentAndToggleWhenPresent() {
        val noGuest = FeatureMappers.guest(FeatureMappersTest.fixture("m7_luci_rpc_wirelessdevices.json"))
        assertEquals("Off", noGuest.pill?.text)
        val form = noGuest.forms.single()
        assertEquals("Create a guest network", form.title)
        assertEquals(listOf("radio", "ssid", "password", "encryption"), form.fields.map { it.key })
        assertTrue(form.fields.first { it.key == "radio" }.choices.contains("radio0"))
        assertTrue(form.fields.first { it.key == "password" }.secret)
        assertEquals(listOf("sae-mixed", "psk2", "sae"), form.fields.first { it.key == "encryption" }.choices)
        assertTrue(noGuest.sections.flatMap { it.rows }.none { it.onToggle != null })

        val built = plan(form.build(mapOf("radio" to "radio1", "ssid" to "Home-Guest", "password" to "guest-pass-1", "encryption" to "psk2")))
        val add = built.ops.single() as UciOp.Add
        assertEquals("guest_radio1", add.name)
        assertEquals("radio1", add.values["device"])
        assertEquals("guest", add.values["network"])
        assertTrue(allowed(add))

        val withGuest = FeatureMappers.guest(guestReply)
        assertTrue("no create form once a guest network exists", withGuest.forms.isEmpty())
        val toggle = withGuest.sections.flatMap { it.rows }.single { it.onToggle != null }
        assertTrue(toggle.value.contains("Home-Guest"))
        val set = plan(toggle.onToggle!!()).ops.single() as UciOp.Set
        assertEquals("guest_radio0", set.section)
        assertEquals("0", set.values["disabled"])
    }
}
