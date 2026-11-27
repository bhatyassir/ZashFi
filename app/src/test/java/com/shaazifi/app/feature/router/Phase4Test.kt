package com.shaazifi.app.feature.router

import com.shaazifi.app.feature.home.UbusCaller
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4 (T-20261117-C0594015). Sources (read 2026-11-17): openwrt/rpcd rc.c (`rc init` actions
 * disable, enable, stop, start, restart, reload); openwrt/luci luci-base rpcd ucode `luci`
 * (getTimezones {zone: {tzstring}}, setPassword {username, password}); luci-mod-system system.js
 * (sets zonename and timezone); luci-mod-network routes.js (route: interface, target, gateway,
 * metric, disabled); luci-app-firewall zones.js / rules.js (zone input/output/forward/masq/mtu_fix;
 * rule name/src/dest/proto/dest_port/target/enabled). Owner decisions: password and reboot with a
 * typed confirmation; zones, rules and routes with automatic undo.
 */
class Phase4Test {

    private fun plan(r: PlanResult) = (r as PlanResult.Ready).plan
    private fun err(r: PlanResult) = (r as PlanResult.Invalid).message

    @Test
    fun servicesStartStopRestartButNeverStopWhatTheAppNeeds() {
        val p = plan(Phase4.serviceAction("dnsmasq", "restart"))
        assertEquals(UbusCall("rc", "init", mapOf("name" to "dnsmasq", "action" to "restart")), p.ubusCall)
        assertTrue(p.ops.isEmpty())
        assertTrue(err(Phase4.serviceAction("uhttpd", "stop")).contains("app", true))
        assertTrue(err(Phase4.serviceAction("rpcd", "disable")).isNotEmpty())
        assertTrue(err(Phase4.serviceAction("network", "stop")).isNotEmpty())
        assertTrue(Phase4.serviceAction("uhttpd", "restart") is PlanResult.Ready)
        assertTrue(err(Phase4.serviceAction("a;b", "start")).isNotEmpty())
        assertTrue(err(Phase4.serviceAction("dnsmasq", "explode")).isNotEmpty())
    }

    @Test
    fun rebootAndPasswordNeedTheWordTyped() {
        assertTrue(err(Phase4.reboot("restart please")).contains("RESTART"))
        assertEquals(UbusCall("system", "reboot", emptyMap()), plan(Phase4.reboot("RESTART")).ubusCall)

        assertTrue(err(Phase4.password("newpass123", "newpass123", "change")).contains("CHANGE"))
        assertTrue(err(Phase4.password("newpass123", "other", "CHANGE")).contains("match", true))
        assertTrue(err(Phase4.password("short", "short", "CHANGE")).contains("8"))
        val p = plan(Phase4.password("newpass123", "newpass123", "CHANGE"))
        assertEquals(UbusCall("luci", "setPassword", mapOf("username" to "root", "password" to "newpass123")), p.ubusCall)
        assertFalse("never shown back", p.lines.any { it.after.contains("newpass123") || it.before.contains("newpass123") })
        assertTrue(p.note!!.contains("saved password", true))
    }

    @Test
    fun timeZoneSetsNameAndString() {
        val zones = mapOf("Asia/Kolkata" to "IST-5:30", "UTC" to "UTC0")
        val p = plan(Phase4.timeZone("cfg01e48a", "UTC", "Asia/Kolkata", zones))
        assertEquals(listOf<UciOp>(UciOp.Set("system", "cfg01e48a", mapOf("zonename" to "Asia/Kolkata", "timezone" to "IST-5:30"))), p.ops)
        assertTrue(err(Phase4.timeZone("cfg01e48a", "UTC", "Mars/Base", zones)).isNotEmpty())
    }

    private val fw = FeatureMappersTest.uci("""{"cfg01":{".type":"zone","name":"lan","network":["lan"],"input":"ACCEPT","output":"ACCEPT","forward":"ACCEPT"},
        "cfg02":{".type":"zone","name":"wan","network":["wan"],"input":"REJECT","output":"ACCEPT","forward":"REJECT","masq":"1","mtu_fix":"1"},
        "cfg03":{".type":"rule","name":"Allow-Ping","src":"wan","proto":"icmp","target":"ACCEPT"}}""")

    @Test
    fun zonesRulesWithChecks() {
        val lan = fw.ofType("zone").first { it.opt("name") == "lan" }
        val p = plan(Phase4.zoneEdit(lan, "REJECT", "ACCEPT", "ACCEPT", "no"))
        assertEquals(listOf<UciOp>(UciOp.Set("firewall", "cfg01", mapOf("input" to "REJECT"))), p.ops)
        assertTrue(p.reloadFirewall)
        assertTrue("warns about losing access", p.note!!.contains("undo", true))
        assertTrue(err(Phase4.zoneEdit(lan, "MAYBE", "ACCEPT", "ACCEPT", "no")).isNotEmpty())

        val add = plan(Phase4.addRule(mapOf("name" to "Block telnet", "src" to "lan", "dest" to "wan", "proto" to "tcp", "dest_port" to "23", "target" to "REJECT"), setOf("lan", "wan")))
        assertEquals(UciOp.Add("firewall", "rule", mapOf("name" to "Block telnet", "src" to "lan", "dest" to "wan", "proto" to "tcp", "dest_port" to "23", "target" to "REJECT")), add.ops.single())
        val multiProto = plan(Phase4.addRule(mapOf("name" to "Web", "src" to "lan", "dest" to "wan", "proto" to "tcp udp", "dest_port" to "8000-8100", "target" to "ACCEPT"), setOf("lan", "wan")))
        assertEquals(listOf("tcp", "udp"), (multiProto.ops.single() as UciOp.Add).values["proto"])
        assertTrue(err(Phase4.addRule(mapOf("name" to "x", "src" to "nowhere", "target" to "ACCEPT"), setOf("lan", "wan"))).contains("zone", true))
        assertTrue(err(Phase4.addRule(mapOf("name" to "x", "src" to "lan", "proto" to "tcp", "dest_port" to "99999", "target" to "ACCEPT"), setOf("lan", "wan"))).contains("port", true))
        assertTrue(err(Phase4.addRule(mapOf("name" to "x", "src" to "lan", "proto" to "tcp", "dest_port" to "8100-8000", "target" to "ACCEPT"), setOf("lan", "wan"))).contains("port", true))
        assertTrue(err(Phase4.addRule(mapOf("name" to "x", "src" to "lan", "proto" to "icmp", "dest_port" to "80", "target" to "ACCEPT"), setOf("lan", "wan"))).contains("port", true))
        assertEquals(listOf<UciOp>(UciOp.Set("firewall", "cfg03", mapOf("enabled" to "0"))), plan(Phase4.ruleEnabled("cfg03", "Allow-Ping", false)).ops)
        assertEquals(listOf<UciOp>(UciOp.Delete("firewall", "cfg03")), plan(Phase4.removeRule("cfg03", "Allow-Ping")).ops)
    }

    @Test
    fun staticRoutes() {
        val p = plan(Phase4.addRoute("lan", "10.8.0.0/24", "192.168.1.2", "", setOf("lan", "wan")))
        assertEquals(UciOp.Add("network", "route", mapOf("interface" to "lan", "target" to "10.8.0.0/24", "gateway" to "192.168.1.2")), p.ops.single())
        assertTrue(err(Phase4.addRoute("lan", "10.8.0.0/33", "", "", setOf("lan"))).contains("address", true))
        assertTrue(err(Phase4.addRoute("nope", "10.8.0.0/24", "", "", setOf("lan"))).contains("interface", true))
        assertEquals(listOf<UciOp>(UciOp.DeleteWhere("network", "route", mapOf("interface" to "lan", "target" to "10.8.0.0/24"))),
            plan(Phase4.removeRoute("lan", "10.8.0.0/24")).ops)
        assertEquals(listOf<UciOp>(UciOp.DeleteWhere("network", "route", mapOf("interface" to "lan", "target" to "10.8.0.0/24", "gateway" to "192.168.1.2", "metric" to "10"))),
            plan(Phase4.removeRoute("lan", "10.8.0.0/24", gateway = "192.168.1.2", metric = "10")).ops)
        assertTrue(err(Phase4.removeRoute("", "10.8.0.0/24")).isNotEmpty())
        assertEquals(listOf<UciOp>(UciOp.Set("network", "cfg0a", mapOf("disabled" to "1"))), plan(Phase4.routeEnabled("cfg0a", "10.8.0.0/24", false)).ops)
    }

    @Test
    fun theWriterAllowsExactlyTheseActions() {
        fun ok(o: String, m: String, a: JsonObject) = RouterWriter.isAllowedWrite(o, m, a)
        fun obj(vararg kv: Pair<String, String>) = buildJsonObject { kv.forEach { (k, v) -> put(k, v) } }
        assertTrue(ok("rc", "init", obj("name" to "dnsmasq", "action" to "restart")))
        assertFalse(ok("rc", "init", obj("name" to "uhttpd", "action" to "stop")))
        assertTrue(ok("system", "reboot", JsonObject(emptyMap())))
        assertTrue(ok("luci", "setPassword", obj("username" to "root", "password" to "newpass123")))
        assertFalse(ok("luci", "setPassword", obj("username" to "admin", "password" to "x")))
        assertTrue(ok("uci", "add", buildJsonObject { put("config", "network"); put("type", "route"); put("values", obj("interface" to "lan", "target" to "10.8.0.0/24")) }))
        assertFalse(ok("uci", "add", buildJsonObject { put("config", "network"); put("type", "route"); put("values", obj("interface" to "lan", "table" to "main")) }))
        assertTrue(ok("uci", "delete", buildJsonObject { put("config", "network"); put("type", "route"); put("match", obj("interface" to "lan", "target" to "10.8.0.0/24")) }))
        assertTrue(ok("uci", "delete", buildJsonObject { put("config", "network"); put("type", "route"); put("match", obj("interface" to "lan", "target" to "10.8.0.0/24", "gateway" to "192.168.1.2", "metric" to "10")) }))
        assertTrue(ok("uci", "set", buildJsonObject { put("config", "system"); put("section", "cfg01e48a"); put("values", obj("zonename" to "UTC", "timezone" to "UTC0")) }))
        assertTrue(ok("uci", "set", buildJsonObject { put("config", "firewall"); put("section", "cfg01"); put("values", obj("input" to "ACCEPT", "masq" to "1", "mtu_fix" to "1")) }))
    }

    @Test
    fun oneShotActionsRunOnceWithoutApply() = runBlocking {
        val calls = mutableListOf<Pair<String, String>>()
        val caller = object : UbusCaller {
            override suspend fun call(ubusObject: String, method: String, args: JsonObject): JsonObject {
                calls += ubusObject to method
                return if (method == "setPassword") Json.parseToJsonElement("""{"result":false}""").jsonObject else JsonObject(emptyMap())
            }
        }
        val runner = SafeChangeRunner(RouterWriter(caller), probeAttempts = 1, probeDelayMs = 0)
        assertEquals(ChangeOutcome.Kept, runner.run(plan(Phase4.serviceAction("dnsmasq", "restart"))))
        assertEquals(listOf("rc" to "init"), calls)
        calls.clear()
        assertTrue("router said no", runner.run(plan(Phase4.password("newpass123", "newpass123", "CHANGE"))) is ChangeOutcome.Failed)
        assertEquals(listOf("luci" to "setPassword"), calls)
    }
}
