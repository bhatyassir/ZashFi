package com.shaazifi.app.feature.router

import com.shaazifi.app.core.ubus.UbusError
import com.shaazifi.app.feature.home.UbusCaller
import com.shaazifi.app.ui.theme.PillTone
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

/**
 * M10b. Commands and arguments as LuCI's diagnostics page uses them (luci-mod-network
 * diagnostics.js and acl.d/luci-mod-network.json, master, read 2026-11-17); services from
 * `rc list` (rpcd rc.c: start, stop, enabled always, running only for procd scripts).
 */
class DiagnosticsTest {

    @Test
    fun onlyPlainHostNamesAndAddressesAreAccepted() {
        listOf(
            "openwrt.org", "1.1.1.1", "router.lan", "my-host", "a.b-c.d",
            "::1", "2001:db8::1", "fe80::1", "2001:0db8:85a3:0000:0000:8a2e:0370:7334", "::ffff:192.0.2.1"
        ).forEach { assertTrue(it, Diagnostics.isSafeHost(it)) }
        listOf(
            "", "-c", "--help", "a b", "a;id", "\$(id)", "a|b", "1.1.1.1 -c 100", "x".repeat(254),
            "-1.1.1.1", "a/b", "a\nb", "2001::db8::1", "1:2:3", "2001:db8::1;id", "-2001:db8::1",
            "2001:db8::1%eth0", "[::1]"
        ).forEach { assertFalse(it, Diagnostics.isSafeHost(it)) }
    }

    @Test
    fun commandsMatchLucisExactly() {
        assertEquals(
            listOf("/bin/ping" to listOf("-4", "-c", "5", "-W", "1", "1.1.1.1"), "/usr/bin/ping" to listOf("-4", "-c", "5", "-W", "1", "1.1.1.1")),
            Diagnostics.commandsFor(Diagnostics.Tool.PING, "1.1.1.1")
        )
        assertEquals(
            listOf("/bin/ping" to listOf("-6", "-c", "5", "-W", "1", "2001:db8::1"), "/usr/bin/ping" to listOf("-6", "-c", "5", "-W", "1", "2001:db8::1")),
            Diagnostics.commandsFor(Diagnostics.Tool.PING, "2001:db8::1")
        )
        assertEquals(
            listOf(
                "/bin/traceroute" to listOf("-4", "-q", "1", "-w", "1", "-n", "-m", "20", "openwrt.org"),
                "/usr/bin/traceroute" to listOf("-4", "-q", "1", "-w", "1", "-n", "-m", "20", "openwrt.org")
            ),
            Diagnostics.commandsFor(Diagnostics.Tool.TRACEROUTE, "openwrt.org")
        )
        assertEquals(
            listOf(
                "/bin/traceroute" to listOf("-6", "-q", "1", "-w", "1", "-n", "-m", "20", "::1"),
                "/usr/bin/traceroute" to listOf("-6", "-q", "1", "-w", "1", "-n", "-m", "20", "::1")
            ),
            Diagnostics.commandsFor(Diagnostics.Tool.TRACEROUTE, "::1")
        )
        assertEquals(
            listOf("/usr/bin/nslookup" to listOf("openwrt.org"), "/bin/nslookup" to listOf("openwrt.org")),
            Diagnostics.commandsFor(Diagnostics.Tool.LOOKUP, "openwrt.org")
        )
        assertNull("unsafe host: no command at all", Diagnostics.commandsFor(Diagnostics.Tool.PING, "-f"))
    }

    private fun exec(cmd: String, params: List<String>) = buildJsonObject {
        put("command", cmd)
        put("params", JsonArray(params.map { JsonPrimitive(it) }))
    }

    @Test
    fun theReaderAllowsOnlyThoseCommandLines() {
        assertTrue(RouterReader.isAllowedRead("file", "exec", exec("/bin/ping", listOf("-4", "-c", "5", "-W", "1", "1.1.1.1"))))
        assertTrue(RouterReader.isAllowedRead("file", "exec", exec("/bin/ping", listOf("-6", "-c", "5", "-W", "1", "2001:db8::1"))))
        assertTrue(RouterReader.isAllowedRead("file", "exec", exec("/usr/bin/nslookup", listOf("openwrt.org"))))
        assertTrue(RouterReader.isAllowedRead("file", "exec", exec("/bin/nslookup", listOf("openwrt.org"))))
        assertFalse(RouterReader.isAllowedRead("file", "exec", exec("/bin/ping", listOf("-4", "-c", "500", "-W", "1", "1.1.1.1"))))
        assertFalse(RouterReader.isAllowedRead("file", "exec", exec("/bin/ping", listOf("-4", "-c", "5", "-W", "1", "-f"))))
        assertFalse(RouterReader.isAllowedRead("file", "exec", exec("/bin/ping", listOf("-4", "-c", "5", "-W", "1", "a", "b"))))
        assertFalse(RouterReader.isAllowedRead("file", "exec", exec("/bin/sh", listOf("-c", "id"))))
        assertTrue(RouterReader.isAllowedRead("rc", "list", JsonObject(emptyMap())))
        assertFalse("starting or stopping services is a write", RouterReader.isAllowedRead("rc", "init", JsonObject(emptyMap())))
    }

    @Test
    fun socketTimeoutSurfacesDiagnosticTimedOutMessage() = runBlocking {
        val timeoutReader = RouterReader(object : UbusCaller {
            override suspend fun call(ubusObject: String, method: String, args: JsonObject): JsonObject =
                throw UbusError.NetworkError(SocketTimeoutException("Read timed out"))
        })
        val res = runDiagnostic(timeoutReader, Diagnostics.Tool.TRACEROUTE, "openwrt.org")
        assertEquals(DiagResult.Failed("Diagnostic timed out"), res)
    }

    @Test
    fun servicesShowStateInStartOrder() {
        val reply = Json.parseToJsonElement(
            """{"uhttpd":{"start":50,"stop":10,"enabled":true,"running":true},
                "dnsmasq":{"start":19,"enabled":true,"running":false},
                "led":{"start":96,"enabled":false},
                "boot":{"start":10,"enabled":true}}"""
        ).jsonObject
        val v = FeatureMappers.services(reply)
        val rows = v.sections.flatMap { it.rows }
        assertEquals(listOf("boot", "dnsmasq", "uhttpd", "led"), rows.map { it.label })
        assertEquals("Running", rows.first { it.label == "uhttpd" }.pill?.text)
        assertEquals(PillTone.Bad, rows.first { it.label == "dnsmasq" }.pill?.tone)
        assertEquals("Starts at boot", rows.first { it.label == "boot" }.sub)
        assertEquals("Does not start at boot", rows.first { it.label == "led" }.sub)
        assertNull("no running state reported: no pill", rows.first { it.label == "led" }.pill)
        assertTrue(v.summary.contains("1 stopped"))
    }
}
