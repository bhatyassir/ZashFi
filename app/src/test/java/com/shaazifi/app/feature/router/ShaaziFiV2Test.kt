package com.shaazifi.app.feature.router

import com.shaazifi.app.feature.demo.DemoRouterChanges
import com.shaazifi.app.feature.devices.AccessRule
import com.shaazifi.app.feature.devices.DemoDevices
import com.shaazifi.app.feature.devices.changeInternet
import com.shaazifi.app.feature.home.HomeParsers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShaaziFiV2Test {
    @Test
    fun livePortHistoryUsesCounterDeltasAndSkipsResets() {
        val reply = Json.parseToJsonElement("""
            {"result":[[24,100,0,200,0],[26,500,0,300,0],[28,20,0,30,0],[30,220,0,90,0]]}
        """).jsonObject
        val rates = requireNotNull(RealtimeStats.rates(reply))
        assertEquals(listOf(26_000_000_000L, 30_000_000_000L), rates.history.map { it.timeNs })
        assertEquals(100.0, rates.currentRx, 0.0)
        assertEquals(30.0, rates.currentTx, 0.0)
        val view = RealtimeStats.view(mapOf("wan" to rates), "wan")
        assertEquals(rates, view.portTraffic[view.internetPort])
    }

    @Test
    fun meshSummaryOnlyDisplaysStatusFields() {
        val status = Json.parseToJsonElement("""{"role":"controller","join_code":"demo-only","unexpected":"hidden"}""").jsonObject
        val summary = FeatureMappers.mesh(null, status, null)
        assertEquals(listOf("Role"), summary.sections.first().rows.map { it.label })
    }

    @Test
    fun frequentFeaturesFillRemainingPlacesWithPreviewShortcuts() {
        val known = setOf("health", "bandwidth", "services", "live", "parental")
        assertEquals(listOf("health", "bandwidth", "services", "live"),
            FrequentFeatures.top(mapOf("health" to 1), known::contains, 4))
    }

    @Test
    fun duplicateNetworkNamesMatchByConfiguredSection() {
        val reply = Json.parseToJsonElement("""
          {"radio0":{"up":true,"config":{"band":"2g","channel":6},"interfaces":[
            {"section":"ap0","ifname":"wlan0","config":{"mode":"ap","ssid":"Shared"}},
            {"section":"ap1","ifname":"wlan1","config":{"mode":"ap","ssid":"Shared"}}
          ]}}
        """).jsonObject
        val keys = HomeParsers.parseRadios(reply, emptyMap()).map { requireNotNull(it.key) }
        val networks = FeatureMappers.wireless(reply).wifiNetworks
        assertEquals(listOf("Shared", "Shared"), networks.map { it.name })
        assertEquals(listOf("wlan0", "wlan1"), keys.map { key ->
            networks.single { it.key.matches(key) }.key.ifname
        })
        assertTrue(networks[1].key.matches(keys[1].copy(ifname = "renamed1")))
        assertFalse(networks[0].key.matches(keys[1]))
    }

    @Test
    fun usageTotalsCombineIpv4AndIpv6AndPreserveMeasuredZero() {
        val report = """
            {"columns":["ip","tx_bytes","mac","rx_bytes"],"data":[
              ["192.0.2.1",35,"02:00:00:00:00:a1",100],
              ["2001:db8::1",20,"02:00:00:00:00:A1",25],
              ["192.0.2.2",0,"02:00:00:00:00:A2",0],
              ["192.0.2.3",null,"02:00:00:00:00:A3",20],
              ["192.0.2.4",-2,"02:00:00:00:00:A4",20],
              ["192.0.2.5",12,"02:00:00:00:00:A5"]]}
        """
        val totals = FeatureMappers.usage(null, report, null, emptyMap()).deviceTotals
        assertEquals(mapOf("02:00:00:00:00:A1" to 180L, "02:00:00:00:00:A2" to 0L), totals)
        assertTrue(FeatureMappers.usage(null, null, null, emptyMap()).deviceTotals.isEmpty())
    }

    @Test
    fun resumeDisablesOnlyImmediateRulesAndPauseReusesOne() {
        val device = DemoDevices.devices.first()
        val firewall = FeatureMappersTest.uci("""
          {"old":{".type":"rule","name":"Block: Tablet","src":"lan","dest":"wan","target":"REJECT","src_mac":"${device.mac}"},
           "manual":{".type":"rule","name":"Parental: Tablet","src":"lan","dest":"wan","target":"REJECT","src_mac":"${device.mac}"},
           "schedule":{".type":"rule","name":"Parental: Tablet","src":"lan","dest":"wan","target":"REJECT","src_mac":"${device.mac}","start_time":"21:00","stop_time":"07:00"},
           "other":{".type":"rule","name":"Parental: Tablet","src":"guest","dest":"wan","target":"REJECT","src_mac":"${device.mac}"}}
        """)
        val rules = AccessRule.parse(firewall)
        assertEquals(3, rules.size)
        val resume = (changeInternet(device, rules) as PlanResult.Ready).plan
        assertEquals(setOf("old", "manual"), resume.ops.map { (it as UciOp.Set).section }.toSet())
        assertEquals("Enabled schedules still apply.", resume.note)
        val paused = (changeInternet(device, rules.map { it.copy(enabled = false) }) as PlanResult.Ready).plan
        assertEquals(UciOp.Set("firewall", "old", mapOf("enabled" to "1")), paused.ops.single())
    }

    @Test
    fun demoServiceChangesUseLocalStateAndKeepProtectedServicesRunning() = runBlocking {
        var reads = 0
        val initial = Features.byId("services")!!.demo
        val services = LoadHolder(load = { reads++; initial }, demo = initial)
        val devices = LoadHolder(load = { reads++; DemoDevices }, demo = DemoDevices)
        val demo = DemoRouterChanges(devices) { services }
        val controller = ChangeController(runPlan = { demo.run(it) })
        val stopped = (Phase4.serviceAction("odhcpd", "stop") as PlanResult.Ready).plan
        controller.request(stopped)
        controller.cancel()
        services.refresh()
        assertTrue((services.state.value as LoadState.DemoData).value.services.first { it.name == "odhcpd" }.running == true)
        controller.request(stopped)
        controller.confirm()
        val updated = (services.state.value as LoadState.DemoData).value.services
        assertEquals(false, updated.first { it.name == "odhcpd" }.running)
        assertEquals(true, updated.first { it.name == "dnsmasq" }.running)
        assertTrue(Phase4.serviceAction("dnsmasq", "stop") is PlanResult.Invalid)
        val unknown = FeatureMappers.services(Json.parseToJsonElement("""{"legacy":{"start":20,"enabled":true}}""").jsonObject)
        assertNull(unknown.services.single().running)
        assertEquals(0, reads)
    }
}
