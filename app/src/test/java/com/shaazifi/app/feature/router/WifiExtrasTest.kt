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

import com.shaazifi.app.ui.theme.PillTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure panel and plan tests: no router is contacted and no file is changed. */
class WifiExtrasTest {

    private fun plan(r: PlanResult): ChangePlan = (r as PlanResult.Ready).plan
    private fun error(r: PlanResult): String = (r as PlanResult.Invalid).message

    private val sample = UciConfig(
        listOf(
            UciSection(
                "default_radio0", "wifi-iface", mapOf(
                    "device" to listOf("radio0"),
                    "mode" to listOf("ap"),
                    "network" to listOf("lan"),
                    "ssid" to listOf("Home"),
                    "wps_pushbutton" to listOf("1"),
                    "wds" to listOf("0"),
                    "wps_label" to listOf("0"),
                    "wps_pin" to listOf("0"),
                    "macfilter" to listOf("deny"),
                    "maclist" to listOf("aa:bb:cc:dd:ee:ff", "11:22:33:44:55:66"),
                    "isolate" to listOf("1")
                )
            ),
            UciSection(
                "guest_radio0", "wifi-iface", mapOf(
                    "device" to listOf("radio1"),
                    "mode" to listOf("ap"),
                    "network" to listOf("guest"),
                    "ssid" to listOf("Guest-Net"),
                    "vlan" to listOf("20"),
                    "wps_pushbutton" to listOf("0"),
                    "wds" to listOf("1"),
                    "wps_label" to listOf("0"),
                    "wps_pin" to listOf("0"),
                    "isolate" to listOf("0"),
                    "macfilter" to listOf("disabled")
                )
            ),
            UciSection(
                "client1", "wifi-iface", mapOf(
                    "device" to listOf("radio0"),
                    "mode" to listOf("sta"),
                    "network" to listOf("wwan"),
                    "ssid" to listOf("Upstairs")
                )
            )
        )
    )

    @Test
    fun panelHasFourReadOnlySections() {
        val view = WifiExtras.panel(sample)
        assertEquals(
            listOf("WPS / WDS", "MAC filtering", "Client isolation", "Guest VLAN"),
            view.sections.map { it.heading }
        )
        assertTrue(view.sections.all { it.emptyText?.isNotBlank() == true })
        assertTrue(view.sections.all { it.form == null && it.moreForms.isEmpty() })
        assertTrue(view.forms.isEmpty())
        assertTrue(view.sections.all { section ->
            section.rows.all { row -> row.toggle == null && row.onToggle == null && row.extra == null }
        })
    }

    @Test
    fun panelShowsWpsAndWdsFlagsForEveryInterface() {
        val rows = WifiExtras.panel(sample).sections[0].rows
        assertEquals(12, rows.size) // four options on each of the three wifi-iface sections
        assertEquals(listOf("wps_pushbutton", "wds", "wps_label", "wps_pin"), rows.take(4).map { it.label })

        val wps = rows.first { it.label == "wps_pushbutton" && it.sub == "default_radio0" }
        assertEquals("1", wps.value)
        assertEquals(ViewPill("On", PillTone.Ok), wps.pill)
        assertEquals(ViewPill("Off", PillTone.Off), rows.first { it.label == "wds" && it.sub == "default_radio0" }.pill)
        assertEquals(ViewPill("On", PillTone.Ok), rows.first { it.label == "wds" && it.sub == "guest_radio0" }.pill)
    }

    @Test
    fun panelShowsMacModeAndOneMonoRowPerAddress() {
        val rows = WifiExtras.panel(sample).sections[1].rows
        assertEquals(4, rows.size) // deny + two entries on default_radio0, disabled on guest_radio0
        assertEquals("deny", rows.first { it.label == "macfilter" && it.sub == "default_radio0" }.value)
        assertEquals("disabled", rows.first { it.label == "macfilter" && it.sub == "guest_radio0" }.value)
        val entries = rows.filter { it.label == "maclist" }
        assertEquals(listOf("aa:bb:cc:dd:ee:ff", "11:22:33:44:55:66"), entries.map { it.value })
        assertTrue(entries.all { it.mono })
        assertTrue(rows.none { it.sub == "client1" })
    }

    @Test
    fun panelShowsIsolationForApsOnlyAndGuestVlanRowsForGuestOnly() {
        val sections = WifiExtras.panel(sample).sections
        val isolate = sections[2].rows
        assertEquals(2, isolate.size)
        assertEquals("1", isolate.first { it.sub == "default_radio0" }.value)
        assertEquals(ViewPill("On", PillTone.Ok), isolate.first { it.sub == "default_radio0" }.pill)
        assertEquals(ViewPill("Off", PillTone.Off), isolate.first { it.sub == "guest_radio0" }.pill)
        assertTrue(isolate.none { it.sub == "client1" })

        val guest = sections[3].rows
        assertEquals(2, guest.size)
        assertEquals("vlan", guest[0].label)
        assertEquals("20", guest[0].value)
        assertTrue(guest[0].mono)
        assertEquals("network", guest[1].label)
        assertEquals("guest", guest[1].value)
        assertEquals("guest_radio0", guest[0].sub)
    }

    @Test
    fun emptyConfigGivesEmptySectionsWithoutActions() {
        val view = WifiExtras.panel(UciConfig(emptyList()))
        assertTrue(view.sections.all { it.rows.isEmpty() && it.emptyText?.isNotBlank() == true })
        assertTrue(view.sections.all { it.form == null && it.moreForms.isEmpty() })
    }

    private data class FlagCase(val result: PlanResult, val section: String, val option: String, val value: String)

    @Test
    fun flagPlansSetExactlyOneOptionWithAConfirmNote() {
        val cases = listOf(
            FlagCase(WifiExtras.setWps("default_radio0", true), "default_radio0", "wps_pushbutton", "1"),
            FlagCase(WifiExtras.setWps("default_radio0", false), "default_radio0", "wps_pushbutton", "0"),
            FlagCase(WifiExtras.setWds("default_radio0", true), "default_radio0", "wds", "1"),
            FlagCase(WifiExtras.setWds("guest_radio0", false), "guest_radio0", "wds", "0"),
            FlagCase(WifiExtras.setIsolate("guest_radio0", true), "guest_radio0", "isolate", "1"),
            FlagCase(WifiExtras.setIsolate("guest_radio0", false), "guest_radio0", "isolate", "0")
        )
        cases.forEach { (result, section, option, value) ->
            val p = plan(result)
            assertEquals(1, p.ops.size)
            assertEquals(UciOp.Set("wireless", section, mapOf(option to value)), p.ops.single())
            assertTrue(p.title.contains("?"))
            assertTrue(p.lines.isNotEmpty())
            assertTrue(p.note?.isNotBlank() == true)
        }
    }

    @Test
    fun allPlansRejectInvalidSectionIds() {
        val badIds = listOf("", "bad-id", "with space", "a".repeat(33), "dot.name", "semi;colon", "üñî")
        badIds.forEach { id ->
            assertTrue("setWps '$id'", WifiExtras.setWps(id, true) is PlanResult.Invalid)
            assertTrue("setWds '$id'", WifiExtras.setWds(id, false) is PlanResult.Invalid)
            assertTrue("setIsolate '$id'", WifiExtras.setIsolate(id, true) is PlanResult.Invalid)
            assertTrue("setMacFilter '$id'", WifiExtras.setMacFilter(id, "disabled", "") is PlanResult.Invalid)
            assertTrue("setGuestVlan '$id'", WifiExtras.setGuestVlan(id, "20") is PlanResult.Invalid)
        }
    }

    @Test
    fun macFilterPlanWritesModeAndListOnly() {
        val p = plan(
            WifiExtras.setMacFilter(
                "default_radio0", "allow",
                "aa:bb:cc:dd:ee:ff, 11:22:33:44:55:66 22:33:44:55:66:77"
            )
        )
        val op = p.ops.single() as UciOp.Set
        assertEquals("wireless", op.config)
        assertEquals("default_radio0", op.section)
        assertEquals(setOf("macfilter", "maclist"), op.values.keys)
        assertEquals("allow", op.values["macfilter"])
        assertEquals(
            listOf("aa:bb:cc:dd:ee:ff", "11:22:33:44:55:66", "22:33:44:55:66:77"),
            op.values["maclist"]
        )
        assertTrue(p.note?.isNotBlank() == true)

        val cleared = plan(WifiExtras.setMacFilter("guest_radio0", "disabled", ""))
        assertEquals(
            UciOp.Set("wireless", "guest_radio0", mapOf("macfilter" to "disabled", "maclist" to emptyList<String>())),
            cleared.ops.single()
        )
    }

    @Test
    fun macFilterRejectsBadModesAddressesAndCounts() {
        assertTrue(error(WifiExtras.setMacFilter("default_radio0", "Allow", "aa:bb:cc:dd:ee:ff")).contains("disabled"))
        assertTrue(WifiExtras.setMacFilter("default_radio0", "block", "aa:bb:cc:dd:ee:ff") is PlanResult.Invalid)
        assertTrue(WifiExtras.setMacFilter("default_radio0", "", "aa:bb:cc:dd:ee:ff") is PlanResult.Invalid)
        assertTrue(WifiExtras.setMacFilter("default_radio0", "allow", "") is PlanResult.Invalid)
        assertTrue(WifiExtras.setMacFilter("default_radio0", "deny", "   ") is PlanResult.Invalid)
        assertTrue(WifiExtras.setMacFilter("default_radio0", "allow", "not-a-mac") is PlanResult.Invalid)
        assertTrue(WifiExtras.setMacFilter("default_radio0", "allow", "aa:bb:cc:dd:ee:gg") is PlanResult.Invalid)
        assertTrue(WifiExtras.setMacFilter("default_radio0", "allow", "aa:bb:cc:dd:ee:ff:11") is PlanResult.Invalid)
        assertTrue(
            WifiExtras.setMacFilter("default_radio0", "allow", List(65) { mac(it) }.joinToString(",")) is PlanResult.Invalid
        )
        // 64 is the boundary and must still be accepted.
        assertTrue(plan(WifiExtras.setMacFilter("default_radio0", "deny", List(64) { mac(it) }.joinToString(","))).ops.isNotEmpty())
    }

    private fun mac(n: Int) = "aa:bb:cc:dd:ee:" + n.toString(16).padStart(2, '0')

    @Test
    fun guestVlanPlanWritesOnlyVlanAndValidatesRange() {
        val p = plan(WifiExtras.setGuestVlan("guest_radio0", "20"))
        assertEquals(UciOp.Set("wireless", "guest_radio0", mapOf("vlan" to "20")), p.ops.single())
        assertTrue(p.note?.isNotBlank() == true)
        assertEquals("1", (plan(WifiExtras.setGuestVlan("guest_radio0", "1")).ops.single() as UciOp.Set).values["vlan"])
        assertEquals("4094", (plan(WifiExtras.setGuestVlan("guest_radio0", "4094")).ops.single() as UciOp.Set).values["vlan"])

        listOf("", "0", "4095", "4096", "abc", "12a", "-1", "+20", " 20", "20 ", "999999999999999999999999999999")
            .forEach { value ->
                assertTrue("vlan '$value'", WifiExtras.setGuestVlan("guest_radio0", value) is PlanResult.Invalid)
            }
    }
}
