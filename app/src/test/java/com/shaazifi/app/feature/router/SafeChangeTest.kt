package com.shaazifi.app.feature.router

import com.shaazifi.app.core.ubus.UbusError
import com.shaazifi.app.feature.home.UbusCaller
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeChangeTest {

    /** Fake router that records every call as "object.method args". */
    private class Recorder(private val failOn: String? = null, private val reachable: Boolean = true) : UbusCaller {
        val log = mutableListOf<String>()
        override suspend fun call(ubusObject: String, method: String, args: JsonObject): JsonObject {
            log += "$ubusObject.$method $args"
            if (failOn != null && "$ubusObject.$method" == failOn) throw UbusError.RpcError(2, "invalid argument")
            if (ubusObject == "system" && !reachable) throw UbusError.NetworkError(java.io.IOException("gone"))
            return if (method == "add") buildJsonObject { put("section", "cfg123") } else JsonObject(emptyMap())
        }
    }

    private val blockPlan = ChangePlan(
        title = "Block internet for Tablet?",
        lines = listOf(ChangeLine("Internet", "Allowed", "Blocked")),
        ops = listOf(
            UciOp.Add("firewall", "rule", mapOf("name" to "Block: Tablet", "src" to "lan", "dest" to "wan",
                "src_mac" to "AA:BB:CC:DD:EE:01", "proto" to "all", "target" to "REJECT", "enabled" to "1"))
        ),
        reloadFirewall = true
    )

    @Test
    fun nothingIsSentWithoutConfirmation() = runBlocking {
        val router = Recorder()
        val controller = ChangeController(SafeChangeRunner(RouterWriter(router), probeAttempts = 1, probeDelayMs = 0))
        controller.request(blockPlan)
        assertTrue(controller.state.value is ChangeUiState.Confirming)
        assertEquals("No call may be made before the user confirms", 0, router.log.size)
        controller.cancel()
        assertEquals(ChangeUiState.Idle, controller.state.value)
        assertEquals(0, router.log.size)
    }

    @Test
    fun confirmedChangeIsStagedAppliedWithRollbackCheckedThenKept() = runBlocking {
        val router = Recorder()
        val controller = ChangeController(SafeChangeRunner(RouterWriter(router), probeAttempts = 1, probeDelayMs = 0))
        controller.request(blockPlan)
        controller.confirm()
        val calls = router.log.map { it.substringBefore(' ') }
        assertEquals(listOf("uci.add", "uci.apply", "file.exec", "system.board", "uci.confirm"), calls)
        assertTrue("apply must use the router's rollback timer", router.log[1].contains("\"rollback\":true") && router.log[1].contains("\"timeout\":120"))
        assertTrue(router.log[2].contains("/etc/init.d/firewall") && router.log[2].contains("reload"))
        assertEquals(ChangeUiState.Done(ChangeOutcome.Kept), controller.state.value)
    }

    @Test
    fun ifTheRouterCannotBeReachedTheChangeIsNotConfirmedAndTheRouterUndoesIt() = runBlocking {
        val router = Recorder(reachable = false)
        val outcome = SafeChangeRunner(RouterWriter(router), probeAttempts = 2, probeDelayMs = 0).run(blockPlan)
        assertEquals(ChangeOutcome.LostConnection, outcome)
        assertFalse(router.log.any { it.startsWith("uci.confirm") })
    }

    @Test
    fun aFailedStepRevertsWhatWasStagedAndNeverApplies() = runBlocking {
        val router = Recorder(failOn = "uci.set")
        val plan = ChangePlan("x", emptyList(), listOf(
            UciOp.Add("dhcp", "host", mapOf("mac" to "AA:BB:CC:DD:EE:01")),
            UciOp.Set("dhcp", "cfg9", mapOf("ip" to "192.168.1.50"))
        ))
        val outcome = SafeChangeRunner(RouterWriter(router), probeAttempts = 1, probeDelayMs = 0).run(plan)
        assertTrue(outcome is ChangeOutcome.Failed)
        assertFalse(router.log.any { it.startsWith("uci.apply") })
        assertTrue(router.log.any { it.startsWith("uci.revert") && it.contains("dhcp") })
    }

    @Test
    fun writerRefusesAnythingOutsideItsList() {
        val ok = { o: String, m: String, a: JsonObject -> RouterWriter.isAllowedWrite(o, m, a) }
        assertTrue(ok("uci", "set", buildJsonObject { put("config", "dhcp"); put("section", "cfg1"); put("values", buildJsonObject { put("ip", "192.168.1.5") }) }))
        assertTrue(ok("uci", "apply", buildJsonObject { put("rollback", true); put("timeout", 120) }))
        assertFalse("apply without rollback is never allowed", ok("uci", "apply", buildJsonObject { put("rollback", false) }))
        assertFalse(ok("uci", "apply", buildJsonObject { put("rollback", true); put("timeout", 5) }))
        assertFalse("configs outside the list", ok("uci", "set", buildJsonObject { put("config", "network"); put("section", "lan"); put("values", buildJsonObject { put("ipaddr", "1.2.3.4") }) }))
        assertFalse(ok("uci", "set", buildJsonObject { put("config", "rpcd"); put("section", "x"); put("values", buildJsonObject {}) }))
        assertFalse(ok("file", "exec", buildJsonObject { put("command", "/sbin/reboot"); put("params", buildJsonArray {}) }))
        assertTrue(ok("file", "exec", buildJsonObject {
            put("command", "/usr/bin/etherwake"); put("params", buildJsonArray { add(JsonPrimitive("-i")); add(JsonPrimitive("br-lan")); add(JsonPrimitive("AA:BB:CC:DD:EE:01")) })
        }))
        assertFalse("wake only takes a MAC address", ok("file", "exec", buildJsonObject {
            put("command", "/usr/bin/etherwake"); put("params", buildJsonArray { add(JsonPrimitive("-i")); add(JsonPrimitive("br-lan")); add(JsonPrimitive("AA;rm -rf /")) })
        }))
        assertFalse(ok("luci", "setPassword", JsonObject(emptyMap())))
    }

    @Test
    fun aCommandPlanRunsOnceWithoutApplyOrConfirm() = runBlocking {
        val router = Recorder()
        val plan = ChangePlan("Wake?", emptyList(), emptyList(),
            command = "/usr/bin/etherwake" to listOf("-i", "br-lan", "AA:BB:CC:DD:EE:01"))
        val outcome = SafeChangeRunner(RouterWriter(router), probeAttempts = 1, probeDelayMs = 0).run(plan)
        assertEquals(ChangeOutcome.Kept, outcome)
        assertEquals(listOf("file.exec"), router.log.map { it.substringBefore(' ') })
    }
}
