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

import com.shaazifi.app.feature.home.UbusCaller
import com.shaazifi.app.ui.theme.PillTone
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Canned `uci get` replies only: no router is contacted and nothing is written. */
class AdGuardPackagePanelTest {

    private val EXPECTED_NOTE =
        "These are the router package settings. Blocking rules and query logs are managed in the AdGuard tab."

    private class FakeCaller(private val uci: Map<String, JsonObject>) : UbusCaller {
        val calls = mutableListOf<Triple<String, String, JsonObject>>()

        override suspend fun call(ubusObject: String, method: String, args: JsonObject): JsonObject {
            calls += Triple(ubusObject, method, args)
            return uci[(args["config"] as? JsonPrimitive)?.content.orEmpty()] ?: JsonObject(emptyMap())
        }
    }

    private class ThrowingCaller : UbusCaller {
        override suspend fun call(ubusObject: String, method: String, args: JsonObject): JsonObject =
            throw IOException("router offline")
    }

    private fun readerOf(caller: UbusCaller) = RouterReader(caller)

    private val adguardConfig = Json.parseToJsonElement(
        """{"values":{"cfg01":{".type":"adguardhome",".index":1,
           "enabled":"1","started":"0",
           "addresses":["0.0.0.0","::"],
           "port":"80","http_port":"3000",
           "configuration_path":"/etc/adguardhome/AdGuardHome.yaml",
           "work_dir":"/etc/adguardhome",
           "querylog_size_memory":"1000","statistics_size_memory":"30000",
           "dns_port":"53","dns_addresses":["0.0.0.0","::"],
           "local_ptr_upstreams":["127.0.0.1#5353"],
           "bootstrap_dns":["9.9.9.9","1.1.1.1"]}}}"""
    ).jsonObject

    @Test
    fun loadShowsPackageAndDnsRowsFromTheRouterConfig() : Unit = runTest {
        val caller = FakeCaller(uci = mapOf("adguardhome" to adguardConfig))
        val view = AdGuardPackagePanel.load(readerOf(caller))

        assertTrue(caller.calls.single().let { (obj, method, args) ->
            obj == "uci" && method == "get" && (args["config"] as? JsonPrimitive)?.content == "adguardhome"
        })
        assertEquals(listOf("Package", "DNS"), view.sections.map { it.heading })

        val packageRows = view.sections[0].rows
        assertEquals(9, packageRows.size)
        assertEquals("Enabled", packageRows[0].label)
        assertEquals("Yes", packageRows[0].value)
        assertEquals(PillTone.Ok, packageRows[0].pill!!.tone)
        assertEquals("On", packageRows[0].pill!!.text)
        assertEquals("Started", packageRows[1].label)
        assertEquals("No", packageRows[1].value)
        assertEquals(PillTone.Off, packageRows[1].pill!!.tone)
        assertEquals("Off", packageRows[1].pill!!.text)
        assertEquals("Addresses", packageRows[2].label)
        assertEquals("0.0.0.0, ::", packageRows[2].value)
        assertTrue(packageRows[2].mono)
        assertEquals("Port" to "80", packageRows[3].label to packageRows[3].value)
        assertEquals("HTTP port" to "3000", packageRows[4].label to packageRows[4].value)
        assertEquals("Configuration path" to "/etc/adguardhome/AdGuardHome.yaml",
            packageRows[5].label to packageRows[5].value)
        assertEquals("Work directory" to "/etc/adguardhome", packageRows[6].label to packageRows[6].value)
        assertEquals("Query log memory" to "1000", packageRows[7].label to packageRows[7].value)
        assertEquals("Statistics memory" to "30000", packageRows[8].label to packageRows[8].value)

        val dnsRows = view.sections[1].rows
        assertEquals(4, dnsRows.size)
        assertEquals("DNS port" to "53", dnsRows[0].label to dnsRows[0].value)
        assertEquals("DNS addresses" to "0.0.0.0, ::", dnsRows[1].label to dnsRows[1].value)
        assertEquals("Local PTR upstreams" to "127.0.0.1#5353", dnsRows[2].label to dnsRows[2].value)
        assertEquals("Bootstrap DNS" to "9.9.9.9, 1.1.1.1", dnsRows[3].label to dnsRows[3].value)

        assertEquals(EXPECTED_NOTE, view.note)
        assertNotNull(view.pill)
        // Read-only: no forms, toggles or change plans on any row.
        assertTrue(view.forms.isEmpty())
        assertTrue(view.sections.all { section ->
            section.form == null && section.moreForms.isEmpty() && section.rows.all { row ->
                row.toggle == null && row.onToggle == null && row.extra == null
            }
        })
    }

    @Test
    fun loadUsesEmptyTextWhenConfigIsMissingOrHasNoAdGuardSection() : Unit = runTest {
        val missing = AdGuardPackagePanel.load(readerOf(FakeCaller(uci = mapOf("adguardhome" to JsonObject(emptyMap())))))
        assertEquals(listOf("Package", "DNS"), missing.sections.map { it.heading })
        assertTrue(missing.sections[0].rows.isEmpty())
        assertTrue(missing.sections[1].rows.isEmpty())
        assertTrue(missing.sections[0].emptyText!!.contains("AdGuard Home"))
        assertTrue(missing.sections[1].emptyText!!.contains("AdGuard Home"))
        assertEquals(EXPECTED_NOTE, missing.note)
        assertTrue(missing.forms.isEmpty())

        val unrelated = Json.parseToJsonElement(
            """{"values":{"cfg01":{".type":"system","enabled":"1","started":"1"}}}"""
        ).jsonObject
        val other = AdGuardPackagePanel.load(readerOf(FakeCaller(uci = mapOf("adguardhome" to unrelated))))
        assertTrue(other.sections.all { it.rows.isEmpty() && it.emptyText != null })
        assertNull(other.sections[0].rows.firstOrNull())
    }

    @Test
    fun loadDoesNotThrowWhenTheReadFails() : Unit = runTest {
        val view = AdGuardPackagePanel.load(readerOf(ThrowingCaller()))
        assertEquals(listOf("Package", "DNS"), view.sections.map { it.heading })
        assertTrue(view.sections.all { it.rows.isEmpty() && it.emptyText != null })
        assertEquals(EXPECTED_NOTE, view.note)
    }
}
