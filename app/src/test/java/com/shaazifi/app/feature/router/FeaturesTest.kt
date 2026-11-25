package com.shaazifi.app.feature.router

import com.shaazifi.app.core.ubus.UbusError
import com.shaazifi.app.feature.devices.Connection
import com.shaazifi.app.feature.devices.DevicesParsers
import com.shaazifi.app.feature.devices.loadDevices
import com.shaazifi.app.feature.home.UbusCaller
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeaturesTest {

    /** Fake router: answers every allowed call with an empty object and records what was asked. */
    private class FakeRouter(private val fail: ((String, String) -> Exception?)? = null) : UbusCaller {
        val calls = mutableListOf<Pair<String, String>>()
        override suspend fun call(ubusObject: String, method: String, args: JsonObject): JsonObject {
            calls += ubusObject to method
            fail?.invoke(ubusObject, method)?.let { throw it }
            return if (ubusObject == "uci") Json.parseToJsonElement("""{"values":{}}""").jsonObject else JsonObject(emptyMap())
        }
    }

    @Test
    fun everyFeatureIsListedOnceWithADemo() {
        val ids = Features.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(Features.all.all { it.group in Features.groups && it.title.isNotBlank() && it.demo.summary.isNotBlank() })
        // UnTangled's screens (src/lib/nav.ts) all have a native screen or a tab of their own.
        val untangled = setOf("guest", "traffic", "usage", "parental", "extend", "aiprotect", "wireless", "wan", "ddns", "qos",
            "dualwan", "upnp", "portfwd", "vpn", "tailscale", "lan", "ipv6", "firewall", "dmz", "admin", "log", "doctor", "usb", "software")
        // "firmware": removed from the app on the owner's instruction (2026-11-17); UnTangled's own page remains in the router pages.
        assertEquals(untangled, Features.all.mapNotNull { it.untangledId }.toSet())
    }

    @Test
    fun everyLoaderOnlyMakesAllowedReads() = runBlocking {
        for (spec in Features.all) {
            val router = FakeRouter()
            try {
                spec.load(RouterReader(router), FeatureContext(adGuardConfigured = false))
            } catch (e: FeatureLoadException) {
                // Not installed etc. is fine for an empty router.
            } catch (e: IllegalStateException) {
                throw AssertionError("${spec.id} tried a call outside the read-only list: ${e.message}")
            }
            assertTrue("${spec.id} made no call", router.calls.isNotEmpty())
            assertTrue(router.calls.none { (_, m) -> m in setOf("set", "add", "delete", "apply", "commit", "setPassword", "write") })
        }
        val dev = FakeRouter()
        loadDevices(RouterReader(dev))
        assertTrue(dev.calls.isNotEmpty())
    }

    @Test
    fun missingPackagesShowAsNotInstalledAndLostConnectionAsUnreachable() = runBlocking {
        val ddns = Features.byId("ddns")!!
        val notFound = FakeRouter { o, _ -> if (o == "uci") UbusError.SessionExpired(4) else null }
        val holder = LoadHolder(load = { ddns.load(RouterReader(notFound), FeatureContext(false)) })
        holder.refresh()
        assertEquals(LoadState.NotInstalled, holder.state.value)

        val offline = FakeRouter { _, _ -> UbusError.NetworkError(java.io.IOException("down")) }
        val h2 = LoadHolder(load = { ddns.load(RouterReader(offline), FeatureContext(false)) })
        h2.refresh()
        assertEquals(LoadState.Unreachable, h2.state.value)

        val denied = FakeRouter { o, m -> if (o == "system" && m == "board") UbusError.AccessDenied() else null }
        val h3 = LoadHolder(load = { ddns.load(RouterReader(denied), FeatureContext(false)) })
        h3.refresh()
        assertEquals(LoadState.SessionExpired, h3.state.value)
    }

    @Test
    fun demoModeNeverCallsTheRouter() = runBlocking {
        var loads = 0
        val holder = LoadHolder(load = { loads++; FeatureView("x") }, demo = FeatureView("demo"))
        holder.pollWhileActive()
        assertEquals(0, loads)
        assertTrue(holder.state.value is LoadState.DemoData)
    }

    @Test
    fun devicesMergeLiveClientsFixedAddressesAndBlocks() {
        val hints = FeatureMappersTest.fixture("m7_luci_rpc_hosthints.json")
        val leases = FeatureMappersTest.fixture("m7_luci_rpc_dhcpleases.json")
        val dhcp = FeatureMappersTest.uci("""{"h":{".type":"host","mac":"02:00:00:00:00:99","ip":"192.168.1.99","name":"NAS"}}""")
        val fw = FeatureMappersTest.uci("""{"b":{".type":"rule","name":"Block: Tablet","src_mac":"02:00:00:00:00:13","src":"lan","dest":"wan","target":"REJECT"}}""")
        val list = DevicesParsers.merge(hints, leases, mapOf("02:00:00:00:00:07" to Connection.WIFI_5), true, dhcp, fw)

        val first = list.devices.first()
        assertTrue("blocked device sorts first", first.blocked)
        assertEquals("02:00:00:00:00:13", first.mac)
        val nas = list.devices.first { it.mac == "02:00:00:00:00:99" }
        assertFalse("reserved but not seen = offline", nas.online)
        assertEquals("NAS", nas.name)
        assertEquals("192.168.1.99", nas.fixedIp)
        assertEquals(Connection.WIFI_5, list.devices.first { it.mac == "02:00:00:00:00:07" }.connection)
        assertTrue(list.devices.filter { it.online && it.mac != "02:00:00:00:00:07" }.all { it.connection == Connection.WIRED })
        assertEquals(Connection.WIFI_5, DevicesParsers.bandFromFreq(5745.0))
        assertEquals(Connection.WIFI_24, DevicesParsers.bandFromFreq(2412.0))
    }
}
