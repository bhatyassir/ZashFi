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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Only canned, allow-listed reads: no router is contacted and nothing is written. */
class ExtraPanelsTest {

    /** Answers `uci get` and `file exec` from saved replies, and records every call. */
    private class FakeCaller(
        private val uci: Map<String, JsonObject> = emptyMap(),
        private val exec: Map<String, JsonObject> = emptyMap()
    ) : UbusCaller {
        val calls = mutableListOf<Triple<String, String, JsonObject>>()

        override suspend fun call(ubusObject: String, method: String, args: JsonObject): JsonObject {
            calls += Triple(ubusObject, method, args)
            return when (ubusObject to method) {
                "uci" to "get" -> uci[(args["config"] as? JsonPrimitive)?.content.orEmpty()] ?: JsonObject(emptyMap())
                "file" to "exec" -> exec[(args["command"] as? JsonPrimitive)?.content.orEmpty()] ?: JsonObject(emptyMap())
                else -> JsonObject(emptyMap())
            }
        }

        fun execCommands(): List<String> = calls
            .filter { (obj, method, _) -> obj == "file" && method == "exec" }
            .map { (_, _, args) -> (args["command"] as? JsonPrimitive)?.content.orEmpty() }
    }

    private fun execReply(stdout: String, code: Int = 0) = buildJsonObject {
        put("code", code)
        put("stdout", stdout)
        put("stderr", "")
    }

    private fun readerOf(caller: UbusCaller) = RouterReader(caller)

    private val firewallWithExtras = Json.parseToJsonElement(
        """{"values":{
          "fwd":{".type":"redirect",".index":1,"name":"Camera","src_dport":"8443","dest_ip":"192.168.1.50","dest_port":"443"},
          "nat2":{".type":"redirect",".index":2,"name":"SNAT-out","target":"SNAT","to_dport":"8080","dest_ip":"10.0.0.9"},
          "bans":{".type":"ipset",".index":3,"name":"blocked","entry":["1.2.3.4","5.6.7.0/24"]},
          "empty":{".type":"ipset",".index":4,"name":"empty-set"}
        }}"""
    ).jsonObject

    @Test
    fun firewallExtrasShowsNonSimpleRedirectsIpSetsAndAReadOnlyNote() : Unit = runTest {
        val caller = FakeCaller(uci = mapOf("firewall" to firewallWithExtras))
        val view = ExtraPanels.firewallExtras(readerOf(caller))

        assertEquals(listOf("NAT Rules", "IP Sets", "Custom Rules"), view.sections.map { it.heading })
        assertTrue(caller.calls.single().let { (obj, method, args) ->
            obj == "uci" && method == "get" && (args["config"] as? JsonPrimitive)?.content == "firewall"
        })

        // The DNAT redirect with src_dport is already editable as a simple port forward.
        val nat = view.sections[0]
        assertEquals(1, nat.rows.size)
        assertEquals("SNAT-out", nat.rows[0].label)
        assertTrue(nat.rows[0].value.contains("src_dport=-"))
        assertTrue(nat.rows[0].value.contains("to_dport=8080"))
        assertTrue(nat.rows[0].value.contains("dest_ip=10.0.0.9"))
        assertTrue(nat.rows[0].mono)

        val ipSets = view.sections[1]
        assertEquals(3, ipSets.rows.size)
        assertEquals("blocked", ipSets.rows[0].label)
        assertEquals("1.2.3.4", ipSets.rows[0].value)
        assertEquals("5.6.7.0/24", ipSets.rows[1].value)
        assertEquals("empty-set", ipSets.rows[2].label)
        assertEquals("No entries", ipSets.rows[2].value)
        assertTrue(ipSets.rows.all { it.mono })

        // Custom rules must never become an editable form or a change plan.
        val custom = view.sections[2]
        assertEquals(1, custom.rows.size)
        assertTrue(custom.rows[0].sub!!.contains("/cgi-bin/luci/admin/network/firewall/custom"))
        assertTrue(view.note!!.contains("/cgi-bin/luci/admin/network/firewall"))
        assertTrue(view.forms.isEmpty())
        assertTrue(view.sections.all { it.form == null && it.moreForms.isEmpty() })
        assertTrue(view.sections.all { rows -> rows.rows.all { it.extra == null && it.onToggle == null && it.toggle == null } })
    }

    @Test
    fun firewallExtrasUsesEmptyTextWhenThereAreNoExtras() : Unit = runTest {
        val caller = FakeCaller(uci = mapOf("firewall" to JsonObject(emptyMap())))
        val view = ExtraPanels.firewallExtras(readerOf(caller))

        assertTrue(view.sections[0].rows.isEmpty())
        assertTrue(view.sections[0].emptyText!!.contains("simple port forward"))
        assertTrue(view.sections[1].rows.isEmpty())
        assertTrue(view.sections[1].emptyText!!.contains("No IP sets"))
        assertEquals(1, view.sections[2].rows.size)
    }

    private val tailscaleJson = """
        {"BackendState":"Running",
         "Self":{"DNSName":"router.tailnet.ts.net","TailscaleIPs":["100.64.0.1","fd7a::1"]},
         "Peer":{
           "n1":{"ID":"n1","HostName":"laptop","Online":true,"ExitNode":true,"TailscaleIPs":["100.64.0.2"]},
           "n2":{"ID":"n2","HostName":"phone","Online":false,"ExitNode":false,"TailscaleIPs":["100.64.0.3"]}
         }}
    """.trimIndent()

    @Test
    fun tailscaleDetailsParsesBackendSelfAndPeers() : Unit = runTest {
        val caller = FakeCaller(exec = mapOf(
            "/usr/bin/tailscale" to execReply(tailscaleJson),
            "/usr/sbin/tailscale" to execReply(tailscaleJson)
        ))
        val view = ExtraPanels.tailscaleDetails(readerOf(caller))

        assertEquals(listOf("Tailscale"), view.sections.map { it.heading })
        val rows = view.sections.single().rows
        assertEquals(5, rows.size)
        assertEquals("Backend state", rows[0].label)
        assertEquals("Running", rows[0].value)
        assertEquals("DNS name", rows[1].label)
        assertEquals("router.tailnet.ts.net", rows[1].value)
        assertEquals("Tailscale IP", rows[2].label)
        assertEquals("100.64.0.1", rows[2].value)
        assertTrue(rows[2].mono)

        assertEquals("laptop", rows[3].label)
        assertEquals("100.64.0.2", rows[3].value)
        assertTrue(rows[3].sub!!.contains("Online: Yes"))
        assertTrue(rows[3].sub!!.contains("Exit node: Yes"))
        assertEquals(PillTone.Ok, rows[3].pill!!.tone)
        assertEquals("phone", rows[4].label)
        assertTrue(rows[4].sub!!.contains("Online: No"))
        assertTrue(rows[4].sub!!.contains("Exit node: No"))
        assertEquals(PillTone.Off, rows[4].pill!!.tone)
        assertTrue(view.forms.isEmpty())

        // /usr/bin is tried first, then /usr/sbin only if needed.
        assertEquals("/usr/bin/tailscale", caller.execCommands().single())
        val args = caller.calls.single().third
        val params = (args["params"] as? kotlinx.serialization.json.JsonArray)
            ?.map { (it as? JsonPrimitive)?.content }
        assertEquals(listOf("status", "--json"), params)
    }

    @Test
    fun tailscaleDetailsFallsBackToUsrSbin() : Unit = runTest {
        val caller = FakeCaller(exec = mapOf(
            "/usr/bin/tailscale" to execReply("", code = 1),
            "/usr/sbin/tailscale" to execReply(tailscaleJson)
        ))
        val view = ExtraPanels.tailscaleDetails(readerOf(caller))

        assertEquals(listOf("/usr/bin/tailscale", "/usr/sbin/tailscale"), caller.execCommands())
        assertEquals("Running", view.sections.single().rows.first().value)
        assertEquals(5, view.sections.single().rows.size)
    }

    @Test
    fun tailscaleDetailsExplainsMissingCommandAndBadJsonWithoutThrowing() : Unit = runTest {
        val missingCaller = FakeCaller(exec = mapOf(
            "/usr/bin/tailscale" to execReply("", code = 1),
            "/usr/sbin/tailscale" to execReply("", code = 1)
        ))
        val missing = ExtraPanels.tailscaleDetails(readerOf(missingCaller))
        assertEquals(listOf("Tailscale"), missing.sections.map { it.heading })
        assertTrue(missing.sections.single().rows.isEmpty())
        assertTrue(missing.sections.single().emptyText!!.contains("not installed"))

        val bad = ExtraPanels.tailscaleDetails(
            readerOf(FakeCaller(exec = mapOf("/usr/bin/tailscale" to execReply("{not json"))))
        )
        assertEquals(listOf("Tailscale"), bad.sections.map { it.heading })
        assertTrue(bad.sections.single().rows.isEmpty())
        assertTrue(bad.sections.single().emptyText!!.contains("valid status"))
        assertNull(bad.pill)
        assertNotNull(bad.note)
        assertFalse(bad.forms.isNotEmpty())
    }
}
