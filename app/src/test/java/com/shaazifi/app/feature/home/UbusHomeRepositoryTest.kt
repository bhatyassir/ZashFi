package com.shaazifi.app.feature.home

import com.shaazifi.app.core.ubus.UbusError
import com.shaazifi.app.feature.router.RouterReader
import com.shaazifi.app.feature.router.ThermalSensor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** Answers ubus calls from made-up replies and records every call it is asked to make. */
private class FakeCaller : UbusCaller {
    val replies = HashMap<String, Any>()
    val calls = ArrayList<Triple<String, String, JsonObject>>()

    fun reply(target: String, json: String) {
        replies[target] = Json.parseToJsonElement(json).jsonObject
    }

    fun fail(target: String, error: Exception) {
        replies[target] = error
    }

    override suspend fun call(ubusObject: String, method: String, args: JsonObject): JsonObject {
        calls.add(Triple(ubusObject, method, args))
        return when (val r = replies["$ubusObject $method"]) {
            is JsonObject -> r
            is Exception -> throw r
            else -> throw UbusError.ObjectNotFound()
        }
    }
}

class UbusHomeRepositoryTest {

    /** A router like the test router: `network.wireless status` is refused, luci-rpc answers. */
    private fun workingRouter(): FakeCaller {
        val c = FakeCaller()
        c.reply("system board", """{"hostname":"Device-1","model":"Example Router X1","release":{"description":"OpenWrt SNAPSHOT r1"}}""")
        c.reply("system info", """{"uptime":71426,"memory":{"total":452972544,"free":59871232,"available":63025152}}""")
        c.reply(
            "network.interface dump",
            """{"interface":[{"interface":"wan","up":true,"proto":"static","l3_device":"wan","device":"wan",
               "ipv4-address":[{"address":"198.51.100.2","mask":24}],"route":[{"target":"0.0.0.0","nexthop":"198.51.100.3"}],"uptime":64384}]}"""
        )
        c.reply("file read", """{"data":"cpu  100 0 50 1000 5 0 3 0 0 0\ncpu0 1 1 1 1\n"}""")
        c.reply("network.device status", """{"wan":{"up":true,"statistics":{"rx_bytes":5000,"tx_bytes":700}}}""")
        c.fail("network.wireless status", UbusError.RpcError(2, "invalid argument"))
        c.reply(
            "luci-rpc getWirelessDevices",
            """{"radio0":{"interfaces":[{"ifname":"phy0-ap0","config":{"mode":"ap"}}]},
                "radio1":{"interfaces":[{"ifname":"phy1-ap0","config":{"mode":"ap"}}]}}"""
        )
        c.reply(
            "luci-rpc getHostHints",
            """{"02:00:00:00:00:01":{"ipaddrs":["198.51.100.11"]},"02:00:00:00:00:02":{"ipaddrs":["198.51.100.12"]},
                "02:00:00:00:00:03":{"ipaddrs":[]}}"""
        )
        c.reply("luci-rpc getDHCPLeases", """{"dhcp_leases":[{"macaddr":"02:00:00:00:00:04","ipaddr":"198.51.100.14"}]}""")
        c.reply("hostapd.phy0-ap0 get_clients", """{"freq":2412,"clients":{}}""")
        c.reply("hostapd.phy1-ap0 get_clients", """{"freq":5745,"clients":{"02:00:00:00:00:02":{"assoc":true}}}""")
        return c
    }

    private fun loadFailure(caller: FakeCaller): HomeLoadException {
        try {
            runBlocking { UbusHomeRepository(caller).load() }
        } catch (e: HomeLoadException) {
            return e
        }
        fail("Expected the reading to fail")
        throw IllegalStateException("unreachable")
    }

    @Test
    fun testLoadsReadingAndFallsBackToLuciRpcForWireless() = runBlocking {
        val caller = workingRouter()
        val reading = UbusHomeRepository(caller).load()

        assertEquals("Example Router X1", reading.board.model)
        assertEquals(71426L, reading.uptimeSec)
        assertEquals(86, reading.memUsedPct)
        assertEquals(CpuSample(busy = 153, total = 1158), reading.cpu)
        assertTrue(reading.wan.up)
        assertEquals("198.51.100.2", reading.wan.ipv4)
        assertEquals(TrafficCounters(5000, 700), reading.wanCounters)
        assertEquals(ClientSummary(total = 3, wifi = 1, wired = 2, wifiKnown = true), reading.clients)
        assertTrue(reading.notices.isEmpty())

        val made = caller.calls.map { "${it.first} ${it.second}" }
        assertTrue(made.indexOf("network.wireless status") < made.indexOf("luci-rpc getWirelessDevices"))
        assertTrue("hostapd.phy0-ap0 get_clients" in made)
        assertTrue("hostapd.phy1-ap0 get_clients" in made)
        assertFalse("iwinfo assoclist" in made)
    }

    @Test
    fun testOnlyReadOnlyAllowlistedCallsAreMade() = runBlocking {
        val caller = workingRouter()
        // Make hostapd fail on one radio so the iwinfo fallback is exercised too.
        caller.fail("hostapd.phy0-ap0 get_clients", UbusError.ObjectNotFound())
        caller.reply("iwinfo assoclist", """{"results":[{"mac":"02:00:00:00:00:01","signal":-60}]}""")
        val reading = UbusHomeRepository(caller).load()
        assertEquals(ClientSummary(total = 3, wifi = 2, wired = 1, wifiKnown = true), reading.clients)

        assertTrue(caller.calls.isNotEmpty())
        for ((obj, method, args) in caller.calls) {
            assertTrue("$obj $method must be on the read-only list", UbusHomeRepository.isAllowedCall(obj, method, args))
        }
        val allowedObjects = setOf("system", "file", "network.interface", "network.device", "network.wireless", "luci-rpc", "iwinfo")
        for ((obj, _, _) in caller.calls) {
            assertTrue(obj in allowedObjects || obj.startsWith("hostapd."))
        }
    }

    @Test
    fun testAllowlistRefusesEverythingElse() {
        val none = buildJsonObject {}
        val refused = listOf(
            Triple("system", "reboot", none),
            Triple("system", "signal", none),
            Triple("uci", "set", none),
            Triple("uci", "commit", none),
            Triple("uci", "apply", none),
            Triple("file", "write", buildJsonObject { put("path", "/proc/stat") }),
            Triple("file", "exec", buildJsonObject { put("command", "reboot") }),
            Triple("file", "read", buildJsonObject { put("path", "/etc/shadow") }),
            Triple("file", "read", buildJsonObject { put("path", "/proc/stat"); put("base64", true) }),
            Triple("file", "read", none),
            Triple("session", "login", none),
            Triple("network.interface", "down", none),
            Triple("network.wireless", "down", none),
            Triple("hostapd.phy0-ap0", "del_client", none),
            Triple("hostapd.phy0-ap0", "get_clients", buildJsonObject { put("x", 1) }),
            Triple("hostapd.bad name", "get_clients", none),
            Triple("hostapd.", "get_clients", none),
            Triple("iwinfo", "scan", buildJsonObject { put("device", "phy0-ap0") }),
            Triple("iwinfo", "assoclist", buildJsonObject { put("device", "bad name") }),
            Triple("luci-rpc", "getHostHints", buildJsonObject { put("x", 1) }),
            Triple("luci", "setPassword", none)
        )
        for ((obj, method, args) in refused) {
            assertFalse("$obj $method $args must be refused", UbusHomeRepository.isAllowedCall(obj, method, args))
        }
        assertTrue(UbusHomeRepository.isAllowedCall("file", "read", buildJsonObject { put("path", "/proc/stat") }))
        assertTrue(UbusHomeRepository.isAllowedCall("hostapd.phy1-ap0", "get_clients", none))
    }

    @Test
    fun testProcStatPathIsHardcodedConstant() = runBlocking {
        assertEquals("/proc/stat", UbusHomeRepository.PROC_STAT_PATH)
        val caller = workingRouter()
        UbusHomeRepository(caller).load()
        val fileCalls = caller.calls.filter { it.first == "file" }
        assertTrue(fileCalls.any { it.second == "read" && it.third == buildJsonObject { put("path", "/proc/stat") } })
        assertTrue(fileCalls.all { UbusHomeRepository.isAllowedCall(it.first, it.second, it.third) })
    }

    @Test
    fun testAccessDeniedOnFirstCallMeansSessionExpired() {
        val caller = workingRouter()
        caller.fail("system board", UbusError.AccessDenied(-32002, "Access denied"))
        assertTrue(loadFailure(caller) is HomeLoadException.SessionExpired)
        // Nothing else is asked once the session is known to be gone.
        assertEquals(1, caller.calls.size)

        val expired = workingRouter()
        expired.fail("system board", UbusError.SessionExpired())
        assertTrue(loadFailure(expired) is HomeLoadException.SessionExpired)
    }

    @Test
    fun testAccessDeniedAfterFirstCallMeansNotPermitted() {
        val caller = workingRouter()
        caller.fail("system info", UbusError.AccessDenied())
        assertTrue(loadFailure(caller) is HomeLoadException.NotPermitted)
    }

    @Test
    fun testMissingCoreObjectMeansFeatureNotInstalled() {
        val caller = workingRouter()
        caller.fail("network.interface dump", UbusError.ObjectNotFound())
        assertTrue(loadFailure(caller) is HomeLoadException.FeatureNotInstalled)
    }

    @Test
    fun testConnectionProblemsMeanUnreachable() {
        val required = workingRouter()
        required.fail("system board", UbusError.NetworkError(IOException("timeout")))
        assertTrue(loadFailure(required) is HomeLoadException.Unreachable)

        // The connection can also drop in the middle of a reading.
        val optional = workingRouter()
        optional.fail("luci-rpc getHostHints", UbusError.NetworkError(IOException("reset")))
        assertTrue(loadFailure(optional) is HomeLoadException.Unreachable)

        val garbage = workingRouter()
        garbage.fail("system info", UbusError.InvalidJson("<html>"))
        assertTrue(loadFailure(garbage) is HomeLoadException.Unreachable)
    }

    @Test
    fun testMissingLuciRpcGivesUnknownDeviceCountNotZero() = runBlocking {
        val missing = workingRouter()
        missing.fail("luci-rpc getHostHints", UbusError.ObjectNotFound())
        missing.fail("luci-rpc getDHCPLeases", UbusError.ObjectNotFound())
        val reading = UbusHomeRepository(missing).load()
        assertNull(reading.clients)
        assertEquals(setOf(HomeNotice.CLIENTS_FEATURE_MISSING), reading.notices)
        assertEquals(86, reading.memUsedPct)

        val denied = workingRouter()
        denied.fail("luci-rpc getHostHints", UbusError.AccessDenied())
        denied.fail("luci-rpc getDHCPLeases", UbusError.AccessDenied())
        assertEquals(setOf(HomeNotice.CLIENTS_NOT_PERMITTED), UbusHomeRepository(denied).load().notices)
    }

    @Test
    fun testWifiNotAnsweringMeansSplitIsUnknown() = runBlocking {
        val caller = workingRouter()
        caller.fail("luci-rpc getWirelessDevices", UbusError.ObjectNotFound())
        val reading = UbusHomeRepository(caller).load()
        assertEquals(ClientSummary(total = 3, wifi = 0, wired = 0, wifiKnown = false), reading.clients)
    }

    @Test
    fun testOptionalReadingsMayBeMissing() = runBlocking {
        val caller = workingRouter()
        caller.fail("file read", UbusError.AccessDenied())
        caller.fail("network.device status", UbusError.ObjectNotFound())
        val reading = UbusHomeRepository(caller).load()
        assertNull(reading.cpu)
        assertNull(reading.wanCounters)
        assertEquals(86, reading.memUsedPct)
    }

    @Test
    fun testSystemBoardIsCachedAndPostCacheAccessDeniedProbesSession() = runBlocking {
        val caller = workingRouter()
        val repo = UbusHomeRepository(caller)
        repo.load()
        repo.load()
        assertEquals(1, caller.calls.count { it.first == "system" && it.second == "board" })

        // Session expires before the 3rd refresh: system.info fails with AccessDenied, probe system.board also fails -> SessionExpired
        caller.fail("system info", UbusError.AccessDenied())
        caller.fail("system board", UbusError.AccessDenied())
        try {
            repo.load()
            fail("expected SessionExpired")
        } catch (_: HomeLoadException.SessionExpired) {
        }

        // Post-gate ubus code 4 (SessionExpired) while system.board still succeeds -> FeatureNotInstalled
        val missingFeature = workingRouter()
        missingFeature.fail("network.interface dump", UbusError.SessionExpired())
        assertTrue(loadFailure(missingFeature) is HomeLoadException.FeatureNotInstalled)
    }

    @Test
    fun testLoadsThermalsIntoHomeReading() = runBlocking {
        val caller = object : UbusCaller {
            val base = workingRouter()
            val thermalFiles = mapOf(
                "/sys/class/hwmon/hwmon0/name" to "cpu_thermal\n",
                "/sys/class/hwmon/hwmon0/temp1_input" to "49274\n",
                "/sys/class/hwmon/hwmon1/name" to "mt7915_phy0\n",
                "/sys/class/hwmon/hwmon1/temp1_input" to "51000\n"
            )
            override suspend fun call(ubusObject: String, method: String, args: JsonObject): JsonObject {
                if (ubusObject == "file" && method == "list" &&
                    (args["path"] as kotlinx.serialization.json.JsonPrimitive).content == "/sys/class/hwmon"
                ) {
                    return Json.parseToJsonElement("""{"entries":[{"name":"hwmon0"},{"name":"hwmon1"}]}""").jsonObject
                }
                if (ubusObject == "file" && method == "read") {
                    val p = (args["path"] as kotlinx.serialization.json.JsonPrimitive).content
                    thermalFiles[p]?.let { return buildJsonObject { put("data", it) } }
                    if (p != "/proc/stat") throw UbusError.ObjectNotFound()
                }
                return base.call(ubusObject, method, args)
            }
        }
        val reading = UbusHomeRepository(caller).load()
        assertEquals(
            listOf(
                ThermalSensor("hwmon0", "cpu_thermal", "CPU / SoC", 49.274),
                ThermalSensor("hwmon1", "mt7915_phy0", "2.4 GHz Wi-Fi", 51.0)
            ),
            reading.thermals
        )
    }

    @Test
    fun testLoadCountersDistinguishesUnreachableFromMissing() = runBlocking {
        val caller = workingRouter()
        val repo = UbusHomeRepository(caller)
        repo.load()

        caller.fail("network.device status", UbusError.ObjectNotFound())
        assertNull(repo.loadCounters())

        caller.fail("network.device status", UbusError.AccessDenied())
        assertNull(repo.loadCounters())

        caller.fail("network.device status", UbusError.NetworkError(IOException("timeout")))
        try {
            repo.loadCounters()
            fail("expected Unreachable")
        } catch (_: HomeLoadException.Unreachable) {
        }
    }
}
