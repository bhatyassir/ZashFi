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
        // The wireless config holds Wi-Fi keys; screens use luci-rpc getWirelessDevices instead and drop the key.
        assertFalse(RouterReader.isAllowedRead("uci", "get", args("config" to "wireless")))
        assertFalse(RouterReader.isAllowedRead("uci", "get", args("config" to "rpcd")))
        assertFalse(RouterReader.isAllowedRead("file", "read", args("path" to "/etc/shadow")))
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
}
