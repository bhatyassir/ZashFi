package com.shaazifi.app.feature.router

import com.shaazifi.app.feature.home.UbusCaller
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureChangesBatch3Test {

    private fun plan(r: PlanResult) = (r as PlanResult.Ready).plan
    private fun error(r: PlanResult) = (r as PlanResult.Invalid).message
    private fun ok(op: UciOp) = RouterWriter.argsFor(op).let { (m, a) -> RouterWriter.isAllowedWrite("uci", m, a) }

    @Test
    fun networkChangesAreLimitedBySection() {
        assertTrue(ok(UciOp.Set("network", "wan", mapOf("proto" to "dhcp"))))
        assertTrue(ok(UciOp.Set("network", "wan", mapOf("proto" to "pppoe", "username" to "u", "password" to "p"))))
        assertTrue(ok(UciOp.Set("network", "wan6", mapOf("disabled" to "1"))))
        assertFalse(ok(UciOp.Set("network", "wan6", mapOf("proto" to "static"))))
        assertTrue("WireGuard tunnel on/off", ok(UciOp.Set("network", "wg0", mapOf("disabled" to "1"))))
        assertFalse(ok(UciOp.Set("network", "wg0", mapOf("private_key" to "x"))))
        assertFalse("the LAN can never be changed here", ok(UciOp.Set("network", "lan", mapOf("ipaddr" to "10.0.0.1"))))
        assertFalse(ok(UciOp.Set("network", "lan", mapOf("disabled" to "1"))))
        assertFalse(ok(UciOp.Set("network", "loopback", mapOf("disabled" to "1"))))
        assertFalse("WAN can't be switched off from here", ok(UciOp.Set("network", "wan", mapOf("disabled" to "1"))))
        assertFalse(ok(UciOp.Delete("network", "wan")))
        assertFalse(ok(UciOp.Add("network", "interface", mapOf("proto" to "static"))))
    }

    @Test
    fun wanPlansAreValidatedAndCheckTheInternet() {
        val dhcp = plan(FeatureChanges.wan("static", "dhcp", "", "", "", "", "", ""))
        assertEquals(UciOp.Set("network", "wan", mapOf("proto" to "dhcp", "peerdns" to "1")), dhcp.ops.single())
        assertTrue(dhcp.checkInternet)

        val st = plan(FeatureChanges.wan("dhcp", "static", "192.168.31.50", "255.255.255.0", "192.168.31.1", "1.1.1.1 9.9.9.9", "", ""))
        val op = st.ops.single() as UciOp.Set
        assertEquals("static", op.values["proto"])
        assertEquals("192.168.31.50", op.values["ipaddr"])
        assertEquals(listOf("1.1.1.1", "9.9.9.9"), op.values["dns"])
        assertEquals("0", op.values["peerdns"])
        assertTrue(ok(op))

        assertTrue(error(FeatureChanges.wan("dhcp", "static", "192.168.31.50", "255.255.255.0", "10.0.0.1", "", "", "")).contains("gateway"))
        assertTrue(error(FeatureChanges.wan("dhcp", "static", "192.168.31.50", "255.0.255.0", "192.168.31.1", "", "", "")).contains("mask"))
        assertTrue(error(FeatureChanges.wan("dhcp", "static", "300.1.1.1", "255.255.255.0", "192.168.31.1", "", "", "")).contains("address"))
        assertTrue(error(FeatureChanges.wan("dhcp", "static", "192.168.31.50", "255.255.255.0", "192.168.31.1", "dns.example", "", "")).contains("DNS"))
        assertTrue(error(FeatureChanges.wan("dhcp", "carrier-pigeon", "", "", "", "", "", "")).isNotBlank())

        val ppp = plan(FeatureChanges.wan("dhcp", "pppoe", "", "", "", "", "isp-user", "isp-secret-9"))
        assertEquals("isp-secret-9", (ppp.ops.single() as UciOp.Set).values["password"])
        assertFalse("ISP password must not be shown", ppp.lines.any { "isp-secret-9" in it.before || "isp-secret-9" in it.after })
        assertTrue(error(FeatureChanges.wan("dhcp", "pppoe", "", "", "", "", "isp-user", "")).contains("password"))
        val keepPw = plan(FeatureChanges.wan("pppoe", "pppoe", "", "", "", "", "isp-user", ""))
        assertFalse("staying on PPPoE with an empty password keeps the old one", (keepPw.ops.single() as UciOp.Set).values.containsKey("password"))
    }

    @Test
    fun ipv6AndWireguardSwitches() {
        val v6 = plan(FeatureChanges.ipv6Enabled(false))
        assertEquals(UciOp.Set("network", "wan6", mapOf("disabled" to "1")), v6.ops.single())
        val wg = plan(FeatureChanges.wireguardEnabled("wg0", false))
        assertEquals(UciOp.Set("network", "wg0", mapOf("disabled" to "1")), wg.ops.single())
        assertTrue(wg.note!!.contains("VPN"))
        assertTrue(FeatureChanges.wireguardEnabled("lan", false) is PlanResult.Invalid)
    }

    private class Router(private val wanUpAfter: Int) : UbusCaller {
        val log = mutableListOf<String>()
        var dumps = 0
        override suspend fun call(ubusObject: String, method: String, args: JsonObject): JsonObject {
            log += "$ubusObject.$method"
            if (ubusObject == "network.interface" && method == "dump") {
                dumps++
                val up = dumps >= wanUpAfter
                return Json.parseToJsonElement(
                    """{"interface":[{"interface":"wan","up":$up,"device":"eth1","proto":"dhcp",
                    "ipv4-address":${if (up) """[{"address":"192.168.31.50","mask":24}]""" else "[]"},
                    "route":[{"target":"0.0.0.0","mask":0,"nexthop":"192.168.31.1"}]}]}"""
                ).jsonObject
            }
            return JsonObject(emptyMap())
        }
    }

    @Test
    fun internetMustComeBackBeforeAWanChangeIsKept() = runBlocking {
        val p = plan(FeatureChanges.wan("static", "dhcp", "", "", "", "", "", ""))
        val good = Router(wanUpAfter = 3)
        assertEquals(ChangeOutcome.Kept, SafeChangeRunner(RouterWriter(good), probeAttempts = 1, probeDelayMs = 0, internetAttempts = 5).run(p))
        assertTrue(good.log.last() == "uci.confirm")

        val bad = Router(wanUpAfter = 99)
        assertEquals(ChangeOutcome.InternetNotBack, SafeChangeRunner(RouterWriter(bad), probeAttempts = 1, probeDelayMs = 0, internetAttempts = 5).run(p))
        assertFalse("never confirm when the internet did not come back", bad.log.contains("uci.confirm"))
        assertEquals(5, bad.dumps)
    }
}
