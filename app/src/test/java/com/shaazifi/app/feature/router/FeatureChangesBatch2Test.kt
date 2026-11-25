package com.shaazifi.app.feature.router

import com.shaazifi.app.feature.home.UbusCaller
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureChangesBatch2Test {

    private fun plan(r: PlanResult) = (r as PlanResult.Ready).plan
    private fun error(r: PlanResult) = (r as PlanResult.Invalid).message
    private fun allowed(p: ChangePlan) = p.ops.all { op ->
        val (m, a) = RouterWriter.argsFor(op)
        RouterWriter.isAllowedWrite("uci", m, a)
    } && p.services.all { (c, ps) -> RouterWriter.isAllowedService(c, ps) }

    @Test
    fun routerName() {
        val p = plan(FeatureChanges.hostname("cfg01e48a", "OpenWrt", " Home-Router "))
        assertEquals(UciOp.Set("system", "cfg01e48a", mapOf("hostname" to "Home-Router")), p.ops.single())
        assertTrue(allowed(p))
        assertTrue(error(FeatureChanges.hostname("s", "a", "bad name!")).contains("letters"))
        assertTrue(error(FeatureChanges.hostname("s", "a", "-start")).contains("letters"))
        assertTrue(error(FeatureChanges.hostname("s", "a", "")).isNotBlank())
    }

    @Test
    fun dhcpPool() {
        val p = plan(FeatureChanges.dhcpPool("lan", "100", "150", "12h"))
        assertEquals(UciOp.Set("dhcp", "lan", mapOf("start" to "100", "limit" to "150", "leasetime" to "12h")), p.ops.single())
        assertTrue(allowed(p))
        assertTrue(error(FeatureChanges.dhcpPool("lan", "0", "150", "12h")).contains("First"))
        assertTrue(error(FeatureChanges.dhcpPool("lan", "200", "100", "12h")).contains("254"))
        assertTrue(error(FeatureChanges.dhcpPool("lan", "100", "50", "1m")).contains("lease"))
        assertTrue(error(FeatureChanges.dhcpPool("lan", "100", "50", "forever")).contains("lease"))
        assertTrue(plan(FeatureChanges.dhcpPool("lan", "10", "20", "2d")).ops.isNotEmpty())
    }

    @Test
    fun packageSwitches() {
        val d = plan(FeatureChanges.dlnaEnabled("config", true))
        assertEquals(UciOp.Set("minidlna", "config", mapOf("enabled" to "1")), d.ops.single())
        assertEquals(listOf("/etc/init.d/minidlna" to listOf("restart"), "/etc/init.d/minidlna" to listOf("enable")), d.services)
        assertTrue(allowed(d))
        assertEquals(listOf("/etc/init.d/minidlna" to listOf("stop")), plan(FeatureChanges.dlnaEnabled("config", false)).services)

        assertEquals(UciOp.Set("adblock", "global", mapOf("adb_enabled" to "0")), plan(FeatureChanges.adblockEnabled("global", false)).ops.single())
        assertEquals(UciOp.Set("banip", "global", mapOf("ban_enabled" to "1")), plan(FeatureChanges.banipEnabled("global", true)).ops.single())
        assertEquals(UciOp.Set("openvpn", "office", mapOf("enabled" to "0")), plan(FeatureChanges.openvpnEnabled("office", "office", false)).ops.single())
        listOf(FeatureChanges.adblockEnabled("g", true), FeatureChanges.banipEnabled("g", true), FeatureChanges.openvpnEnabled("o", "o", true))
            .forEach { assertTrue(allowed(plan(it))) }
    }

    @Test
    fun wifiNameAndPasswordWaitLongerAndNeverShowThePassword() {
        val p = plan(FeatureChanges.wifiNetwork("default_radio0", "Home", "Home-5G", "new-secret-123", "WPA2"))
        assertEquals(UciOp.Set("wireless", "default_radio0", mapOf("ssid" to "Home-5G", "key" to "new-secret-123")), p.ops.single())
        assertTrue("phone will drop off, so the app must wait long enough to rejoin", p.longWait)
        assertFalse("password must not appear in the confirmation", p.lines.any { it.before.contains("new-secret") || it.after.contains("new-secret") || it.label.contains("new-secret") })
        assertFalse((p.note ?: "").contains("new-secret"))
        assertTrue(allowed(p))

        val nameOnly = plan(FeatureChanges.wifiNetwork("default_radio0", "Home", "Home-2", "", "WPA2"))
        assertEquals(UciOp.Set("wireless", "default_radio0", mapOf("ssid" to "Home-2")), nameOnly.ops.single())

        assertTrue(error(FeatureChanges.wifiNetwork("s", "Home", "Home", "short", "WPA2")).contains("8"))
        assertTrue(error(FeatureChanges.wifiNetwork("s", "Home", "", "", "WPA2")).contains("name"))
        assertTrue(error(FeatureChanges.wifiNetwork("s", "Home", "x".repeat(33), "", "WPA2")).contains("32"))
        assertTrue(error(FeatureChanges.wifiNetwork("s", "Home", "Home", "", "WPA2")).contains("Nothing"))
        assertTrue(error(FeatureChanges.wifiNetwork("s", "Home", "Home", "newpassword1", "Open (no password)")).contains("open"))
    }

    @Test
    fun writerKeepsWifiChangesNarrow() {
        fun set(values: Map<String, Any>) = RouterWriter.argsFor(UciOp.Set("wireless", "s1", values)).let { (m, a) -> RouterWriter.isAllowedWrite("uci", m, a) }
        assertTrue(set(mapOf("ssid" to "x", "key" to "12345678")))
        assertFalse(set(mapOf("encryption" to "none")))
        assertFalse(set(mapOf("network" to "wan")))
        assertFalse(set(mapOf("mode" to "sta")))
        assertFalse(set(mapOf("device" to "radio1")))
    }

    @Test
    fun longWaitPlansProbeLongerBeforeGivingUp() = runBlocking {
        var boardCalls = 0
        val router = object : UbusCaller {
            override suspend fun call(ubusObject: String, method: String, args: JsonObject): JsonObject {
                if (ubusObject == "system") { boardCalls++; throw com.shaazifi.app.core.ubus.UbusError.NetworkError(java.io.IOException("off wifi")) }
                return JsonObject(emptyMap())
            }
        }
        val runner = SafeChangeRunner(RouterWriter(router), probeAttempts = 2, probeDelayMs = 0, longWaitAttempts = 30)
        val p = plan(FeatureChanges.wifiNetwork("default_radio0", "Home", "Home-5G", "", "WPA2"))
        assertEquals(ChangeOutcome.LostConnection, runner.run(p))
        assertEquals(30, boardCalls)
    }
}
