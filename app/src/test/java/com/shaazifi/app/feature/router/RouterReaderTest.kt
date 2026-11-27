package com.shaazifi.app.feature.router

import com.shaazifi.app.core.ubus.UbusError
import com.shaazifi.app.feature.home.UbusCaller
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
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

class RouterReaderTest {

    private fun args(vararg pairs: Pair<String, String>) = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }
    private fun exec(command: String, vararg params: String) = buildJsonObject {
        put("command", command)
        put("params", buildJsonArray { params.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
    }

    @Test
    fun readsThatTheScreensNeedAreAllowed() {
        assertTrue(RouterReader.isAllowedRead("uci", "get", args("config" to "network")))
        assertTrue(RouterReader.isAllowedRead("uci", "get", args("config" to "firewall")))
        assertTrue(RouterReader.isAllowedRead("luci-rpc", "getWirelessDevices", JsonObject(emptyMap())))
        assertTrue(RouterReader.isAllowedRead("file", "read", args("path" to "/proc/mounts")))
        assertTrue(RouterReader.isAllowedRead("file", "list", args("path" to "/sys/class/hwmon")))
        assertTrue(RouterReader.isAllowedRead("file", "list", args("path" to "/sys/class/thermal")))
        assertTrue(RouterReader.isAllowedRead("file", "read", args("path" to "/sys/class/hwmon/hwmon0/name")))
        assertTrue(RouterReader.isAllowedRead("file", "read", args("path" to "/sys/class/hwmon/hwmon1/temp1_input")))
        assertTrue(RouterReader.isAllowedRead("file", "read", args("path" to "/sys/class/hwmon/hwmon12/temp8_crit")))
        assertTrue(RouterReader.isAllowedRead("file", "read", args("path" to "/sys/class/thermal/thermal_zone0/type")))
        assertTrue(RouterReader.isAllowedRead("file", "read", args("path" to "/sys/class/thermal/thermal_zone0/temp")))
        assertTrue(RouterReader.isAllowedRead("file", "exec", exec("/usr/bin/apk", "info")))
        assertTrue(RouterReader.isAllowedRead("file", "exec", exec("/usr/bin/vnstat", "--json")))
        assertTrue(RouterReader.isAllowedRead("file", "exec", exec("/usr/sbin/tailscale", "status", "--json")))
        assertTrue(RouterReader.isAllowedRead("log", "read", buildJsonObject { put("lines", 200); put("stream", false) }))
        assertTrue(RouterReader.isAllowedRead("hostapd.phy0-ap0", "get_clients", JsonObject(emptyMap())))
    }

    @Test
    fun anythingThatChangesTheRouterIsRefused() {
        assertFalse(RouterReader.isAllowedRead("uci", "set", args("config" to "network")))
        assertFalse(RouterReader.isAllowedRead("uci", "apply", JsonObject(emptyMap())))
        assertFalse(RouterReader.isAllowedRead("uci", "delete", args("config" to "firewall")))
        assertFalse(RouterReader.isAllowedRead("file", "exec", exec("/sbin/reboot")))
        assertFalse(RouterReader.isAllowedRead("file", "exec", exec("/usr/sbin/tailscale", "down")))
        assertFalse(RouterReader.isAllowedRead("file", "exec", exec("/usr/sbin/tailscale", "status", "--json", "--extra")))
        assertFalse(RouterReader.isAllowedRead("file", "write", args("path" to "/etc/crontabs/root")))
        assertFalse(RouterReader.isAllowedRead("luci", "setPassword", JsonObject(emptyMap())))
        assertFalse(RouterReader.isAllowedRead("system", "reboot", JsonObject(emptyMap())))
        assertFalse(RouterReader.isAllowedRead("untangled.mesh", "apply", JsonObject(emptyMap())))
    }

    @Test
    fun secretsAndOddReadsAreRefused() {
        // Wireless UCI is read for WPS/WDS, isolation, MAC filtering and VLAN fields only;
        // WifiExtras.panel never selects or displays `key`, and no QR screen uses this read.
        assertTrue(RouterReader.isAllowedRead("uci", "get", args("config" to "wireless")))
        assertFalse(RouterReader.isAllowedRead("uci", "get", args("config" to "rpcd")))
        assertFalse(RouterReader.isAllowedRead("file", "read", args("path" to "/etc/shadow")))
        assertFalse(RouterReader.isAllowedRead("file", "list", args("path" to "/etc")))
        assertFalse(RouterReader.isAllowedRead("file", "read", args("path" to "/sys/class/hwmon/hwmon0/power")))
        assertFalse(RouterReader.isAllowedRead("file", "read", args("path" to "/sys/class/hwmon/hwmon0/temp9_input")))
        assertFalse(RouterReader.isAllowedRead("file", "read", args("path" to "/sys/class/thermal/thermal_zone0/mode")))
        assertFalse(RouterReader.isAllowedRead("log", "read", buildJsonObject { put("lines", 5000); put("stream", false) }))
        assertFalse(RouterReader.isAllowedRead("log", "read", buildJsonObject { put("lines", 200); put("stream", true) }))
        assertFalse(RouterReader.isAllowedRead("hostapd.phy0;rm", "get_clients", JsonObject(emptyMap())))
        assertFalse(RouterReader.isAllowedRead("system", "board", args("x" to "y")))
    }

    @Test
    fun aRefusedCallIsNeverSent() = runBlocking {
        var sent = 0
        val reader = RouterReader(object : UbusCaller {
            override suspend fun call(ubusObject: String, method: String, args: JsonObject): JsonObject { sent++; return JsonObject(emptyMap()) }
        })
        try {
            reader.required("uci", "set", args("config" to "network"))
            fail("must refuse")
        } catch (_: IllegalStateException) {
        }
        assertEquals(0, sent)
    }

    @Test
    fun failuresMapToScreenStates() {
        assertTrue(RouterReader.classify(UbusError.NetworkError(IOException("x")), false) is FeatureLoadException.Unreachable)
        assertTrue(RouterReader.classify(IOException("x"), false) is FeatureLoadException.Unreachable)
        assertTrue(RouterReader.classify(UbusError.AccessDenied(), true) is FeatureLoadException.SessionExpired)
        assertTrue(RouterReader.classify(UbusError.AccessDenied(), false) is FeatureLoadException.NotPermitted)
        // ubus 4 NOT_FOUND (named SessionExpired in UbusError) on a feature read means "not on this router".
        assertTrue(RouterReader.classify(UbusError.SessionExpired(), false) is FeatureLoadException.NotInstalled)
        assertTrue(RouterReader.classify(UbusError.ObjectNotFound(), false) is FeatureLoadException.NotInstalled)
    }

    @Test
    fun uciRepliesAreParsedInOrderWithListsAndFlags() {
        val reply = Json.parseToJsonElement(
            """{"values":{
              "lan":{".type":"interface",".name":"lan",".index":1,"proto":"static","ipaddr":"192.168.1.1/24"},
              "loopback":{".type":"interface",".index":0,"proto":"static"},
              "x":{".type":"zone",".index":2,"network":["lan","guest"],"masq":"1"}
            }}"""
        ).jsonObject
        val cfg = UciConfig.parse(reply)
        assertEquals(listOf("loopback", "lan", "x"), cfg.sections.map { it.id })
        assertEquals("192.168.1.1/24", cfg.byId("lan")!!.opt("ipaddr"))
        assertEquals(listOf("lan", "guest"), cfg.byId("x")!!.list("network"))
        assertTrue(cfg.byId("x")!!.flag("masq"))
        assertFalse(cfg.byId("lan")!!.flag("disabled"))
    }

    @Test
    fun readThermalsReadsMt7986HwmonViaFileList() = runBlocking {
        val files = mapOf(
            "/sys/class/hwmon/hwmon0/name" to "cpu_thermal\n",
            "/sys/class/hwmon/hwmon0/temp1_input" to "49274\n",
            "/sys/class/hwmon/hwmon1/name" to "mt7915_phy0\n",
            "/sys/class/hwmon/hwmon1/temp1_input" to "51000\n",
            "/sys/class/hwmon/hwmon1/temp1_max" to "120000\n",
            "/sys/class/hwmon/hwmon1/temp1_crit" to "110000\n",
            "/sys/class/hwmon/hwmon2/name" to "mt7915_phy1\n",
            "/sys/class/hwmon/hwmon2/temp1_input" to "53000\n",
            "/sys/class/hwmon/hwmon2/temp1_max" to "120000\n",
            "/sys/class/hwmon/hwmon2/temp1_crit" to "110000\n"
        )
        val reader = RouterReader(object : UbusCaller {
            override suspend fun call(ubusObject: String, method: String, args: JsonObject): JsonObject =
                when (ubusObject to method) {
                    "file" to "list" -> when ((args["path"] as kotlinx.serialization.json.JsonPrimitive).content) {
                        "/sys/class/hwmon" -> Json.parseToJsonElement("""{"entries":[{"name":"hwmon0"},{"name":"hwmon1"},{"name":"hwmon2"}]}""").jsonObject
                        else -> Json.parseToJsonElement("""{"entries":[{"name":"thermal_zone0"}]}""").jsonObject
                    }
                    "file" to "read" -> {
                        val p = (args["path"] as kotlinx.serialization.json.JsonPrimitive).content
                        val content = files[p] ?: throw UbusError.ObjectNotFound()
                        buildJsonObject { put("data", content) }
                    }
                    else -> throw UbusError.ObjectNotFound()
                }
        })
        val sensors = reader.readThermals()
        assertEquals(3, sensors.size)
        assertEquals(ThermalSensor("hwmon0", "cpu_thermal", "CPU / SoC", 49.274), sensors[0])
        assertEquals(ThermalSensor("hwmon1", "mt7915_phy0", "2.4 GHz Wi-Fi", 51.0, 120.0, 110.0), sensors[1])
        assertEquals(ThermalSensor("hwmon2", "mt7915_phy1", "5 GHz Wi-Fi", 53.0, 120.0, 110.0), sensors[2])
    }

    @Test
    fun readThermalsFallsBackToProbingAndThermalZoneAndDeduplicatesCpu() = runBlocking {
        // Case 1: file list denied; probes hwmon0..3 (phy0 only) and falls back to thermal_zone0 for CPU, deduplicating extra CPU.
        val files = mapOf(
            "/sys/class/hwmon/hwmon0/name" to "mt7915_phy0\n",
            "/sys/class/hwmon/hwmon0/temp1_input" to "50500\n",
            "/sys/class/thermal/thermal_zone0/type" to "cpu-thermal\n",
            "/sys/class/thermal/thermal_zone0/temp" to "48500\n",
            "/sys/class/thermal/thermal_zone1/type" to "cpu_thermal\n",
            "/sys/class/thermal/thermal_zone1/temp" to "49000\n"
        )
        val probeReader = RouterReader(object : UbusCaller {
            override suspend fun call(ubusObject: String, method: String, args: JsonObject): JsonObject =
                when (ubusObject to method) {
                    "file" to "list" -> throw UbusError.AccessDenied()
                    "file" to "read" -> {
                        val p = (args["path"] as kotlinx.serialization.json.JsonPrimitive).content
                        val content = files[p] ?: throw UbusError.ObjectNotFound()
                        buildJsonObject { put("data", content) }
                    }
                    else -> throw UbusError.ObjectNotFound()
                }
        })
        val mixed = probeReader.readThermals()
        assertEquals(listOf("2.4 GHz Wi-Fi", "CPU / SoC"), mixed.map { it.label })
        assertEquals(48.5, mixed[1].tempCelsius, 0.001)

        // Case 2: no thermal sensors anywhere returns empty list gracefully.
        val emptyReader = RouterReader(object : UbusCaller {
            override suspend fun call(ubusObject: String, method: String, args: JsonObject): JsonObject =
                throw UbusError.ObjectNotFound()
        })
        assertTrue(emptyReader.readThermals().isEmpty())
    }

    @Test
    fun readFlowOffloadingParsesFirewallDefaults() = runBlocking {
        val reader = RouterReader(object : UbusCaller {
            override suspend fun call(ubusObject: String, method: String, args: JsonObject): JsonObject =
                Json.parseToJsonElement("""{"values":{"@defaults[0]":{".type":"defaults","flow_offloading":"1","flow_offloading_hw":"1"}}}""").jsonObject
        })
        assertEquals(FlowOffloadingStatus(software = true, hardware = true), reader.readFlowOffloading())

        val missing = RouterReader(object : UbusCaller {
            override suspend fun call(ubusObject: String, method: String, args: JsonObject): JsonObject =
                throw UbusError.ObjectNotFound()
        })
        assertNull(missing.readFlowOffloading())
    }

    @Test
    fun postGateAccessDeniedDistinguishesExpiredSessionFromPermissionDenied() = runBlocking {
        // Post-gate AccessDenied when system.board also denies -> SessionExpired
        val expiredReader = RouterReader(object : UbusCaller {
            override suspend fun call(ubusObject: String, method: String, args: JsonObject): JsonObject =
                throw UbusError.AccessDenied()
        })
        try {
            expiredReader.required("system", "info", sessionGate = false)
            fail("expected SessionExpired")
        } catch (_: FeatureLoadException.SessionExpired) {
        }

        // Post-gate AccessDenied when system.board succeeds -> NotPermitted
        val deniedReader = RouterReader(object : UbusCaller {
            override suspend fun call(ubusObject: String, method: String, args: JsonObject): JsonObject =
                if (ubusObject == "system" && method == "board") JsonObject(emptyMap())
                else throw UbusError.AccessDenied()
        })
        try {
            deniedReader.required("system", "info", sessionGate = false)
            fail("expected NotPermitted")
        } catch (_: FeatureLoadException.NotPermitted) {
        }

        // Post-gate ubus code 4 (UbusError.SessionExpired) when system.board succeeds -> NotInstalled
        val missingFeatureReader = RouterReader(object : UbusCaller {
            override suspend fun call(ubusObject: String, method: String, args: JsonObject): JsonObject =
                if (ubusObject == "system" && method == "board") JsonObject(emptyMap())
                else throw UbusError.SessionExpired()
        })
        try {
            missingFeatureReader.required("untangled.mesh", "info", sessionGate = false)
            fail("expected NotInstalled")
        } catch (_: FeatureLoadException.NotInstalled) {
        }
    }
}
